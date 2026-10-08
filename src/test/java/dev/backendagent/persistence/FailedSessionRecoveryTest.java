package dev.backendagent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.history.ObservationArchive;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FailedSessionRecoveryTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    Path data() { return root.resolve("sessions"); }
    Workspace workspace() throws IOException { return new Workspace(root, root.resolve("agent-local.properties"), data()); }
    ToolCall read(String id) { return new ToolCall(id,"read_file",Map.of("path","Main.java","start_line","1","end_line","20")); }
    ToolCall patch() { return new ToolCall("patch","apply_patch",Map.of("path","Main.java","old_text","class Main {}","new_text","class Main { int n; }")); }
    Path file(UUID id,String name) { return data().resolve(id.toString()).resolve(name); }

    UUID failAfterPatch(AtomicReference<String> priorCheckpoint) throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();store.bindWorkspace(workspace);
            var session=new AgentSession("modify then explain",store);store.create(session);
            var calls=new AtomicInteger();
            new AgentRuntime(request -> switch(calls.getAndIncrement()) {
                case 0 -> ModelResponse.callTool(read("read"));
                case 1 -> ModelResponse.callTool(patch());
                default -> {
                    try { priorCheckpoint.set(Files.readString(file(session.id(),"checkpoint.json"))); }
                    catch(IOException failure) { throw new RuntimeException(failure); }
                    throw new IllegalStateException("model response interrupted");
                }
            },List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session)),5).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());store.saveSnapshot(session);
            return session.id();
        }
    }

    @Test void exhaustedRetriesPersistCountAndResumeWithoutReplayingCompletedPatch() throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");
        UUID id;
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();store.bindWorkspace(workspace);
            var session=new AgentSession("modify then explain",store);store.create(session);id=session.id();
            var calls=new AtomicInteger();
            new AgentRuntime(request -> switch(calls.getAndIncrement()) {
                case 0 -> ModelResponse.callTool(read("read"));
                case 1 -> ModelResponse.callTool(patch());
                default -> throw new ModelCallFailure("DeepSeek request timed out",
                        new ModelFailureDiagnostic(ModelFailureDiagnostic.Code.TIMEOUT,
                                ModelFailureDiagnostic.FinishReason.MISSING,null,true));
            },List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session)),10).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());
            assertEquals(5,session.modelCalls());assertEquals(2,session.history().size());
            assertEquals(3,session.events().stream().filter(e -> e.type()==AgentSession.EventType.MODEL_CALL_FAILED).count());
            store.saveSnapshot(session);
        }
        var journal=Files.readString(file(id,"events.jsonl"));
        assertTrue(journal.contains("TIMEOUT"));
        try(var store=new FileSessionStore(data())) {
            var restored=store.restoreFailed(id,workspace(),6);
            new AgentRuntime(request -> ModelResponse.finish("recovered"),List.of(),6).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());
            assertEquals(6,restored.modelCalls());assertEquals(2,restored.history().size());
            assertEquals("class Main { int n; }\n",Files.readString(root.resolve("Main.java")));
        }
    }

    @Test void resumesFailedModelCallWithoutReplayingPatchOrAddingUserTurn() throws Exception {
        UUID id=failAfterPatch(new AtomicReference<>());
        try(var store=new FileSessionStore(data())) {
            var restored=store.restoreFailed(id,workspace(),4);
            assertEquals(3,restored.modelCalls());assertEquals(2,restored.history().size());
            assertEquals(SessionFailure.Boundary.COMPLETE_TOOL_BATCH,restored.failure().getBoundary());
            new AgentRuntime(request -> {
                assertEquals(1,request.workspaceState().getRevision());
                return ModelResponse.finish("finished after recovery");
            },List.of(),4).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());
            assertEquals(4,restored.modelCalls());assertEquals(2,restored.history().size());
            assertEquals(1,restored.turns().size());assertNull(restored.failure());
            assertEquals("class Main { int n; }\n",Files.readString(root.resolve("Main.java")));
        }
    }

    @Test void anotherModelFailureSavesANewResumableCheckpoint() throws Exception {
        UUID id=failAfterPatch(new AtomicReference<>());
        try(var store=new FileSessionStore(data())) {
            var restored=store.restoreFailed(id,workspace(),4);
            new AgentRuntime(request -> { throw new IllegalStateException("still unavailable"); },List.of(),4).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.FAILED,restored.status());assertEquals(4,restored.failure().getModelCallNumber());
            store.saveSnapshot(restored);
        }
        try(var store=new FileSessionStore(data())) {
            var restored=store.restoreFailed(id,workspace(),5);
            new AgentRuntime(request -> ModelResponse.finish("done"),List.of(),5).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());
            assertEquals(2,restored.history().size());
        }
    }

    UUID makeLegacyFailure() throws Exception {
        var prior=new AtomicReference<String>();UUID id=failAfterPatch(prior);
        Files.writeString(file(id,"checkpoint.json"),prior.get());
        var lines=new ArrayList<String>(Files.readAllLines(file(id,"events.jsonl")));
        var last=(ObjectNode)json.readTree(lines.getLast());last.putNull("payload");lines.set(lines.size()-1,json.writeValueAsString(last));
        Files.writeString(file(id,"events.jsonl"),String.join("\n",lines)+"\n");return id;
    }

    @Test void oldFailureTailIsAuditedWithoutDiscardingEventsOrCallCost() throws Exception {
        UUID id=makeLegacyFailure();String checkpoint=Files.readString(file(id,"checkpoint.json"));
        try(var store=new FileSessionStore(data())) {
            var restored=store.restoreFailed(id,workspace(),4);
            assertEquals(3,restored.modelCalls());assertEquals("LegacyModelFailure",restored.failure().getExceptionType());
            assertEquals(checkpoint,Files.readString(file(id,"checkpoint.json")));
            new AgentRuntime(request -> ModelResponse.finish("legacy recovered"),List.of(),4).resumeFailed(restored,false);
            assertTrue(Files.readString(file(id,"events.jsonl")).contains("model response interrupted"));
            assertEquals(2,restored.history().size());
        }
    }

    @Test void legacyTailContainingUncheckpointedToolRequestIsRejected() throws Exception {
        UUID id=makeLegacyFailure();var lines=new ArrayList<String>(Files.readAllLines(file(id,"events.jsonl")));
        for(int i=lines.size()-2;i>=0;i--) {
            var event=(ObjectNode)json.readTree(lines.get(i));
            if(event.path("type").asText().equals("CONTEXT_ASSEMBLED")) {
                event.put("type","TOOL_CALL_REQUESTED");lines.set(i,json.writeValueAsString(event));break;
            }
        }
        Files.writeString(file(id,"events.jsonl"),String.join("\n",lines)+"\n");
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),4)); }
    }

    @Test void legacyCheckpointAfterWholeToolBatchAcceptsWaitingEventButRejectsMissingResult() throws Exception {
        UUID id=makeLegacyFailure();var cp=(ObjectNode)json.readTree(Files.readString(file(id,"checkpoint.json")));
        long oldWatermark=cp.path("lastEventSequence").asLong();var kept=new ArrayList<ObjectNode>();int watermark=0;
        var cursorTypes=Set.of("TOOL_BATCH_STARTED","TOOL_BATCH_PROGRESS","TOOL_BATCH_COMPLETED","TOOL_EXECUTION_STARTED");
        for(String line:Files.readAllLines(file(id,"events.jsonl"))) {
            var event=(ObjectNode)json.readTree(line);long oldSequence=event.path("sequence").asLong();
            if(cursorTypes.contains(event.path("type").asText())) continue;
            event.put("sequence",kept.size()+1);kept.add(event);if(oldSequence<=oldWatermark) watermark++;
        }
        cp.put("lastEventSequence",watermark);Files.writeString(file(id,"checkpoint.json"),json.writeValueAsString(cp));
        var snapshot=(ObjectNode)json.readTree(Files.readString(file(id,"session.json")));
        snapshot.put("lastEventSequence",kept.size());Files.writeString(file(id,"session.json"),json.writeValueAsString(snapshot));
        writeEvents(id,kept);
        assertEquals("WAITING_FOR_TOOL",kept.get(watermark-1).path("status").asText());
        try(var store=new FileSessionStore(data())) { assertEquals(3,store.restoreFailed(id,workspace(),4).modelCalls()); }
        for(int i=watermark-1;i>=0;i--) {
            var event=kept.get(i);
            if(event.path("type").asText().equals("MODEL_RESPONSE_RECEIVED")) {
                ((com.fasterxml.jackson.databind.node.ArrayNode)event.path("payload").path("toolCalls"))
                        .add(json.valueToTree(read("never-completed")));break;
            }
        }
        writeEvents(id,kept);
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),4)); }
    }

    void writeEvents(UUID id,List<ObjectNode> events) throws IOException {
        var lines=new ArrayList<String>();for(var event:events) lines.add(json.writeValueAsString(event));
        Files.writeString(file(id,"events.jsonl"),String.join("\n",lines)+"\n");
    }

    @Test void rejectsChangedWorkspaceAndInsufficientBudget() throws Exception {
        UUID id=failAfterPatch(new AtomicReference<>());
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),3)); }
        Files.writeString(root.resolve("Main.java"),"class ExternallyChanged {}\n");
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),4)); }
    }

    @Test void resumesRemainingToolAfterCompletedPatchWithoutCallingModelFirst() throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");UUID id;
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();store.bindWorkspace(workspace);var injected=new AtomicBoolean();
            SessionEventSink sink=new SessionEventSink() {
                public void append(AgentSession session,AgentSession.Event event,Object payload) {
                    if(event.type()==AgentSession.EventType.TOOL_CALL_REQUESTED && payload instanceof ToolCall call
                            && call.id().equals("after") && injected.compareAndSet(false,true)) throw new IllegalStateException("stop between tools");
                    store.append(session,event,payload);
                }
                public void checkpoint(AgentSession session) { store.checkpoint(session); }
                public ObservationArchive observationArchive(AgentSession session) { return store.observationArchive(session); }
            };
            var session=new AgentSession("patch then read",sink);store.create(session);var calls=new AtomicInteger();
            new AgentRuntime(request -> calls.getAndIncrement()==0 ? ModelResponse.callTool(read("before"))
                    : ModelResponse.callTools(List.of(patch(),read("after")),"modify and verify"),
                    List.of(new ReadFileTool(workspace),new ApplyPatchTool(workspace,session)),4).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());assertEquals(1,session.pendingToolBatch().getNextCallIndex());
            assertFalse(session.pendingToolBatch().isInFlight());store.saveSnapshot(session);id=session.id();
        }
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();var restored=store.restoreFailed(id,workspace,3);
            new AgentRuntime(request -> {
                assertEquals(3,restored.history().size());assertNull(restored.pendingToolBatch());
                assertEquals("after",restored.history().getLast().call().id());
                return ModelResponse.finish("done");
            },List.of(new ReadFileTool(workspace)),3).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());assertEquals(1,restored.workspaceState().getRevision());
            assertEquals(1,restored.history().stream().filter(e->e.call().id().equals("patch")).count());
        }
    }

    UUID failInFlight(boolean changeWorkspace) throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();store.bindWorkspace(workspace);var count=new AtomicInteger();
            Tool inconsistent=new Tool() {
                public String name() { return "read_file"; }
                public ToolResult execute(Map<String,String> args) {
                    if(count.getAndIncrement()==0) return new ReadFileTool(workspace).execute(args);
                    if(changeWorkspace) try { Files.writeString(root.resolve("Main.java"),"class UnknownWrite {}\n"); }
                    catch(IOException failure) { throw new RuntimeException(failure); }
                    return new ToolResult(true,"result with invalid provenance",new FileFingerprint("Wrong.java","0".repeat(64)));
                }
            };
            var session=new AgentSession("read batch",store);store.create(session);
            new AgentRuntime(request -> ModelResponse.callTools(List.of(read("first"),read("second")),"read both"),List.of(inconsistent),3).run(session);
            assertEquals(AgentSession.Status.FAILED,session.status());assertTrue(session.pendingToolBatch().isInFlight());
            assertEquals(1,session.pendingToolBatch().getNextCallIndex());store.saveSnapshot(session);return session.id();
        }
    }

    @Test void inFlightToolRequiresExplicitRetryAndCompletedToolIsNotReplayed() throws Exception {
        UUID id=failInFlight(false);
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),2)); }
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();var restored=store.restoreFailed(id,workspace,2,true);
            new AgentRuntime(request -> ModelResponse.finish("done"),List.of(new ReadFileTool(workspace)),2).resumeFailed(restored,true);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());assertEquals(2,restored.history().size());
            assertEquals(1,restored.history().stream().filter(e->e.call().id().equals("first")).count());
            assertTrue(Files.readString(file(id,"events.jsonl")).contains("TOOL_CALL_RETRY_AUTHORIZED"));
        }
    }

    @Test void uncertainToolThatChangedFilesCannotBeBlindlyRetried() throws Exception {
        UUID id=failInFlight(true);
        try(var store=new FileSessionStore(data())) {
            var failure=assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),2,true));
            assertTrue(failure.getMessage().contains("changed workspace"));
        }
        assertEquals("class UnknownWrite {}\n",Files.readString(root.resolve("Main.java")));
    }

    @Test void forgedPendingCursorCannotSkipUnfinishedCall() throws Exception {
        UUID id=failInFlight(false);var cp=(ObjectNode)json.readTree(Files.readString(file(id,"checkpoint.json")));
        ((ObjectNode)cp.path("pendingToolBatch")).put("nextCallIndex",2).put("inFlight",false);
        Files.writeString(file(id,"checkpoint.json"),json.writeValueAsString(cp));
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),2,true)); }
    }

    @Test void interruptedInFlightBatchWithoutFailureEventRequiresExplicitRetry() throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");UUID id;
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();store.bindWorkspace(workspace);var count=new AtomicInteger();
            Tool interrupted=new Tool() {
                public String name() { return "read_file"; }
                public ToolResult execute(Map<String,String> args) {
                    if(count.getAndIncrement()==0) return new ReadFileTool(workspace).execute(args);
                    throw new AssertionError("simulated process interruption, no result persisted");
                }
            };
            var session=new AgentSession("read both",store);id=session.id();store.create(session);
            assertThrows(AssertionError.class,()->new AgentRuntime(request->ModelResponse.callTools(
                    List.of(read("first"),read("second")),"read both"),List.of(interrupted),3).run(session));
            assertEquals(AgentSession.Status.WAITING_FOR_TOOL,session.status());store.saveSnapshot(session);
        }
        String log=Files.readString(file(id,"events.jsonl"));assertFalse(log.contains("SESSION_FAILED"));
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.restoreFailed(id,workspace(),2)); }
        assertEquals(log,Files.readString(file(id,"events.jsonl")));
        try(var store=new FileSessionStore(data())) {
            var workspace=workspace();var restored=store.restoreFailed(id,workspace,2,true);
            assertEquals("InterruptedToolBatch",restored.failure().getExceptionType());
            new AgentRuntime(request->ModelResponse.finish("done"),List.of(new ReadFileTool(workspace)),2).resumeFailed(restored,true);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());assertEquals(2,restored.history().size());
            assertTrue(Files.readString(file(id,"events.jsonl")).startsWith(log));
        }
    }
}
