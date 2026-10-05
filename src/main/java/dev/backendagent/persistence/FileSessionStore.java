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
    private dev.backendagent.tools.Workspace workspace;
    private final Map<UUID, FileChannel> lockChannels = new HashMap<>();
    private final Map<UUID, java.nio.channels.FileLock> locks = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<UUID, Long> sequences = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();

    public FileSessionStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path sessionDirectory(UUID id) { return root.resolve(id.toString()); }

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

    public void bindWorkspace(dev.backendagent.tools.Workspace workspace) {
        this.workspace = java.util.Objects.requireNonNull(workspace);
    }

    @Override
    public synchronized void checkpoint(AgentSession session) {
        // Unit runtimes without a bound workspace retain the existing event-only behavior.
        if (workspace == null) { return; }
        requireWritable(session.id());
        if (session.status() != AgentSession.Status.RUNNING
                && session.status() != AgentSession.Status.BUDGET_EXHAUSTED) {
            throw new PersistenceException("Checkpoint requires a complete tool-batch boundary", null);
        }
        Path temporary = null;
        try {
            safeDirectory(session.id());
            var checkpoint = new SessionCheckpoint(1, session.id(), session.objective(), session.status(),
                    session.modelCalls(), session.events().size(), session.history(), session.memoryFacts(),
                    session.staleEvidenceIds(), workspace.rootPath(), workspace.checkpointHashes(),
                    workspace.originalContents(), workspace.createdFilePaths());
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

    public synchronized AgentSession restore(UUID id, dev.backendagent.tools.Workspace workspace,
                                              int maxModelCalls) throws IOException {
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
            if (!id.equals(checkpoint.getSessionId()) || checkpoint.getLastEventSequence() != events.size()
                    || checkpoint.getStatus() != AgentSession.Status.BUDGET_EXHAUSTED || events.isEmpty()
                    || !events.get(events.size() - 1).path("status").asText().equals("BUDGET_EXHAUSTED")
                    || events.get(events.size() - 1).path("modelCalls").asInt() != checkpoint.getModelCalls()) {
                throw new IOException("Checkpoint is not a complete budget stop; pending execution cannot be replayed");
            }
            if (maxModelCalls <= checkpoint.getModelCalls()) {
                throw new IOException("--max-model-calls must exceed already used calls: " + checkpoint.getModelCalls());
            }
            if (!workspace.rootPath().equals(checkpoint.getWorkspacePath())
                    || !workspace.checkpointHashes().equals(checkpoint.getWorkspaceHashes())) {
                throw new IOException("Workspace differs from checkpoint; resume requires the same unchanged workspace");
            }
            var recordedEvents = new java.util.ArrayList<AgentSession.Event>();
            for (var event : events) {
                recordedEvents.add(new AgentSession.Event(event.path("sequence").asLong(),
                        AgentSession.EventType.valueOf(event.path("type").asText()), event.path("detail").asText()));
            }
            var session = AgentSession.restore(checkpoint, recordedEvents, this);
            workspace.restoreChanges(checkpoint.getOriginalContents(), checkpoint.getCreatedFiles());
            bindWorkspace(workspace);
            sequences.put(id, checkpoint.getLastEventSequence());
            return session;
        } catch (IOException | RuntimeException failure) {
            releaseLock(id);
            throw failure;
        }
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
            snapshot.set("workingMemory", json.valueToTree(session.memoryFacts()));
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
