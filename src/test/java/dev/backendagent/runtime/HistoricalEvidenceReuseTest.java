package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import dev.backendagent.persistence.FileSessionStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalEvidenceReuseTest {
    @TempDir Path root;
    @Test void numberedEvidenceDecodesOnlyRetrievedLinesAndPreservesLiteralPrefixes() throws Exception {
        var w=new Workspace(root,root.resolve("local.properties"));
        Files.writeString(root.resolve("note.txt"),"first\n1: literal value\nlast\n");
        var source=new ToolExchange(new ToolCall("read","read_file",Map.of("path","note.txt","start_line","1","end_line","3")),w.readFile("note.txt","1","3"));
        var excerpt=dev.backendagent.history.HistoricalEvidence.excerpt(source,1,ObservationEvidence.Validity.CURRENT,3,3);
        assertEquals("1: literal value\n",excerpt.rawSourceExcerpt(source));
        assertFalse(excerpt.rawSourceExcerpt(source).contains("first"));assertFalse(excerpt.rawSourceExcerpt(source).contains("last"));
        var header=dev.backendagent.history.HistoricalEvidence.excerpt(source,1,ObservationEvidence.Validity.CURRENT,1,1);
        assertNull(header.rawSourceExcerpt(source));
        var raw=new ToolExchange(new ToolCall("legacy","read_file",Map.of("path","note.txt","format","raw")),w.readFile("note.txt","2","2","raw"));
        var legacy=dev.backendagent.history.HistoricalEvidence.excerpt(raw,2,ObservationEvidence.Validity.CURRENT,1,1);
        assertEquals("1: literal value\n",legacy.rawSourceExcerpt(raw));
    }
    @Test void retrievedCurrentRangeAuthorizesPatchThenExpiresAndClearsAcrossPersistedTurns() throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo"));
        Files.writeString(repo.resolve("Main.java"),"class Main {\r\n    int value = 1;\r\n}\r\n");
        var workspace=new Workspace(repo,root.resolve("local.properties"));
        java.util.UUID id;
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(workspace);
            var session=new AgentSession("repair",store);store.create(session);id=session.id();
            var step=new AtomicInteger();
            var tools=List.<Tool>of(new ReadFileTool(workspace),new ListFilesTool(workspace),
                    new ReadObservationTool(session,workspace,store.observationArchive(session)),new ApplyPatchTool(workspace,session));
            new AgentRuntime(request->{
                int n=step.getAndIncrement();
                if(n==0)return ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","Main.java","start_line","1","end_line","3")));
                if(n<=12)return ModelResponse.callTool(new ToolCall("list-"+n,"list_files",Map.of("path",".")));
                if(n==13)return ModelResponse.callTool(new ToolCall("retrieve","read_observation",Map.of("call_id","read","start_line","3","end_line","3")));
                if(n==14){
                    assertTrue(request.history().stream().noneMatch(e->e.call().id().equals("read")));
                    assertEquals(ObservationEvidence.Validity.CURRENT,request.historicalEvidence().getFirst().getCurrentValidity());
                    assertTrue(session.hasCurrentPatchSource("Main.java","    int value = 1;",workspace));
                    assertFalse(session.hasCurrentPatchSource("Main.java","class Main",workspace));
                    return ModelResponse.callTool(new ToolCall("patch","apply_patch",Map.of("path","Main.java","old_text","    int value = 1;","new_text","    int value = 2;")));
                }
                assertTrue(session.history().getLast().result().successful());
                assertTrue(request.historicalEvidence().isEmpty());
                return ModelResponse.finish("done");
            },tools,50).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status(),session.failure()==null?"":session.failure().toString());
            assertEquals(1,session.history().stream().filter(e->e.call().name().equals("read_file")).count());
            assertTrue(Files.readString(repo.resolve("Main.java")).contains("value = 2"));
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var session=store.openCompleted(id,workspace,60);var step=new AtomicInteger();
            var retrieval=new ReadObservationTool(session,workspace,store.observationArchive(session));
            new AgentRuntime(request->{
                if(step.getAndIncrement()==0){
                    assertTrue(request.historicalEvidence().isEmpty());
                    return ModelResponse.callTool(new ToolCall("retrieve-again","read_observation",Map.of("call_id","read","start_line","3","end_line","3")));
                }
                assertTrue(request.historicalEvidence().isEmpty());
                assertFalse(session.hasCurrentPatchSource("Main.java","value = 1",workspace));
                return ModelResponse.finish("reviewed");
            },List.of(retrieval),60).continueConversation(session,"review history");
            assertEquals(AgentSession.Status.COMPLETED,session.status());
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var session=store.openCompleted(id,workspace,65);
            assertTrue(session.historicalEvidence().isEmpty());
            new AgentRuntime(request->{assertTrue(request.historicalEvidence().isEmpty());return ModelResponse.finish("new turn");},List.of(),65)
                    .continueConversation(session,"next task");
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            var session=store.openCompleted(id,workspace,70);
            assertTrue(session.historicalEvidence().isEmpty());
            assertNotNull(session.originalObservation("read"));
        }
    }
    @Test void visibleOriginalIsNotDuplicatedInEvidenceAreaAndRemovalSurvivesRestore() throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo"));Files.writeString(repo.resolve("Main.java"),"unchanged\n");
        var workspace=new Workspace(repo,root.resolve("local.properties"));java.util.UUID id;
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(workspace);var session=new AgentSession("inspect",store);store.create(session);id=session.id();
            var step=new AtomicInteger();
            new AgentRuntime(request->{
                int n=step.getAndIncrement();
                if(n==0)return ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","Main.java","start_line","1","end_line","1")));
                if(n==1)return ModelResponse.callTool(new ToolCall("retrieve","read_observation",Map.of("call_id","read","start_line","1","end_line","1")));
                assertTrue(request.history().stream().anyMatch(e->e.call().id().equals("read")));
                assertTrue(request.historicalEvidence().isEmpty());assertTrue(session.historicalEvidence().isEmpty());
                return ModelResponse.finish("done");
            },List.of(new ReadFileTool(workspace),new ReadObservationTool(session,workspace,store.observationArchive(session))),10).run(session);
            assertEquals(AgentSession.Status.COMPLETED,session.status());
        }
        try(var store=new FileSessionStore(root.resolve("sessions"))) {
            assertTrue(store.openCompleted(id,workspace,15).historicalEvidence().isEmpty());
        }
    }
    @Test void externalFileChangeInvalidatesRetainedEvidenceOnNextProjection() throws Exception {
        var workspace=new Workspace(root,root.resolve("local.properties"));Files.writeString(root.resolve("Main.java"),"old\n");
        var session=new AgentSession("inspect");var step=new AtomicInteger();
        new AgentRuntime(request->{
            int n=step.getAndIncrement();
            if(n==0)return ModelResponse.callTool(new ToolCall("read","read_file",Map.of("path","Main.java","start_line","1","end_line","1")));
            if(n<=12)return ModelResponse.callTool(new ToolCall("list-"+n,"list_files",Map.of("path",".")));
            if(n==13)return ModelResponse.callTool(new ToolCall("retrieve","read_observation",Map.of("call_id","read","start_line","1","end_line","1")));
            assertEquals(ObservationEvidence.Validity.CURRENT,request.historicalEvidence().getFirst().getCurrentValidity());
            try{Files.writeString(root.resolve("Main.java"),"external\n");}catch(Exception e){throw new RuntimeException(e);}
            session.refreshVerificationBasis();
            assertTrue(session.historicalEvidenceForContext().isEmpty());
            assertTrue(session.historicalEvidence().isEmpty());
            assertFalse(session.hasCurrentPatchSource("Main.java","old",workspace));
            return ModelResponse.finish("done");
        },List.of(new ReadFileTool(workspace),new ListFilesTool(workspace),new ReadObservationTool(session,workspace)),20).run(session);
        assertEquals(AgentSession.Status.COMPLETED,session.status());
    }
}
