package dev.backendagent.runtime;

import dev.backendagent.history.ArchivedBody;
import dev.backendagent.model.ToolExchange;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exact repeated observations, counted once per complete assistant tool batch. */
public final class NoProgressDetector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Set<String> seen = new HashSet<>();
    private final dev.backendagent.model.ReadCoverage readCoverage=new dev.backendagent.model.ReadCoverage();
    private long revision;
    private int consecutiveBatches;
    private int processedIndex;
    private final java.util.Map<String, Integer> patchFailures = new java.util.HashMap<>();

    static NoProgressDetector start(AgentSession session) {
        var detector = new NoProgressDetector();
        var history = session.history();
        detector.processedIndex = session.turns().getLast().getStartHistoryIndex();
        detector.revision = WorkspaceState.fromHistory(history.subList(0, detector.processedIndex)).getRevision();
        detector.observeCompletedHistory(session);
        // Explicit execution/resume grants a fresh correction window, while retaining seen observations.
        detector.consecutiveBatches = 0;
        detector.patchFailures.clear();
        return detector;
    }

    NoProgressReport observeCompletedHistory(AgentSession session) {
        var history = session.history();
        int end = history.size();
        if (session.pendingToolBatch() != null) {
            int pendingCall = session.pendingToolBatch().getModelCallNumber();
            while (end > processedIndex && history.get(end - 1).modelCallNumber() == pendingCall) end--;
        }
        while (processedIndex < end) {
            int next = processedIndex + 1;
            int call = history.get(processedIndex).modelCallNumber();
            while (call > 0 && next < end && history.get(next).modelCallNumber() == call) next++;
            observeBatch(history.subList(processedIndex, next));
            processedIndex = next;
        }
        return report();
    }

    public NoProgressReport observeBatch(List<ToolExchange> batch) {
        if (batch.isEmpty()) throw new IllegalArgumentException("Progress detector requires a complete nonempty batch");
        boolean progress = false;
        for (var exchange : batch) {
            String target = exchange.call().arguments().get("path");
            if (target != null) {
                try { target = java.nio.file.Path.of(target).normalize().toString(); }
                catch (java.nio.file.InvalidPathException ignored) { /* Count failed invalid paths as supplied. */ }
                if (WorkspaceState.isSuccessfulWrite(exchange)) patchFailures.remove(target);
                else if ((exchange.call().name().equals("apply_patch") || exchange.call().name().equals("apply_patch_batch")) && !exchange.result().successful())
                    patchFailures.merge(target, 1, Integer::sum);
            }
            if (WorkspaceState.isSuccessfulWrite(exchange)) {
                revision++;
                seen.clear();
                readCoverage.clear();
                progress = true;
            } else if(exchange.call().name().equals("read_file")&&exchange.result().successful()
                    &&exchange.result().fileFingerprint()!=null&&exchange.result().readMetadata()!=null){
                progress |= readCoverage.observe(exchange.result().fileFingerprint(),exchange.result().readMetadata());
            } else if (!java.util.Set.of("remember_fact", "pin_verification_basis")
                    .contains(exchange.call().name()) || !exchange.result().successful()) {
                progress |= seen.add(signature(exchange));
            }
            // remember_fact and other bookkeeping are not independent workspace observations.
        }
        consecutiveBatches = progress ? 0 : consecutiveBatches + 1;
        return report();
    }

    public NoProgressReport report() { return new NoProgressReport(consecutiveBatches, revision, patchFailures); }

    private String signature(ToolExchange exchange) {
        var arguments = new TreeMap<>(exchange.call().arguments());
        String path = arguments.get("path");
        if (path != null) {
            try { arguments.put("path", java.nio.file.Path.of(path).normalize().toString()); }
            catch (java.nio.file.InvalidPathException invalid) { /* Failed calls still have stable raw arguments. */ }
        }
        String bodyHash = exchange.archivedBody() == null ? ArchivedBody.hash(exchange.result().content())
                : exchange.archivedBody().getSha256();
        var fingerprint = exchange.result().fileFingerprint();
        try {
            return ArchivedBody.hash(JSON.writeValueAsString(List.of(exchange.call().name(), arguments,
                    revision, exchange.result().successful(), bodyHash,
                    fingerprint == null ? "" : fingerprint.getPath() + ":" + fingerprint.getSha256())));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Cannot encode progress signature");
        }
    }
}
