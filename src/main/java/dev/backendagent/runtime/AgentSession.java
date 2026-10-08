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
    public enum Status { CREATED, RUNNING, COMPACTING, WAITING_FOR_TOOL, COMPLETED, FAILED, BUDGET_EXHAUSTED }

    public enum EventType {
        CONVERSATION_COMPACTION_STARTED, CONVERSATION_COMPACTION_COMPLETED, CONVERSATION_COMPACTION_FAILED, CONVERSATION_SUMMARY_SKIPPED, CONVERSATION_SUMMARY_ATTEMPTED, USER_TURN_STARTED, SESSION_STARTED, REQUEST_BUDGET_CHECKED, COMPACTION_STARTED, COMPACTION_COMPLETED, COMPACTION_FAILED, CONTEXT_ASSEMBLED, MODEL_CALL_STARTED, MODEL_RESPONSE_RECEIVED, MODEL_CALL_FAILED, MODEL_RETRY_SCHEDULED,
        TOOL_CALL_REQUESTED, TOOL_EXECUTION_SUCCEEDED, TOOL_EXECUTION_FAILED, WORKING_MEMORY_UPDATED, FILE_EVIDENCE_INVALIDATED,
        WORKSPACE_REVISION_ADVANCED, TEST_RESULT_RECORDED,
        WORKING_MEMORY_LOADED, HISTORY_BODIES_ARCHIVED, HISTORICAL_EVIDENCE_UPDATED, WINDOW_SUMMARY_ATTEMPTED, WINDOW_SUMMARY_SKIPPED, SESSION_RESUMED, SESSION_COMPLETED, SESSION_FAILED, BUDGET_EXHAUSTED,
        TOOL_BATCH_STARTED, TOOL_BATCH_PROGRESS, TOOL_BATCH_COMPLETED, TOOL_CALL_RETRY_AUTHORIZED, TOOL_EXECUTION_STARTED
    }

    public record Event(long sequence, EventType type, String detail) {}

    private final UUID id;
    private final String objective;
    private final List<ConversationTurn> completedTurns = new ArrayList<>();
    private String currentUserMessage;
    private int turnStartHistoryIndex;
    private final List<Event> events = new ArrayList<>();
    private final List<ToolExchange> history = new ArrayList<>();
    private final WorkingMemory workingMemory = new WorkingMemory();
    private final dev.backendagent.history.HistoricalEvidenceArea historicalEvidence = new dev.backendagent.history.HistoricalEvidenceArea();
    private final Set<String> staleEvidenceIds = new HashSet<>();
    private Status status = Status.CREATED;
    private int modelCalls;
    private String answer;
    private ContextProjection contextProjection;
    private ContextSummary contextSummary;
    private ConversationSummary conversationSummary;
    private SessionFailure failure;
    private PendingToolBatch pendingToolBatch;
    private boolean inFlightRetryValidated;
    private WorkspaceState workspaceState = WorkspaceState.initial();
    private final SessionEventSink eventSink;
    private final dev.backendagent.history.ObservationArchive archive;
    public static final int RECENT_FULL_BATCHES = ContextWindowPolicy.RECENT_BATCHES + ContextWindowPolicy.SUMMARY_BATCHES;

    public AgentSession(String objective) {
        this(objective, (session, event, payload) -> { });
    }

    public AgentSession(String objective, SessionEventSink eventSink) {
        if (Objects.requireNonNull(objective).isBlank() || objective.length() > 8000) {
            throw new IllegalArgumentException("Objective must be nonblank and at most 8000 characters");
        }
        this.id = UUID.randomUUID();
        this.objective = objective;
        this.currentUserMessage = objective;
        this.eventSink = Objects.requireNonNull(eventSink);
        this.archive = eventSink.observationArchive(this);
    }

    private AgentSession(dev.backendagent.persistence.SessionCheckpoint checkpoint,
                         List<Event> recordedEvents, SessionEventSink eventSink) {
        this.id = checkpoint.getSessionId();
        this.objective = checkpoint.getObjective();
        if (checkpoint.getTurns().isEmpty()) {
            this.currentUserMessage = objective;
        } else {
            var savedTurns = checkpoint.getTurns();
            completedTurns.addAll(savedTurns.subList(0, savedTurns.size() - 1));
            var current = savedTurns.getLast();
            currentUserMessage = current.getUserMessage();
            turnStartHistoryIndex = current.getStartHistoryIndex();
            answer = current.getAnswer();
        }
        this.status = checkpoint.getStatus();
        this.modelCalls = checkpoint.getModelCalls();
        this.eventSink = Objects.requireNonNull(eventSink);
        this.archive = eventSink.observationArchive(this);
        recordedEvents.forEach(event -> this.events.add(previewEvent(event)));
        this.history.addAll(checkpoint.getHistory());
        if (archive == null && history.stream().anyMatch(exchange -> exchange.archivedBody() != null)) {
            throw new IllegalArgumentException("Restoring archived history requires a durable source");
        }
        this.workspaceState = WorkspaceState.fromHistory(checkpoint.getHistory());
        this.contextProjection = checkpoint.getContextProjection();
        this.contextSummary = checkpoint.getContextSummary();
        this.conversationSummary = checkpoint.getConversationSummary();
        this.failure = checkpoint.getFailure();
        this.pendingToolBatch = checkpoint.getPendingToolBatch();
        this.inFlightRetryValidated = pendingToolBatch != null && pendingToolBatch.getPreExecutionHashes() != null
                && pendingToolBatch.getPreExecutionHashes().equals(checkpoint.getWorkspaceHashes());
        this.staleEvidenceIds.addAll(checkpoint.getStaleEvidenceIds());
        // Older checkpoints have no workspaceState. Derive historical test invalidation as well.
        invalidateHistoricalTests();
        checkpoint.getWorkingMemory().forEach(workingMemory::save);
        checkpoint.getHistoricalEvidence().forEach(historicalEvidence::retain);
    }

    public static AgentSession restore(dev.backendagent.persistence.SessionCheckpoint checkpoint,
                                       List<Event> recordedEvents, SessionEventSink eventSink) {
        if (checkpoint.getHistory().stream().anyMatch(exchange -> exchange.archivedBody() != null)) {
            throw new IllegalArgumentException("Archived checkpoints require durable observation validation");
        }
        return restore(checkpoint, recordedEvents, eventSink, checkpoint.getHistory());
    }

    public static AgentSession restore(dev.backendagent.persistence.SessionCheckpoint checkpoint,
            List<Event> recordedEvents, SessionEventSink eventSink, List<ToolExchange> originals) {
        if (originals.size() != checkpoint.getHistory().size()) { throw new IllegalArgumentException("Incomplete archive history"); }
        for (int i = 0; i < originals.size(); i++) {
            var saved = checkpoint.getHistory().get(i);
            if (saved.archivedBody() == null) {
                if (!saved.equals(originals.get(i))) { throw new IllegalArgumentException("Checkpoint history differs from original"); }
            } else {
                saved.archivedBody().verify(saved.archivedBody().getEventSequence(), originals.get(i), saved);
            }
        }
        if ((checkpoint.getStatus() != Status.BUDGET_EXHAUSTED && checkpoint.getStatus() != Status.COMPLETED
                && checkpoint.getStatus() != Status.FAILED)
                || checkpoint.getLastEventSequence() != recordedEvents.size()
                || recordedEvents.isEmpty()
                || recordedEvents.getLast().type() != (checkpoint.getStatus() == Status.COMPLETED ? EventType.SESSION_COMPLETED
                    : checkpoint.getStatus() == Status.FAILED ? EventType.SESSION_FAILED : EventType.BUDGET_EXHAUSTED)) {
            throw new IllegalArgumentException("Only a complete execution boundary can be restored");
        }
        if (checkpoint.getStatus() == Status.FAILED && (checkpoint.getFailure() == null
                || (!checkpoint.getFailure().canResume() && checkpoint.getPendingToolBatch() == null)
                || checkpoint.getFailure().getModelCallNumber() != checkpoint.getModelCalls()
                || checkpoint.getFailure().getCompletedExchanges() != checkpoint.getHistory().size())) {
            throw new IllegalArgumentException("Failed checkpoint is not a recoverable tool-batch boundary");
        }
        if (checkpoint.getPendingToolBatch() != null && checkpoint.getStatus() != Status.FAILED) {
            throw new IllegalArgumentException("Only failed recovery can restore a pending tool batch");
        }
        var ids = new HashSet<String>();
        long recordedRevision = 0;
        for (ToolExchange exchange : checkpoint.getHistory()) {
            if (!ids.add(exchange.call().id()) || exchange.modelCallNumber() > checkpoint.getModelCalls()) {
                throw new IllegalArgumentException("Invalid checkpoint history");
            }
            if (exchange.evidence() != null && exchange.evidence().getWorkspaceRevision() != recordedRevision) {
                throw new IllegalArgumentException("Checkpoint evidence revision disagrees with history");
            }
            if (WorkspaceState.isSuccessfulWrite(exchange)) { recordedRevision++; }
        }
        if (checkpoint.getWorkspaceState() != null
                && !checkpoint.getWorkspaceState().equals(WorkspaceState.fromHistory(checkpoint.getHistory()))) {
            throw new IllegalArgumentException("Checkpoint workspace state disagrees with tool history");
        }
        for (MemoryFact fact : checkpoint.getWorkingMemory()) {
            if (checkpoint.getStaleEvidenceIds().contains(fact.getSourceCallId())
                    || originals.stream().noneMatch(exchange ->
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
        if (checkpoint.getContextSummary() != null) {
            checkpoint.getContextSummary().validateAgainst(originals);
        }
        if (checkpoint.getContextProjection() != null) {
            checkpoint.getContextProjection().validateAgainst(checkpoint.getHistory());
        }
        var replayed = dev.backendagent.history.HistoricalEvidenceArea.replay(originals);
        if (!replayed.snapshot().equals(checkpoint.getHistoricalEvidence())) {
            throw new IllegalArgumentException("Checkpoint historical evidence differs from successful retrievals");
        }
        validateTurns(checkpoint);
        if(checkpoint.getConversationSummary()!=null) checkpoint.getConversationSummary().validateAgainst(checkpoint.getTurns());
        return new AgentSession(checkpoint, recordedEvents, eventSink);
    }

    private static void validateTurns(dev.backendagent.persistence.SessionCheckpoint checkpoint) {
        int end = 0;
        var turns = checkpoint.getTurns();
        if (checkpoint.getSchemaVersion() >= 3 && turns.isEmpty()) throw new IllegalArgumentException("Missing conversation turns");
        for (int i = 0; i < turns.size(); i++) {
            var turn = turns.get(i);
            if (turn.getTurnId() != i + 1 || turn.getStartHistoryIndex() != end
                    || turn.getEndHistoryIndex() > checkpoint.getHistory().size()
                    || (i < turns.size() - 1 && turn.getStatus() != Status.COMPLETED)) {
                throw new IllegalArgumentException("Conversation turn ranges or status disagree with history");
            }
            end = turn.getEndHistoryIndex();
        }
        if (!turns.isEmpty() && (end != checkpoint.getHistory().size()
                || turns.getLast().getStatus() != checkpoint.getStatus()
                || !turns.getFirst().getUserMessage().equals(checkpoint.getObjective()))) {
            throw new IllegalArgumentException("Conversation turns disagree with checkpoint");
        }
    }

    public List<ConversationTurn> turns() {
        var turns = new ArrayList<>(completedTurns);
        turns.add(new ConversationTurn(turns.size() + 1, currentUserMessage, status, answer,
                turnStartHistoryIndex, history.size()));
        return List.copyOf(turns);
    }
    public ConversationSummary conversationSummary() { return conversationSummary; }
    public SessionFailure failure() { return failure; }
    public PendingToolBatch pendingToolBatch() { return pendingToolBatch; }
    void setPendingToolBatch(PendingToolBatch batch) { pendingToolBatch = batch; }
    public void capturePendingToolHashes(java.util.Map<String, String> hashes) {
        if (pendingToolBatch != null && pendingToolBatch.isInFlight()
                && pendingToolBatch.getPreExecutionHashes() == null) {
            pendingToolBatch = pendingToolBatch.withPreExecutionHashes(hashes);
        }
    }
    void installConversationSummary(ConversationSummary summary) { conversationSummary=summary; }
    int lastConversationSummaryAttemptEnd() {
        for(int i=events.size()-1;i>=0;i--) {
            var event=events.get(i);
            if(event.type()==EventType.CONVERSATION_SUMMARY_ATTEMPTED || event.type()==EventType.CONVERSATION_SUMMARY_SKIPPED)
                return Integer.parseInt(event.detail());
        }
        return 0;
    }
    public String currentTask() { return currentUserMessage; }

    void continueWith(String message) {
        if (status != Status.COMPLETED) throw new IllegalStateException("Only a completed turn accepts a new user message");
        if (completedTurns.size() >= 31) throw new IllegalArgumentException("Conversation currently supports at most 32 turns");
        var next = new ConversationTurn(completedTurns.size() + 2, message, Status.RUNNING, null, history.size(), history.size());
        completedTurns.add(turns().getLast());
        currentUserMessage = next.getUserMessage();
        turnStartHistoryIndex = history.size();
        answer = null;
        status = Status.RUNNING;
        append(EventType.USER_TURN_STARTED, "turn=" + next.getTurnId(), next);
        checkpoint();
    }

    public Set<String> staleEvidenceIds() { return Set.copyOf(staleEvidenceIds); }
    void checkpoint() { eventSink.checkpoint(this); }

    void resume() { resume(false); }
    void resume(boolean retryInFlight) {
        if (status != Status.BUDGET_EXHAUSTED && (status != Status.FAILED || failure == null
                || (!failure.canResume() && !(retryInFlight && pendingToolBatch != null)))) {
            throw new IllegalStateException("Only budget stops or recoverable failed sessions can resume");
        }
        if (pendingToolBatch != null && pendingToolBatch.isInFlight() && (!retryInFlight || !inFlightRetryValidated)) {
            throw new IllegalStateException("In-flight tool requires explicit retry and verified unchanged workspace");
        }
        var previousFailure = failure;
        status = Status.RUNNING;
        failure = null;
        if (pendingToolBatch != null && pendingToolBatch.isInFlight()) {
            append(EventType.TOOL_CALL_RETRY_AUTHORIZED,
                    pendingToolBatch.getCalls().get(pendingToolBatch.getNextCallIndex()).id(), pendingToolBatch);
            pendingToolBatch = pendingToolBatch.retrying();
            inFlightRetryValidated = false;
        }
        append(EventType.SESSION_RESUMED, "Continuing after model call " + modelCalls
                + (previousFailure == null ? "" : "; recovering failed turn"), previousFailure);
        checkpoint();
    }

    void fail(RuntimeException cause, boolean completeToolBatch) {
        String type = cause.getClass().getSimpleName();
        failure = new SessionFailure(completeToolBatch ? SessionFailure.Boundary.COMPLETE_TOOL_BATCH
                : pendingToolBatch == null ? SessionFailure.Boundary.INCOMPLETE_TOOL_BATCH
                : pendingToolBatch.isInFlight() ? SessionFailure.Boundary.TOOL_IN_FLIGHT
                : SessionFailure.Boundary.BETWEEN_TOOLS, modelCalls, history.size(),
                type.isBlank() ? "RuntimeException" : type);
        transitionTo(Status.FAILED);
        append(EventType.SESSION_FAILED, type + ": " + cause.getMessage(), failure);
        if (failure.canResume() || pendingToolBatch != null) checkpoint();
    }

    public UUID id() { return id; }
    public String objective() { return objective; }
    public Status status() { return status; }
    public int modelCalls() { return modelCalls; }
    public String answer() { return answer; }
    public List<Event> events() { return List.copyOf(events); }
    public ContextSummary contextSummary() { return contextSummary; }
    void installSummary(ContextSummary summary) { this.contextSummary = summary; }
    public ContextProjection contextProjection() { return contextProjection; }
    void installProjection(ContextProjection projection) { this.contextProjection = projection; }
    public List<ToolExchange> history() { return List.copyOf(history); }
    public boolean hasDurableArchive() { return archive != null; }
    int lastWindowSummaryAttemptEnd() { return lastWindowSummaryEnd(false); }
    int lastWindowSummarySkippedEnd() { return lastWindowSummaryEnd(true); }
    private int lastWindowSummaryEnd(boolean skippedOnly) {
        for (int index = events.size() - 1; index >= 0; index--) {
            var event = events.get(index);
            if (event.type() == EventType.WINDOW_SUMMARY_SKIPPED
                    || (!skippedOnly && event.type() == EventType.WINDOW_SUMMARY_ATTEMPTED)) {
                int end = Integer.parseInt(event.detail());
                if (end < 0 || end > history.size()) { throw new IllegalArgumentException("Invalid summary attempt boundary"); }
                return end;
            }
        }
        return 0;
    }
    public List<MemoryFact> memoryFacts() { return workingMemory.snapshot(); }
    public java.util.List<dev.backendagent.history.HistoricalEvidence> historicalEvidence() { return historicalEvidence.snapshot(); }
    public int historicalEvidenceCharacters() { return historicalEvidence.characterCount(); }
    void retainHistoricalEvidence(ToolExchange retrieval) {
        var evidence = retrieval.result().historicalEvidence();
        if (evidence == null) { return; }
        evidence.validateAgainst(originalObservation(evidence.getSourceCallId()), retrieval);
        historicalEvidence.retain(evidence);
        append(EventType.HISTORICAL_EVIDENCE_UPDATED, "retained=" + historicalEvidence.snapshot().size()
                + ", sourceCallId=" + evidence.getSourceCallId(), historicalEvidence.snapshot());
    }
    public int memoryCharacters() { return workingMemory.characterCount(); }
    public WorkspaceState workspaceState() { return workspaceState; }

    public void importVerifiedMemory(MemoryFact oldFact, ToolExchange verified, UUID sourceSessionId) {
        if (status != Status.CREATED || !verified.result().successful()
                || !(verified.call().name().equals("read_file") || verified.call().name().equals("search_code"))
                || !verified.call().name().equals(oldFact.getSourceTool())
                || !oldFact.getSourcePath().equals(verified.call().arguments().get("path"))
                || !verified.result().content().contains(oldFact.getEvidenceQuote())
                || history.stream().anyMatch(exchange -> exchange.call().id().equals(verified.call().id()))) {
            throw new IllegalArgumentException("Memory import requires unique verified evidence before task start");
        }
        var bound = bindEvidence(verified);
        var refreshed = new MemoryFact(oldFact.getStatement(), verified.call().id(), verified.call().name(),
                oldFact.getSourcePath(), oldFact.getEvidenceQuote());
        history.add(bound);
        workingMemory.save(refreshed);
        append(EventType.WORKING_MEMORY_LOADED, "fromSession=" + sourceSessionId + ", sourceCallId="
                + oldFact.getSourceCallId() + ", refreshedCallId=" + verified.call().id(),
                java.util.Map.of("sourceSessionId", sourceSessionId.toString(), "oldSourceCallId", oldFact.getSourceCallId(),
                        "verifiedExchange", bound, "workingMemory", memoryFacts()));
    }

    public void saveFact(String statement, String sourceCallId, String evidenceQuote) {
        if (status != Status.WAITING_FOR_TOOL) {
            throw new IllegalStateException("Memory may only be updated while executing a tool");
        }
        ToolExchange source = originalObservation(sourceCallId);
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
                && exchange.evidence() != null
                && exchange.call().arguments().get("path") != null
                && Path.of(exchange.call().arguments().get("path")).normalize().equals(requested));
    }

    public boolean hasCurrentRead(String relativePath, dev.backendagent.tools.WorkspaceAccess workspace) {
        if (!hasCurrentRead(relativePath)) { return false; }
        Path requested = Path.of(relativePath).normalize();
        for (int index = history.size() - 1; index >= 0; index--) {
            var exchange = history.get(index);
            if (exchange.evidence() != null && Path.of(exchange.evidence().getFile().getPath()).equals(requested)
                    && !staleEvidenceIds.contains(exchange.call().id())) {
                return assessFileEvidence(exchange.call().id(), workspace) == ObservationEvidence.Validity.CURRENT;
            }
        }
        return false;
    }

    void transitionTo(Status next) {
        boolean allowed = switch (status) {
            case CREATED -> next == Status.RUNNING;
            case RUNNING -> next == Status.COMPACTING || next == Status.WAITING_FOR_TOOL || next == Status.COMPLETED
                    || next == Status.FAILED || next == Status.BUDGET_EXHAUSTED;
            case COMPACTING -> next == Status.RUNNING || next == Status.FAILED;
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
        events.add(archive == null ? event : previewEvent(event));
    }

    private static Event previewEvent(Event event) {
        String detail = event.detail();
        return new Event(event.sequence(), event.type(), detail.length() <= 1200 ? detail
                : detail.substring(0, 1000) + "\n[事件详情仅保留预览，完整内容见 events.jsonl]");
    }

    /** Reads only when the selected observation is unloaded; never restores it into history. */
    public ToolExchange originalObservation(String callId) {
        var saved = history.stream().filter(exchange -> exchange.call().id().equals(callId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Evidence tool call was not found"));
        if (saved.archivedBody() == null) { return saved; }
        if (archive == null) { throw new dev.backendagent.persistence.PersistenceException("Durable archive is unavailable", null); }
        try {
            var entry = new dev.backendagent.history.HistoryArchiveReader(archive, null).read(callId);
            if (entry == null) { throw new java.io.IOException("Archived observation is missing"); }
            saved.archivedBody().verify(entry.getSequence(), entry.getExchange(), saved);
            return entry.getExchange();
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new dev.backendagent.persistence.PersistenceException("Cannot verify archived observation", failure);
        }
    }

    /** Called only after a complete batch has been durably appended. */
    void releaseOldBodies() {
        if (archive == null || history.isEmpty()) { return; }
        int cutoff = history.size();
        for (int batch = 0; batch < RECENT_FULL_BATCHES && cutoff > 0; batch++) {
            int turn = history.get(--cutoff).modelCallNumber();
            while (turn > 0 && cutoff > 0 && history.get(cutoff - 1).modelCallNumber() == turn) { cutoff--; }
        }
        var candidates = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < cutoff; i++) {
            var exchange = history.get(i);
            if (exchange.archivedBody() == null && exchange.result().content().length()
                    > dev.backendagent.history.ArchivedBody.EXCERPT_CHARACTERS + 400) {
                candidates.put(exchange.call().id(), i);
            }
        }
        if (candidates.isEmpty()) { return; }
        var replacements = new java.util.HashMap<Integer, ToolExchange>();
        try {
            archive.visit((sequence, original) -> {
                var index = candidates.get(original.call().id());
                if (index == null) { return; }
                if (!history.get(index).equals(original)) { throw new java.io.IOException("Observation differs from archive"); }
                replacements.put(index, dev.backendagent.history.ArchivedBody.unload(sequence, original));
            });
            if (replacements.size() != candidates.size()) { throw new java.io.IOException("Incomplete observation archive"); }
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new dev.backendagent.persistence.PersistenceException("Cannot unload historical bodies safely", failure);
        }
        // The previous projection was computed from pre-unload bodies; its audit event remains durable.
        contextProjection = null;
        replacements.forEach(history::set);
        append(EventType.HISTORY_BODIES_ARCHIVED, "unloaded=" + replacements.size()
                + ", recentFullBatches=" + RECENT_FULL_BATCHES);
    }

    void countModelCall() { modelCalls++; }
    void remember(ToolExchange exchange) {
        exchange = bindEvidence(exchange);
        history.add(exchange);
        workspaceState = workspaceState.after(exchange);
        if (WorkspaceState.isSuccessfulWrite(exchange)) { invalidateHistoricalTests(); }
        if (exchange.call().name().equals("read_observation")
                && staleEvidenceIds.contains(exchange.call().arguments().get("call_id"))) {
            staleEvidenceIds.add(exchange.call().id());
        }
    }

    private ToolExchange bindEvidence(ToolExchange exchange) {
        if (exchange.call().name().equals("read_file") && exchange.result().successful()
                && exchange.result().fileFingerprint() != null) {
            var evidence = new ObservationEvidence(ObservationEvidence.Type.FILE_CONTENT, exchange.call().id(),
                    exchange.result().fileFingerprint(), workspaceState.getRevision());
            return new ToolExchange(exchange.call(), exchange.result(), exchange.assistantContent(),
                    exchange.modelCallNumber(), evidence);
        }
        return exchange;
    }

    /** Point-in-time verification; no stored CURRENT boolean and no host filesystem fallback. */
    public ObservationEvidence.Validity assessFileEvidence(String callId, dev.backendagent.tools.WorkspaceAccess workspace) {
        var source = history.stream().filter(exchange -> exchange.call().id().equals(callId)).findFirst();
        if (source.isEmpty() || source.get().evidence() == null) { return ObservationEvidence.Validity.UNKNOWN; }
        if (staleEvidenceIds.contains(callId)) { return ObservationEvidence.Validity.STALE; }
        var saved = source.get().evidence().getFile();
        try {
            var current = workspace.fileFingerprint(saved.getPath());
            if (current == null) { return ObservationEvidence.Validity.UNKNOWN; }
            return saved.equals(current) ? ObservationEvidence.Validity.CURRENT : ObservationEvidence.Validity.STALE;
        } catch (dev.backendagent.persistence.PersistenceException fatal) {
            throw fatal;
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            return ObservationEvidence.Validity.UNKNOWN;
        }
    }

    private void invalidateHistoricalTests() {
        long observedRevision = 0;
        for (var exchange : history) {
            if (WorkspaceState.isSuccessfulWrite(exchange)) { observedRevision++; }
            if (exchange.call().name().equals("run_tests") && observedRevision < workspaceState.getRevision()) {
                staleEvidenceIds.add(exchange.call().id());
            }
            if (exchange.call().name().equals("read_observation")
                    && staleEvidenceIds.contains(exchange.call().arguments().get("call_id"))) {
                staleEvidenceIds.add(exchange.call().id());
            }
        }
    }
    void finish(String answer) { this.answer = answer; }
}
