package dev.backendagent.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.backendagent.tools.Workspace;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.ApplyPatchTool;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DockerWorkspaceTest {
    @TempDir Path root;
    private Path source() throws IOException {
        Path source = Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("Main.java"), "class Main {}\n");
        Files.writeString(source.resolve("agent-local.properties"), "model.api-key=secret\n");
        Files.createDirectory(source.resolve(".git"));
        Files.writeString(source.resolve(".git/config"), "private git metadata");
        Files.createDirectory(source.resolve("target"));
        Files.writeString(source.resolve("target/private.txt"), "build output");
        return source;
    }
    private Path sessionDirectory() throws IOException { return Files.createDirectory(root.resolve("session")); }

    /** Local dispatcher only in a fake runner; production always launches Docker. */
    private static class FakeDocker implements CommandRunner {
        final java.util.List<List<String>> commands = new java.util.ArrayList<>();
        boolean loseWriteAck;
        boolean failCleanup;
        public CommandResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(List.copyOf(command));
            if (command.get(1).equals("rm")) { return new CommandResult(failCleanup ? 1 : 0, false, "cleanup"); }
            Path workspace = null;
            Path exchange = null;
            for (var part : command) {
                if (part.startsWith("type=bind,src=")) {
                    int end = part.indexOf(",dst=");
                    Path path = Path.of(part.substring("type=bind,src=".length(), end));
                    if (part.contains("dst=/workspace")) { workspace = path; }
                    if (part.contains("dst=/exchange")) { exchange = path; }
                }
            }
            try {
                WorkspaceWorker.handle(workspace, exchange);
                if (loseWriteAck && !command.stream().anyMatch(c -> c.endsWith("dst=/workspace,readonly"))) {
                    Files.delete(exchange.resolve("output.json"));
                }
                return new CommandResult(0, false, "");
            } catch (Exception failure) { throw new IOException("fake worker error", failure); }
        }
    }

    @Test
    void fileOperationsUseOnlyFilteredCopyAndMutationsNeverChangeSource() throws Exception {
        Path source = source();
        Path session = sessionDirectory();
        var runner = new FakeDocker();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                session, runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        assertFalse(Files.exists(Path.of(workspace.rootPath()).resolve("agent-local.properties")));
        assertFalse(Files.exists(Path.of(workspace.rootPath()).resolve(".git")));
        assertFalse(Files.exists(Path.of(workspace.rootPath()).resolve("target")));
        assertTrue(workspace.listFiles(".").content().contains("Main.java"));
        assertTrue(workspace.readFile("Main.java", "1", "20").content().contains("class Main {}"));
        var fingerprint = workspace.readFile("Main.java", "1", "1").fileFingerprint();
        assertEquals(fingerprint, workspace.fileFingerprint("./Main.java"));
        assertEquals("Main.java", fingerprint.getPath());
        assertTrue(workspace.searchCode(".", "class Main").content().contains("Main.java:1"));
        assertTrue(workspace.applyPatch("Main.java", "class Main {}", "class Main { int n; }").successful());
        assertNotEquals(fingerprint, workspace.fileFingerprint("Main.java"));
        assertTrue(workspace.createFile("New.java", "class New {}\n").successful());
        assertTrue(workspace.diff("Main.java").content().contains("-class Main {}"));
        assertTrue(workspace.diff("New.java").content().contains("/dev/null"));
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
        assertFalse(Files.exists(source.resolve("New.java")));
        assertEquals(2, workspace.originalContents().size());
        assertEquals(java.util.Set.of("New.java"), workspace.createdFilePaths());
        assertTrue(workspace.checkpointHashes().containsKey("New.java"));
        for (var command : runner.commands) {
            if (command.get(1).equals("rm")) { continue; }
            assertTrue(command.contains("none"));
            assertTrue(command.contains("--read-only"));
            assertTrue(command.contains("ALL"));
            assertTrue(command.contains("10001:10001"));
            assertFalse(command.toString().contains(source.toString()));
            assertFalse(command.toString().contains("secret"));
            assertFalse(command.contains("sh"));
            assertFalse(command.toString().contains("docker.sock"));
        }
    }

    @Test
    void rejectedPathsNeverEscapeAndFailedCreateDoesNotOverwrite() throws Exception {
        var source = source();
        var runner = new FakeDocker();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        assertFalse(workspace.readFile("../source/agent-local.properties", "1", "20").successful());
        assertFalse(workspace.createFile("../escaped.java", "bad").successful());
        assertFalse(workspace.createFile("Main.java", "overwrite").successful());
        assertFalse(workspace.readFile("/etc/passwd", "1", "20").successful());
        assertFalse(workspace.readFile("agent-local.properties", "1", "20").successful());
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
    }

    @Test
    void unacknowledgedMutationStopsAndCannotBeAutomaticallyRetried() throws Exception {
        var source = source();
        var runner = new FakeDocker();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        runner.loseWriteAck = true;
        assertThrows(PersistenceException.class, () -> workspace.applyPatch("Main.java", "class Main {}", "class Changed {}"));
        assertTrue(Files.readString(Path.of(workspace.rootPath()).resolve("Main.java")).contains("Changed"));
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
        int commands = runner.commands.size();
        assertThrows(PersistenceException.class, () -> workspace.readFile("Main.java", "1", "20"));
        assertEquals(commands, runner.commands.size());
    }

    @Test
    void exportRequiresNewDestinationAndPreservesSource() throws Exception {
        var source = source();
        var runner = new FakeDocker();
        var session = sessionDirectory();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                session, runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        workspace.applyPatch("Main.java", "class Main {}", "class Changed {}");
        Path destination = root.resolve("exported");
        DockerWorkspace.export(session, destination);
        assertTrue(Files.readString(destination.resolve("Main.java")).contains("Changed"));
        assertFalse(Files.exists(destination.resolve("agent-local.properties")));
        assertThrows(IOException.class, () -> DockerWorkspace.export(session, destination));
        assertThrows(IOException.class, () -> DockerWorkspace.export(session, Path.of(workspace.rootPath()).resolve("nested-export")));
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
        assertThrows(IOException.class, () -> DockerWorkspace.open(root, session, runner, DockerSandboxExecutor.DEFAULT_IMAGE));
    }

    @Test
    void budgetResumeRestoresRemoteBaselinesAndContinuesSameIsolatedFiles() throws Exception {
        var source = source();
        Path data = root.resolve("sessions");
        var runner = new FakeDocker();
        UUID id;
        try (var store = new FileSessionStore(data)) {
            var session = new AgentSession("modify", store);
            store.create(session);
            id = session.id();
            var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties"), data),
                    store.sessionDirectory(id), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
            store.bindWorkspace(workspace);
            var turn = new java.util.concurrent.atomic.AtomicInteger();
            new AgentRuntime(request -> ModelResponse.callTool(turn.getAndIncrement() == 0
                    ? new ToolCall("read", "read_file", Map.of("path", "Main.java", "start_line", "1", "end_line", "20"))
                    : new ToolCall("patch", "apply_patch", Map.of("path", "Main.java", "old_text", "class Main {}", "new_text", "class Changed {}"))),
                    List.of(new ReadFileTool(workspace), new ApplyPatchTool(workspace, session)), 2).run(session);
            assertEquals(AgentSession.Status.BUDGET_EXHAUSTED, session.status());
        }
        try (var store = new FileSessionStore(data)) {
            var workspace = DockerWorkspace.open(source, store.sessionDirectory(id), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
            var session = store.restore(id, workspace, 3);
            assertEquals(1, session.workspaceState().getRevision());
            assertTrue(workspace.diff("Main.java").content().contains("-class Main {}"));
            assertFalse(session.hasCurrentRead("Main.java"));
            new AgentRuntime(request -> {
                assertEquals(1, request.workspaceState().getRevision());
                return ModelResponse.finish("continued");
            }, List.of(), 3).resume(session);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertThrows(IOException.class, () -> store.exportWorkspace(id, root.resolve("blocked-export")));
        }
        try (var store = new FileSessionStore(data)) { store.exportWorkspace(id, root.resolve("review-copy")); }
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
    }

    @Test
    void workerStartupFailsWithoutHostFallback() throws Exception {
        var source = source();
        CommandRunner broken = (command, timeout) -> new CommandResult(1, false, "Docker unavailable");
        assertThrows(PersistenceException.class, () -> DockerWorkspace.create(
                new Workspace(source, source.resolve("agent-local.properties")), sessionDirectory(), broken,
                DockerSandboxExecutor.DEFAULT_IMAGE));
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
    }
    @Test
    void testSnapshotComesFromEditedIsolatedCopyAndStillOmitsSecrets() throws Exception {
        var source = source();
        Files.writeString(source.resolve("pom.xml"), "<project/>\n");
        var runner = new FakeDocker();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        workspace.applyPatch("Main.java", "class Main {}", "class Changed {}");
        Path snapshot = workspace.createTestSnapshot();
        try {
            assertTrue(Files.readString(snapshot.resolve("Main.java")).contains("Changed"));
            assertFalse(Files.exists(snapshot.resolve("agent-local.properties")));
            assertTrue(Files.isDirectory(snapshot.resolve("target")));
        } finally {
            try (var paths = Files.walk(snapshot)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); }
            }
        }
        assertEquals("class Main {}\n", Files.readString(source.resolve("Main.java")));
    }

    @Test
    void timedOutWriteIsFatal() throws Exception {
        var source = source();
        var runner = new FakeDocker() {
            boolean timeoutWrite;
            public CommandResult run(List<String> command, Duration timeout) throws IOException {
                if (timeoutWrite && command.get(1).equals("run")
                        && command.stream().noneMatch(c -> c.endsWith("dst=/workspace,readonly"))) {
                    return new CommandResult(-1, true, "timeout");
                }
                return super.run(command, timeout);
            }
        };
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        runner.timeoutWrite = true;
        assertThrows(PersistenceException.class, () -> workspace.createFile("New.java", "class New {}"));
        assertFalse(Files.exists(source.resolve("New.java")));
    }

    @Test
    void unknownWorkerCommandsAreNeverExecuted() throws Exception {
        var source = source();
        Path exchange = Files.createDirectory(root.resolve("exchange"));
        Files.writeString(exchange.resolve("input.json"), "{\"version\":1,\"nonce\":\"test\","
                + "\"operation\":\"exec_shell\",\"arguments\":{\"command\":\"touch bad\"},\"originals\":{},\"created\":[]}");
        assertThrows(IllegalArgumentException.class, () -> WorkspaceWorker.handle(source, exchange));
        assertFalse(Files.exists(source.resolve("bad")));
        assertFalse(Files.exists(exchange.resolve("output.json")));
    }

    @Test
    void readWithoutFingerprintFromOldWorkerFailsWithoutHostFallback() throws Exception {
        var source = source();
        var runner = new FakeDocker() {
            @Override public CommandResult run(List<String> command, Duration timeout) throws IOException {
                var result = super.run(command, timeout);
                if (command.get(1).equals("run")) {
                    for (var part : command) {
                        if (part.startsWith("type=bind,src=") && part.contains("dst=/exchange")) {
                            Path exchange = Path.of(part.substring("type=bind,src=".length(), part.indexOf(",dst=")));
                            var json = new com.fasterxml.jackson.databind.ObjectMapper();
                            var response = json.readTree(Files.readString(exchange.resolve("output.json")));
                            ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("result")).remove("fileFingerprint");
                            Files.writeString(exchange.resolve("output.json"), json.writeValueAsString(response));
                        }
                    }
                }
                return result;
            }
        };
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        var read = workspace.readFile("Main.java", "1", "1");
        assertFalse(read.successful());
        assertNull(read.fileFingerprint());
        assertFalse(read.content().contains("class Main"));
        assertThrows(IOException.class, () -> workspace.fileFingerprint("Main.java"));
    }

    @Test
    void failedCleanupStopsFurtherOperations() throws Exception {
        var source = source();
        var runner = new FakeDocker();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                sessionDirectory(), runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        runner.failCleanup = true;
        assertThrows(PersistenceException.class, () -> workspace.readFile("Main.java", "1", "20"));
        int count = runner.commands.size();
        assertThrows(PersistenceException.class, () -> workspace.listFiles("."));
        assertEquals(count, runner.commands.size());
    }

}
