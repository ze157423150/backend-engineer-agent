package dev.backendagent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import dev.backendagent.cli.AgentOptions;
import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.model.DeepSeekModelClient;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;
import dev.backendagent.runtime.ContextBudget;
import dev.backendagent.tools.ListFilesTool;
import dev.backendagent.tools.ReadFileTool;
import dev.backendagent.tools.SearchCodeTool;
import dev.backendagent.tools.Workspace;
import dev.backendagent.tools.RememberFactTool;
import dev.backendagent.tools.ApplyPatchTool;
import dev.backendagent.tools.WorkspaceDiffTool;
import dev.backendagent.tools.CreateFileTool;
import dev.backendagent.tools.RunTestsTool;
import dev.backendagent.sandbox.DockerSandboxExecutor;
import dev.backendagent.sandbox.LocalProcessRunner;
import java.time.Duration;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.persistence.PersistenceException;
import dev.backendagent.memory.MemoryLoader;

public final class AgentApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.print(AgentOptions.USAGE);
            return;
        }
        AgentOptions options;
        try {
            options = AgentOptions.parse(args);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.err.print(AgentOptions.USAGE);
            System.exit(2);
            return;
        }
        var store = new FileSessionStore(options.getDataDirectory());
        if (options.getShowSession() != null) {
            try {
                System.out.println(store.read(options.getShowSession()).toPrettyString());
            } catch (IOException failure) {
                System.err.println("Cannot read session; check ID, --data-dir and stored file integrity.");
                System.exit(1);
            }
            return;
        }
        if (options.getExportSession() != null) {
            try {
                store.exportWorkspace(options.getExportSession(), options.getOutputWorkspace());
                System.out.println("Exported isolated workspace to: " + options.getOutputWorkspace().toAbsolutePath());
            } catch (IOException failure) {
                System.err.println("Cannot export workspace: " + failure.getMessage());
                System.exit(1);
            } finally { store.close(); }
            return;
        }
        try {
            Path protectedConfig = options.getConfigFile() == null
                    ? Path.of("agent-local.properties") : options.getConfigFile();
            var config = AgentConfigLoader.load(options.getConfigFile());
            var model = new DeepSeekModelClient(config);
            var runner = new LocalProcessRunner();
            boolean resuming = options.getResumeSession() != null;
            dev.backendagent.sandbox.DockerWorkspace workspace;
            AgentSession session;
            if (resuming) {
                workspace = dev.backendagent.sandbox.DockerWorkspace.open(options.getWorkspace(),
                        store.sessionDirectory(options.getResumeSession()), runner, options.getSandboxImage());
                session = store.restore(options.getResumeSession(), workspace, options.getMaxModelCalls());
            } else {
                var source = new Workspace(options.getWorkspace(), protectedConfig, options.getDataDirectory());
                session = new AgentSession(options.getTask(), store);
                store.create(session);
                workspace = dev.backendagent.sandbox.DockerWorkspace.create(source, store.sessionDirectory(session.id()),
                        runner, options.getSandboxImage());
            }
            store.bindWorkspace(workspace);
            var sandbox = new DockerSandboxExecutor(runner, options.getSandboxImage(),
                    Duration.ofSeconds(options.getTestTimeoutSeconds()));
            var archive = store.observationArchive(session);
            var tools = List.of(new ListFilesTool(workspace), new ReadFileTool(workspace),
                    new dev.backendagent.tools.ReadObservationTool(session, workspace, archive),
                    new dev.backendagent.tools.SearchHistoryTool(new dev.backendagent.history.HistoryArchiveReader(archive, workspace)),
                    new SearchCodeTool(workspace), new RememberFactTool(session),
                    new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session),
                    new WorkspaceDiffTool(workspace), new RunTestsTool(workspace, sandbox));
            var runtime = new AgentRuntime(model, tools, options.getMaxModelCalls(),
                    new ContextBudget(options.getMaxHistoryCharacters()), options.getMinimumSummaryInputCharacters(), System.out::println);

            System.out.println("Session ID: " + session.id());
            System.out.println("Session files: " + store.sessionDirectory(session.id()));
            System.out.println("Isolated workspace: " + workspace.rootPath());
            System.out.println("File tools run in Docker; source repository is unchanged. Export the result explicitly when ready.");
            if (options.getMemoryFrom() != null) {
                var report = new MemoryLoader().load(store, options.getMemoryFrom(), workspace, session);
                store.saveSnapshot(session);
                System.out.println("Memory loaded: " + report.getLoaded() + ", skipped: " + report.getSkipped());
            }
            System.out.println("Agent started. Compaction progress is shown live; full execution trace follows when the task ends.");
            if (resuming) { runtime.resume(session); } else { runtime.run(session); }
            store.saveSnapshot(session);
            printResult(session);
            if (session.status() != AgentSession.Status.COMPLETED) {
                System.exit(1);
            }
        } catch (PersistenceException failure) {
            System.err.println(failure.getMessage());
            System.err.println("Execution stopped. Previously applied file changes were not rolled back.");
            System.exit(1);
        } catch (IOException failure) {
            System.err.println("Cannot access workspace, memory or checkpoint: " + failure.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        } finally {
            store.close();
        }
    }

    private static void printResult(AgentSession session) {
        session.events().forEach(event ->
                System.out.printf("%02d %-26s %s%n", event.sequence(), event.type(), event.detail()));
        System.out.println("Status: " + session.status());
        System.out.println("Model calls: " + session.modelCalls());
        System.out.println("Workspace revision: " + session.workspaceState().getRevision());
        System.out.println("Test status: " + session.workspaceState().testStatus());
        var latestTest = session.workspaceState().getLatestTest();
        if (latestTest != null) {
            System.out.println("Latest test: " + latestTest.getCallId() + " [revision="
                    + latestTest.getWorkspaceRevision() + ", passed=" + latestTest.isPassed() + "]");
        }
        System.out.println("Working memory: " + session.memoryFacts().size() + " notes");
        session.memoryFacts().forEach(fact -> System.out.println("Memory: " + fact.getStatement()
                + " [source=" + fact.getSourceCallId() + ", path=" + fact.getSourcePath()
                + ", quote=" + fact.getEvidenceQuote() + "]"));
        if (session.answer() != null) {
            System.out.println("Answer: " + session.answer());
        }
    }
}
