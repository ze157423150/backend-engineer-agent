package dev.backendagent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.history.HistoryArchiveReader;
import dev.backendagent.persistence.FileSessionStore;
import dev.backendagent.runtime.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.tools.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Controlled archive setup with real Docker tools, followed by autonomous live-model retrieval. */
public final class HistoryFreshnessEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBJECTIVE = "完成历史审计，在 audit/result.json 新建 JSON 报告。此前读取过 docs/receipt.txt、docs/rate.txt 和 docs/control.txt，"
            + "其中部分文件随后被修改。先用历史索引找到它们的早期 read_file 记录，并显式提取历史原文；调用 ID 不提供，请自行检索。"
            + "receipt 的旧标识只能作为历史事实；旧费率不能代表当前费率，提取旧费率之后必须重新读取 docs/rate.txt。"
            + "报告恰好包含 receiptId（历史 RECEIPT_ID 字符串）、historicalRate（旧 RATE 整数）、currentRate（当前 RATE 整数）、"
            + "receiptValidity、rateValidity、controlValidity（索引/提取核验的早期记录 CURRENT/STALE/UNKNOWN），以及 controlMarker（历史 CONTROL_MARKER 字符串）。"
            + "不要改写 docs 中的文件，不要把旧值当作当前值，创建报告后读取确认。";
    private HistoryFreshnessEvaluation() { }
    public static void main(String[] args) throws Exception {
        Path output = Path.of("reports/history-freshness-" + UUID.randomUUID()).toAbsolutePath();
        Path config = Path.of("agent-local.properties");
        int repeats = 3, maxCalls = 16;
        boolean live = false, ownsOutput = false;
        var report = JSON.createObjectNode().put("status", "PREFLIGHT").put("externalApiCalls", 0);
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--run" -> live = true;
                    case "--preflight" -> { }
                    case "--output" -> output = Path.of(args[++i]).toAbsolutePath();
                    case "--config" -> config = Path.of(args[++i]);
                    case "--repeats" -> repeats = Integer.parseInt(args[++i]);
                    case "--max-calls" -> maxCalls = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown option");
                }
            }
            if (repeats < 1 || repeats > 3 || maxCalls < 4 || maxCalls > 20) { throw new IllegalArgumentException("Invalid limits"); }
            Files.createDirectory(output); ownsOutput = true;
            Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            report.put("method", "controlled seeded history using real Docker tools; autonomous live retrieval; no baseline or spontaneous-retrieval claim");
            report.put("historicalEvidencePolicy", "bounded-historical-only-v1");
            report.put("maximumExternalCalls", repeats * maxCalls).put("repeats", repeats).put("maxLiveCallsPerRun", maxCalls);
            var runner = new LocalProcessRunner();
            String image = DockerSandboxExecutor.DEFAULT_IMAGE;
            var inspected = runner.run(List.of("docker", "image", "inspect", image, "--format", "{{.Id}}"), Duration.ofSeconds(10));
            if (inspected.getExitCode() != 0) { throw new IllegalStateException("Docker image unavailable"); }
            report.put("dockerImageId", inspected.getOutput().trim());
            var results = report.putArray("runs");
            var modelConfig = live ? AgentConfigLoader.load(config) : null;
            if (live) { report.put("model", modelConfig.getModelName()).put("providerHost", modelConfig.getBaseUrl().getHost()); }
            for (int repeat = 1; repeat <= (live ? repeats : 1); repeat++) {
                System.out.println("Preparing history-freshness repeat " + repeat);
                Path source = output.resolve("source-" + repeat);
                Files.createDirectories(source.resolve("docs/padding"));
                Files.createDirectories(source.resolve("audit"));
                String receipt = UUID.randomUUID().toString(), control = UUID.randomUUID().toString();
                int oldRate = 100 + new Random().nextInt(300), newRate = oldRate + 733;
                Files.writeString(source.resolve("docs/receipt.txt"), "RECEIPT_ID=" + receipt + "\n" + "已归档的合成审计背景，不表示当前文件状态。\n".repeat(100));
                Files.writeString(source.resolve("docs/rate.txt"), "RATE=" + oldRate + "\n");
                Files.writeString(source.resolve("docs/control.txt"), "CONTROL_MARKER=" + control + "\n");
                for (int i = 0; i < 14; i++) { Files.writeString(source.resolve("docs/padding/" + i + ".txt"), "合成背景材料轮次 " + i + "，不含业务规则。\n"); }
                try (var store = new FileSessionStore(output.resolve("sessions"))) {
                    var session = new AgentSession(OBJECTIVE, store); store.create(session);
                    var workspace = DockerWorkspace.create(new Workspace(source, config, output.resolve("sessions")), store.sessionDirectory(session.id()), runner, image);
                    store.bindWorkspace(workspace);
                    var archive = store.observationArchive(session);
                    List<Tool> tools = List.of(new ListFilesTool(workspace), new ReadFileTool(workspace), new SearchCodeTool(workspace),
                            new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session),
                            new SearchHistoryTool(new HistoryArchiveReader(archive, workspace)), new ReadObservationTool(session, workspace, archive));
                    var setup = new ArrayList<ToolCall>();
                    setup.add(read("docs/receipt.txt")); setup.add(read("docs/rate.txt")); setup.add(read("docs/control.txt"));
                    setup.add(new ToolCall(UUID.randomUUID().toString(), "apply_patch", Map.of("path", "docs/receipt.txt", "old_text", "RECEIPT_ID=" + receipt, "new_text", "RECEIPT_ID=REMOVED")));
                    setup.add(new ToolCall(UUID.randomUUID().toString(), "apply_patch", Map.of("path", "docs/rate.txt", "old_text", "RATE=" + oldRate, "new_text", "RATE=" + newRate)));
                    for (int i = 0; i < 14; i++) { setup.add(read("docs/padding/" + i + ".txt")); }
                    var cursor = new java.util.concurrent.atomic.AtomicInteger();
                    ModelClient preparation = request -> ModelResponse.callTools(List.of(setup.get(cursor.getAndIncrement())), null);
                    new AgentRuntime(preparation, tools, setup.size(), new ContextBudget(64000)).run(session);
                    if (session.status() != AgentSession.Status.BUDGET_EXHAUSTED || session.history().size() != setup.size()
                            || session.history().stream().anyMatch(x -> !x.result().successful()) || session.history().getFirst().archivedBody() == null) {
                        throw new IllegalStateException("Controlled archive preparation failed");
                    }
                    var result = results.addObject().put("repeat", repeat).put("sessionId", session.id().toString()).put("setupScriptedRounds", setup.size());
                    var reader = new HistoryArchiveReader(archive, workspace);
                    boolean verified = reader.read(setup.get(0).id()).getValidity() == ObservationEvidence.Validity.STALE
                            && reader.read(setup.get(1).id()).getValidity() == ObservationEvidence.Validity.STALE
                            && reader.read(setup.get(2).id()).getValidity() == ObservationEvidence.Validity.CURRENT;
                    result.put("preflightEvidenceValid", verified);
                    if (!verified) { throw new IllegalStateException("Freshness preflight failed"); }
                    if (!live) { report.put("status", "READY_NO_API_CALLS"); save(output, report); return; }
                    var metrics = new ArrayList<ModelCallMetrics>();
                    Path runOutput = output;
                    String metricFile = "repeat-" + repeat + "-api.jsonl";
                    var client = new DeepSeekModelClient(modelConfig, metric -> {
                        metrics.add(metric); report.put("externalApiCalls", report.path("externalApiCalls").asInt() + 1);
                        try { Files.writeString(runOutput.resolve(metricFile), JSON.writeValueAsString(metric) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                        catch (java.io.IOException error) { throw new dev.backendagent.persistence.PersistenceException("Cannot save metrics", error); }
                    });
                    var requests = new ArrayList<ObjectNode>();
                    ModelClient measured = new ModelClient() {
                        public RequestBudgetReport inspectRequest(ModelRequest request) { return client.inspectRequest(request); }
                        public boolean supportsSummarization() { return client.supportsSummarization(); }
                        public List<SummaryNote> summarize(SummaryRequest request) { return client.summarize(request); }
                        public ModelResponse execute(ModelRequest request) {
                            try {
                                String body = JSON.writeValueAsString(client.buildRequest(request));
                                boolean oldVisible = body.contains(receipt);
                                boolean oldPresent = request.history().stream().anyMatch(x -> x.call().id().equals(setup.get(0).id()));
                                requests.add(JSON.createObjectNode().put("requestNumber", requests.size() + 1).put("receiptMarkerVisible", oldVisible).put("originalReceiptExchangeVisible", oldPresent)
                                        .put("historicalEvidenceEntries", request.historicalEvidence().size())
                                        .put("receiptMarkerInHistoricalEvidence", request.historicalEvidence().stream().anyMatch(e -> e.getContent().contains(receipt))));
                                if (requests.size() == 1 && (oldVisible || oldPresent)) { throw new IllegalStateException("Initial request leaks archived target"); }
                                return client.execute(request);
                            } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot measure request"); }
                        }
                    };
                    int before = session.history().size();
                    long start = System.nanoTime();
                    new AgentRuntime(measured, tools, setup.size() + maxCalls, new ContextBudget(64000), System.out::println).resume(session);
                    result.put("status", session.status().name()).put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
                    store.saveSnapshot(session);
                    result.put("historicalEvidenceEntries", session.historicalEvidence().size()).put("historicalEvidenceCharacters", session.historicalEvidenceCharacters());
                    grade(result, session, workspace, setup, before, receipt, control, oldRate, newRate);
                    result.put("apiRequests", metrics.size()).put("summaryRequests", metrics.stream().filter(x -> x.getKind().equals("SUMMARY")).count());
                    result.put("requestBytes", metrics.stream().mapToLong(ModelCallMetrics::getRequestBytes).sum());
                    boolean complete = !metrics.isEmpty() && metrics.stream().allMatch(x -> x.getTotalTokens() != null);
                    result.put("usageComplete", complete);
                    if (complete) { result.put("reportedTotalTokens", metrics.stream().mapToLong(ModelCallMetrics::getTotalTokens).sum()); } else { result.putNull("reportedTotalTokens"); }
                    result.set("apiCalls", JSON.valueToTree(metrics)); result.set("requestVisibility", JSON.valueToTree(requests));
                    report.put("status", "RUNNING"); save(output, report);
                    if (metrics.stream().anyMatch(x -> x.getHttpStatus() != 200)) { report.put("status", "STOPPED_API_ERROR"); save(output, report); return; }
                }
            }
            report.put("status", "COMPLETE"); save(output, report);
            System.out.println("Finished: " + output.resolve("report.json"));
        } catch (Exception failure) {
            report.put("status", "BLOCKED").put("failureType", failure.getClass().getSimpleName());
            if (ownsOutput) { save(output, report); }
            System.err.println("History evaluation blocked: " + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }
    private static ToolCall read(String path) { return new ToolCall(UUID.randomUUID().toString(), "read_file", Map.of("path", path, "start_line", "1", "end_line", "150")); }
    private static void save(Path output, ObjectNode report) throws java.io.IOException { Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report)); }
    static void grade(ObjectNode result, AgentSession session, WorkspaceAccess workspace, List<ToolCall> setup, int before,
                      String receipt, String control, int oldRate, int newRate) throws java.io.IOException {
        var live = session.history().subList(before, session.history().size());
        var indexed = new HashSet<String>(); var extracted = new HashSet<String>();
        boolean staleHidden = true, freshRead = false, freshAfterStale = false, staleSeen = false, docsUnmodified = true;
        for (var exchange : live) {
            if (!exchange.result().successful()) { continue; }
            var call = exchange.call(); String text = exchange.result().content();
            if (call.name().equals("search_history")) {
                var page = JSON.readTree(text);
                for (var match : page.path("matches")) {
                    String id = match.path("callId").asText();
                    for (int i = 0; i < 3; i++) {
                        if (id.equals(setup.get(i).id()) && match.path("validity").asText().equals(i == 2 ? "CURRENT" : "STALE")) {
                            indexed.add(id);
                            if (i < 2 && !match.path("snippet").asText().contains("过期正文片段已省略")) { staleHidden = false; }
                        }
                    }
                }
            }
            if (call.name().equals("read_observation")) {
                for (int i = 0; i < 3; i++) {
                    if (setup.get(i).id().equals(call.arguments().get("call_id")) && text.contains("fileEvidenceValidity=" + (i == 2 ? "CURRENT" : "STALE"))) {
                        String expected = i == 0 ? receipt : i == 1 ? "RATE=" + oldRate : control;
                        if (indexed.contains(setup.get(i).id()) && text.contains(expected)) { extracted.add(setup.get(i).id()); if (i == 1) { staleSeen = true; } }
                    }
                }
            }
            if (call.name().equals("read_file") && "docs/rate.txt".equals(call.arguments().get("path")) && text.contains("RATE=" + newRate)) {
                freshRead = true; freshAfterStale |= staleSeen;
            }
            if (call.name().equals("apply_patch") || call.name().equals("create_file")) {
                if (!"audit/result.json".equals(call.arguments().get("path"))) { docsUnmodified = false; }
            }
        }
        boolean outputCorrect = false;
        var output = workspace.readFile("audit/result.json", "1", "200");
        if (output.successful()) {
            // Read via the worker, remove its numbered-line envelope; no host execution of generated output.
            String data = output.content().lines().filter(x -> x.matches("[0-9]+:.*")).map(x -> x.substring(x.indexOf(':') + 2)).reduce("", (a,b) -> a + b + "\n");
            try {
                var node = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(data);
                outputCorrect = node.isObject() && node.size() == 7 && node.path("receiptId").asText().equals(receipt)
                        && node.path("controlMarker").asText().equals(control) && node.path("historicalRate").isIntegralNumber() && node.path("historicalRate").asInt() == oldRate
                        && node.path("currentRate").isIntegralNumber() && node.path("currentRate").asInt() == newRate
                        && node.path("receiptValidity").asText().equals("STALE") && node.path("rateValidity").asText().equals("STALE") && node.path("controlValidity").asText().equals("CURRENT");
            } catch (java.io.IOException | RuntimeException invalid) { outputCorrect = false; }
        }
        result.put("indexedAllTargets", indexed.size() == 3).put("extractedAllTargets", extracted.size() == 3).put("staleSnippetsHidden", staleHidden)
                .put("freshFileRead", freshRead).put("freshReadAfterStaleExtraction", freshAfterStale).put("docsUnmodified", docsUnmodified).put("outputCorrect", outputCorrect);
        result.put("success", session.status() == AgentSession.Status.COMPLETED && indexed.size() == 3 && extracted.size() == 3 && staleHidden && freshRead && freshAfterStale && docsUnmodified && outputCorrect);
    }
}
