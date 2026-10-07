package dev.backendagent.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.backendagent.tools.Workspace;
import dev.backendagent.model.ToolResult;

/** Fixed tool dispatcher inside the container. Never loads classes or runs commands from the repository. */
public final class WorkspaceWorker {
    public static final int MAX_PROTOCOL_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private WorkspaceWorker() { }
    public static void main(String[] args) {
        if (args.length != 0) { System.exit(2); }
        try { handle(Path.of("/workspace"), Path.of("/exchange")); }
        catch (Exception failure) { System.err.println("Workspace worker failed; operation may be incomplete."); System.exit(1); }
    }

    /** Testable dispatcher; production invokes this only from the fixed container entrypoint. */
    public static void handle(Path root, Path exchange) throws Exception {
        Path input = exchange.resolve("input.json");
        if (Files.isSymbolicLink(input) || Files.size(input) > MAX_PROTOCOL_BYTES) { throw new IllegalArgumentException("Invalid input"); }
        JsonNode request = JSON.readTree(Files.readString(input));
        if (request.path("version").asInt() != 1 || !request.path("nonce").isTextual()) {
            throw new IllegalArgumentException("Invalid protocol");
        }
        String operation = request.path("operation").asText();
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        Map<String, String> originals = JSON.convertValue(request.path("originals"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
        Set<String> created = JSON.convertValue(request.path("created"),
                new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() { });
        workspace.restoreChanges(originals, created);
        var args = request.path("arguments");
        Set<String> expected = switch (operation) {
            case "ping", "hashes" -> Set.of();
            case "list_files", "workspace_diff", "file_fingerprint" -> Set.of("path");
            case "read_file" -> Set.of("path", "start_line", "end_line");
            case "search_code" -> Set.of("path", "query");
            case "apply_patch" -> Set.of("path", "old_text", "new_text");
            case "create_file" -> Set.of("path", "content");
            default -> throw new IllegalArgumentException("Operation not allowed");
        };
        var actual = new java.util.HashSet<String>();
        args.fieldNames().forEachRemaining(actual::add);
        if (!args.isObject() || !expected.equals(actual)) { throw new IllegalArgumentException("Unexpected arguments"); }
        ToolResult result = switch (operation) {
            case "ping" -> new ToolResult(true, "workspace-worker-v1");
            case "list_files" -> workspace.listFiles(text(args, "path"));
            case "read_file" -> workspace.readFile(text(args, "path"), text(args, "start_line"), text(args, "end_line"));
            case "file_fingerprint" -> new ToolResult(true, "file fingerprint", workspace.fileFingerprint(text(args, "path")));
            case "search_code" -> workspace.searchCode(text(args, "path"), text(args, "query"));
            case "apply_patch" -> workspace.applyPatch(text(args, "path"), text(args, "old_text"), text(args, "new_text"));
            case "create_file" -> workspace.createFile(text(args, "path"), text(args, "content"));
            case "workspace_diff" -> workspace.diff(text(args, "path"));
            case "hashes" -> new ToolResult(true, "workspace hashes");
            default -> throw new IllegalArgumentException("Operation not allowed");
        };
        var response = JSON.createObjectNode().put("version", 1).put("nonce", request.path("nonce").asText());
        response.set("result", JSON.valueToTree(result));
        response.set("originals", JSON.valueToTree(workspace.originalContents()));
        response.set("created", JSON.valueToTree(workspace.createdFilePaths()));
        if (operation.equals("hashes")) { response.set("hashes", JSON.valueToTree(workspace.checkpointHashes())); }
        byte[] bytes = JSON.writeValueAsBytes(response);
        if (bytes.length > MAX_PROTOCOL_BYTES) { throw new IllegalArgumentException("Response exceeds limit"); }
        Files.write(exchange.resolve("output.json"), bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static String text(JsonNode args, String name) {
        if (!args.path(name).isTextual()) { throw new IllegalArgumentException("Invalid tool argument"); }
        return args.path(name).asText();
    }
}
