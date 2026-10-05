package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.backendagent.model.ModelClient;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {
    @TempDir
    Path directory;

    @Test
    void readsRequestedLineRangeFromActualFile() throws IOException {
        var workspace = workspace();
        Files.writeString(directory.resolve("Service.java"), "class Service {\n    void cancel() {}\n}\n");

        var result = workspace.readFile("Service.java", "2", "2");
        assertTrue(result.successful());
        assertTrue(result.content().contains("file=Service.java, totalLines=3"));
        assertTrue(result.content().contains("2:     void cancel() {}"));
        assertFalse(result.content().contains("1: class"));
    }

    @Test
    void traversalAndAbsolutePathsCannotReadOutsideWorkspace() throws IOException {
        Path repository = Files.createDirectory(directory.resolve("repo"));
        Path outside = Files.writeString(directory.resolve("outside.java"), "private fixture");
        var workspace = new Workspace(repository, directory.resolve("local.properties"));

        assertFalse(workspace.readFile("../outside.java", "1", "10").successful());
        assertFalse(workspace.readFile(outside.toString(), "1", "10").successful());
        assertFalse(workspace.searchCode("..", "private").successful());
        assertFalse(workspace.listFiles("..").successful());
    }

    @Test
    void symlinksAndSecretFilesAreExcludedFromEveryTool() throws IOException {
        Path repository = Files.createDirectory(directory.resolve("repo"));
        Path config = Files.writeString(repository.resolve("custom-config.properties"), "dummy-key");
        Files.writeString(repository.resolve("agent-local.properties"), "dummy-key");
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve(".git/config.txt"), "dummy-key");
        Files.createSymbolicLink(repository.resolve("alias.properties"), config);
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("Data.java"), "dummy-key");
        Files.createSymbolicLink(repository.resolve("linked"), outside);
        var workspace = new Workspace(repository, config);

        assertEquals("[空目录]", workspace.listFiles(".").content());
        for (String path : List.of("agent-local.properties", "custom-config.properties", "alias.properties",
                ".git/config.txt", "linked/Data.java")) {
            assertFalse(workspace.readFile(path, "1", "10").successful(), path);
        }
        assertEquals("[未找到匹配]", workspace.searchCode(".", "dummy-key").content());
        Path rootAlias = directory.resolve("repo-alias");
        Files.createSymbolicLink(rootAlias, repository);
        var aliasedWorkspace = new Workspace(rootAlias, rootAlias.resolve("custom-config.properties"));
        assertFalse(aliasedWorkspace.readFile("custom-config.properties", "1", "10").successful());
    }

    @Test
    void searchReturnsPathsAndLinesAndSkipsBuildOutput() throws IOException {
        var workspace = workspace();
        Files.createDirectories(directory.resolve("src"));
        Files.createDirectories(directory.resolve("target"));
        Files.writeString(directory.resolve("src/Service.java"), "class Service {\n    cache.evict(id);\n}\n");
        Files.writeString(directory.resolve("target/Generated.java"), "cache.evict(id);");

        var result = workspace.searchCode(".", "cache.evict");
        assertTrue(result.successful());
        assertTrue(result.content().contains("src/Service.java:2:     cache.evict(id);"));
        assertFalse(result.content().contains("Generated.java"));
    }

    @Test
    void oversizedAndNonTextFilesAndInvalidRangesAreRejected() throws IOException {
        var workspace = workspace();
        Files.writeString(directory.resolve("Large.java"), "x".repeat(256 * 1024 + 1));
        Files.write(directory.resolve("image.png"), new byte[] {0, 1, 2});
        Files.writeString(directory.resolve("Small.java"), "class Small {}");

        assertFalse(workspace.readFile("Large.java", "1", "10").successful());
        assertFalse(workspace.readFile("image.png", "1", "10").successful());
        assertFalse(workspace.readFile("Small.java", "0", "10").successful());
        assertFalse(workspace.readFile("Small.java", "1", "201").successful());
        assertFalse(workspace.readFile("Small.java", "two", "10").successful());
    }

    @Test
    void searchLimitsAreReportedAndLongLineSnippetIncludesMatch() throws IOException {
        var workspace = workspace();
        Files.writeString(directory.resolve("Long.java"), "x".repeat(1000) + "uniqueNeedle");
        assertTrue(workspace.searchCode(".", "uniqueNeedle").content().contains("uniqueNeedle"));
        Files.writeString(directory.resolve("Many.java"), "needle\n".repeat(100));
        var result = workspace.searchCode("Many.java", "needle");
        assertTrue(result.successful());
        assertEquals(50, result.content().lines().filter(line -> line.startsWith("Many.java:")).count());
        assertTrue(result.content().contains("搜索不完整"));
    }

    @Test
    void runtimeCanDiscoverUnknownFilenameAndReadItsContents() throws IOException {
        var workspace = workspace();
        String filename = "Service" + UUID.randomUUID().toString().replace("-", "") + ".java";
        Files.writeString(directory.resolve(filename), "class Discovered { void cancelOrder() {} }\n");
        // This deterministic test client follows tool observations; it is not a real LLM.
        ModelClient model = request -> {
            if (request.history().isEmpty()) {
                return ModelResponse.callTool(new ToolCall("list-1", "list_files", Map.of("path", ".")));
            }
            var observed = request.history().getLast().result();
            assertTrue(observed.successful());
            if (request.history().size() == 1) {
                String discoveredPath = observed.content().lines().filter(line -> line.endsWith(".java"))
                        .findFirst().orElseThrow();
                return ModelResponse.callTool(new ToolCall("read-1", "read_file", Map.of(
                        "path", discoveredPath, "start_line", "1", "end_line", "20")));
            }
            return ModelResponse.finish(observed.content());
        };
        var session = new AgentSession("find the code and explain cancellation");
        new AgentRuntime(model, List.of(new ListFilesTool(workspace), new ReadFileTool(workspace),
                new SearchCodeTool(workspace)), 5).run(session);

        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(3, session.modelCalls());
        assertTrue(session.answer().contains(filename));
        assertTrue(session.answer().contains("cancelOrder"));
        assertEquals(filename, session.history().get(1).call().arguments().get("path"));
    }

    private Workspace workspace() throws IOException {
        return new Workspace(directory, directory.resolve("local-config.properties"));
    }

    @Test
    void customSessionDataCannotBeReadPatchedCreatedOrCopiedIntoSandbox() throws IOException {
        Path data = Files.createDirectory(directory.resolve("session-store"));
        Files.writeString(directory.resolve("pom.xml"), "<project/>\n");
        Files.writeString(data.resolve("session.json"), "private-session-data");
        var workspace = new Workspace(directory, directory.resolve("agent-local.properties"), data);
        assertFalse(workspace.readFile("session-store/session.json", "1", "20").successful());
        assertFalse(workspace.applyPatch("session-store/session.json", "private", "public").successful());
        assertFalse(workspace.createFile("session-store/new.json", "new").successful());
        assertFalse(workspace.listFiles("session-store").successful());
        assertFalse(workspace.searchCode(".", "private-session-data").content().contains("private-session-data"));
        Path snapshot = workspace.createTestSnapshot();
        try { assertFalse(Files.exists(snapshot.resolve("session-store"))); }
        finally {
            try (var paths = Files.walk(snapshot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
            }
        }
    }
}
