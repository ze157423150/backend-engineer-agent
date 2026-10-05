package dev.backendagent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.nio.file.Path;

import dev.backendagent.model.ToolExchange;
import dev.backendagent.memory.MemoryFact;
import dev.backendagent.memory.WorkingMemory;
import dev.backendagent.persistence.SessionEventSink;

public final class AgentSession {
    public enum Status { CREATED, RUNNING, WAITING_FOR_TOOL, COMPLETED, FAILED, BUDGET_EXHAUSTED }

    public enum EventType {
        SESSION_STARTED, CONTEXT_ASSEMBLED, MODEL_CALL_STARTED, MODEL_RESPONSE_RECEIVED,
        TOOL_CALL_REQUESTED, TOOL_EXECUTION_SUCCEEDED, TOOL_EXECUTION_FAILED, WORKING_MEMORY_UPDATED, FILE_EVIDENCE_INVALIDATED,
        WORKING_MEMORY_LOADED, SESSION_RESUMED, SESSION_COMPLETED, SESSION_FAILED, BUDGET_EXHAUSTED
    }

    public record Event(long sequence, EventType type, String detail) {}

    private final UUID id;
    private final String objective;
    private final List<Event> events = new ArrayList<>();
    private final List<ToolExchange> history = new ArrayList<>();
    private final WorkingMemory workingMemory = new WorkingMemory();
    private final Set<String> staleEvidenceIds = new HashSet<>();
    private Status status = Status.CREATED;
    private int modelCalls;
    private String answer;
    private final SessionEventSink eventSink;

    public AgentSession(String objective) {
        this(objective, (session, event, payload) -> { });
    }

    public AgentSession(String objective, SessionEventSink eventSink) {
        if (Objects.requireNonNull(objective).isBlank()) {
            throw new IllegalArgumentException("Objective must not be blank");
        }
        this.id = UUID.randomUUID();
        this.objective = objective;
        this.eventSink = Objects.requireNonNull(eventSink);
    }

    private AgentSession(dev.backendagent.persistence.SessionCheckpoint checkpoint,
                         List<Event> recordedEvents, SessionEventSink eventSink) {
        this.id = checkpoint.getSessionId();
        this.objective = checkpoint.getObjective();
        this.status = checkpoint.getStatus();
        this.modelCalls = checkpoint.getModelCalls();
        this.eventSink = Objects.requireNonNull(eventSink);
        this.events.addAll(recordedEvents);
        this.history.addAll(checkpoint.getHistory());
        this.staleEvidenceIds.addAll(checkpoint.getStaleEvidenceIds());
        checkpoint.getWorkingMemory().forEach(workingMemory::save);
    }

    public static AgentSession restore(dev.backendagent.persistence.SessionCheckpoint checkpoint,
                                       List<Event> recordedEvents, SessionEventSink eventSink) {
        if (checkpoint.getStatus() != Status.BUDGET_EXHAUSTED
                || checkpoint.getLastEventSequence() != recordedEvents.size()
                || recordedEvents.isEmpty()
                || recordedEvents.getLast().type() != EventType.BUDGET_EXHAUSTED) {
            throw new IllegalArgumentException("Only a complete budget-stop checkpoint can be restored");
        }
        var ids = new HashSet<String>();
        for (ToolExchange exchange : checkpoint.getHistory()) {
            if (!ids.add(exchange.call().id()) || exchange.modelCallNumber() > checkpoint.getModelCalls()) {
                throw new IllegalArgumentException("Invalid checkpoint history");
            }
        }
        for (MemoryFact fact : checkpoint.getWorkingMemory()) {
            if (checkpoint.getStaleEvidenceIds().contains(fact.getSourceCallId())
                    || checkpoint.getHistory().stream().noneMatch(exchange ->
                        exchange.call().id().equals(fact.getSourceCallId()) && exchange.result().successful()
                        && exchange.call().name().equals(fact.getSourceTool())
                        && (fact.getSourceTool().equals("read_file") || fact.getSourceTool().equals("search_code"))
                        && fact.getSourcePath().equals(exchange.call().arguments().get("path"))
                        && exchange.result().content().contains(fact.getEvidenceQuote()))) {
                throw new IllegalArgumentException("Invalid checkpoint memory evidence");
            }
        }
        if (checkpoint.getWorkingMemory().size() > WorkingMemory.MAX_FACTS
                || checkpoint.getWorkingMemory().stream().mapToInt(MemoryFact::characterCount).sum()
                    > WorkingMemory.MAX_CHARACTERS) {
            throw new IllegalArgumentException("Checkpoint memory exceeds limits");
        }
        return new AgentSession(checkpoint, recordedEvents, eventSink);
    }

    public Set<String> staleEvidenceIds() { return Set.copyOf(staleEvidenceIds); }
    void checkpoint() { eventSink.checkpoint(this); }

    void resume() {
        if (status != Status.BUDGET_EXHAUSTED) {
            throw new IllegalStateException("Only budget-exhausted sessions can resume");
        }
        status = Status.RUNNING;
        append(EventType.SESSION_RESUMED, "Continuing after model call " + modelCalls);
    }

    public UUID id() { return id; }
    public String objective() { return objective; }
    public Status status() { return status; }
    public int modelCalls() { return modelCalls; }
    public String answer() { return answer; }
    public List<Event> events() { return List.copyOf(events); }
    public List<ToolExchange> history() { return List.copyOf(history); }
    public List<MemoryFact> memoryFacts() { return workingMemory.snapshot(); }
    public int memoryCharacters() { return workingMemory.characterCount(); }

    public void importVerifiedMemory(MemoryFact oldFact, ToolExchange verified, UUID sourceSessionId) {
        if (status != Status.CREATED || !verified.result().successful()
                || !(verified.call().name().equals("read_file") || verified.call().name().equals("search_code"))
                || !verified.call().name().equals(oldFact.getSourceTool())
                || !oldFact.getSourcePath().equals(verified.call().arguments().get("path"))
                || !verified.result().content().contains(oldFact.getEvidenceQuote())
                || history.stream().anyMatch(exchange -> exchange.call().id().equals(verified.call().id()))) {
            throw new IllegalArgumentException("Memory import requires unique verified evidence before task start");
        }
        var refreshed = new MemoryFact(oldFact.getStatement(), verified.call().id(), verified.call().name(),
                oldFact.getSourcePath(), oldFact.getEvidenceQuote());
        history.add(verified);
        workingMemory.save(refreshed);
        append(EventType.WORKING_MEMORY_LOADED, "fromSession=" + sourceSessionId + ", sourceCallId="
                + oldFact.getSourceCallId() + ", refreshedCallId=" + verified.call().id(),
                java.util.Map.of("sourceSessionId", sourceSessionId.toString(), "oldSourceCallId", oldFact.getSourceCallId(),
                        "verifiedExchange", verified, "workingMemory", memoryFacts()));
    }

    public void saveFact(String statement, String sourceCallId, String evidenceQuote) {
        if (status != Status.WAITING_FOR_TOOL) {
            throw new IllegalStateException("Memory may only be updated while executing a tool");
        }
        ToolExchange source = history.stream().filter(exchange -> exchange.call().id().equals(sourceCallId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Evidence tool call was not found"));
        if (staleEvidenceIds.contains(sourceCallId)) {
            throw new IllegalArgumentException("Evidence predates a file modification; read or search again");
        }
        if (!source.result().successful() || !(source.call().name().equals("read_file")
                || source.call().name().equals("search_code"))) {
            throw new IllegalArgumentException("Evidence must come from successful read_file or search_code");
        }
        var fact = new MemoryFact(statement, source.call().id(), source.call().name(),
                source.call().arguments().get("path"), evidenceQuote);
        if (!source.result().content().contains(evidenceQuote)) {
            throw new IllegalArgumentException("Evidence quote does not match the original tool result");
        }
        workingMemory.save(fact);
        append(EventType.WORKING_MEMORY_UPDATED, "facts=" + memoryFacts().size()
                + ", characters=" + memoryCharacters() + ", sourceCallId=" + sourceCallId, memoryFacts());
    }

    public void invalidateFileEvidence(String relativePath) {
        Path changed = Path.of(relativePath).normalize();
        for (ToolExchange exchange : history) {
            if (!exchange.result().successful()) { continue; }
            String tool = exchange.call().name();
            String path = exchange.call().arguments().get("path");
            // Search can span many files; invalidate all earlier search evidence conservatively.
            if (tool.equals("search_code") || (tool.equals("read_file") && path != null
                    && Path.of(path).normalize().equals(changed))) {
                staleEvidenceIds.add(exchange.call().id());
            }
        }
        workingMemory.removeSources(staleEvidenceIds);
        append(EventType.FILE_EVIDENCE_INVALIDATED, "file=" + changed
                + ", remainingMemoryFacts=" + memoryFacts().size()
                + "; earlier file reads and searches are historical data; read again before citing current code",
                java.util.Map.of("path", changed.toString(), "invalidatedCallIds", Set.copyOf(staleEvidenceIds),
                        "workingMemory", memoryFacts()));
    }

    public boolean hasCurrentRead(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) { return false; }
        Path requested = Path.of(relativePath).normalize();
        return history.stream().anyMatch(exchange -> exchange.call().name().equals("read_file")
                && exchange.result().successful() && !staleEvidenceIds.contains(exchange.call().id())
                && exchange.call().arguments().get("path") != null
                && Path.of(exchange.call().arguments().get("path")).normalize().equals(requested));
    }

    void transitionTo(Status next) {
        boolean allowed = switch (status) {
            case CREATED -> next == Status.RUNNING;
            case RUNNING -> next == Status.WAITING_FOR_TOOL || next == Status.COMPLETED
                    || next == Status.FAILED || next == Status.BUDGET_EXHAUSTED;
            case WAITING_FOR_TOOL -> next == Status.RUNNING || next == Status.FAILED;
            case COMPLETED, FAILED, BUDGET_EXHAUSTED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Invalid transition: " + status + " -> " + next);
        }
        status = next;
    }

    void append(EventType type, String detail) {
        append(type, detail, null);
    }

    void append(EventType type, String detail, Object payload) {
        var event = new Event(events.size() + 1L, type, detail);
        eventSink.append(this, event, payload);
        events.add(event);
    }

    void countModelCall() { modelCalls++; }
    void remember(ToolExchange exchange) { history.add(exchange); }
    void finish(String answer) { this.answer = answer; }
}
