package dev.backendagent.ptc;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import dev.backendagent.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python/container/RPC verification; no model API calls. */
@EnabledIfSystemProperty(named="ptc.integration",matches="true")
class DockerPtcIntegrationTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();

    @Test void dependentHistoryCallsStayOutsideOrdinaryHistoryAndRemainRetrievableAfterRestore() throws Exception {
        var workspace=new Workspace(Files.createDirectory(root.resolve("repo")),root.resolve("private.properties"));
        var path=root.resolve("sessions");UUID id;
        try(var store=new FileSessionStore(path)) {
            var session=new AgentSession("PTC_PRIVATE_REFERENCE_"+"x".repeat(1800),store);id=session.id();store.create(session);store.bindWorkspace(workspace);
            String code="""
                    page = await tools.search_turns({'keyword':'PTC_PRIVATE_REFERENCE_', 'role':'user', 'before_turn':'0', 'limit':'1'})
                    reference = page['matches'][0]['messageId']
                    body = await tools.read_turn({'message_id':reference, 'start_line':'1', 'end_line':'2'})
                    rank = await tools.integer_calculate({'expression':'(95*20+99)/100', 'result_name':'rank'})
                    return {'messageId':reference, 'bodyLength':len(body['content']), 'rank':rank['value']}
                    """;
            run(session,code,List.of(new IntegerCalculateTool(),new SearchTurnsTool(session),new ReadTurnTool(session)),new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
            assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,session.modelCalls());assertEquals(4,session.history().size());
            var result=json.readTree(session.history().getLast().result().content());assertEquals("19",result.path("result").path("rank").asText());
            assertEquals(3,result.path("callCount").asInt());assertFalse(result.toString().contains("PTC_PRIVATE_REFERENCE_"));
            store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(path)) {
            var restored=store.openCompleted(id,workspace,10);var original=new ArrayList<ToolExchange>();
            store.observationArchive(restored).visit((s,e)->original.add(e));
            assertEquals(4,original.size());assertEquals(4,restored.history().size());
            assertTrue(original.stream().anyMatch(e->e.call().name().equals("read_turn")&&e.result().content().contains("PTC_PRIVATE_REFERENCE_")));
        }
    }

    @Test void pythonCatchesToolFailureAndOnlyReportsFinalValueWithAudit() throws Exception {
        var session=new AgentSession("核算");
        String code="""
                try:
                    await tools.integer_calculate({'expression':'1/0', 'result_name':'invalid'})
                except ToolCallError as error:
                    assert error.tool_name == 'integer_calculate'
                good = await tools.integer_calculate({'expression':'6*7', 'result_name':'answer'})
                return {'answer':good['value']}
                """;
        run(session,code,List.of(new IntegerCalculateTool()),new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
        var result=json.readTree(session.history().getLast().result().content());
        assertTrue(session.history().getLast().result().successful());assertEquals("42",result.path("result").path("answer").asText());
        assertEquals(1,result.path("failedCalls").size());assertEquals(2,result.path("callCount").asInt());
    }

    @Test void multilinePatchPreservesExactSourceAndReplacementThroughContainerRpc() throws Exception {
        var source=Files.createDirectory(root.resolve("repo"));
        String before="class A {\n    int n = 1;\n}\n",after="class A {\n    int n = 2;\n}\n";
        Files.writeString(source.resolve("A.java"),before);
        var workspace=new Workspace(source,root.resolve("private.properties"));
        var session=new AgentSession("repair multiline source");
        String code="old = \"\"\"class A {\n    int n = 1;\n}\n\"\"\"\n"
                +"new = \"\"\"class A {\n    int n = 2;\n}\n\"\"\"\n"
                +"source = await tools.read_file({'path':'A.java','start_line':1,'end_line':20})\n"
                +"assert source['content'] == old\n"
                +"return await tools.apply_patch({'path':'A.java','old_text':old,'new_text':new})";
        run(session,code,List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session)),
                new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
        assertTrue(session.history().getLast().result().successful(),session.history().getLast().result().content());
        assertEquals(after,Files.readString(source.resolve("A.java")));
        assertEquals(1,session.workspaceState().getRevision());
    }

    @Test void listPatchArgumentHasActionableFeedbackAndCanBeCorrectedWithoutRereading() throws Exception {
        var source = Files.createDirectory(root.resolve("repo"));
        String before = "class A { int n = 1; }\n";
        Files.writeString(source.resolve("A.java"), before);
        var workspace = new Workspace(source, root.resolve("private.properties"));
        var session = new AgentSession("repair source");
        String code = """
                original = await tools.read_file({'path':'A.java','start_line':1,'end_line':20})
                edits = [{'id':'one','old_text':original['content'],'new_text':'class A { int n = 2; }\\n'}]
                try:
                    await tools.apply_patch_batch({'path':'A.java','patches':edits})
                    raise AssertionError('A Python list must not reach the tool')
                except ToolCallError as error:
                    assert 'json.dumps' in error.message
                    assert '本次工具未执行' in error.message
                return await tools.apply_patch_batch({'path':'A.java','patches':json.dumps(edits, ensure_ascii=False)})
                """;
        run(session, code, List.of(new ReadFileTool(workspace), new ApplyPatchBatchTool(workspace, session)),
                new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
        assertTrue(session.history().getLast().result().successful(), session.history().getLast().result().content());
        assertEquals("class A { int n = 2; }\n", Files.readString(source.resolve("A.java")));
        assertEquals(1, session.workspaceState().getRevision());
        assertEquals(1, session.history().stream().filter(e -> e.isProgramChild() && e.call().name().equals("read_file")).count());
        assertEquals(1, session.history().stream().filter(e -> e.isProgramChild() && e.call().name().equals("apply_patch_batch")).count());
    }

    @Test void missingReadRangeIsRejectedAndExplicitRangeSucceedsThroughActualContainerRpc() throws Exception {
        var source = Files.createDirectory(root.resolve("repo"));
        Files.writeString(source.resolve("A.java"), "// source line\n".repeat(100));
        var workspace = new Workspace(source, root.resolve("private.properties"));
        var session = new AgentSession("read source");
        run(session, """
                try:
                    await tools.read_file({'path':'A.java'})
                    raise AssertionError('Missing range must not silently read a default page')
                except ToolCallError as error:
                    assert 'start_line' in error.message and 'end_line' in error.message
                return await tools.read_file({'path':'A.java','start_line':10,'end_line':30})
                """, List.of(new ReadFileTool(workspace)), new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
        assertEquals(AgentSession.Status.COMPLETED, session.status()); assertEquals(2, session.modelCalls());
        var outer = session.history().getLast(); assertTrue(outer.result().successful(), outer.result().content());
        var data = json.readTree(outer.result().content()); assertEquals(1, data.path("callCount").asInt());
        assertEquals(0, data.path("failedCalls").size()); // Rejected locally before a tool invocation.
        assertEquals(10, data.path("result").path("readMetadata").path("startLine").asInt());
        assertEquals(30, data.path("result").path("readMetadata").path("endLine").asInt());
        assertFalse(data.path("result").path("readMetadata").path("reachedEndOfFile").asBoolean());
        assertTrue(session.events().stream().noneMatch(e -> e.type() == AgentSession.EventType.TOOL_EXECUTION_FAILED));
    }

    @Test void generatedSdkKeywordsExecuteWithRequiredArgumentsInActualContainer() throws Exception {
        var source = Files.createDirectory(root.resolve("repo")); Files.writeString(source.resolve("A.java"), "class A {}\n");
        var workspace = new Workspace(source, root.resolve("private.properties")); var session = new AgentSession("read and calculate");
        run(session, """
                import inspect
                assert set(inspect.signature(tools.read_file).parameters) == {'path','start_line','end_line'}
                source = await tools.read_file(path='A.java',start_line=1,end_line=10)
                result = await tools.integer_calculate(expression='2+3',result_name='sum')
                return {'source':source['content'],'value':result['value']}
                """, List.of(new ReadFileTool(workspace), new IntegerCalculateTool()), new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
        var data = json.readTree(session.history().getLast().result().content());
        assertTrue(session.history().getLast().result().successful(), data.toString());
        assertEquals("5", data.path("result").path("value").asText());
        assertEquals("class A {}\n", data.path("result").path("source").asText());
    }

    @Test void containerHasNoProjectMountAndRootIsReadOnly() {
        var result=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE).run("""
                import os
                assert not os.path.exists('/home/agent/backend-engineer-agent/agent-local.properties')
                assert not os.path.exists('/workspace')
                try:
                    with open('/ptc-write-test', 'w') as file:
                        file.write('x')
                    return {'readOnly':False}
                except OSError:
                    return {'readOnly':True}
                """,List.of(),(n,a)->{throw new AssertionError("No tools requested");});
        assertTrue(result.successful(),String.valueOf(result.error()));assertTrue(result.value().path("readOnly").asBoolean());
    }

    @Test void generatedProgramErrorIncludesOriginalLineWithoutRuntimeStack() {
        var result=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE).run(
                "first = 1\nassert 'old' in None or True\nreturn first",List.of(),(n,a)->{throw new AssertionError();});
        assertFalse(result.successful());assertTrue(result.error().contains("TypeError at program line 2:"),result.error());
        assertFalse(result.error().contains("runtime.py"));assertFalse(result.error().contains("/opt/ptc"));
    }

    @Test void syntaxFeedbackIncludesOriginalColumnAndBoundedSourceBeforeAnyDispatch() {
        var result = new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE).run(
                "first = 1\nif:\n    pass", List.of(), (n,a) -> {throw new AssertionError("Syntax failure must not dispatch");});
        assertFalse(result.successful());
        assertTrue(result.error().contains("SyntaxError at program line 2, column 3:"),result.error());
        assertTrue(result.error().contains("\nif:\n  ^"),result.error());
        assertTrue(result.error().length() <= 800);assertFalse(result.error().contains("/opt/ptc"));
    }

    @Test void infiniteLoopTimesOutAndContainerIsRemoved() throws Exception {
        long before=System.nanoTime();
        var result=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE,Duration.ofSeconds(2))
                .run("while True:\n    pass",List.of(),(n,a)->{throw new AssertionError();});
        assertFalse(result.successful());assertTrue(result.error().contains("PTC_TIMEOUT"),result.error());
        assertTrue(Duration.ofNanos(System.nanoTime()-before).compareTo(Duration.ofSeconds(20))<0);
        var containers=new dev.backendagent.sandbox.LocalProcessRunner().run(List.of("docker","ps","--all","--quiet","--filter","name=backend-ptc-"),Duration.ofSeconds(10));
        assertEquals("",containers.getOutput().trim());
    }

    @Test void oversizedReturnIsFailureRatherThanIncompleteEvidence() {
        var result=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE).run("return 'x'*30000",List.of(),(n,a)->{throw new AssertionError();});
        assertFalse(result.successful());assertTrue(result.error().contains("PTC_OUTPUT_LIMIT"));assertNull(result.value());
    }


    @Test void pythonDrivesIsolatedFileRepairMavenTestAndDurableRestore() throws Exception {
        var source=Files.createDirectory(root.resolve("source"));
        try(var files=Files.walk(Path.of("examples/test-repair"))) {
            for(var file:files.toList()) {
                var dest=source.resolve(Path.of("examples/test-repair").relativize(file));
                if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest);
            }
        }
        var runner=new dev.backendagent.sandbox.LocalProcessRunner();
        var data=root.resolve("sessions");UUID id;
        dev.backendagent.sandbox.DockerWorkspace workspace;
        try(var store=new FileSessionStore(data)) {
            var session=new AgentSession("repair and test",store);id=session.id();store.create(session);
            workspace=dev.backendagent.sandbox.DockerWorkspace.create(new Workspace(source,root.resolve("private.properties")),
                store.sessionDirectory(id),runner,dev.backendagent.sandbox.DockerSandboxExecutor.DEFAULT_IMAGE);
            store.bindWorkspace(workspace);
            String code="""
                    source = await tools.read_file({'path':'src/main/java/example/Calculator.java','start_line':1,'end_line':100})
                    assert 'return left - right;' in source['content']
                    await tools.apply_patch({'path':'src/main/java/example/Calculator.java','old_text':'return left - right;','new_text':'return left + right;'})
                    test = await tools.run_tests({})
                    return {'test':test['content'][:350]}
                    """;
            run(session,code,List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session),
                new RunTestsTool(workspace,new dev.backendagent.sandbox.DockerSandboxExecutor(runner,
                    dev.backendagent.sandbox.DockerSandboxExecutor.DEFAULT_IMAGE,Duration.ofSeconds(120)))),
                new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE));
            assertEquals(AgentSession.Status.COMPLETED,session.status());
            assertEquals(1,session.workspaceState().getRevision());
            assertEquals(WorkspaceState.TestStatus.CURRENT_PASSED,session.workspaceState().testStatus());
            assertTrue(Files.readString(source.resolve("src/main/java/example/Calculator.java")).contains("left - right"));
            store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(data)) {
            var restored=store.openCompleted(id,workspace,10);
            assertEquals(1,restored.workspaceState().getRevision());
            assertEquals(WorkspaceState.TestStatus.CURRENT_PASSED,restored.workspaceState().testStatus());
        }
    }

    private void run(AgentSession session,String code,List<Tool> tools,PtcExecutor executor) {
        var step=new AtomicInteger();
        new AgentRuntime(q->step.getAndIncrement()==0
                ? ModelResponse.callTool(new ToolCall("program","run_code",Map.of("code",code,"description","实际容器程序验证")))
                : ModelResponse.finish("done"),tools,3).withPtc(executor).run(session);
    }
}
