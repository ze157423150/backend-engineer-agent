package dev.backendagent.model;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Regrades saved tool results using typed freshness; never calls a model or rewrites the source report. */
public final class LongHistoryScenarioRescore {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path original = Path.of(args[0]);
        var report = (ObjectNode) json.readTree(original.toFile());
        report.put("gradingPolicy", "typed-historical-evidence-v1");
        for (var entry : report.withArray("runs")) {
            var row = (ObjectNode) entry;
            var saved = json.readTree(original.getParent().resolve("sessions").resolve(row.path("sessionId").asText()).resolve("session.json").toFile());
            var history = List.of(json.treeToValue(saved.get("history"), ToolExchange[].class));
            boolean followed = RealModelEvaluation.followedLongHistoryScenario(history);
            row.put("initialScenarioFollowed", row.path("scenarioFollowed").asBoolean());
            row.put("initialSuccess", row.path("success").asBoolean());
            row.put("scenarioFollowed", followed);
            row.put("success", followed && row.path("hiddenTestsPassed").asBoolean() && row.path("status").asText().equals("COMPLETED"));
        }
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
    }
}
