package dev.backendagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.backendagent.config.AgentConfigLoader;
import dev.backendagent.runtime.ContextSummary;
import dev.backendagent.runtime.SummaryNote;
import java.nio.file.*;
import java.util.*;

/** Opt-in live summary experiment. Maven tests never call an external model. */
public final class SummaryQualityEvaluation {
    static final ObjectMapper JSON = new ObjectMapper();
    static final class Fixture {
        final String name;
        final SummaryRequest request;
        final List<String> requiredMarkers;
        final List<String> unsupportedMarkers;
        final int preferredNotes;
        Fixture(String name, String objective, List<ToolExchange> observations, List<String> required,
                List<String> unsupported, int preferredNotes) {
            this.name = name;
            this.request = new SummaryRequest(objective, null, 0, observations.size(), 4000, observations);
            this.requiredMarkers = required;
            this.unsupportedMarkers = unsupported;
            this.preferredNotes = preferredNotes;
        }
    }
    private static ToolExchange observation(String id, String tool, String path, String content, boolean success, int batch) {
        return new ToolExchange(new ToolCall(id, tool, path.isEmpty() ? Map.of() : Map.of("path", path)),
                new ToolResult(success, content), null, batch);
    }
    static List<Fixture> fixtures() {
        var duplicates = new ArrayList<ToolExchange>();
        var background = new ArrayList<ToolExchange>();
        for (int i = 0; i < 4; i++) {
            duplicates.add(observation("read-rate-" + i, "read_file", "docs/rate.txt", "file=docs/rate.txt\n1: RATE=225\n", true, i + 1));
            background.add(observation("padding-" + i, "read_file", "docs/padding/" + i + ".txt",
                    "合成背景材料，不含业务规则。\n", true, i + 1));
        }
        var failed = List.of(
            observation("read-price", "read_file", "Price.java", "历史代码：int rate = 958;", true, 1),
            observation("patch-price", "apply_patch", "Price.java", "已将 rate 从958改为225；修改确认不代表测试通过。", true, 2),
            observation("test-price", "run_tests", "", "status=FAILED\nexitCode=1\n[ERROR] DiscountTest expected: 225 but was: 958\n"
                    + "[INFO] build progress\n".repeat(60), false, 3),
            observation("read-test", "read_file", "DiscountTest.java", "assertEquals(225, discount.rate());", true, 4));
        var pending = List.of(
            observation("read-contract", "read_file", "docs/contract.txt", "契约：金额不得为负数。", true, 1),
            observation("read-negative", "read_file", "DiscountTest.java", "现有测试覆盖负数金额。", true, 2),
            observation("explicit-todo", "read_file", "TODO.txt", "TODO: 补充零金额用例，尚未执行。", true, 3),
            observation("read-limit", "read_file", "docs/limit.txt", "输入金额上限为1000。", true, 4));
        var speculative = List.of("引入缓存", "新增索引", "重构架构", "部署上线", "建议优化");
        return List.of(
            new Fixture("short_duplicates", "记录费率读取事实，用于后续比较。", duplicates,
                    List.of("225"), speculative, 1),
            new Fixture("failed_test", "记录此次代码修改和测试结果。", failed,
                    List.of("225", "958", "FAILED|测试失败|测试未通过|run_tests 失败"), speculative, 4),
            new Fixture("explicit_pending", "整理已有契约、测试与待办。", pending,
                    List.of("负数", "零金额", "1000"), speculative, 4),
            new Fixture("partial_background", "修复折扣计算并运行测试。", background,
                    List.of("不含业务规则"),
                    List.of("尚未测试", "未执行测试", "尚未修改", "尚未读取", "尚未定位", "尚未完成", "尚未获得", "仍需", "下一步"), 1));
    }
    static ObjectNode assess(Fixture fixture, List<SummaryNote> notes) throws java.io.IOException {
        var result = JSON.createObjectNode();
        String text = notes.stream().map(n -> n.getStatement() + "\n" + n.getEvidenceQuote()).reduce("", (a, b) -> a + "\n" + b);
        var missing = result.putArray("missingRequiredMarkers");
        fixture.requiredMarkers.stream().filter(marker -> Arrays.stream(marker.split("\\|"))
                .noneMatch(text::contains)).forEach(missing::add);
        var suspicious = result.putArray("unsupportedMarkerHits");
        fixture.unsupportedMarkers.stream().filter(text::contains).forEach(suspicious::add);
        var badQuotes = result.putArray("invalidQuoteSources");
        var unsupportedNext = result.putArray("unsupportedNextStepSources");
        // Diagnostic only: shared evidence does not prove two statements express the same fact.
        var dedup = new dev.backendagent.runtime.SummaryDeduplicator().analyze(notes,
                id -> fixture.request.getObservations().stream().filter(x -> x.call().id().equals(id)).findFirst().orElse(null));
        int duplicateReferences = dedup.getRemovedCount();
        for (var note : notes) {
            var source = fixture.request.getObservations().stream().filter(x -> x.call().id().equals(note.getSourceCallId())).findFirst();
            if (source.isEmpty() || !source.get().result().content().contains(note.getEvidenceQuote())) badQuotes.add(note.getSourceCallId());
            if (note.getKind() == SummaryNote.Kind.NEXT_STEP) unsupportedNext.add(note.getSourceCallId());
        }
        int observationCharacters = JSON.writeValueAsString(fixture.request.getObservations()).length();
        int summaryCharacters = JSON.writeValueAsString(Map.of("revision", 1, "coveredExchanges", fixture.request.getToIndex(), "notes", notes)).length();
        long runtimeInput = fixture.request.getObservations().stream().mapToLong(new dev.backendagent.runtime.ContextBudget(64000)::measure).sum();
        result.put("runtimeMeasuredInputCharacters", runtimeInput).put("smallerThanRuntimeMeasuredInput", summaryCharacters < runtimeInput);
        result.put("serializedObservationsCharacters", observationCharacters).put("serializedSummaryCharacters", summaryCharacters)
                .put("smallerThanSerializedObservations", summaryCharacters < observationCharacters)
                .put("noteCount", notes.size()).put("preferredMaximumNotes", fixture.preferredNotes)
                .put("excessiveNotes", notes.size() > fixture.preferredNotes).put("duplicateReferenceCount", duplicateReferences)
                .put("statementCharacters", notes.stream().mapToInt(n -> n.getStatement().length()).sum())
                .put("quoteCharacters", notes.stream().mapToInt(n -> n.getEvidenceQuote().length()).sum());
        result.put("fixtureChecksPassed", !notes.isEmpty() && missing.isEmpty() && suspicious.isEmpty() && badQuotes.isEmpty()
                && unsupportedNext.isEmpty() && duplicateReferences == 0);
        result.set("suspectedDuplicatePairs", JSON.valueToTree(dedup.getSuspectedPairs()));
        result.put("gradingPolicy", "evidence-and-facts-v4");
        result.put("noteCountIsDiagnosticOnly", true);
        result.put("notice", "条目数量仅用于观察精简程度，不依据背景类别判失败。固定场景的关键词/来源检查，不能证明语义真实性；结构字符比较不是实际HTTP或计费token，也不等同运行时摘要验收。");
        return result;
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--rescore")) {
            Path path = Path.of(args[1]);
            var saved = (ObjectNode) JSON.readTree(path.toFile());
            for (var run : saved.path("runs")) {
                if (!run.has("notes")) continue;
                var fixture = fixtures().stream().filter(f -> f.name.equals(run.path("fixture").asText())).findFirst().orElseThrow();
                var notes = JSON.readValue(JSON.writeValueAsString(run.path("notes")), SummaryNote[].class);
                ((ObjectNode) run).set("assessment", assess(fixture, List.of(notes)));
            }
            saved.put("assessmentPolicy", "historical-facts-only-v2");
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(saved));
            return;
        }
        if (args.length != 4 || !args[0].equals("--run")) throw new IllegalArgumentException("Usage: --run config output-directory label");
        Path output = Path.of(args[2]);
        Files.createDirectory(output);
        Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var config = AgentConfigLoader.load(Path.of(args[1]));
        var metrics = new ArrayList<ModelCallMetrics>();
        var client = new DeepSeekModelClient(config, metrics::add);
        var report = JSON.createObjectNode().put("label", args[3]).put("assessmentPolicy", "historical-facts-only-v2").put("model", config.getModelName())
                .put("providerHost", config.getBaseUrl().getHost()).put("repeatsPerFixture", 2)
                .put("method", "fixed synthetic summary-only inputs; independent repeats, no seeded generation or full task evaluation");
        report.put("prompt", client.buildSummaryRequest(fixtures().getFirst().request).path("messages").get(0).path("content").asText());
        var runs = report.putArray("runs");
        for (var fixture : fixtures()) {
            for (int repeat = 1; repeat <= 2; repeat++) {
                var row = runs.addObject().put("fixture", fixture.name).put("repeat", repeat);
                row.set("input", JSON.valueToTree(fixture.request));
                int before = metrics.size();
                try {
                    var notes = client.summarize(fixture.request);
                    row.set("notes", JSON.valueToTree(notes));
                    row.set("assessment", assess(fixture, notes));
                    row.put("parsed", true);
                } catch (RuntimeException failure) {
                    row.put("parsed", false).put("failureType", failure.getClass().getSimpleName());
                }
                row.set("apiMetrics", JSON.valueToTree(metrics.subList(before, metrics.size())));
                Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                System.out.println(args[3] + " " + fixture.name + " repeat=" + repeat + " parsed=" + row.path("parsed").asBoolean()
                        + " checks=" + row.path("assessment").path("fixtureChecksPassed").asBoolean());
            }
        }
        report.put("status", "COMPLETE").put("actualHttpAttempts", metrics.size());
        Files.writeString(output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
