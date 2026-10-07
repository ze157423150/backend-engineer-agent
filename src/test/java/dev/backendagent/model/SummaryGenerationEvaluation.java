package dev.backendagent.model;

import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.runtime.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Exercises the real runtime plan, skip, validation and request-budget path on synthetic histories. */
public final class SummaryGenerationEvaluation {
    static final class Case {
        final String name;
        final SummaryQualityEvaluation.Fixture fixture;
        final boolean expectSkip;
        Case(String name, SummaryQualityEvaluation.Fixture fixture, boolean expectSkip) {
            this.name = name; this.fixture = fixture; this.expectSkip = expectSkip;
        }
    }
    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var fixture : SummaryQualityEvaluation.fixtures()) {
            cases.add(new Case(fixture.name + "_short", fixture, true));
            var expanded = new ArrayList<ToolExchange>();
            for (var obs : fixture.request.getObservations()) {
                String noise = obs.call().name().equals("run_tests") ? "[INFO] build progress\n" : "背景说明，无新增业务事实。\n";
                // Added noise tests reduction, not additional requirements or executed code.
                expanded.add(new ToolExchange(obs.call(), new ToolResult(obs.result().successful(),
                        obs.result().content() + noise.repeat(250)), null, obs.modelCallNumber()));
            }
            var longFixture = new SummaryQualityEvaluation.Fixture(fixture.name + "_long", fixture.request.getObjective(),
                    expanded, fixture.requiredMarkers, fixture.unsupportedMarkers, fixture.preferredNotes);
            cases.add(new Case(longFixture.name, longFixture, false));
        }
        return cases;
    }
    private static void replay(Path input, Path output) throws Exception {
        var json = SummaryQualityEvaluation.JSON;
        var report = (ObjectNode) json.readTree(input.toFile());
        report.put("replaySource", input.toString()).put("gradingPolicy", "evidence-and-facts-v4");
        for (var value : report.withArray("runs")) {
            var row = (ObjectNode) value;
            if (row.path("expectSkip").asBoolean() || !row.has("notes")) continue;
            var test = cases().stream().filter(c -> c.name.equals(row.path("fixture").asText())).findFirst().orElseThrow();
            var session = SummaryEvaluationSession.seed(test.fixture.request.getObjective(), test.fixture.request.getObservations());
            var compactor = new ContextCompactor(new ContextBudget(64000));
            var request = compactor.plan(session);
            var notes = List.of(json.treeToValue(row.get("notes"), SummaryNote[].class));
            var eligible = new SummaryQualityEvaluation.Fixture(test.fixture.name, request.getObjective(), request.getObservations(),
                    test.fixture.requiredMarkers, test.fixture.unsupportedMarkers, test.fixture.preferredNotes);
            row.set("initialAssessment", row.get("assessment"));
            row.set("assessment", SummaryQualityEvaluation.assess(eligible, notes));
            var accepted = compactor.validate(request, notes, session);
            row.set("acceptedNotes", json.valueToTree(accepted.getNotes()));
            row.set("acceptedAssessment", SummaryQualityEvaluation.assess(eligible, accepted.getNotes()));
            row.set("deduplication", json.valueToTree(new SummaryDeduplicator().analyze(notes, session::originalObservation)));
            row.put("qualified", row.path("runtimeAccepted").asBoolean()
                    && row.path("acceptedAssessment").path("fixtureChecksPassed").asBoolean());
        }
        // Preserve the original live report, usage and notes. Replay makes no network requests.
        Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--replay")) {
            replay(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length != 3 || !args[0].equals("--run")) throw new IllegalArgumentException("Usage: --run config output-directory");
        var json = SummaryQualityEvaluation.JSON;
        var output = Path.of(args[2]);
        Files.createDirectory(output);
        Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = AgentConfigLoader.load(Path.of(args[1]));
        var report = json.createObjectNode().put("method", "synthetic complete batches; actual runtime planner, skip, validator, request budget; live summary only")
                .put("model", config.getModelName()).put("providerHost", config.getBaseUrl().getHost())
                .put("minimumInputCharacters", ContextCompactor.DEFAULT_MIN_INPUT_CHARACTERS);
        var runs = report.putArray("runs");
        int actualCalls = 0;
        for (var test : cases()) {
            int repeats = test.expectSkip ? 2 : 3;
            for (int repeat = 1; repeat <= repeats; repeat++) {
                var session = SummaryEvaluationSession.seed(test.fixture.request.getObjective(), test.fixture.request.getObservations());
                var budget = new ContextBudget(64000);
                var compactor = new ContextCompactor(budget);
                var plan = compactor.decide(session);
                var row = runs.addObject().put("fixture", test.name).put("repeat", repeat).put("expectSkip", test.expectSkip)
                        .put("plannedReason", plan.getReason().name()).put("inputBodyCharacters", plan.getInputCharacters());
                row.set("originalObservations", json.valueToTree(test.fixture.request.getObservations()));
                row.set("plannedInput", json.valueToTree(plan.getRequest()));
                var metrics = new ArrayList<ModelCallMetrics>();
                var client = new DeepSeekModelClient(config, metrics::add);
                ModelClient model = new ModelClient() {
                    public boolean supportsSummarization() { return true; }
                    public RequestBudgetReport inspectRequest(ModelRequest r) { return client.inspectRequest(r); }
                    public List<SummaryNote> summarize(SummaryRequest r) {
                        if (test.expectSkip) throw new IllegalStateException("Short fixture unexpectedly requested summary");
                        try {
                            var notes = client.summarize(r);
                            row.set("notes", json.valueToTree(notes));
                            // Quality checks use only the eligible, excerpted inputs; stale originals are excluded.
                            var eligible = new SummaryQualityEvaluation.Fixture(test.fixture.name, r.getObjective(), r.getObservations(),
                                    test.fixture.requiredMarkers, test.fixture.unsupportedMarkers, test.fixture.preferredNotes);
                            row.set("assessment", SummaryQualityEvaluation.assess(eligible, notes));
                            try {
                                var accepted = compactor.validate(r, notes, session);
                                row.set("acceptedNotes", json.valueToTree(accepted.getNotes()));
                                row.set("deduplication", json.valueToTree(new SummaryDeduplicator().analyze(notes, session::originalObservation)));
                                row.set("acceptedAssessment", SummaryQualityEvaluation.assess(eligible, accepted.getNotes()));
                            }
                            catch (RuntimeException rejected) { row.put("validationReason", rejected.getMessage()); }
                            return notes;
                        } catch (java.io.IOException impossible) {
                            throw new IllegalStateException("Cannot assess synthetic summary");
                        }
                    }
                    public ModelResponse execute(ModelRequest r) {
                        row.set("outgoingRequestBudget", json.valueToTree(inspectRequest(r)));
                        return ModelResponse.finish("summary evaluation completed; no task-model API call");
                    }
                };
                int callsBefore = session.modelCalls();
                new AgentRuntime(model, List.of(), callsBefore + 2, budget).run(session);
                boolean skipped = session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.WINDOW_SUMMARY_SKIPPED);
                boolean accepted = session.events().stream().anyMatch(e -> e.type() == AgentSession.EventType.COMPACTION_COMPLETED);
                boolean qualified = session.status() == AgentSession.Status.COMPLETED && (test.expectSkip
                        ? skipped && metrics.isEmpty() && session.contextSummary() == null
                        : accepted && row.path("acceptedAssessment").path("fixtureChecksPassed").asBoolean());
                row.put("runtimeStatus", session.status().name()).put("skipped", skipped).put("runtimeAccepted", accepted)
                        .put("qualified", qualified).put("actualHttpAttempts", metrics.size());
                row.set("apiMetrics", json.valueToTree(metrics));
                if (session.contextSummary() != null) row.put("acceptedSummaryCharacters", session.contextSummary().characterCount());
                row.set("events", json.valueToTree(session.events()));
                actualCalls += metrics.size();
                report.put("actualHttpAttempts", actualCalls);
                Files.writeString(output.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println(test.name + " repeat=" + repeat + " plan=" + plan.getReason() + " accepted=" + accepted + " qualified=" + qualified);
            }
        }
        report.put("status", "COMPLETE");
        Files.writeString(output.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
