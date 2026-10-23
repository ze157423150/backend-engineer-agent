package dev.backendagent.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import dev.backendagent.model.ToolExchange;

/** Bounded request metadata, rebuilt from durable tool history before projection. */
public final class TurnWriteSummary {
    private final List<String> paths;
    private final int omittedPaths;

    private TurnWriteSummary(List<String> paths, int omittedPaths) {
        this.paths = List.copyOf(paths);
        this.omittedPaths = omittedPaths;
    }

    public List<String> getPaths() { return paths; }
    public int getOmittedPaths() { return omittedPaths; }

    public static TurnWriteSummary fromHistory(List<ToolExchange> history) {
        var unique = new LinkedHashSet<String>();
        for (var exchange : history) {
            if (!WorkspaceState.isSuccessfulWrite(exchange)) continue;
            String path = exchange.result().fileFingerprint() == null ? exchange.call().arguments().get("path")
                    : exchange.result().fileFingerprint().getPath();
            if (path != null && !path.isBlank()) unique.add(path);
        }
        var paths = new java.util.ArrayList<String>();
        int characters = 0;
        for (String path : unique) {
            if (paths.size() >= 32 || characters + path.length() > 2048) continue;
            paths.add(path);
            characters += path.length();
        }
        return new TurnWriteSummary(paths, unique.size() - paths.size());
    }
}
