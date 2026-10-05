package dev.backendagent.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import dev.backendagent.model.ModelResponse;
import dev.backendagent.model.ToolCall;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DockerSandboxExecutorTest {
    @TempDir Path root;

    @Test
    void limitsContainerMountsOnlyFilteredSnapshotAndCleansIt() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        Path config = Files.writeString(root.resolve("custom.properties"), "dummy-secret");
        Files.writeString(root.resolve("agent-local.properties"), "dummy-secret");
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target/Old.java"), "old output");
        Files.createSymbolicLink(root.resolve("Alias.java"), root.resolve("Main.java"));
        var commands = new ArrayList<List<String>>();
        var captured = new AtomicReference<Path>();
        CommandRunner runner = (command, timeout) -> {
            commands.add(command);
            if (command.get(1).equals("run")) {
                Path snapshot = snapshot(command);
                captured.set(snapshot);
                assertNotEquals(root, snapshot);
                assertTrue(Files.exists(snapshot.resolve("pom.xml")));
                assertFalse(Files.exists(snapshot.resolve("custom.properties")));
                assertFalse(Files.exists(snapshot.resolve("agent-local.properties")));
                assertTrue(Files.isDirectory(snapshot.resolve("target")));
                assertFalse(Files.exists(snapshot.resolve("target/Old.java")));
                assertFalse(Files.exists(snapshot.resolve("Alias.java")));
                assertEquals("none", command.get(command.indexOf("--network") + 1));
                assertTrue(command.contains("--read-only"));
                assertTrue(command.contains("--pull=never"));
                assertTrue(command.contains("10001:10001"));
                assertTrue(command.contains("ALL"));
                assertEquals("mvn", command.get(command.indexOf("--entrypoint") + 1));
                assertEquals("test", command.getLast());
                assertTrue(command.contains("-o"));
                assertTrue(command.contains("-Dmaven.repo.local=/opt/maven-repository"));
                assertEquals(Duration.ofSeconds(2), timeout);
                return new CommandResult(0, false, "BUILD SUCCESS");
            }
            return new CommandResult(0, false, "removed");
        };
        var result = executor(runner).runTests(new Workspace(root, config));
        assertTrue(result.successful());
        assertTrue(result.content().contains("status=PASSED"));
        assertEquals(List.of("docker", "rm", "-f", commands.getFirst().get(commands.getFirst().indexOf("--name") + 1)), commands.getLast());
        assertFalse(Files.exists(captured.get()));
    }

    @Test
    void timeoutBecomesFailedToolResultAndForcesContainerRemoval() throws Exception {
        var calls = new AtomicInteger();
        var result = executor((command, timeout) -> {
            calls.incrementAndGet();
            return command.get(1).equals("run") ? new CommandResult(-1, true, "partial output")
                    : new CommandResult(0, false, "");
        }).runTests(workspace());
        assertFalse(result.successful());
        assertTrue(result.content().contains("TIMED_OUT"));
        assertEquals(2, calls.get());
    }

    @Test
    void missingDockerAndMissingPomAreReportedWithoutHostMavenFallback() throws Exception {
        var calls = new AtomicInteger();
        CommandRunner missing = (command, timeout) -> {
            assertEquals("docker", command.getFirst());
            calls.incrementAndGet();
            throw new IOException("missing executable");
        };
        assertTrue(executor(missing).runTests(workspace()).content().contains("status=ERROR"));
        assertEquals(2, calls.get());
        Files.delete(root.resolve("pom.xml"));
        calls.set(0);
        assertFalse(executor(missing).runTests(new Workspace(root, root.resolve("agent-local.properties"))).successful());
        assertEquals(0, calls.get());
    }

    @Test
    void failedTestsReachModelAndNextSnapshotContainsItsPatch() throws Exception {
        var workspace = workspace();
        Files.writeString(root.resolve("Calculator.java"), "class Calculator { int add(int a, int b) { return a - b; } }\n");
        var executions = new AtomicInteger();
        var executor = executor((command, timeout) -> {
            if (!command.get(1).equals("run")) { return new CommandResult(0, false, ""); }
            String code = Files.readString(snapshot(command).resolve("Calculator.java"));
            if (executions.getAndIncrement() == 0) {
                assertTrue(code.contains("a - b"));
                return new CommandResult(1, false, "CalculatorTest: expected 5 but was -1");
            }
            assertTrue(code.contains("a + b"));
            return new CommandResult(0, false, "Tests run: 1, Failures: 0\nBUILD SUCCESS");
        });
        var session = new AgentSession("fix addition and test");
        var turns = new AtomicInteger();
        new AgentRuntime(request -> {
            switch (turns.getAndIncrement()) {
                case 0: return tool("test-old", "run_tests", Map.of());
                case 1:
                    assertFalse(request.history().getLast().result().successful());
                    assertTrue(request.history().getLast().result().content().contains("expected 5"));
                    return tool("read", "read_file", Map.of("path", "Calculator.java", "start_line", "1", "end_line", "20"));
                case 2: return tool("patch", "apply_patch", Map.of("path", "Calculator.java", "old_text", "a - b", "new_text", "a + b"));
                case 3: return tool("test-new", "run_tests", Map.of());
                default:
                    assertTrue(request.history().getLast().result().successful());
                    return ModelResponse.finish("fixed and tested");
            }
        }, List.of(new RunTestsTool(workspace, executor), new ReadFileTool(workspace), new ApplyPatchTool(workspace, session)), 5).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertEquals(2, executions.get());
    }

    @Test
    void rejectsModelSuppliedCommandsAndInvalidConfiguration() throws Exception {
        var tool = new RunTestsTool(workspace(), executor((command, timeout) -> { fail("must not run"); return null; }));
        assertFalse(tool.execute(Map.of("command", "arbitrary")).successful());
        assertThrows(IllegalArgumentException.class, () -> new DockerSandboxExecutor(new LocalProcessRunner(), "--privileged", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new DockerSandboxExecutor(new LocalProcessRunner(), "image", Duration.ofSeconds(601)));
    }

    private Workspace workspace() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        return new Workspace(root, root.resolve("agent-local.properties"));
    }
    private DockerSandboxExecutor executor(CommandRunner runner) {
        return new DockerSandboxExecutor(runner, "test-image:local", Duration.ofSeconds(2));
    }
    private Path snapshot(List<String> command) {
        String mount = command.get(command.indexOf("--mount") + 1);
        return Path.of(mount.substring("type=bind,src=".length(), mount.indexOf(",dst=")));
    }
    private ModelResponse tool(String id, String name, Map<String, String> arguments) {
        return ModelResponse.callTool(new ToolCall(id, name, arguments));
    }
}
