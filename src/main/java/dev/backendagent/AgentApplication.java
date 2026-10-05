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
        try {
            Path protectedConfig = options.getConfigFile() == null
                    ? Path.of("agent-local.properties") : options.getConfigFile();
            var workspace = new Workspace(options.getWorkspace(), protectedConfig, options.getDataDirectory());
            var config = AgentConfigLoader.load(options.getConfigFile());
            var model = new DeepSeekModelClient(config);
            store.bindWorkspace(workspace);
            boolean resuming = options.getResumeSession() != null;
            var session = resuming
                    ? store.restore(options.getResumeSession(), workspace, options.getMaxModelCalls())
                    : new AgentSession(options.getTask(), store);
            var sandbox = new DockerSandboxExecutor(new LocalProcessRunner(), options.getSandboxImage(),
                    Duration.ofSeconds(options.getTestTimeoutSeconds()));
            var tools = List.of(new ListFilesTool(workspace), new ReadFileTool(workspace),
                    new SearchCodeTool(workspace), new RememberFactTool(session),
                    new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session),
                    new WorkspaceDiffTool(workspace), new RunTestsTool(workspace, sandbox));
            var runtime = new AgentRuntime(model, tools, options.getMaxModelCalls(),
                    new ContextBudget(options.getMaxHistoryCharacters()));

            if (!resuming) { store.create(session); }
            System.out.println("Session ID: " + session.id());
            System.out.println("Session files: " + store.sessionDirectory(session.id()));
            if (options.getMemoryFrom() != null) {
                var report = new MemoryLoader().load(store, options.getMemoryFrom(), workspace, session);
                store.saveSnapshot(session);
                System.out.println("Memory loaded: " + report.getLoaded() + ", skipped: " + report.getSkipped());
            }
            System.out.println("Agent started. Execution trace will be printed when the task ends.");
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
        System.out.println("Working memory: " + session.memoryFacts().size() + " notes");
        session.memoryFacts().forEach(fact -> System.out.println("Memory: " + fact.getStatement()
                + " [source=" + fact.getSourceCallId() + ", path=" + fact.getSourcePath()
                + ", quote=" + fact.getEvidenceQuote() + "]"));
        if (session.answer() != null) {
            System.out.println("Answer: " + session.answer());
        }
    }
}
