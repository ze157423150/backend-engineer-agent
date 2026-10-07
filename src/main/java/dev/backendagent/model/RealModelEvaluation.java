package dev.backendagent.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.runtime.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.tools.*;
import dev.backendagent.history.HistoryArchiveReader;

/** Explicit opt-in live evaluation. No model requests until Docker, worker and graders pass preflight. */
public final class RealModelEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RealModelEvaluation() { }
    public static void main(String[] args) {
        Path output = Path.of("reports", "real-model-" + UUID.randomUUID()).toAbsolutePath();
        var report = JSON.createObjectNode();
        report.put("externalApiCalls", 0).put("status", "PREFLIGHT");
        try {
            var options = new java.util.HashMap<String, String>();
            boolean run = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--run")) { run = true; }
                else if (args[i].equals("--preflight")) { /* No API calls. */ }
                else {
                    if (!Set.of("--config", "--image", "--output", "--repeats", "--max-calls", "--task", "--mode").contains(args[i]) || i + 1 >= args.length) {
                        throw new IllegalArgumentException("Usage: --preflight OR --run [--config file] [--image name] [--output new-dir] [--repeats 1..3] [--max-calls 4..80] [--task normalize|clamp|early-rule|early-rule-sequential|long-history] [--mode full-history|layered]");
                    }
                    options.put(args[i], args[++i]);
                }
            }
            if (options.containsKey("--output")) { output = Path.of(options.get("--output")).toAbsolutePath(); }
            if (Files.exists(output)) { throw new IllegalArgumentException("Evaluation output directory must not exist"); }
            Files.createDirectories(output);
            Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            int repeats = Integer.parseInt(options.getOrDefault("--repeats", "3"));
            int maxCalls = Integer.parseInt(options.getOrDefault("--max-calls", "12"));
            if (repeats < 1 || repeats > 3 || maxCalls < 4 || maxCalls > 80) { throw new IllegalArgumentException("Invalid repeats or max-calls"); }
            if (options.containsKey("--mode") && !Set.of("full-history", "layered").contains(options.get("--mode"))) {
                throw new IllegalArgumentException("Invalid mode");
            }
            String image = options.getOrDefault("--image", DockerSandboxExecutor.DEFAULT_IMAGE);
            report.put("repeats", repeats).put("maxCallsPerRun", maxCalls).put("maximumModelCalls", 6 * repeats * maxCalls);
            report.put("method", "independent autonomous full-history and layered runs; counterbalanced order; no statistical significance claim");
            report.put("dockerImage", image);
            var runner = new LocalProcessRunner();
            report.put("stage", "DOCKER_CLI_AND_DAEMON");
            var info = runner.run(List.of("docker", "info", "--format", "{{.ServerVersion}}"), Duration.ofSeconds(10));
            if (info.getExitCode() != 0 || info.isTimedOut()) { throw new IllegalStateException("Docker daemon unavailable"); }
            report.put("stage", "SANDBOX_IMAGE");
            var inspected = runner.run(List.of("docker", "image", "inspect", image, "--format", "{{.Id}}"), Duration.ofSeconds(10));
            if (inspected.getExitCode() != 0 || inspected.isTimedOut()) { throw new IllegalStateException("Sandbox image unavailable; build it before evaluation"); }
            report.put("dockerImageId", inspected.getOutput().trim());
            String pom = Files.readString(Path.of("pom.xml"));
            var executor = new DockerSandboxExecutor(runner, image, Duration.ofSeconds(90));
            var fixtures = EvaluationFixture.tasks();
            if (options.containsKey("--task")) {
                String selectedTask = options.get("--task");
                fixtures = selectedTask.equals("early-rule-sequential")
                        ? List.of(EvaluationFixture.sequentialHistoryTask())
                        : selectedTask.equals("long-history") ? List.of(EvaluationFixture.longHistoryTask())
                        : fixtures.stream().filter(fixture -> fixture.id.equals(selectedTask)).toList();
                if (fixtures.isEmpty()) { throw new IllegalArgumentException("Unknown evaluation task"); }
            }
            report.put("fixtureVersion", "2-explicit-invalid-input-exception");
            report.put("maximumModelCalls", fixtures.size() * (options.containsKey("--mode") ? 1 : 2) * repeats * maxCalls);
            if (options.containsKey("--mode")) report.put("method", "autonomous selected mode; paired encoded full-history counterfactual on layered trajectory; baseline not transmitted");
            report.put("stage", "PRIVATE_GRADERS");
            var checks = report.putArray("preflightChecks");
            for (var fixture : fixtures) {
                Path seed = output.resolve("preflight").resolve(fixture.id + "-seed");
                Path solution = output.resolve("preflight").resolve(fixture.id + "-reference");
                fixture.write(seed, pom, false, true);
                fixture.write(solution, pom, true, true);
                var negative = executor.runTests(new Workspace(seed, Path.of("agent-local.properties"), output.resolve("sessions")));
                var positive = executor.runTests(new Workspace(solution, Path.of("agent-local.properties"), output.resolve("sessions")));
                boolean valid = !negative.successful() && negative.content().contains("status=FAILED\n") && negative.content().contains("Tests run:")
                        && positive.successful() && positive.content().contains("Tests run:");
                checks.addObject().put("task", fixture.id).put("seedRejected", !negative.successful())
                        .put("referencePassed", positive.successful()).put("valid", valid);
                if (!valid) { throw new IllegalStateException("Fixture grader or sandbox dependencies unavailable: " + fixture.id); }
            }
            // Worker protocol must also be available before loading API credentials.
            report.put("stage", "WORKSPACE_WORKER");
            try (var store = new FileSessionStore(output.resolve("sessions"))) {
                var session = new AgentSession("worker preflight", store);
                store.create(session);
                Path source = output.resolve("preflight/worker-source");
                fixtures.getFirst().write(source, pom, false, false);
                var worker = DockerWorkspace.create(new Workspace(source, Path.of("agent-local.properties"), output.resolve("sessions")),
                        store.sessionDirectory(session.id()), runner, image);
                if (!worker.readFile("src/main/java/dev/eval/FixTarget.java", "1", "20").successful()) {
                    throw new IllegalStateException("Workspace worker cannot read evaluation fixture");
                }
            }
            report.put("preflightPassed", true);
            if (!run) {
                report.put("status", "READY_NO_API_CALLS");
                writeReport(output, report);
                System.out.println("Preflight passed; no API requests. Report: " + output.resolve("report.json"));
                return;
            }
            Path configFile = options.containsKey("--config") ? Path.of(options.get("--config")) : null;
            report.put("stage", "MODEL_CONFIGURATION");
            var config = AgentConfigLoader.load(configFile);
            report.put("model", config.getModelName()).put("providerHost", config.getBaseUrl().getHost());
            var results = report.putArray("runs");
            report.put("stage", "LIVE_EVALUATION");
            boolean stop = false;
            for (var fixture : fixtures) {
                for (int repeat = 1; repeat <= repeats && !stop; repeat++) {
                    // Reverse the pair order on alternate repetitions.
                    var modes = repeat % 2 == 1 ? List.of("full-history", "layered") : List.of("layered", "full-history");
                    for (String mode : modes) {
                        if (options.containsKey("--mode") && !mode.equals(options.get("--mode"))) continue;
                        String label = fixture.id + "-" + repeat + "-" + mode;
                        System.out.println("Evaluating " + label + "; at most " + maxCalls + " model calls");
                        Path source = output.resolve("sources").resolve(label);
                        fixture.write(source, pom, false, false);
                        var calls = new ArrayList<ModelCallMetrics>();
                        var client = new DeepSeekModelClient(config, metric -> {
                            calls.add(metric);
                            report.put("externalApiCalls", report.path("externalApiCalls").asInt() + 1);
                            try { Files.writeString(outputPath(source).resolve(label + "-api.jsonl"), JSON.writeValueAsString(metric) + "\n",
                                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
                            catch (java.io.IOException failure) { throw new dev.backendagent.persistence.PersistenceException("Cannot save evaluation API metrics", failure); }
                        });
                        try (var store = new FileSessionStore(output.resolve("sessions"))) {
                            var session = new AgentSession(fixture.objective, store);
                            store.create(session);
                            var workspace = DockerWorkspace.create(new Workspace(source, configFile == null ? Path.of("agent-local.properties") : configFile,
                                    output.resolve("sessions")), store.sessionDirectory(session.id()), runner, image);
                            store.bindWorkspace(workspace);
                            var pairedRequests = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
                            var summaryInputs = new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
                            var model = evaluationModel(client, session, mode, pairedRequests, summaryInputs);
                            var archive = store.observationArchive(session);
                            var tools = List.of(new ListFilesTool(workspace), new ReadFileTool(workspace), new SearchCodeTool(workspace),
                                    new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session), new WorkspaceDiffTool(workspace),
                                    new RememberFactTool(session), new ReadObservationTool(session, workspace, archive),
                                    new SearchHistoryTool(new HistoryArchiveReader(archive, workspace)), new RunTestsTool(workspace, executor));
                            long start = System.nanoTime();
                            new AgentRuntime(model, tools, maxCalls, new ContextBudget(64000), System.out::println).run(session);
                            long elapsed = (System.nanoTime() - start) / 1_000_000;
                            store.saveSnapshot(session);
                            Path grading = workspace.createTestSnapshot();
                            ToolResult grade;
                            try {
                                // Agent changes to pom/tests cannot weaken the private grade.
                                Files.writeString(grading.resolve("pom.xml"), pom);
                                Path tests = grading.resolve("src/test");
                                if (Files.exists(tests)) {
                                    try (var paths = Files.walk(tests)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); } }
                                }
                                Files.createDirectories(grading.resolve("src/test/java/dev/eval"));
                                Files.writeString(grading.resolve("src/test/java/dev/eval/FixTargetTest.java"), EvaluationFixture.testSource(fixture.hiddenTests));
                                grade = executor.runTests(new Workspace(grading, Path.of("agent-local.properties"), output.resolve("sessions")));
                            } finally {
                                try (var paths = Files.walk(grading)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(path); } }
                            }
                            Files.writeString(output.resolve(label + "-grade.txt"), grade.content());
                            boolean followed = !fixture.historyTask || inspectedRulesBeforeWrite(session);
                            if (fixture.id.equals("long-history")) followed = followed && followedLongHistoryScenario(session);
                            var result = results.addObject();
                            result.put("task", fixture.id).put("repeat", repeat).put("mode", mode).put("sessionId", session.id().toString());
                            result.put("status", session.status().name()).put("elapsedMillis", elapsed).put("modelCalls", session.modelCalls());
                            boolean passed = grade.successful() && grade.content().contains("Tests run:");
                            result.put("hiddenTestsPassed", passed).put("scenarioFollowed", followed)
                                    .put("success", passed && followed && session.status() == AgentSession.Status.COMPLETED);
                            result.put("summaryAccepted", session.events().stream().filter(event -> event.type() == AgentSession.EventType.COMPACTION_COMPLETED).count());
                            result.put("summaryRejected", session.events().stream().filter(event -> event.type() == AgentSession.EventType.COMPACTION_FAILED).count());
                            result.put("apiRequests", calls.size()).put("summaryRequests", calls.stream().filter(call -> call.getKind().equals("SUMMARY")).count());
                            result.put("requestBytes", calls.stream().mapToLong(ModelCallMetrics::getRequestBytes).sum());
                            boolean usageComplete = !calls.isEmpty() && calls.stream().allMatch(call -> call.getTotalTokens() != null);
                            result.put("usageComplete", usageComplete);
                            if (usageComplete) { result.put("reportedTotalTokens", calls.stream().mapToLong(ModelCallMetrics::getTotalTokens).sum()); }
                            else { result.putNull("reportedTotalTokens"); }
                            result.set("apiCalls", JSON.valueToTree(calls));
                            result.set("pairedRequests", JSON.valueToTree(pairedRequests));
                            result.set("summaryInputs", JSON.valueToTree(summaryInputs));
                            report.put("status", "RUNNING");
                            writeReport(output, report);
                            if (calls.stream().anyMatch(call -> call.getHttpStatus() != 200)) { stop = true; break; }
                        }
                    }
                }
                if (stop) { break; }
            }
            report.put("status", stop ? "STOPPED_API_ERROR" : "COMPLETE");
            report.put("limitations", "Small sample; no significance or fee claim. Fixture notes deliberately create historical reading demand; tool trajectories may differ. Full-history requests cannot be silently pruned; budget failure is recorded. Missing provider usage remains null.");
            writeReport(output, report);
            System.out.println("Evaluation status: " + report.path("status").asText() + "; report: " + output.resolve("report.json"));
        } catch (Exception failure) {
            report.put("status", "BLOCKED").put("failureType", failure.getClass().getSimpleName());
            // Do not print configuration/HTTP bodies or exception messages that might contain secrets.
            report.put("reason", "Preflight or evaluation failed; check Docker CLI/daemon/image, fixture checks and configuration. No host execution fallback.");
            try { if (Files.isDirectory(output)) { writeReport(output, report); } } catch (Exception ignored) { }
            System.err.println("Evaluation blocked (" + failure.getClass().getSimpleName() + "). Report: " + output.resolve("report.json"));
            System.exit(2);
        }
    }
    private static Path outputPath(Path source) { return source.getParent().getParent(); }
    private static void writeReport(Path output, com.fasterxml.jackson.databind.node.ObjectNode report) throws java.io.IOException {
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    static ModelClient evaluationModel(DeepSeekModelClient client, AgentSession session, String mode) {
        return evaluationModel(client, session, mode, null, null);
    }
    static ModelClient evaluationModel(DeepSeekModelClient client, AgentSession session, String mode,
            List<com.fasterxml.jackson.databind.node.ObjectNode> paired, List<com.fasterxml.jackson.databind.node.ObjectNode> summaries) {
        boolean full = mode.equals("full-history");
        return new ModelClient() {
            private ModelRequest actual(ModelRequest request) {
                if (!full) { return request; }
                var original = session.history().stream().map(exchange -> session.originalObservation(exchange.call().id())).toList();
                return new ModelRequest(request.objective(), StaleObservationFilter.filter(original), request.availableTools(),
                        request.remainingModelCalls(), 0, 0, request.workingMemory(), null, null, Set.of(), request.workspaceState(), request.historicalEvidence());
            }
            public RequestBudgetReport inspectRequest(ModelRequest request) { return client.inspectRequest(actual(request)); }
            public ModelResponse execute(ModelRequest request) {
                if (!full && paired != null) {
                    var originals = session.history().stream().map(exchange -> session.originalObservation(exchange.call().id())).toList();
                    var baseline = new ModelRequest(request.objective(), StaleObservationFilter.filter(originals), request.availableTools(),
                            request.remainingModelCalls(), 0, 0, request.workingMemory(), null, null, Set.of(), request.workspaceState(), request.historicalEvidence());
                    var f = client.inspectRequest(baseline);
                    var l = client.inspectRequest(request);
                    paired.add(JSON.createObjectNode().put("round", paired.size() + 1).put("historySize", originals.size())
                            .put("fullHistoryBytes", f.getRequestBytes()).put("layeredBytes", l.getRequestBytes())
                            .put("fullWithinBudget", f.isWithinBudget()).put("layeredWithinBudget", l.isWithinBudget())
                            .put("visibleExchanges", request.history().size()).put("remainingCalls", request.remainingModelCalls()));
                }
                return client.execute(actual(request));
            }
            public boolean supportsSummarization() { return !full; }
            public List<SummaryNote> summarize(SummaryRequest request) {
                if (summaries != null) summaries.add(JSON.valueToTree(request));
                return client.summarize(request);
            }
        };
    }
    private static boolean followedLongHistoryScenario(AgentSession session) {
        return followedLongHistoryScenario(session.history());
    }
    static boolean followedLongHistoryScenario(List<ToolExchange> history) {
        int write = 0;
        while (write < history.size() && !WorkspaceState.isSuccessfulWrite(history.get(write))) write++;
        if (write == history.size()) return false;
        var rounds = new java.util.HashSet<Integer>();
        for (int i = 1; i <= 32; i++) {
            String path = "docs/notes/note-" + i + ".txt";
            var read = history.subList(0, write).stream().filter(x -> x.call().name().equals("read_file")
                    && path.equals(x.call().arguments().get("path")) && x.result().successful()).findFirst();
            if (read.isEmpty() || !rounds.add(read.get().modelCallNumber())) return false;
        }
        var after = history.subList(write, history.size());
        boolean search = after.stream().anyMatch(x -> x.call().name().equals("search_history") && x.result().successful());
        boolean historical = after.stream().anyMatch(x -> x.call().name().equals("read_observation") && x.result().successful()
                && x.result().historicalEvidence() != null
                && x.result().historicalEvidence().getValidityAtRetrieval() == ObservationEvidence.Validity.STALE
                && x.result().historicalEvidence().getSourceFile() != null
                && "src/main/java/dev/eval/FixTarget.java".equals(x.result().historicalEvidence().getSourceFile().getPath())
                && x.result().historicalEvidence().getContent().contains("RoundingMode.DOWN"));
        boolean fresh = after.stream().anyMatch(x -> x.call().name().equals("read_file") && x.result().successful()
                && "src/main/java/dev/eval/FixTarget.java".equals(x.call().arguments().get("path")));
        return search && historical && fresh;
    }
    private static boolean inspectedRulesBeforeWrite(AgentSession session) {
        var history = session.history();
        int firstWrite = 0;
        while (firstWrite < history.size() && !WorkspaceState.isSuccessfulWrite(history.get(firstWrite))) { firstWrite++; }
        if (firstWrite == history.size()) { return false; }
        return history.subList(0, firstWrite).stream().anyMatch(exchange -> exchange.call().name().equals("read_file")
                && exchange.result().successful() && "docs/rules.txt".equals(Path.of(exchange.call().arguments().get("path")).normalize().toString()));
    }
}
