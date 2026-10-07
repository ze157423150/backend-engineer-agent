package dev.backendagent.model;

import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.config.ModelConfig;
import dev.backendagent.runtime.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

/** Opt-in captures controlled fixture responses; parses them through the unmodified production client locally. */
public final class SummaryRejectionDiagnosis {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path source = Path.of(args[0]), output = Path.of(args[2]);
        Files.createDirectory(output);
        Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = AgentConfigLoader.load(Path.of(args[1]));
        var builder = new DeepSeekModelClient(config);
        var http = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var saved = json.readTree(source.resolve("report.json").toFile());
        var report = json.createObjectNode().put("method", "new generations from exact saved failed inputs; not original responses; parser and historical validator reused");
        var runs = report.putArray("runs");
        int attempts = 0;
        for (var task : saved.path("runs")) {
            var directory = source.resolve("sessions").resolve(task.path("sessionId").asText());
            var snapshot = json.readTree(directory.resolve("session.json").toFile());
            var history = List.of(json.treeToValue(snapshot.get("history"), ToolExchange[].class));
            var outcomes = new ArrayList<JsonNode>();
            for (String line : Files.readAllLines(directory.resolve("events.jsonl"))) {
                var event = json.readTree(line);
                if (Set.of("COMPACTION_COMPLETED", "COMPACTION_FAILED").contains(event.path("type").asText())) outcomes.add(event);
            }
            for (int index = 0; index < outcomes.size(); index++) {
                var original = outcomes.get(index);
                if (!original.path("type").asText().equals("COMPACTION_FAILED")) continue;
                var q = task.path("summaryInputs").get(index);
                var prior = q.path("previousSummary").isNull() ? null : json.treeToValue(q.get("previousSummary"), ContextSummary.class);
                var request = new SummaryRequest(q.path("objective").asText(), prior, q.path("fromIndex").asInt(), q.path("toIndex").asInt(),
                        q.path("maxOutputCharacters").asInt(), List.of(json.treeToValue(q.get("observations"), ToolExchange[].class)));
                int call = original.path("modelCalls").asInt();
                String fixture = "task-" + task.path("repeat").asInt() + "-call-" + call;
                String body = json.writeValueAsString(builder.buildSummaryRequest(request));
                if (!config.getRequestBudget().measure(body).isWithinBudget()) throw new IllegalStateException("Saved request no longer fits configured budget");
                Files.writeString(output.resolve(fixture + "-request.json"), body);
                for (int repeat = 1; repeat <= 3; repeat++) {
                    var row = runs.addObject().put("fixture", fixture).put("repeat", repeat).put("originalFailure", original.path("detail").asText());
                    var live = HttpRequest.newBuilder(URI.create(config.getBaseUrl().toString().replaceAll("/+$", "") + "/chat/completions"))
                            .timeout(config.getRequestTimeout()).header("Authorization", "Bearer " + config.getApiKey())
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    var response = http.send(live, HttpResponse.BodyHandlers.ofString());
                    attempts++; row.put("httpStatus", response.statusCode());
                    if (response.statusCode() != 200) throw new IllegalStateException("Diagnostic HTTP failed; response not recorded");
                    Files.writeString(output.resolve(fixture + "-" + repeat + "-response.json"), response.body());
                    row.set("usage", json.readTree(response.body()).path("usage"));
                    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                    byte[] captured = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    server.createContext("/chat/completions", exchange -> {
                        exchange.getRequestBody().readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, captured.length);
                        try (var stream = exchange.getResponseBody()) { stream.write(captured); }
                        exchange.close();
                    });
                    server.start();
                    String stage = "PARSE";
                    try {
                        var replayConfig = new ModelConfig("offline-replay", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                config.getModelName(), config.getConnectTimeout(), config.getRequestTimeout(), config.getRequestBudget());
                        var notes = new DeepSeekModelClient(replayConfig).summarize(request);
                        row.set("notes", json.valueToTree(notes));
                        stage = "VALIDATE";
                        var session = SummaryDiagnosticSession.reconstruct(request.getObjective(), history, call, prior);
                        var accepted = new ContextCompactor(new ContextBudget(64000)).validate(request, notes, session);
                        row.put("accepted", true).put("summaryCharacters", accepted.characterCount());
                    } catch (RuntimeException failure) {
                        row.put("accepted", false).put("failureStage", stage).put("failureType", failure.getClass().getSimpleName())
                                .put("reason", failure.getMessage());
                    } finally { server.stop(0); }
                    report.put("externalHttpAttempts", attempts);
                    Files.writeString(output.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    System.out.println(fixture + " repeat=" + repeat + " accepted=" + row.path("accepted")
                            + " stage=" + row.path("failureStage").asText() + " reason=" + row.path("reason").asText());
                }
            }
        }
        report.put("status", "COMPLETE");
        Files.writeString(output.resolve("report.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
