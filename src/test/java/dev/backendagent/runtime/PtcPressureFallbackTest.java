package dev.backendagent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PtcPressureFallbackTest {
    final ObjectMapper json=new ObjectMapper();
    ToolExchange program() throws Exception {
        var content=json.createObjectNode().put("kind","program_result").put("usageScope","TOOL_PROGRAM_OUTPUT")
                .put("callCount",3).put("error","original failure detail");
        content.putArray("sources").add("source-1");content.putArray("failedCalls").addObject().put("error","important failure");
        content.putArray("logs").add("中文证据\\\"".repeat(1500));content.putNull("result");
        return new ToolExchange(new ToolCall("program","run_code",Map.of("code","return data","description","inspect")),
                new ToolResult(false,json.writeValueAsString(content)),null,1);
    }
    @Test void pressureExcerptIsExplicitAndKeepsFailureAndSourceReceipts() throws Exception {
        var original=program();var view=new ToolResultCompressor().pruneProgramOutput(original,2000);
        assertNotEquals(original,view);assertTrue(view.result().content().length()<=2000);
        var data=json.readTree(view.result().content());assertTrue(data.path("outputTruncated").asBoolean());
        assertEquals("program",data.path("archiveCallId").asText());assertEquals("source-1",data.path("sources").get(0).asText());
        assertEquals("important failure",data.path("failedCalls").get(0).path("error").asText());
        assertEquals("original failure detail",data.path("error").asText());assertFalse(view.result().successful());
        assertTrue(original.result().content().contains("中文证据"));assertFalse(json.readTree(original.result().content()).has("outputTruncated"));
    }
    @Test void legacyProjectionRemainsValidAndPressurePolicyCanBeRestored() throws Exception {
        var session=new AgentSession("inspect");session.remember(program());
        var assembler=new ContextAssembler(new ContextBudget(64000));
        var legacy=assembler.preview(session,List.of(),10,null);assertEquals(7,legacy.contextProjection().getPolicyVersion());
        assertEquals(session.history().getFirst().result().content(),legacy.history().getFirst().result().content());
        legacy.contextProjection().validateAgainst(session.history());
        var bounded=assembler.previewForRequestPressure(session,List.of(),10,null,null);
        assertEquals(9,bounded.contextProjection().getPolicyVersion());
        assertTrue(bounded.history().getFirst().result().content().length()<legacy.history().getFirst().result().content().length());
        var restored=json.readValue(json.writeValueAsString(bounded.contextProjection()),ContextProjection.class);
        restored.validateAgainst(session.history());
    }
    @Test void failedParameterFeedbackListsMissingAndUnexpectedKeysBeforeToolExecution() throws Exception {
        var count=new AtomicInteger();
        Tool search=new Tool(){
            public String name(){return "search_history";}
            public ToolDefinition definition(){return new ToolDefinition(name(),"search",Map.of("tool","filter","path","filter","keyword","text","before_sequence","cursor","limit","count"));}
            public ToolResult execute(Map<String,String>a){count.incrementAndGet();return new ToolResult(true,"{\"matches\":[]}");}
        };
        var session=new AgentSession("search");var step=new AtomicInteger();
        new AgentRuntime(q->step.getAndIncrement()==0?ModelResponse.callTool(new ToolCall("p","run_code",Map.of("code","return 1","description","search"))):ModelResponse.finish("done"),List.of(search),3)
                .withPtc((c,n,d)->{
                    var failure=assertThrows(IllegalArgumentException.class,()->d.call("search_history",Map.of("keyword","5xx","before_sequence","0","limit","5","extra","x")));
                    assertTrue(failure.getMessage().contains("missing=[path, tool]"));assertTrue(failure.getMessage().contains("unexpected=[extra]"));
                    assertTrue(failure.getMessage().contains("空字符串"));assertEquals(0,count.get());
                    d.call("search_history",Map.of("keyword","5xx","before_sequence","0","limit","5","path","","tool",""));
                    return new dev.backendagent.ptc.PtcRunResult(json.createObjectNode().put("handled",true),List.of(),null);
                }).run(session);
        assertEquals(1,count.get());assertEquals(AgentSession.Status.COMPLETED,session.status());
    }
    @Test void evidenceDedupRequiresSameSourceVersionAndCurrency() throws Exception {
        var original=new ToolExchange(new ToolCall("source","read_file",Map.of("path","A.java")),
                new ToolResult(true,"1: class A {}\n",new FileFingerprint("A.java","a".repeat(64))));
        var evidence=dev.backendagent.history.HistoricalEvidence.excerpt(original,1,ObservationEvidence.Validity.CURRENT,1,1)
                .withCurrentValidity(ObservationEvidence.Validity.CURRENT);
        var body=json.createObjectNode().put("kind","program_result");body.putObject("result").set("evidence",json.valueToTree(evidence));
        var visible=new ToolExchange(new ToolCall("p","run_code",Map.of()),new ToolResult(true,json.writeValueAsString(body)));
        assertTrue(dev.backendagent.history.HistoricalEvidenceArea.hiddenOnly(List.of(evidence),List.of(visible)).isEmpty());
        assertEquals(1,dev.backendagent.history.HistoricalEvidenceArea.hiddenOnly(
                List.of(evidence.withCurrentValidity(ObservationEvidence.Validity.STALE)),List.of(visible)).size());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("result").path("evidence")).put("sourceBodySha256","b".repeat(64));
        visible=new ToolExchange(visible.call(),new ToolResult(true,json.writeValueAsString(body)));
        assertEquals(1,dev.backendagent.history.HistoricalEvidenceArea.hiddenOnly(List.of(evidence),List.of(visible)).size());
        body.put("outputTruncated",true);
        visible=new ToolExchange(visible.call(),new ToolResult(true,json.writeValueAsString(body)));
        assertEquals(1,dev.backendagent.history.HistoricalEvidenceArea.hiddenOnly(List.of(evidence),List.of(visible)).size());
    }

    @Test void pressureCanRevisitShortSkippedDialogueButNotRetryAnActualAttempt() {
        var session=new AgentSession("first");
        for(int i=1;i<=5;i++){
            var runtime=new AgentRuntime(q->ModelResponse.finish("answer"+"x".repeat(700)),List.of(),20);
            if(i==1)runtime.run(session);else runtime.continueConversation(session,"turn "+i);
        }
        session.continueWith("current");
        session.installConversationSummary(new ConversationSummary(1,2,List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS,"first","turn-1-user","first"))));
        session.append(AgentSession.EventType.CONVERSATION_SUMMARY_SKIPPED,"3");
        var compactor=new ConversationCompactor(2048);
        assertNull(compactor.plan(session));var request=compactor.plan(session,true);
        assertNotNull(request);assertEquals(2,request.fromIndex());assertEquals(3,request.toIndex());
        assertTrue(request.maxOutputCharacters()<4000);assertEquals(2,request.messages().size());
        session.append(AgentSession.EventType.CONVERSATION_SUMMARY_ATTEMPTED,"3");
        assertNull(compactor.plan(session,true));
    }
}
