package dev.backendagent.runtime;

import dev.backendagent.model.*;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NoProgressRuntimeTest {
    @TempDir Path root;
    private ToolCall read(int number) { return new ToolCall("read-" + number, "read_file", Map.of("path", "Main.java")); }
    private Tool reader() {
        return new Tool() {
            public String name() { return "read_file"; }
            public ToolResult execute(Map<String, String> arguments) { return new ToolResult(true, "unchanged source"); }
        };
    }

    @Test void warnsTwiceThenStopsAtCompleteBatchWithoutAnotherModelCall() {
        var requests = new ArrayList<ModelRequest>();
        var counter = new AtomicInteger();
        var session = new AgentSession("fix source");
        ModelClient model = request -> {
            requests.add(request);
            return ModelResponse.callTool(read(counter.incrementAndGet()));
        };
        new AgentRuntime(model, List.of(reader()), 40).run(session);
        assertEquals(AgentSession.Status.FAILED, session.status());
        assertEquals("NoProgressException", session.failure().getExceptionType());
        assertTrue(session.failure().canResume());
        assertNull(session.pendingToolBatch());
        assertEquals(13, session.modelCalls());
        assertEquals(13, session.history().size());
        assertEquals(2, session.events().stream().filter(e -> e.type() == AgentSession.EventType.NO_PROGRESS_WARNING).count());
        assertEquals(1, session.events().stream().filter(e -> e.type() == AgentSession.EventType.NO_PROGRESS_STOPPED).count());
        assertNull(requests.get(4).noProgressReport());
        assertEquals(4, requests.get(5).noProgressReport().getConsecutiveBatches());
        assertEquals(2, requests.get(9).noProgressReport().getWarningLevel());
    }

    @Test void correctionAfterWarningResetsNoticeAndAllowsCompletion() {
        var counter = new AtomicInteger();
        var patched = new java.util.concurrent.atomic.AtomicBoolean();
        Tool patch = new Tool() {
            public String name() { return "apply_patch"; }
            public ToolResult execute(Map<String, String> arguments) { patched.set(true); return new ToolResult(true, "applied"); }
        };
        var session = new AgentSession("fix source");
        ModelClient model = request -> {
            if (patched.get()) {
                assertNull(request.noProgressReport());
                assertEquals(1, request.workspaceState().getRevision());
                return ModelResponse.finish("modified; tests not run");
            }
            if (request.noProgressReport() != null) return ModelResponse.callTool(new ToolCall("patch", "apply_patch", Map.of()));
            return ModelResponse.callTool(read(counter.incrementAndGet()));
        };
        new AgentRuntime(model, List.of(reader(), patch), 40).run(session);
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        assertTrue(session.events().stream().noneMatch(e -> e.type() == AgentSession.EventType.NO_PROGRESS_STOPPED));
    }

    @Test void stoppedSessionRestoresAndExplicitResumeRetainsSeenObservations() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        var workspace = new Workspace(root, root.resolve("agent-local.properties"), root.resolve("sessions"));
        var counter = new AtomicInteger();
        java.util.UUID id;
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            store.bindWorkspace(workspace);
            var session = new AgentSession("inspect source", store); store.create(session);
            new AgentRuntime(request -> ModelResponse.callTool(read(counter.incrementAndGet())),
                    List.of(new ReadFileTool(workspace)), 40).run(session);
            assertEquals(AgentSession.Status.FAILED, session.status());
            id = session.id();
            var events = store.read(id).path("events");
            assertTrue(events.toString().contains("NO_PROGRESS_STOPPED"));
        }
        try (var store = new FileSessionStore(root.resolve("sessions"))) {
            var session = store.restoreFailed(id, workspace, 60);
            assertTrue(session.failure().canResume());
            int before = session.history().size();
            ModelClient model = request -> {
                if (counter.incrementAndGet() <= 17) return ModelResponse.callTool(read(counter.get()));
                assertEquals(4, request.noProgressReport().getConsecutiveBatches());
                return ModelResponse.finish("stopped reading and reported findings");
            };
            new AgentRuntime(model, List.of(new ReadFileTool(workspace)), 60).resumeFailed(session, false);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertEquals(before + 4, session.history().size());
            assertEquals(3, session.events().stream().filter(e -> e.type() == AgentSession.EventType.NO_PROGRESS_WARNING).count());
        }
    }

    @Test void newTurnAndOtherSessionDoNotInheritStagnation() {
        var session = new AgentSession("first request");
        var counter = new AtomicInteger();
        ModelClient model = request -> counter.incrementAndGet() <= 5 ? ModelResponse.callTool(read(counter.get()))
                : ModelResponse.finish("findings");
        new AgentRuntime(model, List.of(reader()), 40).run(session);
        assertNotNull(session.noProgressReport());
        ModelClient next = request -> {
            assertNull(request.noProgressReport());
            return request.turns().getLast().getEndHistoryIndex() == request.turns().getLast().getStartHistoryIndex()
                    ? ModelResponse.callTool(read(100)) : ModelResponse.finish("next findings");
        };
        var runtime = new AgentRuntime(next, List.of(reader()), 50);
        runtime.continueConversation(session, "read it again for this requirement");
        assertEquals(AgentSession.Status.COMPLETED, session.status());
        var other = new AgentSession("independent request");
        runtime.run(other);
        assertEquals(AgentSession.Status.COMPLETED, other.status());
        assertTrue(other.events().stream().noneMatch(e -> e.type() == AgentSession.EventType.NO_PROGRESS_WARNING));
    }
}
