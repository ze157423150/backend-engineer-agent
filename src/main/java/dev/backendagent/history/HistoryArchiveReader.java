package dev.backendagent.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.WorkspaceAccess;

/** Bounded on-demand log scans; never caches an entire transcript or trusts snippets as instructions. */
public final class HistoryArchiveReader {
    private final ObservationArchive archive;
    private final WorkspaceAccess workspace;

    public HistoryArchiveReader(ObservationArchive archive, WorkspaceAccess workspace) {
        this.archive = Objects.requireNonNull(archive);
        this.workspace = workspace;
    }

    public HistoryPage search(String tool, String path, String keyword, long beforeSequence, int limit) throws IOException {
        if (tool == null || path == null || keyword == null || tool.length() > 100 || path.length() > 1024
                || keyword.length() > 200 || keyword.contains("\n") || keyword.contains("\r")
                || beforeSequence < 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Invalid history query; limit 1..20, keyword at most 200 characters");
        }
        String normalizedPath = path.isEmpty() ? "" : normalize(path);
        var metadata = new ArrayList<ToolExchange>();
        var selected = new ArrayDeque<ArchivedEntry>();
        archive.visit((sequence, exchange) -> {
            metadata.add(metadata(exchange));
            String name = exchange.call().name();
            if ((!tool.isEmpty() && !tool.equals(name)) || (tool.isEmpty()
                    && (name.equals("search_history") || name.equals("read_observation")))) { return; }
            String sourcePath = exchange.call().arguments().get("path");
            if (!path.isEmpty() && (sourcePath == null || !Path.of(sourcePath).normalize().toString().equals(normalizedPath))) { return; }
            if ((beforeSequence > 0 && sequence >= beforeSequence) || !exchange.result().content().contains(keyword)) { return; }
            selected.addLast(new ArchivedEntry(sequence, exchange));
            if (selected.size() > limit + 1) { selected.removeFirst(); }
        });
        boolean hasOlder = selected.size() > limit;
        if (hasOlder) { selected.removeFirst(); }
        var freshness = new Freshness(metadata, workspace);
        var matches = new ArrayList<HistoryReference>();
        var newestFirst = selected.descendingIterator();
        while (newestFirst.hasNext()) {
            var entry = newestFirst.next();
            var exchange = entry.exchange;
            var validity = freshness.assess(exchange);
            String[] lines = exchange.result().content().split("\\R", -1);
            int match = 0;
            while (match < lines.length && !lines[match].contains(keyword)) { match++; }
            String line = lines[Math.min(match, lines.length - 1)];
            int start = Math.max(0, line.indexOf(keyword) - 60);
            String snippet = validity == ObservationEvidence.Validity.STALE ? "[过期正文片段已省略；可显式提取历史]"
                    : line.substring(start, Math.min(start + 220, line.length()));
            matches.add(new HistoryReference(entry.sequence, exchange.call().id(), exchange.call().name(),
                    exchange.call().arguments().getOrDefault("path", ""), exchange.result().successful(),
                    validity, Math.min(match + 1, lines.length), lines.length, snippet,
                    exchange.evidence() != null ? Long.valueOf(exchange.evidence().getWorkspaceRevision())
                        : freshness.testRevisions.get(exchange.call().id())));
        }
        long next = hasOlder ? matches.getLast().getEventSequence() : 0;
        return new HistoryPage(matches, next, freshness.revision);
    }

    public ArchivedEntry read(String callId) throws IOException {
        if (callId == null || callId.isBlank() || callId.length() > 256) { throw new IllegalArgumentException("Invalid call_id"); }
        var metadata = new ArrayList<ToolExchange>();
        ArchivedEntry[] found = {null};
        archive.visit((sequence, exchange) -> {
            metadata.add(metadata(exchange));
            if (exchange.call().id().equals(callId)) { found[0] = new ArchivedEntry(sequence, exchange); }
        });
        if (found[0] != null) { found[0].validity = new Freshness(metadata, workspace).assess(found[0].exchange); }
        return found[0];
    }

    private static String normalize(String path) {
        Path value = Path.of(path);
        if (value.isAbsolute() || value.normalize().startsWith("..")) { throw new IllegalArgumentException("History path must be relative"); }
        return value.normalize().toString();
    }

    private static ToolExchange metadata(ToolExchange exchange) {
        var arguments = new HashMap<String, String>();
        for (String field : List.of("path", "call_id")) {
            if (exchange.call().arguments().containsKey(field)) { arguments.put(field, exchange.call().arguments().get(field)); }
        }
        return new ToolExchange(new ToolCall(exchange.call().id(), exchange.call().name(), arguments),
                new ToolResult(exchange.result().successful(), "", exchange.result().fileFingerprint()), null,
                exchange.modelCallNumber(), exchange.evidence());
    }

    /** Only the selected record carries its body. */
    public static final class ArchivedEntry {
        private final long sequence;
        private final ToolExchange exchange;
        private ObservationEvidence.Validity validity;
        private ArchivedEntry(long sequence, ToolExchange exchange) { this.sequence = sequence; this.exchange = exchange; }
        public long getSequence() { return sequence; }
        public ToolExchange getExchange() { return exchange; }
        public ObservationEvidence.Validity getValidity() { return validity; }
    }

    private static final class Freshness {
        private final Set<String> stale;
        private final Map<String, Long> testRevisions = new HashMap<>();
        private final Map<String, FileFingerprint> currentFiles = new HashMap<>();
        private final WorkspaceAccess workspace;
        private long revision;
        private Freshness(List<ToolExchange> metadata, WorkspaceAccess workspace) {
            this.workspace = workspace;
            this.stale = StaleObservationFilter.staleIds(metadata);
            for (var exchange : metadata) {
                if (WorkspaceState.isSuccessfulWrite(exchange)) { revision++; }
                if (exchange.call().name().equals("run_tests")) { testRevisions.put(exchange.call().id(), revision); }
            }
        }
        private ObservationEvidence.Validity assess(ToolExchange exchange) {
            if (stale.contains(exchange.call().id())) { return ObservationEvidence.Validity.STALE; }
            if (testRevisions.containsKey(exchange.call().id())) {
                return testRevisions.get(exchange.call().id()) == revision
                        ? ObservationEvidence.Validity.CURRENT : ObservationEvidence.Validity.STALE;
            }
            if (workspace == null || exchange.evidence() == null) { return ObservationEvidence.Validity.UNKNOWN; }
            String path = exchange.evidence().getFile().getPath();
            if (!currentFiles.containsKey(path)) {
                try { currentFiles.put(path, workspace.fileFingerprint(path)); }
                catch (IOException | IllegalArgumentException unavailable) { currentFiles.put(path, null); }
            }
            var current = currentFiles.get(path);
            return current == null ? ObservationEvidence.Validity.UNKNOWN
                    : current.equals(exchange.evidence().getFile()) ? ObservationEvidence.Validity.CURRENT : ObservationEvidence.Validity.STALE;
        }
    }
}
