package dev.backendagent.persistence;

import dev.backendagent.runtime.*;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSummaryCheckpointTest {
    @TempDir Path root;
    private Workspace workspace() throws Exception { return new Workspace(root,root.resolve("agent-local.properties"),root.resolve("sessions")); }
    private ModelClient model() {
        return new ModelClient() {
            public boolean supportsConversationSummarization(){return true;}
            public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request) {
                var source=request.messages().getFirst();
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"历史用户输入："+source.content(),source.id(),source.content()));
            }
            public ModelResponse execute(ModelRequest request){return ModelResponse.finish("已完成"+"x".repeat(1000));}
        };
    }
    private UUID prepared() throws Exception {
        Files.writeString(root.resolve("rule.txt"),"unchanged");
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var session=new AgentSession("初始规则100",store);store.create(session);store.bindWorkspace(workspace());
            new AgentRuntime(model(),List.of(),40).run(session);
            for(int i=2;i<=7;i++)new AgentRuntime(model(),List.of(),40).continueConversation(session,"规则轮次"+i);
            assertNotNull(session.conversationSummary());assertEquals(4,session.conversationSummary().getCoveredTurns());
            store.saveSnapshot(session);return session.id();
        }
    }
    @Test void restoresSummaryAndContinuesIncrementallyWithoutRefeedingOldRawMessages() throws Exception {
        var id=prepared();
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var session=store.openCompleted(id,workspace(),40);assertEquals(1,session.conversationSummary().getRevision());
            var retrieved=new ReadTurnTool(session).execute(Map.of("message_id","turn-1-user","start_line","1","end_line","1"));
            assertTrue(retrieved.successful());assertTrue(retrieved.content().contains("初始规则100"));
            ModelClient client=new ModelClient() {
                public boolean supportsConversationSummarization(){return true;}
                public List<SummaryNote> summarizeConversation(ConversationSummaryRequest request) {
                    assertEquals(4,request.fromIndex());assertEquals(8,request.toIndex());
                    assertNotNull(request.previousSummary());
                    assertTrue(request.messages().stream().allMatch(m->m.turnId()>4));
                    var source=request.messages().getFirst();
                    return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS,"历史输入",source.id(),source.content()));
                }
                public ModelResponse execute(ModelRequest request){return ModelResponse.finish("已完成"+"x".repeat(1000));}
            };
            for(int i=8;i<=11;i++)new AgentRuntime(client,List.of(),40).continueConversation(session,"新要求"+i);
            assertEquals(2,session.conversationSummary().getRevision());assertEquals(8,session.conversationSummary().getCoveredTurns());
        }
    }
    @Test void editedSummaryWithStillValidQuoteIsRejectedByEventLogComparison() throws Exception {
        var id=prepared();var path=root.resolve("sessions").resolve(id.toString()).resolve("checkpoint.json");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var cp=json.readTree(path.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)cp.path("conversationSummary").path("notes").get(0)).put("statement","篡改了摘要结论");
        Files.writeString(path,cp.toString());
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var failure=assertThrows(java.io.IOException.class,()->store.openCompleted(id,workspace(),40));
            assertTrue(failure.getMessage().contains("Conversation summary disagrees"));
        }
    }
}
