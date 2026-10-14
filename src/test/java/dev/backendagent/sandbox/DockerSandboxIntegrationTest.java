package dev.backendagent.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import dev.backendagent.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real container verification after building the local image. Never invokes a model API. */
@EnabledIfSystemProperty(named = "docker.integration", matches = "true")
class DockerSandboxIntegrationTest {
    @Test void createdSourceCanBePatchedInContainerWithoutReadFile() throws Exception {
        Path source=Files.createDirectory(root.resolve("source"));Path sessionDir=Files.createDirectory(root.resolve("session"));
        var w=DockerWorkspace.create(new Workspace(source,source.resolve("agent-local.properties")),sessionDir,new LocalProcessRunner(),DockerSandboxExecutor.DEFAULT_IMAGE);
        var s=new dev.backendagent.runtime.AgentSession("create and edit");var count=new java.util.concurrent.atomic.AtomicInteger();
        new dev.backendagent.runtime.AgentRuntime(request->switch(count.getAndIncrement()){
            case 0->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("c","create_file",java.util.Map.of("path","Main.java","content","class Main { int n=1; }\n")));
            case 1->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("p","apply_patch",java.util.Map.of("path","Main.java","old_text","n=1","new_text","n=2")));
            default->dev.backendagent.model.ModelResponse.finish("done");
        },java.util.List.of(new dev.backendagent.tools.CreateFileTool(w,s),new dev.backendagent.tools.ApplyPatchTool(w,s)),3).run(s);
        assertTrue(s.history().getLast().result().successful());
        assertTrue(s.history().stream().noneMatch(e->e.call().name().equals("read_file")));
        assertTrue(Files.readString(sessionDir.resolve("workspace/Main.java")).contains("n=2"));
        assertFalse(Files.exists(source.resolve("Main.java")));
    }
    @Test void rawReadAndNestedCreationWorkInsideContainer() throws Exception {
        Path source=Files.createDirectory(root.resolve("source"));
        Files.writeString(source.resolve("Main.java"),"class Main {\r\n  int n = 1;\r\n}\r\n");
        Path session=Files.createDirectory(root.resolve("session"));
        var w=DockerWorkspace.create(new Workspace(source,source.resolve("agent-local.properties")),session,
                new LocalProcessRunner(),DockerSandboxExecutor.DEFAULT_IMAGE);
        var raw=w.readFile("Main.java","2","2","raw");
        assertTrue(raw.successful(),raw.content());
        assertEquals("  int n = 1;\r\n",raw.content());
        assertEquals(3,raw.readMetadata().getTotalLines());
        assertEquals(2,raw.readMetadata().getStartLine());
        assertEquals(2,raw.readMetadata().getEndLine());
        assertTrue(w.applyPatch("Main.java",raw.content(),"  int n = 2;\r\n").successful());
        assertTrue(w.createFile("src/test/java/example/NewTest.java","class NewTest {}\n").successful());
        assertTrue(w.readFile("src/test/java/example/NewTest.java","1","1","raw").content().contains("class NewTest"));
        assertFalse(w.createFile(".hidden/Bad.java","bad").successful());
        assertTrue(Files.readString(source.resolve("Main.java")).contains("n = 1"));
    }
    @TempDir Path root;

    @Test
    void realMavenFailsBeforePatchAndPassesAfterPatch() throws Exception {
        Path fixture = Path.of("examples/test-repair");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.toList()) {
                Path target = root.resolve(fixture.relativize(source));
                if (Files.isDirectory(source)) { Files.createDirectories(target); }
                else { Files.copy(source, target); }
            }
        }
        var workspace = new Workspace(root, root.resolve("agent-local.properties"));
        var executor = new DockerSandboxExecutor(new LocalProcessRunner(), DockerSandboxExecutor.DEFAULT_IMAGE, Duration.ofSeconds(120));
        var failed = executor.runTests(workspace);
        assertFalse(failed.successful());
        assertTrue(failed.content().contains("exitCode=1"), failed.content());
        assertTrue(failed.content().contains("CalculatorTest"), failed.content());
        assertTrue(workspace.applyPatch("src/main/java/example/Calculator.java", "left - right", "left + right").successful());
        var passed = executor.runTests(workspace);
        assertTrue(passed.successful(), passed.content());
        assertTrue(passed.content().contains("BUILD SUCCESS"));
    }
    @Test
    void realFileToolsModifyOnlyIsolatedCopyAndTestsUseItsCurrentSource() throws Exception {
        Path source = Files.createDirectory(root.resolve("source"));
        try (var paths = Files.walk(Path.of("examples/test-repair"))) {
            for (var original : paths.toList()) {
                Path target = source.resolve(Path.of("examples/test-repair").relativize(original));
                if (Files.isDirectory(original)) { Files.createDirectories(target); }
                else { Files.copy(original, target); }
            }
        }
        Files.writeString(source.resolve("agent-local.properties"), "secret-key");
        Path session = Files.createDirectory(root.resolve("session"));
        var runner = new LocalProcessRunner();
        var workspace = DockerWorkspace.create(new Workspace(source, source.resolve("agent-local.properties")),
                session, runner, DockerSandboxExecutor.DEFAULT_IMAGE);
        String path = "src/main/java/example/Calculator.java";
        assertTrue(workspace.readFile(path, "1", "40").content().contains("left - right"));
        assertFalse(workspace.readFile("agent-local.properties", "1", "20").successful());
        assertTrue(workspace.applyPatch(path, "left - right", "left + right").successful());
        assertTrue(workspace.diff(path).content().contains("left + right"));
        var executor = new DockerSandboxExecutor(runner, DockerSandboxExecutor.DEFAULT_IMAGE, Duration.ofSeconds(120));
        assertTrue(executor.runTests(workspace).successful());
        assertTrue(Files.readString(source.resolve(path)).contains("left - right"));
        Path destination = root.resolve("exported");
        DockerWorkspace.export(session, destination);
        assertTrue(Files.readString(destination.resolve(path)).contains("left + right"));
    }

}
