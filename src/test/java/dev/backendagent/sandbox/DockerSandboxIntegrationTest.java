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
    @Test void successfulPatchFragmentsCanBeReusedInContainerWithoutRereading() throws Exception {
        var source=Files.createDirectory(root.resolve("source"));Files.writeString(source.resolve("Main.java"),"class Main { int n=1; }\n");
        var workspace=DockerWorkspace.create(new Workspace(source,source.resolve("private.properties")),
                Files.createDirectory(root.resolve("session")),new LocalProcessRunner(),DockerSandboxExecutor.DEFAULT_IMAGE);
        var session=new dev.backendagent.runtime.AgentSession("edit twice");var step=new java.util.concurrent.atomic.AtomicInteger();
        new dev.backendagent.runtime.AgentRuntime(q->switch(step.getAndIncrement()){
            case 0->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("r","read_file",java.util.Map.of("path","Main.java","start_line","1","end_line","10","format","raw")));
            case 1->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("p","apply_patch",java.util.Map.of("path","Main.java","old_text","n=1","new_text","n=2")));
            case 2->{assertNotNull(q.history().getLast().result().fileFingerprint());yield dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("p2","apply_patch",java.util.Map.of("path","Main.java","old_text","n=2","new_text","n=3")));}
            default->dev.backendagent.model.ModelResponse.finish("done");
        },java.util.List.of(new dev.backendagent.tools.ReadFileTool(workspace),new dev.backendagent.tools.ApplyPatchTool(workspace,session)),4).run(session);
        assertEquals(dev.backendagent.runtime.AgentSession.Status.COMPLETED,session.status());
        assertTrue(session.history().getLast().result().successful());
        assertEquals(workspace.fileFingerprint("Main.java"),session.history().getLast().result().fileFingerprint());
        assertEquals(1,session.history().stream().filter(e->e.call().name().equals("read_file")).count());
        assertEquals("class Main { int n=1; }\n",Files.readString(source.resolve("Main.java")));
    }
    @Test void contractBasisAndCompletionGateWorkWithRealMaven() throws Exception {
        Path source=Files.createDirectory(root.resolve("source"));
        Files.copy(Path.of("examples/test-repair/pom.xml"),source.resolve("pom.xml"));
        Files.createDirectories(source.resolve("src/main/java/example"));
        Files.createDirectories(source.resolve("src/test/java/example"));
        Files.createDirectories(source.resolve("docs"));
        Files.writeString(source.resolve("docs/tax.md"),"Tax in cents = (base*rate+5000)/10000; 1000 basis points = 10%.\n");
        Files.writeString(source.resolve("src/main/java/example/Tax.java"),"package example; public class Tax { public static long tax(long base,int rate){ return (base*rate+5000)/10000; } }\n");
        Files.writeString(source.resolve("src/test/java/example/TaxTest.java"),"package example; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class TaxTest { @Test void amount(){ assertEquals(180L, Tax.tax(18000,1000)); } }\n");
        var runner=new LocalProcessRunner();Path sessionDir=Files.createDirectory(root.resolve("session"));
        var workspace=DockerWorkspace.create(new Workspace(source,source.resolve("private.properties")),sessionDir,runner,DockerSandboxExecutor.DEFAULT_IMAGE);
        var session=new dev.backendagent.runtime.AgentSession("verify tax");var step=new java.util.concurrent.atomic.AtomicInteger();
        var tools=java.util.List.of(new dev.backendagent.tools.ReadFileTool(workspace),new dev.backendagent.tools.PinVerificationBasisTool(session,workspace),
                new dev.backendagent.tools.ApplyPatchTool(workspace,session),
                new dev.backendagent.tools.RunTestsTool(workspace,new DockerSandboxExecutor(runner,DockerSandboxExecutor.DEFAULT_IMAGE,Duration.ofSeconds(120))));
        new dev.backendagent.runtime.AgentRuntime(request->switch(step.getAndIncrement()){
            case 0->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("contract","read_file",java.util.Map.of("path","docs/tax.md","start_line","1","end_line","1","format","raw")));
            case 1->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("pin","pin_verification_basis",java.util.Map.of("basis_id","TAX","source_call_id","contract","start_line","1","end_line","1")));
            case 2->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("failed-test","run_tests",java.util.Map.of()));
            case 3->dev.backendagent.model.ModelResponse.finish("done");
            case 4->{
                assertEquals(1,request.verificationContext().getCompletionRejections());
                assertEquals("180",request.verificationContext().getLatestTest().getFailures().getFirst().getExpected());
                assertEquals("1800",request.verificationContext().getLatestTest().getFailures().getFirst().getActual());
                yield dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("read-test","read_file",java.util.Map.of("path","src/test/java/example/TaxTest.java","start_line","1","end_line","10","format","raw")));
            }
            case 5->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("fix-test","apply_patch",java.util.Map.of("path","src/test/java/example/TaxTest.java","old_text","assertEquals(180L,","new_text","assertEquals(1800L,")));
            case 6->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("passed-test","run_tests",java.util.Map.of()));
            default->dev.backendagent.model.ModelResponse.finish("verified");
        },tools,12).run(session);
        assertEquals(dev.backendagent.runtime.AgentSession.Status.COMPLETED,session.status());
        assertEquals(1,session.completionRejections());assertEquals(2,session.history().stream().filter(e->e.call().name().equals("run_tests")).count());
        assertTrue(Files.readString(source.resolve("src/test/java/example/TaxTest.java")).contains("assertEquals(180L,"));
    }
    @Test void untestedWriteIsRejectedBeforeRealMavenValidation() throws Exception {
        Path source=Files.createDirectory(root.resolve("source"));
        Files.copy(Path.of("examples/test-repair/pom.xml"),source.resolve("pom.xml"));
        Files.createDirectories(source.resolve("src/main/java/example"));
        Files.writeString(source.resolve("src/main/java/example/Value.java"),"package example; public class Value { public static int value(){return 1;} }\n");
        var runner=new LocalProcessRunner();var sessionDir=Files.createDirectory(root.resolve("session"));
        var workspace=DockerWorkspace.create(new Workspace(source,source.resolve("private.properties")),sessionDir,runner,DockerSandboxExecutor.DEFAULT_IMAGE);
        var session=new dev.backendagent.runtime.AgentSession("create regression test and run it");var step=new java.util.concurrent.atomic.AtomicInteger();
        var test="package example; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class ValueTest { @Test void value(){assertEquals(1, Value.value());} }\n";
        new dev.backendagent.runtime.AgentRuntime(r->switch(step.getAndIncrement()){
            case 0->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("write","create_file",java.util.Map.of("path","src/test/java/example/ValueTest.java","content",test)));
            case 1->{assertTrue(r.verificationContext().getCompletionIssue().contains("尚未运行测试"));yield dev.backendagent.model.ModelResponse.finish("please confirm tests");}
            case 2->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("test","run_tests",java.util.Map.of()));
            default->dev.backendagent.model.ModelResponse.finish("verified");
        },java.util.List.of(new dev.backendagent.tools.CreateFileTool(workspace,session),new dev.backendagent.tools.RunTestsTool(workspace,new DockerSandboxExecutor(runner,DockerSandboxExecutor.DEFAULT_IMAGE,Duration.ofSeconds(120)))),6).run(session);
        assertEquals(dev.backendagent.runtime.AgentSession.Status.COMPLETED,session.status());assertEquals(1,session.completionRejections());
        assertTrue(session.latestVerificationTest().verifiedPassed());assertFalse(Files.exists(source.resolve("src/test")));
    }
    @Test void groupedPatchesCommitOnceAndOverlapReturnsActionableFeedback() throws Exception {
        var source=Files.createDirectory(root.resolve("source"));Files.writeString(source.resolve("Main.java"),"class Main { int a=1; int b=2; }\n");
        var sessionDir=Files.createDirectory(root.resolve("session"));var w=DockerWorkspace.create(new Workspace(source,source.resolve("private.properties")),sessionDir,new LocalProcessRunner(),DockerSandboxExecutor.DEFAULT_IMAGE);
        var session=new dev.backendagent.runtime.AgentSession("edit");var step=new java.util.concurrent.atomic.AtomicInteger();
        new dev.backendagent.runtime.AgentRuntime(r->switch(step.getAndIncrement()){
            case 0,2->dev.backendagent.model.ModelResponse.callTool(new dev.backendagent.model.ToolCall("read"+step.get(),"read_file",java.util.Map.of("path","Main.java","start_line","1","end_line","10","format","raw")));
            case 1->dev.backendagent.model.ModelResponse.callTools(java.util.List.of(new dev.backendagent.model.ToolCall("a","apply_patch",java.util.Map.of("path","Main.java","old_text","a=1","new_text","a=10")),new dev.backendagent.model.ToolCall("b","apply_patch",java.util.Map.of("path","Main.java","old_text","b=2","new_text","b=20"))),null);
            case 3->dev.backendagent.model.ModelResponse.callTools(java.util.List.of(new dev.backendagent.model.ToolCall("whole","apply_patch",java.util.Map.of("path","Main.java","old_text","a=10; int b=20","new_text","a=30; int b=40")),new dev.backendagent.model.ToolCall("part","apply_patch",java.util.Map.of("path","Main.java","old_text","b=20","new_text","b=50"))),null);
            default->{assertTrue(r.history().getLast().result().content().contains("PATCH_OVERLAP"));assertTrue(r.history().getLast().result().content().contains("whole"));assertTrue(r.history().getLast().result().content().contains("全部未写入"));yield dev.backendagent.model.ModelResponse.finish("done");}
        },java.util.List.of(new dev.backendagent.tools.ReadFileTool(w),new dev.backendagent.tools.ApplyPatchTool(w,session)),6).run(session);
        assertEquals(dev.backendagent.runtime.AgentSession.Status.COMPLETED,session.status());assertEquals(1,session.workspaceState().getRevision());
        assertEquals("class Main { int a=10; int b=20; }\n",Files.readString(sessionDir.resolve("workspace/Main.java")));
        assertEquals("class Main { int a=1; int b=2; }\n",Files.readString(source.resolve("Main.java")));
    }
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
