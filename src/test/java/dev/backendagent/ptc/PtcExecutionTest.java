package dev.backendagent.ptc;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import dev.backendagent.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PtcExecutionTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    private AgentRuntime runtime(AgentSession session, PtcExecutor executor, List<Tool> tools) {
        var calls = new AtomicInteger();
        return new AgentRuntime(request -> {
            assertEquals(List.of("run_code"),request.availableTools().stream().map(ToolDefinition::getName).toList());
            assertTrue(request.availableTools().stream().anyMatch(t -> t.getName().equals("run_code")));
            return calls.getAndIncrement() == 0 ? ModelResponse.callTool(new ToolCall("program", "run_code",
                    Map.of("code", "return 1", "description", "验证程序编排"))) : ModelResponse.finish("done");
        }, tools, 3).withPtc(executor);
    }

    @Test void nestedResultsAreArchivedButOnlyOuterResultReachesModelAndRestore() throws Exception {
        var source = Files.createDirectory(root.resolve("workspace"));
        var workspace = new Workspace(source, source.resolve("private.properties"));
        var data = root.resolve("sessions"); UUID id;
        try (var store = new FileSessionStore(data)) {
            var session = new AgentSession("SECRET_REFERENCE_" + "x".repeat(2000), store);id=session.id();store.create(session);store.bindWorkspace(workspace);
            PtcExecutor executor = (code,names,dispatch) -> {
                var page=dispatch.call("search_turns",Map.of("keyword","SECRET_REFERENCE_","role","user","before_turn","0","limit","1"));
                String message=page.path("matches").get(0).path("messageId").asText();
                var original=dispatch.call("read_turn",Map.of("message_id",message,"start_line","1","end_line","2"));
                assertEquals(session.objective()+"\n",original.path("content").asText());
                var value=dispatch.call("integer_calculate",Map.of("expression","(95*20+99)/100","result_name","rank"));
                return new PtcRunResult(value,List.of(),null);
            };
            runtime(session,executor,List.of(new IntegerCalculateTool(),new SearchTurnsTool(session),new ReadTurnTool(session))).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status());assertEquals(2,session.modelCalls());
            assertEquals(4,session.history().size());
            String body=session.history().getLast().result().content();assertFalse(body.contains("SECRET_REFERENCE_"));
            assertEquals(3,json.readTree(body).path("callCount").asInt());
            var archived=new ArrayList<ToolExchange>();store.observationArchive(session).visit((seq,e)->archived.add(e));
            assertEquals(4,archived.size());assertTrue(archived.stream().anyMatch(e->e.call().name().equals("read_turn")&&e.result().content().contains("SECRET_REFERENCE_")));
            store.saveSnapshot(session);
        }
        try(var reopened=new FileSessionStore(data)) {
            var restored=reopened.openCompleted(id,workspace,10);
            assertEquals(4,restored.history().size());assertEquals(AgentSession.Status.COMPLETED,restored.status());
            var archived=new ArrayList<ToolExchange>();reopened.observationArchive(restored).visit((seq,e)->archived.add(e));assertEquals(4,archived.size());
        }
    }

    @Test void rejectedWritesAreAuditedEvenWhenProgramCatchesFailure() throws Exception {
        var writes=new AtomicInteger();
        Tool write=new Tool(){public String name(){return "apply_patch";}public ToolResult execute(Map<String,String>a){writes.incrementAndGet();return new ToolResult(true,"changed");}};
        var session=new AgentSession("inspect");
        PtcExecutor executor=(code,names,dispatch)->{
            assertTrue(names.contains("apply_patch"));
            assertThrows(IllegalArgumentException.class,()->dispatch.call("delete_everything",Map.of()));
            return new PtcRunResult(json.createObjectNode().put("handled",true),List.of(),null);
        };
        runtime(session,executor,List.of(new IntegerCalculateTool(),write)).run(session);
        assertEquals(0,writes.get());var body=json.readTree(session.history().getLast().result().content());
        assertEquals("delete_everything",body.path("failedCalls").get(0).path("tool").asText());
        assertTrue(body.path("failedCalls").get(0).path("error").asText().contains("PTC_TOOL_NOT_ALLOWED"));
        assertEquals(0,session.workspaceState().getRevision());
    }

    @Test void catchingQuotaFailureCannotTurnProgramIntoSuccess() throws Exception {
        var session=new AgentSession("calculate");
        PtcExecutor executor=(code,names,dispatch)->{
            for(int i=0;i<18;i++)try{dispatch.call("integer_calculate",Map.of("expression","2+3","result_name","sum"));}catch(IllegalArgumentException ignored){}
            return new PtcRunResult(json.createObjectNode().put("claimedSuccess",true),List.of(),null);
        };
        runtime(session,executor,List.of(new IntegerCalculateTool())).run(session);
        assertFalse(session.history().getLast().result().successful());
        var body=json.readTree(session.history().getLast().result().content());assertEquals("PTC_LIMIT_EXCEEDED",body.path("error").asText());
        assertEquals(16,session.events().stream().filter(e->e.type()==AgentSession.EventType.PTC_TOOL_CALL_COMPLETED).count());
    }

    @Test void transportCannotBeEnabledTogetherWithToolDiscovery() {
        var runtime=new AgentRuntime(q->ModelResponse.finish("done"),List.of(new IntegerCalculateTool()),2);
        PtcExecutor unused=(c,n,d)->new PtcRunResult(null,List.of(),null);
        runtime.withToolSelection("catalog");assertThrows(IllegalArgumentException.class,()->runtime.withPtc(unused));
        runtime.withToolSelection("all").withPtc(unused);
        assertThrows(IllegalArgumentException.class,()->runtime.withToolSelection(true));
        assertThrows(IllegalArgumentException.class,()->runtime.withToolSelection(new dev.backendagent.selection.RuleToolSelector(),8));
    }

    @Test void cliDefaultsToPtcAndRequiresFullTools() {
        var normal=dev.backendagent.cli.AgentOptions.parse(new String[]{"--workspace",".","--task","inspect"});assertTrue(normal.isPtcEnabled());
        var ptc=dev.backendagent.cli.AgentOptions.parse(new String[]{"--workspace",".","--task","inspect","--ptc","true"});assertTrue(ptc.isPtcEnabled());
        assertThrows(IllegalArgumentException.class,()->dev.backendagent.cli.AgentOptions.parse(new String[]{"--workspace",".","--task","inspect","--ptc","true","--tool-selection","catalog"}));
        assertEquals("custom:local",dev.backendagent.cli.AgentOptions.parse(new String[]{"--workspace",".","--task","inspect","--ptc-image","custom:local"}).getPtcImage());
    }

    @Test void invalidEnvelopeArgumentsNeverStartTheProgramAndExplainTheSpecificField() {
        var invalid=List.of(Map.of("code","return 1"),Map.of("description","inspect"),
                Map.of("code","return 1","description","inspect","extra","x"),
                Map.of("code","return 1","description",""),Map.of("code","return 1","description","x".repeat(101)),
                Map.of("code","","description","inspect"),Map.of("code","x".repeat(32001),"description","inspect"));
        var reasons=List.of("missing=[description]","missing=[code]","unexpected=[extra]","description:blank","description:too_long","code:blank","code:too_long");
        for(int i=0;i<invalid.size();i++) {
            var args=invalid.get(i);var step=new AtomicInteger();var session=new AgentSession("inspect");
            new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("p","run_code",args)):ModelResponse.finish("done"),List.of(),3)
                    .withPtc((c,n,d)->{throw new AssertionError("Invalid arguments must never start a container");}).run(session);
            String feedback=session.history().getFirst().result().content();assertTrue(feedback.contains(reasons.get(i)),feedback);
            assertTrue(feedback.contains("程序未启动"));assertFalse(session.history().getFirst().result().successful());
            if (i == 0) assertTrue(feedback.contains("保留原code，仅补齐同级description"));
        }
    }

    @Test void commandProvidesNoHostMountOrNetworkAndImageIsHostControlled() {
        var executor=new DockerPtcExecutor(DockerPtcExecutor.DEFAULT_IMAGE);
        String command=String.join(" ",executor.command("ptc-test"));
        assertTrue(command.contains("--network none"));assertTrue(command.contains("--read-only"));assertTrue(command.contains("--cap-drop ALL"));
        assertTrue(command.contains("--log-driver none"));
        assertFalse(command.contains("--mount"));assertFalse(command.contains("docker.sock"));assertFalse(command.contains("/workspace"));
        assertThrows(IllegalArgumentException.class,()->new DockerPtcExecutor("bad image --privileged"));
    }
}
