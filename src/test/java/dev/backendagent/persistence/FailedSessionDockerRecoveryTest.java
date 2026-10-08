package dev.backendagent.persistence;

import dev.backendagent.history.ObservationArchive;
import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="docker.integration",matches="true")
class FailedSessionDockerRecoveryTest {
    @TempDir Path root;

    @Test void resumesRemainingDockerToolAndKeepsCompletedCreateExactlyOnce() throws Exception {
        Path source=root.resolve("source"),data=root.resolve("sessions");Files.createDirectory(source);
        Files.writeString(source.resolve("README.txt"),"Independent Docker recovery fixture\n");
        var runner=new LocalProcessRunner();UUID id;
        try(var store=new FileSessionStore(data)) {
            var injected=new AtomicBoolean();
            SessionEventSink sink=new SessionEventSink() {
                public void append(AgentSession session,AgentSession.Event event,Object payload) {
                    if(event.type()==AgentSession.EventType.TOOL_CALL_REQUESTED && payload instanceof ToolCall call
                            && call.id().equals("read-created") && injected.compareAndSet(false,true)) {
                        throw new AssertionError("injected process interruption before second Docker tool");
                    }
                    store.append(session,event,payload);
                }
                public void checkpoint(AgentSession session) { store.checkpoint(session); }
                public ObservationArchive observationArchive(AgentSession session) { return store.observationArchive(session); }
            };
            var session=new AgentSession("create then read note",sink);id=session.id();store.create(session);
            var workspace=DockerWorkspace.create(new Workspace(source,root.resolve("private.properties"),data),
                    store.sessionDirectory(id),runner,DockerSandboxExecutor.DEFAULT_IMAGE);store.bindWorkspace(workspace);
            var batch=List.of(new ToolCall("create","create_file",Map.of("path","note.txt","content","created once\n")),
                    new ToolCall("read-created","read_file",Map.of("path","note.txt","start_line","1","end_line","5")));
            assertThrows(AssertionError.class, () -> new AgentRuntime(request->ModelResponse.callTools(batch,"create and inspect"),
                    List.of(new CreateFileTool(workspace,session),new ReadFileTool(workspace)),3).run(session));
            assertEquals(AgentSession.Status.WAITING_FOR_TOOL,session.status());assertEquals(1,session.history().size());
            assertEquals(1,session.pendingToolBatch().getNextCallIndex());assertFalse(session.pendingToolBatch().isInFlight());
            assertEquals("created once\n",Files.readString(store.sessionDirectory(id).resolve("workspace/note.txt")));
            store.saveSnapshot(session);
        }
        try(var store=new FileSessionStore(data)) {
            var workspace=DockerWorkspace.open(source,store.sessionDirectory(id),runner,DockerSandboxExecutor.DEFAULT_IMAGE);
            var restored=store.restoreFailed(id,workspace,2);
            new AgentRuntime(request->{
                assertEquals(2,restored.history().size());
                assertTrue(restored.history().getLast().result().content().contains("created once"));
                assertNotNull(restored.history().getLast().evidence());
                return ModelResponse.finish("recovered");
            },List.of(new ReadFileTool(workspace)),2).resumeFailed(restored,false);
            assertEquals(AgentSession.Status.COMPLETED,restored.status());assertEquals(1,restored.workspaceState().getRevision());
            assertEquals(1,restored.history().stream().filter(e->e.call().name().equals("create_file")).count());
            assertFalse(Files.exists(source.resolve("note.txt")));
        }
    }
}
