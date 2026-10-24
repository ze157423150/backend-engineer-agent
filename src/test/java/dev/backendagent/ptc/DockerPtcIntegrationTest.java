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
            assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,session.modelCalls());assertEquals(1,session.history().size());
            var result=json.readTree(session.history().getFirst().result().content());assertEquals("19",result.path("result").path("rank").asText());
            assertEquals(3,result.path("callCount").asInt());assertFalse(result.toString().contains("PTC_PRIVATE_REFERENCE_"));
            store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(path)) {
            var restored=store.openCompleted(id,workspace,10);var original=new ArrayList<ToolExchange>();
            store.observationArchive(restored).visit((s,e)->original.add(e));
            assertEquals(4,original.size());assertEquals(1,restored.history().size());
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
        var result=json.readTree(session.history().getFirst().result().content());
        assertTrue(session.history().getFirst().result().successful());assertEquals("42",result.path("result").path("answer").asText());
        assertEquals(1,result.path("failedCalls").size());assertEquals(2,result.path("callCount").asInt());
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
        var result=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE).run("return 'x'*8000",List.of(),(n,a)->{throw new AssertionError();});
        assertFalse(result.successful());assertTrue(result.error().contains("PTC_OUTPUT_LIMIT"));assertNull(result.value());
    }

    private void run(AgentSession session,String code,List<Tool> tools,PtcExecutor executor) {
        var step=new AtomicInteger();
        new AgentRuntime(q->step.getAndIncrement()==0
                ? ModelResponse.callTool(new ToolCall("program","run_code",Map.of("code",code,"description","实际容器程序验证")))
                : ModelResponse.finish("done"),tools,3).withPtc(executor).run(session);
    }
}
