package dev.backendagent.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.runtime.AgentSession;

/** Durable event log, inspection snapshot and resumable checkpoint, guarded by a session lock. */
public final class FileSessionStore implements SessionEventSink, AutoCloseable {
    private final Path root;
    private dev.backendagent.tools.WorkspaceAccess workspace;
    private final Map<UUID, FileChannel> lockChannels = new HashMap<>();
    private final Map<UUID, java.nio.channels.FileLock> locks = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Long> sequences = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();

    public FileSessionStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path sessionDirectory(UUID id) { return root.resolve(id.toString()); }

    /** Bound to this session; the model cannot choose a log path or another session ID. */
    public dev.backendagent.history.ObservationArchive observationArchive(AgentSession session) {
        return visitor -> visitObservations(session.id(), session.events().size(), visitor);
    }

    public synchronized void visitObservations(UUID id, long expectedSequence,
            dev.backendagent.history.ObservationVisitor visitor) throws IOException {
        safeDirectory(id);
        Path file = sessionDirectory(id).resolve("events.jsonl");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Invalid observation archive"); }
        final long maxBytes = 32L * 1024 * 1024;
        var ids = new HashSet<String>();
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > maxBytes) { throw new IOException("Observation archive exceeds limit"); }
            if (size > 0) {
                channel.position(size - 1);
                var tail = ByteBuffer.allocate(1);
                if (channel.read(tail) != 1 || tail.array()[0] != '\n') { throw new IOException("Incomplete archive tail"); }
                channel.position(0);
            }
            var bounded = new java.io.FilterInputStream(java.nio.channels.Channels.newInputStream(channel)) {
                private long consumed;
                private void count(int amount) throws IOException {
                    if (amount > 0 && (consumed += amount) > maxBytes) { throw new IOException("Archive grew beyond limit"); }
                }
                @Override public int read() throws IOException { int value = in.read(); count(value < 0 ? 0 : 1); return value; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int amount = in.read(bytes, offset, length); count(amount); return amount;
                }
            };
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(bounded,
                    StandardCharsets.UTF_8.newDecoder()))) {
                long sequence = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    var event = json.readTree(line);
                    if (event == null || !event.isObject() || !event.path("sequence").isIntegralNumber()
                            || !event.path("sequence").canConvertToLong()
                            || event.path("sequence").asLong() != ++sequence) { throw new IOException("Invalid archive event order"); }
                    String type = event.path("type").asText();
                    AgentSession.EventType.valueOf(type);
                    var payload = switch (type) {
                        case "TOOL_EXECUTION_SUCCEEDED", "TOOL_EXECUTION_FAILED" -> event.path("payload");
                        case "WORKING_MEMORY_LOADED" -> event.path("payload").path("verifiedExchange");
                        default -> null;
                    };
                    if (payload == null) { continue; }
                    if (!payload.isObject() || !payload.path("call").path("id").isTextual()
                            || !payload.path("call").path("name").isTextual()
                            || !payload.path("call").path("arguments").isObject()
                            || !payload.path("result").path("content").isTextual()
                            || !payload.path("result").path("successful").isBoolean()) {
                        throw new IOException("Invalid archived observation");
                    }
                    for (var argument : payload.path("call").path("arguments")) {
                        if (!argument.isTextual()) { throw new IOException("Invalid archived tool argument"); }
                    }
                    var exchange = json.treeToValue(payload, dev.backendagent.model.ToolExchange.class);
                    if (exchange.archivedBody() != null || !ids.add(exchange.call().id())
                            || (type.equals("TOOL_EXECUTION_SUCCEEDED") && !exchange.result().successful())
                            || (type.equals("TOOL_EXECUTION_FAILED") && exchange.result().successful())) {
                        throw new IOException("Duplicate or inconsistent archived observation");
                    }
                    visitor.accept(sequence, exchange);
                }
                if (sequence != expectedSequence) { throw new IOException("Archive watermark disagrees with session"); }
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid archive record", invalid); }
    }

    public synchronized void create(AgentSession session) {
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) { throw new IOException("Symlink data directory"); }
            Files.createDirectory(sessionDirectory(session.id()));
            acquireLock(session.id());
            Files.createFile(sessionDirectory(session.id()).resolve("events.jsonl"));
            sequences.put(session.id(), 0L);
            saveSnapshot(session);
        } catch (IOException failure) {
            failed.add(session.id());
            releaseLock(session.id());
            throw new PersistenceException("Cannot create session storage; check --data-dir and permissions", failure);
        }
    }

    public synchronized void exportWorkspace(UUID id, Path destination) throws IOException {
        safeDirectory(id);
        acquireLock(id);
        try { dev.backendagent.sandbox.DockerWorkspace.export(sessionDirectory(id), destination); }
        finally { releaseLock(id); }
    }

    /** Hold the normal session lock from deletion preview through confirmation and removal. */
    public synchronized SessionDeletion prepareDeletion(UUID id) throws IOException {
        safeDirectory(id);
        acquireLock(id);
        try { return new SessionDeletion(this, id); }
        catch (IOException | RuntimeException failure) { releaseLock(id); throw failure; }
    }

    synchronized boolean ownsDeletionLock(UUID id) { return locks.containsKey(id) && locks.get(id).isValid(); }
    synchronized void releaseDeletionLock(UUID id) { releaseLock(id); }

    public void bindWorkspace(dev.backendagent.tools.WorkspaceAccess workspace) {
        this.workspace = java.util.Objects.requireNonNull(workspace);
    }

    @Override
    public synchronized void checkpoint(AgentSession session) {
        // Unit runtimes without a bound workspace retain the existing event-only behavior.
        if (workspace == null) { return; }
        requireWritable(session.id());
        if (session.status() != AgentSession.Status.RUNNING
                && session.status() != AgentSession.Status.BUDGET_EXHAUSTED
                && session.status() != AgentSession.Status.COMPLETED
                && !(session.status() == AgentSession.Status.FAILED && session.failure() != null
                    && (session.failure().canResume() || session.pendingToolBatch() != null))
                && !(session.status() == AgentSession.Status.WAITING_FOR_TOOL && session.pendingToolBatch() != null)) {
            throw new PersistenceException("Checkpoint requires a complete tool-batch boundary", null);
        }
        Path temporary = null;
        try {
            safeDirectory(session.id());
            var hashes = workspace.checkpointHashes();
            session.capturePendingToolHashes(hashes);
            var checkpoint = new SessionCheckpoint(3, session.id(), session.objective(), session.status(),
                    session.modelCalls(), session.events().size(), session.history(), session.memoryFacts(),
                    session.staleEvidenceIds(), workspace.rootPath(), hashes,
                    workspace.originalContents(), workspace.createdFilePaths(), session.contextProjection(), session.contextSummary(),
                    session.workspaceState(), session.historicalEvidence(), session.turns(), session.conversationSummary(),
                    session.failure(), session.pendingToolBatch());
            Path target = sessionDirectory(session.id()).resolve("checkpoint.json");
            if (Files.isSymbolicLink(target)) { throw new IOException("Symlink checkpoint"); }
            temporary = Files.createTempFile(sessionDirectory(session.id()), ".checkpoint-", ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                byte[] encoded = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(checkpoint);
                if (encoded.length > 16 * 1024 * 1024) { throw new IOException("Checkpoint exceeds 16 MiB"); }
                var bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) { channel.write(bytes); }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | IllegalArgumentException failure) {
            failed.add(session.id());
            throw new PersistenceException("Cannot save checkpoint; execution stopped", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public synchronized AgentSession restore(UUID id, dev.backendagent.tools.WorkspaceAccess workspace,
                                              int maxModelCalls) throws IOException {
        return restoreAtBoundary(id, workspace, maxModelCalls, false, false, false);
    }

    public synchronized AgentSession openCompleted(UUID id, dev.backendagent.tools.WorkspaceAccess workspace,
                                                  int maxModelCalls) throws IOException {
        return restoreAtBoundary(id, workspace, maxModelCalls, true, false, false);
    }

    public synchronized AgentSession restoreFailed(UUID id, dev.backendagent.tools.WorkspaceAccess workspace,
                                                   int maxModelCalls) throws IOException {
        return restoreFailed(id, workspace, maxModelCalls, false);
    }

    public synchronized AgentSession restoreFailed(UUID id, dev.backendagent.tools.WorkspaceAccess workspace,
                                                   int maxModelCalls, boolean retryInFlight) throws IOException {
        return restoreAtBoundary(id, workspace, maxModelCalls, false, true, retryInFlight);
    }

    private AgentSession restoreAtBoundary(UUID id, dev.backendagent.tools.WorkspaceAccess workspace,
                                          int maxModelCalls, boolean completed, boolean recoveringFailure, boolean retryInFlight) throws IOException {
        safeDirectory(id);
        acquireLock(id);
        try {
            var saved = read(id);
            Path file = sessionDirectory(id).resolve("checkpoint.json");
            if (Files.isSymbolicLink(file) || Files.size(file) > 16 * 1024 * 1024) {
                throw new IOException("Invalid checkpoint file");
            }
            var checkpoint = json.readValue(Files.readString(file), SessionCheckpoint.class);
            var events = saved.path("events");
            boolean interruptedBatch = recoveringFailure && checkpoint.getPendingToolBatch() != null
                    && (checkpoint.getStatus() == AgentSession.Status.WAITING_FOR_TOOL
                        || checkpoint.getStatus() == AgentSession.Status.RUNNING)
                    && !events.isEmpty() && !events.get(events.size() - 1).path("type").asText().equals("SESSION_FAILED");
            long originalSequence = events.size();
            if (interruptedBatch) {
                checkpoint = recoverInterruptedBatch(checkpoint, (com.fasterxml.jackson.databind.node.ArrayNode) events);
            } else if (recoveringFailure && checkpoint.getStatus() == AgentSession.Status.RUNNING) {
                checkpoint = recoverLegacyFailure(checkpoint, events);
            }
            var expectedStatus = recoveringFailure ? AgentSession.Status.FAILED
                    : completed ? AgentSession.Status.COMPLETED : AgentSession.Status.BUDGET_EXHAUSTED;
            if (!id.equals(checkpoint.getSessionId()) || checkpoint.getLastEventSequence() != events.size()
                    || checkpoint.getStatus() != expectedStatus || events.isEmpty()
                    || !events.get(events.size() - 1).path("status").asText().equals(expectedStatus.name())
                    || events.get(events.size() - 1).path("modelCalls").asInt() != checkpoint.getModelCalls()) {
                throw new IOException(recoveringFailure ? "Checkpoint is not a recoverable failed session"
                        : completed ? "Checkpoint is not a complete finished turn"
                        : "Checkpoint is not a complete budget stop; pending execution cannot be replayed");
            }
            if (recoveringFailure) {
                var failure = checkpoint.getFailure();
                var pending = checkpoint.getPendingToolBatch();
                if (failure == null || (!failure.canResume() && pending == null)
                        || failure.getModelCallNumber() != checkpoint.getModelCalls()
                        || failure.getCompletedExchanges() != checkpoint.getHistory().size()) {
                    throw new IOException("Failed session has an incomplete tool batch; cannot replay uncertain effects");
                }
                if (pending != null && pending.isInFlight()) {
                    if (!retryInFlight) throw new IOException("In-flight tool requires --retry-incomplete-tool true");
                    if (pending.getPreExecutionHashes() == null
                            || !pending.getPreExecutionHashes().equals(checkpoint.getWorkspaceHashes())) {
                        throw new IOException("In-flight tool changed workspace; inspect its effects before retrying");
                    }
                }
                var recordedFailure = events.get(events.size() - 1).path("payload");
                if (!recordedFailure.isNull() && !recordedFailure.equals(json.valueToTree(failure))) {
                    throw new IOException("Failure metadata disagrees with event log");
                }
                validateFailedToolBoundary(events, checkpoint);
            }
            if (maxModelCalls <= checkpoint.getModelCalls()) {
                throw new IOException("--max-model-calls must exceed already used calls: " + checkpoint.getModelCalls());
            }
            if (!workspace.rootPath().equals(checkpoint.getWorkspacePath())
                    || !workspace.checkpointHashes().equals(checkpoint.getWorkspaceHashes())) {
                throw new IOException("Workspace differs from checkpoint; resume requires the same unchanged workspace");
            }
            if (checkpoint.getSchemaVersion() >= 3) {
                boolean originalTaskMatches = false;
                for (var event : events) if (event.path("type").asText().equals("SESSION_STARTED")) {
                    originalTaskMatches = event.path("detail").asText().equals(checkpoint.getObjective());
                    break;
                }
                if (!originalTaskMatches) throw new IOException("Initial user message disagrees with event log");
                var starts = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for (var event : events) if (event.path("type").asText().equals("USER_TURN_STARTED")) starts.add(event.path("payload"));
                var turns = checkpoint.getTurns();
                if (starts.size() != turns.size() - 1) throw new IOException("Turn messages disagree with event log");
                for (int i = 1; i < turns.size(); i++) {
                    var start = starts.get(i - 1);
                    var turn = turns.get(i);
                    if (start.path("turnId").asInt() != turn.getTurnId()
                            || !start.path("userMessage").asText().equals(turn.getUserMessage())
                            || start.path("startHistoryIndex").asInt() != turn.getStartHistoryIndex()) {
                        throw new IOException("Turn messages disagree with event log");
                    }
                }
                int finished = 0;
                for (var event : events) if (event.path("type").asText().equals("SESSION_COMPLETED")) {
                    if (finished >= turns.size() || !event.path("detail").asText().equals(turns.get(finished++).getAnswer())) {
                        throw new IOException("Turn answers disagree with event log");
                    }
                }
                if (finished != turns.size() - (completed ? 0 : 1)) throw new IOException("Turn completion count disagrees with event log");
            }
            com.fasterxml.jackson.databind.JsonNode lastDialogueSummary = json.nullNode();
            for(var event : events) if(event.path("type").asText().equals("CONVERSATION_COMPACTION_COMPLETED")) lastDialogueSummary=event.path("payload");
            if(!lastDialogueSummary.equals(json.valueToTree(checkpoint.getConversationSummary()))) {
                throw new IOException("Conversation summary disagrees with event log");
            }
            var recordedEvents = new java.util.ArrayList<AgentSession.Event>();
            for (var event : events) {
                recordedEvents.add(new AgentSession.Event(event.path("sequence").asLong(),
                        AgentSession.EventType.valueOf(event.path("type").asText()), event.path("detail").asText()));
            }
            var originals = new java.util.ArrayList<dev.backendagent.model.ToolExchange>();
            final var restoredCheckpoint = checkpoint;
            if (checkpoint.getSchemaVersion() >= 2 || !checkpoint.getHistoricalEvidence().isEmpty() || checkpoint.getHistory().stream().anyMatch(entry -> entry.archivedBody() != null)) {
                var sourceSequences = new java.util.HashMap<String, Long>();
                visitObservations(id, interruptedBatch ? originalSequence : checkpoint.getLastEventSequence(), (sequence, original) -> {
                    var historical = original.result().historicalEvidence();
                    if (historical != null && !java.util.Objects.equals(sourceSequences.get(historical.getSourceCallId()), historical.getSourceEventSequence())) {
                        throw new IOException("Historical evidence source sequence differs from archive");
                    }
                    sourceSequences.put(original.call().id(), sequence);
                    int index = originals.size();
                    if (index >= restoredCheckpoint.getHistory().size()) { throw new IOException("Archive has additional observations"); }
                    var entry = restoredCheckpoint.getHistory().get(index);
                    if (entry.archivedBody() == null) {
                        if (!entry.equals(original)) { throw new IOException("Checkpoint observation differs from archive"); }
                    } else { entry.archivedBody().verify(sequence, original, entry); }
                    originals.add(original);
                });
            } else { originals.addAll(checkpoint.getHistory()); }
            var session = AgentSession.restore(checkpoint, recordedEvents, this, originals);
            workspace.restoreChanges(checkpoint.getOriginalContents(), checkpoint.getCreatedFiles());
            bindWorkspace(workspace);
            sequences.put(id, interruptedBatch ? originalSequence : checkpoint.getLastEventSequence());
            if (interruptedBatch) {
                // Only after all hashes, history and cursor checks pass, durably record the detected interruption.
                append(session, session.events().getLast(), session.failure());
                checkpoint(session);
            }
            return session;
        } catch (IOException | RuntimeException failure) {
            releaseLock(id);
            throw failure;
        }
    }

    private SessionCheckpoint recoverInterruptedBatch(SessionCheckpoint checkpoint,
            com.fasterxml.jackson.databind.node.ArrayNode events) throws IOException {
        long watermark = checkpoint.getLastEventSequence();
        if (watermark < 1 || watermark > events.size()) throw new IOException("Interrupted batch checkpoint watermark is invalid");
        // Results or memory updates beyond the saved cursor need reconciliation, not blind execution.
        for (int index = (int) watermark; index < events.size(); index++) {
            var event = events.get(index);
            if (!event.path("type").asText().equals("TOOL_EXECUTION_STARTED")
                    || event.path("modelCalls").asInt() != checkpoint.getModelCalls()) {
                throw new IOException("Interrupted batch has uncheckpointed results or state changes");
            }
        }
        var pending = checkpoint.getPendingToolBatch();
        var failure = new dev.backendagent.runtime.SessionFailure(pending.isInFlight()
                ? dev.backendagent.runtime.SessionFailure.Boundary.TOOL_IN_FLIGHT
                : dev.backendagent.runtime.SessionFailure.Boundary.BETWEEN_TOOLS,
                checkpoint.getModelCalls(), checkpoint.getHistory().size(), "InterruptedToolBatch");
        var normalized = (ObjectNode) json.valueToTree(checkpoint);
        normalized.put("status", "FAILED").put("lastEventSequence", events.size() + 1L);
        var turns = normalized.path("turns");
        if (!turns.isEmpty()) ((ObjectNode) turns.get(turns.size() - 1)).put("status", "FAILED");
        normalized.set("failure", json.valueToTree(failure));
        var event = events.addObject();
        event.put("sequence", events.size()).put("type", "SESSION_FAILED").put("status", "FAILED")
                .put("modelCalls", checkpoint.getModelCalls())
                .put("detail", "Interrupted pending tool batch detected during explicit recovery");
        event.set("payload", json.valueToTree(failure));
        return json.treeToValue(normalized, SessionCheckpoint.class);
    }

    /** Older model failures have a RUNNING checkpoint followed only by request bookkeeping. */
    private SessionCheckpoint recoverLegacyFailure(SessionCheckpoint checkpoint,
            com.fasterxml.jackson.databind.JsonNode events) throws IOException {
        long watermark = checkpoint.getLastEventSequence();
        if (checkpoint.getPendingToolBatch() != null || watermark < 1 || watermark >= events.size()
                || !events.get(events.size() - 1).path("type").asText().equals("SESSION_FAILED")
                || !(events.get((int) watermark - 1).path("status").asText().equals("RUNNING")
                    || legacyBatchCompleted(checkpoint, events, (int) watermark))
                || events.get((int) watermark - 1).path("modelCalls").asInt() != checkpoint.getModelCalls()) {
            throw new IOException("Legacy failure has no verified pre-tool checkpoint");
        }
        int calls = checkpoint.getModelCalls();
        boolean started = false;
        for (int index = (int) watermark; index < events.size(); index++) {
            var event = events.get(index);
            String type = event.path("type").asText();
            if (type.equals("MODEL_CALL_STARTED")) {
                if (started) throw new IOException("Legacy failure has multiple uncheckpointed requests");
                started = true;
                calls++;
            } else if (!type.equals("REQUEST_BUDGET_CHECKED") && !type.equals("CONTEXT_ASSEMBLED")
                    && !(index == events.size() - 1 && type.equals("SESSION_FAILED"))) {
                throw new IOException("Legacy failure includes uncheckpointed tools or state changes");
            }
            String expectedStatus = index == events.size() - 1 ? "FAILED" : "RUNNING";
            if (event.path("modelCalls").asInt() != calls
                    || !event.path("status").asText().equals(expectedStatus)) {
                throw new IOException("Legacy failure call count or status disagrees with checkpoint");
            }
        }
        var normalized = (ObjectNode) json.valueToTree(checkpoint);
        normalized.put("status", "FAILED").put("modelCalls", calls).put("lastEventSequence", events.size());
        var turns = normalized.path("turns");
        if (turns.isArray() && !turns.isEmpty()) ((ObjectNode) turns.get(turns.size() - 1)).put("status", "FAILED");
        var failure = new dev.backendagent.runtime.SessionFailure(
                dev.backendagent.runtime.SessionFailure.Boundary.COMPLETE_TOOL_BATCH,
                calls, checkpoint.getHistory().size(), "LegacyModelFailure");
        normalized.set("failure", json.valueToTree(failure));
        return json.treeToValue(normalized, SessionCheckpoint.class);
    }

    /** Old runtimes switched to RUNNING without logging a batch-completed event. */
    private boolean legacyBatchCompleted(SessionCheckpoint checkpoint,
            com.fasterxml.jackson.databind.JsonNode events, int watermark) throws IOException {
        if (!events.get(watermark - 1).path("status").asText().equals("WAITING_FOR_TOOL")) return false;
        int responseIndex = watermark - 1;
        while (responseIndex >= 0 && !events.get(responseIndex).path("type").asText().equals("MODEL_RESPONSE_RECEIVED")) responseIndex--;
        if (responseIndex < 0) return false;
        var response = events.get(responseIndex);
        if (response.path("modelCalls").asInt() != checkpoint.getModelCalls()
                || !response.path("payload").path("type").asText().equals("TOOL_CALL")) return false;
        var expected = response.path("payload").path("toolCalls");
        if (!expected.isArray() || expected.isEmpty()) return false;
        int completed = 0;
        for (int i = responseIndex + 1; i < watermark; i++) {
            var event = events.get(i);
            if (event.path("modelCalls").asInt() != checkpoint.getModelCalls()) return false;
            if (event.path("type").asText().equals("TOOL_EXECUTION_SUCCEEDED")
                    || event.path("type").asText().equals("TOOL_EXECUTION_FAILED")) {
                if (completed >= expected.size() || !expected.get(completed++).equals(event.path("payload").path("call"))) return false;
            }
        }
        return completed == expected.size();
    }

    /** Replay batch cursor events; a saved cursor cannot skip or repeat recorded tool results. */
    private void validateFailedToolBoundary(com.fasterxml.jackson.databind.JsonNode events,
            SessionCheckpoint checkpoint) throws IOException {
        dev.backendagent.runtime.PendingToolBatch cursor = null;
        com.fasterxml.jackson.databind.JsonNode lastResponse = null;
        for (var event : events) {
            switch (event.path("type").asText()) {
                case "MODEL_RESPONSE_RECEIVED" -> lastResponse = event.path("payload");
                case "TOOL_BATCH_STARTED" -> {
                    if (cursor != null) throw new IOException("Overlapping pending tool batches");
                    cursor = json.treeToValue(event.path("payload"), dev.backendagent.runtime.PendingToolBatch.class);
                    if (cursor.getNextCallIndex() != 0 || cursor.isInFlight()) throw new IOException("Invalid initial batch cursor");
                    if (lastResponse == null || !lastResponse.path("type").asText().equals("TOOL_CALL")
                            || !lastResponse.path("toolCalls").equals(json.valueToTree(cursor.getCalls()))
                            || !java.util.Objects.equals(lastResponse.path("assistantContent").isNull() ? null
                                : lastResponse.path("assistantContent").asText(), cursor.getAssistantContent())) {
                        throw new IOException("Pending batch differs from model response");
                    }
                }
                case "TOOL_EXECUTION_STARTED" -> {
                    var started = json.treeToValue(event.path("payload"), dev.backendagent.runtime.PendingToolBatch.class);
                    if (cursor == null || !cursor.isInFlight() || !cursorMatches(cursor, started)
                            || started.getPreExecutionHashes() == null) {
                        throw new IOException("Missing verified pre-execution hashes");
                    }
                    cursor = started;
                }
                case "TOOL_CALL_REQUESTED" -> {
                    if (cursor != null) {
                        if (cursor.isInFlight() || cursor.getNextCallIndex() >= cursor.getCalls().size()
                                || !json.valueToTree(cursor.getCalls().get(cursor.getNextCallIndex())).equals(event.path("payload"))) {
                            throw new IOException("Tool request disagrees with batch cursor");
                        }
                        cursor = cursor.starting();
                    }
                }
                case "TOOL_BATCH_PROGRESS" -> {
                    if (cursor == null || !cursor.isInFlight()) throw new IOException("Batch progress without a requested tool");
                    cursor = cursor.completedOne();
                    if (!cursorMatches(cursor, json.treeToValue(event.path("payload"), dev.backendagent.runtime.PendingToolBatch.class))) {
                        throw new IOException("Batch progress disagrees with cursor");
                    }
                }
                case "TOOL_CALL_RETRY_AUTHORIZED" -> {
                    if (cursor == null || !cursor.isInFlight()) throw new IOException("Retry without an in-flight tool");
                    cursor = cursor.retrying();
                }
                case "TOOL_BATCH_COMPLETED" -> {
                    if (cursor == null || cursor.isInFlight() || cursor.getNextCallIndex() != cursor.getCalls().size()) {
                        throw new IOException("Tool batch completed before all calls returned");
                    }
                    cursor = null;
                }
                default -> { }
            }
        }
        var saved = checkpoint.getPendingToolBatch();
        if (!cursorMatches(cursor, saved)) throw new IOException("Pending batch disagrees with event log");
        if (saved != null && saved.isInFlight()
                && !java.util.Objects.equals(cursor.getPreExecutionHashes(), saved.getPreExecutionHashes())) {
            throw new IOException("Pre-execution hashes disagree with event log");
        }
        if (saved == null) {
            if (!checkpoint.getFailure().canResume()) throw new IOException("Failure has uncertain untracked tool effects");
            return;
        }
        var expectedBoundary = saved.isInFlight() ? dev.backendagent.runtime.SessionFailure.Boundary.TOOL_IN_FLIGHT
                : dev.backendagent.runtime.SessionFailure.Boundary.BETWEEN_TOOLS;
        if (checkpoint.getFailure().getBoundary() != expectedBoundary) throw new IOException("Failure boundary disagrees with pending tool");
        if (saved.getModelCallNumber() != checkpoint.getModelCalls()) throw new IOException("Pending batch call number differs from checkpoint");
        var completed = checkpoint.getHistory().stream().filter(e -> e.modelCallNumber() == saved.getModelCallNumber()).toList();
        if (completed.size() != saved.getNextCallIndex()) throw new IOException("Pending cursor skips completed observations");
        for (int i = 0; i < completed.size(); i++) {
            if (!completed.get(i).call().equals(saved.getCalls().get(i))
                    || !java.util.Objects.equals(completed.get(i).assistantContent(), saved.getAssistantContent())) {
                throw new IOException("Pending batch differs from completed observations");
            }
        }
        for (int i = saved.getNextCallIndex(); i < saved.getCalls().size(); i++) {
            String id = saved.getCalls().get(i).id();
            if (checkpoint.getHistory().stream().anyMatch(e -> e.call().id().equals(id))) {
                throw new IOException("Pending tool already has a completed result");
            }
        }
    }

    private boolean cursorMatches(dev.backendagent.runtime.PendingToolBatch left,
                                  dev.backendagent.runtime.PendingToolBatch right) {
        if (left == null || right == null) return left == right;
        return left.getModelCallNumber() == right.getModelCallNumber()
                && left.getNextCallIndex() == right.getNextCallIndex() && left.isInFlight() == right.isInFlight()
                && left.getCalls().equals(right.getCalls())
                && java.util.Objects.equals(left.getAssistantContent(), right.getAssistantContent());
    }

    private void acquireLock(UUID id) throws IOException {
        if (locks.containsKey(id)) { throw new IOException("Session already attached to this writer"); }
        var channel = FileChannel.open(sessionDirectory(id).resolve("session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) { throw new IOException("Session is already running in another process"); }
            locks.put(id, lock);
            lockChannels.put(id, channel);
        } catch (java.nio.channels.OverlappingFileLockException failure) {
            channel.close();
            throw new IOException("Session is already running in another writer", failure);
        } catch (IOException failure) { channel.close(); throw failure; }
    }

    private void releaseLock(UUID id) {
        var lock = locks.remove(id);
        var channel = lockChannels.remove(id);
        try { if (lock != null) { lock.release(); } } catch (IOException ignored) { }
        try { if (channel != null) { channel.close(); } } catch (IOException ignored) { }
        sequences.remove(id);
    }

    @Override
    public synchronized void close() {
        for (UUID id : Set.copyOf(locks.keySet())) { releaseLock(id); }
    }

    @Override
    public synchronized void append(AgentSession session, AgentSession.Event event, Object payload) {
        requireWritable(session.id());
        if (event.sequence() != sequences.get(session.id()) + 1) {
            failed.add(session.id());
            throw new PersistenceException("Session event sequence is not continuous", null);
        }
        try {
            ObjectNode stored = json.createObjectNode();
            stored.put("sequence", event.sequence()).put("timestamp", Instant.now().toString());
            stored.put("type", event.type().name()).put("detail", event.detail());
            stored.put("status", session.status().name()).put("modelCalls", session.modelCalls());
            stored.set("payload", json.valueToTree(payload));
            byte[] bytes = (json.writeValueAsString(stored) + "\n").getBytes(StandardCharsets.UTF_8);
            safeDirectory(session.id());
            try (var channel = FileChannel.open(sessionDirectory(session.id()).resolve("events.jsonl"),
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
                channel.force(true);
            }
            sequences.put(session.id(), event.sequence());
        } catch (IOException | IllegalArgumentException failure) {
            failed.add(session.id());
            throw new PersistenceException("Cannot append session event; execution stopped", failure);
        }
    }

    public synchronized void saveSnapshot(AgentSession session) {
        requireWritable(session.id());
        Path temporary = null;
        try {
            safeDirectory(session.id());
            ObjectNode snapshot = json.createObjectNode();
            snapshot.put("schemaVersion", 1).put("id", session.id().toString()).put("objective", session.objective());
            snapshot.put("status", session.status().name()).put("modelCalls", session.modelCalls());
            snapshot.put("answer", session.answer()).put("lastEventSequence", session.events().size());
            snapshot.put("savedAt", Instant.now().toString());
            snapshot.set("history", json.valueToTree(session.history()));
            snapshot.set("contextProjection", json.valueToTree(session.contextProjection()));
            snapshot.set("contextSummary", json.valueToTree(session.contextSummary()));
            snapshot.set("workspaceState", json.valueToTree(session.workspaceState()));
            snapshot.put("testStatus", session.workspaceState().testStatus().name());
            snapshot.set("workingMemory", json.valueToTree(session.memoryFacts()));
            snapshot.set("historicalEvidence", json.valueToTree(session.historicalEvidence()));
            snapshot.set("turns", json.valueToTree(session.turns()));
            snapshot.set("conversationSummary",json.valueToTree(session.conversationSummary()));
            snapshot.set("failure",json.valueToTree(session.failure()));
            snapshot.set("pendingToolBatch",json.valueToTree(session.pendingToolBatch()));
            Path target = sessionDirectory(session.id()).resolve("session.json");
            if (Files.isSymbolicLink(target)) { throw new IOException("Symlink snapshot"); }
            temporary = Files.createTempFile(sessionDirectory(session.id()), ".snapshot-", ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
                while (bytes.hasRemaining()) { channel.write(bytes); }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | IllegalArgumentException failure) {
            failed.add(session.id());
            throw new PersistenceException("Cannot save session snapshot; persisted events remain available", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    /** Reads with a new store instance, with no model configuration or execution involved. */
    public ObjectNode read(UUID id) throws IOException {
        safeDirectory(id);
        Path metadata = sessionDirectory(id).resolve("session.json");
        Path log = sessionDirectory(id).resolve("events.jsonl");
        if (Files.isSymbolicLink(metadata) || Files.isSymbolicLink(log)
                || Files.size(metadata) > 16 * 1024 * 1024 || Files.size(log) > 32 * 1024 * 1024) {
            throw new IOException("Invalid session files");
        }
        var snapshot = json.readTree(Files.readString(metadata));
        if (snapshot == null || !snapshot.isObject() || snapshot.path("schemaVersion").asInt() != 1
                || !snapshot.path("id").asText().equals(id.toString())) { throw new IOException("Invalid snapshot"); }
        ObjectNode result = json.createObjectNode();
        result.set("snapshot", snapshot);
        var events = result.putArray("events");
        String contents = Files.readString(log);
        if (!contents.isEmpty() && !contents.endsWith("\n")) { throw new IOException("Incomplete event log tail"); }
        for (String line : contents.lines().toList()) {
            var event = json.readTree(line);
            if (event == null || !event.isObject() || event.path("sequence").asLong() != events.size() + 1L) {
                throw new IOException("Invalid event order");
            }
            events.add(event);
        }
        long snapshotSequence = snapshot.path("lastEventSequence").asLong();
        if (snapshotSequence < 0 || snapshotSequence > events.size()) { throw new IOException("Invalid snapshot event sequence"); }
        result.put("snapshotOutOfDate", snapshotSequence != events.size());
        result.put("lastRecordedStatus", events.isEmpty() ? snapshot.path("status").asText()
                : events.get(events.size() - 1).path("status").asText());
        return result;
    }

    private void requireWritable(UUID id) {
        if (failed.contains(id) || !sequences.containsKey(id)) {
            throw new PersistenceException("Session store is unavailable or session was not created", null);
        }
    }
    private void safeDirectory(UUID id) throws IOException {
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(sessionDirectory(id))
                || !Files.isDirectory(sessionDirectory(id))) { throw new IOException("Session directory unavailable"); }
    }
}
