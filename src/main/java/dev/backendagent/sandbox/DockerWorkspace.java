package dev.backendagent.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.backendagent.model.ToolResult;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.tools.Workspace;
import dev.backendagent.tools.WorkspaceAccess;

/** Isolated persistent source copy; each repository operation runs in a disposable constrained container. */
public final class DockerWorkspace implements WorkspaceAccess {
    private final Path root;
    private final CommandRunner runner;
    private final String image;
    private final ObjectMapper json = new ObjectMapper();
    private Map<String, String> originals = Map.of();
    private Set<String> created = Set.of();
    private boolean uncertainMutation;

    private DockerWorkspace(Path root, CommandRunner runner, String image) throws IOException {
        if (!image.matches("[A-Za-z0-9][A-Za-z0-9._/:@-]{0,200}")) { throw new IllegalArgumentException("Invalid image"); }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Invalid isolated workspace"); }
        this.root = root.toRealPath();
        if (this.root.toString().contains(",")) { throw new IOException("Docker bind paths cannot contain commas"); }
        this.runner = java.util.Objects.requireNonNull(runner);
        this.image = image;
        if (!invoke("ping", Map.of(), false).path("result").path("successful").asBoolean()) {
            throw new IOException("Workspace worker not ready; rebuild the sandbox image");
        }
    }
    public static DockerWorkspace create(Workspace source, Path sessionDirectory, CommandRunner runner, String image) throws IOException {
        Path destination = sessionDirectory.resolve("workspace");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Isolated workspace already exists"); }
        Files.setPosixFilePermissions(sessionDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path snapshot = source.createWorkspaceSnapshot();
        try {
            copySnapshot(snapshot, destination);
            // The private session parent protects this writable mount from other host users.
            try (var paths = Files.walk(destination)) {
                for (Path path : paths.toList()) {
                    Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(
                            Files.isDirectory(path) ? "rwxrwxrwx" : "rw-rw-rw-"));
                }
            }
            Files.writeString(sessionDirectory.resolve("source-workspace.txt"), source.rootPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            return new DockerWorkspace(destination, runner, image);
        } finally { deleteTree(snapshot); }
    }
    public static DockerWorkspace open(Path source, Path sessionDirectory, CommandRunner runner, String image) throws IOException {
        Path marker = sessionDirectory.resolve("source-workspace.txt");
        if (Files.isSymbolicLink(sessionDirectory) || Files.isSymbolicLink(marker) || Files.size(marker) > 8192
                || !Files.readString(marker).equals(source.toRealPath().toString())) {
            throw new IOException("Resume requires the original source path and its isolated workspace");
        }
        return new DockerWorkspace(sessionDirectory.resolve("workspace"), runner, image);
    }
    public String rootPath() { return root.toString(); }
    public ToolResult listFiles(String path) { return tool("list_files", Map.of("path", path), false); }
    public ToolResult readFile(String path, String start, String end) { return tool("read_file", Map.of("path", path, "start_line", start, "end_line", end), false); }
    public dev.backendagent.model.FileFingerprint fileFingerprint(String path) throws IOException {
        return json.treeToValue(invoke("file_fingerprint", Map.of("path", path), false).path("result"),
                ToolResult.class).fileFingerprint();
    }
    public ToolResult searchCode(String path, String query) { return tool("search_code", Map.of("path", path, "query", query), false); }
    public ToolResult applyPatch(String path, String oldText, String newText) { return tool("apply_patch", Map.of("path", path, "old_text", oldText, "new_text", newText), true); }
    public ToolResult createFile(String path, String content) { return tool("create_file", Map.of("path", path, "content", content), true); }
    public ToolResult diff(String path) { return tool("workspace_diff", Map.of("path", path), false); }
    public Map<String, String> originalContents() { return originals; }
    public Set<String> createdFilePaths() { return created; }
    public Map<String, String> checkpointHashes() throws IOException {
        return json.convertValue(invoke("hashes", Map.of(), false).path("hashes"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
    }
    public void restoreChanges(Map<String, String> originals, Set<String> created) throws IOException {
        if (originals.size() > 32 || !originals.keySet().containsAll(created)) { throw new IOException("Invalid restored changes"); }
        this.originals = Map.copyOf(originals);
        this.created = Set.copyOf(created);
        invoke("hashes", Map.of(), false); // Worker validates file paths/baselines before any later tools.
    }
    public Path createTestSnapshot() throws IOException {
        // Trusted lifecycle copy of ONLY the isolated source; no tool reads the original repository here.
        return new Workspace(root, root.resolve("agent-local.properties")).createTestSnapshot();
    }

    private ToolResult tool(String operation, Map<String, String> arguments, boolean mutation) {
        try { return json.treeToValue(invoke(operation, arguments, mutation).path("result"), ToolResult.class); }
        catch (IOException failure) {
            return new ToolResult(false, "隔离工作区工具执行失败：检查 Docker、镜像和权限；更新代码后须重建沙箱镜像。没有在宿主机回退执行。" );
        }
    }
    private synchronized JsonNode invoke(String operation, Map<String, String> arguments, boolean mutation) throws IOException {
        if (uncertainMutation) { throw new PersistenceException("Workspace has an unacknowledged mutation; automatic continuation refused", null); }
        Path exchangeParent = Files.createTempDirectory("backend-agent-exchange-");
        Path exchange = Files.createDirectory(exchangeParent.resolve("io"));
        String name = "backend-agent-workspace-" + UUID.randomUUID();
        boolean attempted = false;
        try {
            Files.setPosixFilePermissions(exchange, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            String nonce = UUID.randomUUID().toString();
            var request = json.createObjectNode().put("version", 1).put("nonce", nonce).put("operation", operation);
            request.set("arguments", json.valueToTree(arguments));
            request.set("originals", json.valueToTree(originals));
            request.set("created", json.valueToTree(created));
            byte[] input = json.writeValueAsBytes(request);
            if (input.length > WorkspaceWorker.MAX_PROTOCOL_BYTES) { throw new IOException("Worker input too large"); }
            Files.write(exchange.resolve("input.json"), input);
            attempted = true;
            var execution = runner.run(command(exchange, name, mutation), Duration.ofSeconds(30));
            Path output = exchange.resolve("output.json");
            if (execution.isTimedOut() || execution.getExitCode() != 0 || Files.isSymbolicLink(output)
                    || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > WorkspaceWorker.MAX_PROTOCOL_BYTES) {
                throw new IOException("Docker worker failed or incomplete response");
            }
            var response = json.readTree(Files.readString(output));
            if (response == null || response.path("version").asInt() != 1 || !nonce.equals(response.path("nonce").asText())
                    || !response.path("result").path("successful").isBoolean()
                    || !response.path("result").path("content").isTextual()
                    || response.path("result").path("content").asText().length() > 25000
                    || !response.path("originals").isObject() || !response.path("created").isArray()) {
                throw new IOException("Invalid worker acknowledgement");
            }
            var result = json.treeToValue(response.path("result"), ToolResult.class);
            boolean needsFingerprint = result.successful()
                    && (operation.equals("read_file") || operation.equals("file_fingerprint"));
            if (needsFingerprint && (result.fileFingerprint() == null
                    || !Path.of(arguments.get("path")).normalize().toString().equals(result.fileFingerprint().getPath()))) {
                throw new IOException("Missing or mismatched worker file fingerprint; rebuild sandbox image");
            }
            if (!needsFingerprint && result.fileFingerprint() != null) {
                throw new IOException("Unexpected worker fingerprint");
            }
            var nextOriginals = json.convertValue(response.path("originals"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
            var nextCreated = json.convertValue(response.path("created"),
                    new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() { });
            if (nextOriginals.size() > 32 || !nextOriginals.keySet().containsAll(nextCreated)) { throw new IOException("Invalid worker state"); }
            originals = Map.copyOf(nextOriginals);
            created = Set.copyOf(nextCreated);
            return response;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            if (mutation && attempted) { uncertainMutation = true; throw new PersistenceException("Isolated write interrupted; inspect sandbox before continuing", failure); }
            throw new IOException("Workspace worker interrupted", failure);
        } catch (IOException | RuntimeException failure) {
            if (mutation && attempted) { uncertainMutation = true; throw new PersistenceException("Isolated write acknowledgement missing; inspect sandbox before continuing", failure); }
            if (failure instanceof IOException io) { throw io; }
            throw new IOException("Invalid worker protocol", failure);
        } finally {
            if (attempted) {
                try {
                    var cleanup = runner.run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(10));
                    if (cleanup.isTimedOut() || (cleanup.getExitCode() != 0 && !cleanup.getOutput().contains("No such container"))) {
                        uncertainMutation = true;
                        throw new PersistenceException("Cannot confirm workspace container cleanup; execution stopped", null);
                    }
                } catch (InterruptedException failure) { uncertainMutation = true; Thread.currentThread().interrupt(); throw new PersistenceException("Workspace cleanup interrupted", failure); }
                catch (IOException failure) { uncertainMutation = true; throw new PersistenceException("Workspace cleanup failed", failure); }
            }
            deleteTree(exchangeParent);
        }
    }
    private List<String> command(Path exchange, String name, boolean mutation) {
        return List.of("docker", "run", "--rm", "--pull=never", "--name", name,
                "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", "128", "--memory", "256m", "--memory-swap", "256m", "--cpus", "1", "--user", "10001:10001",
                "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m", "--mount", "type=bind,src=" + root + ",dst=/workspace" + (mutation ? "" : ",readonly"),
                "--mount", "type=bind,src=" + exchange + ",dst=/exchange", "--workdir", "/workspace",
                "--env", "HOME=/tmp", "--entrypoint", "java", image, "-Xmx128m", "-cp", "/opt/agent-tools.jar",
                "dev.backendagent.sandbox.WorkspaceWorker");
    }
    public static void export(Path sessionDirectory, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Export destination must not exist"); }
        Path source = sessionDirectory.resolve("workspace");
        if (Files.isSymbolicLink(sessionDirectory) || Files.isSymbolicLink(source)) { throw new IOException("Invalid sandbox path"); }
        Path absoluteDestination = destination.toAbsolutePath().normalize();
        Path resolvedDestination = absoluteDestination.getParent().toRealPath().resolve(absoluteDestination.getFileName());
        if (resolvedDestination.startsWith(sessionDirectory.toRealPath())) {
            throw new IOException("Export must be outside the session directory");
        }
        var workspace = new Workspace(source, source.resolve("agent-local.properties"));
        Path snapshot = workspace.createWorkspaceSnapshot();
        try { copySnapshot(snapshot, destination.toAbsolutePath()); }
        finally { deleteTree(snapshot); }
    }
    private static void copySnapshot(Path snapshot, Path destination) throws IOException {
        Files.createDirectory(destination);
        try (var paths = Files.walk(snapshot)) {
            for (Path source : paths.toList()) {
                if (source.equals(snapshot)) { continue; }
                if (Files.isSymbolicLink(source)) { throw new IOException("Snapshot contains a symlink"); }
                Path target = destination.resolve(snapshot.relativize(source));
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(target); }
                else { Files.copy(source, target); }
            }
        } catch (IOException | RuntimeException failure) {
            deleteTree(destination);
            throw failure;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) { return; }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }
}
