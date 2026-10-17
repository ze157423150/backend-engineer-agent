package dev.backendagent.persistence;

import dev.backendagent.model.*;
import dev.backendagent.runtime.*;
import dev.backendagent.tools.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PendingSummaryCheckpointTest {
    @TempDir Path root;

    @Test
    void acceptedCoverageSurvivesCompletedSessionRestore() throws Exception {
        Path data = root.resolve("sessions");
        var workspace = new Workspace(root, root.resolve("agent-local.properties"), data);
        var step = new AtomicInteger();
        var model = new ModelClient() {
            public boolean supportsSummarization() { return true; }
            public List<SummaryNote> summarize(SummaryRequest request) {
                var source = request.getObservations().getFirst();
                return List.of(new SummaryNote(SummaryNote.Kind.PROGRESS, "Listed a historical entry",
                        source.call().id(), source.result().content().split("\n")[0]));
            }
            public ModelResponse execute(ModelRequest request) {
                int current = step.getAndIncrement();
                return current == 12 ? ModelResponse.finish("done") : ModelResponse.callTool(
                        new ToolCall("list-" + current, "list_files", Map.of("path", "dir-" + current)));
            }
        };
        Tool tool = new Tool() {
            public String name() { return "list_files"; }
            public ToolResult execute(Map<String, String> args) {
                return new ToolResult(true, "entry-" + args.get("path") + "\n" + "x".repeat(600));
            }
        };
        UUID id;
        int coverage;
        try (var store = new FileSessionStore(data)) {
            store.bindWorkspace(workspace);
            var session = new AgentSession("inspect", store);
            store.create(session);
            new AgentRuntime(model, List.of(tool), 40).run(session);
            assertEquals(AgentSession.Status.COMPLETED, session.status());
            assertNotNull(session.contextSummary());
            coverage = session.contextProjection().getSummaryCoveredExchanges();
            assertTrue(coverage > 0);
            store.saveSnapshot(session);
            id = session.id();
        }
        try (var store = new FileSessionStore(data)) {
            var restored = store.openCompleted(id, workspace, 40);
            assertEquals(7, restored.contextProjection().getPolicyVersion());
            assertEquals(coverage, restored.contextProjection().getSummaryCoveredExchanges());
            restored.contextProjection().validateAgainst(restored.history());
        }
    }
}
