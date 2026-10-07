package dev.backendagent.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Trusted synthetic projects and private graders. Hidden tests never enter an agent workspace. */
final class EvaluationFixture {
    final String id;
    final String objective;
    final String seed;
    final String solution;
    final String hiddenTests;
    final boolean historyTask;
    EvaluationFixture(String id, String objective, String seed, String solution, String hiddenTests, boolean historyTask) {
        this.id = id; this.objective = objective; this.seed = seed; this.solution = solution;
        this.hiddenTests = hiddenTests; this.historyTask = historyTask;
    }
    static java.util.List<EvaluationFixture> tasks() {
        String prefix = "package dev.eval;\nimport java.math.*;\nimport java.util.*;\npublic class FixTarget {\n";
        return java.util.List.of(
            new EvaluationFixture("normalize", "修复 FixTarget.normalize：null 返回空字符串，去掉首尾空白，使用与系统 Locale 无关的小写。运行测试验证。",
                prefix + "public static String normalize(String input) { return input.trim().toLowerCase(); }\n}",
                prefix + "public static String normalize(String input) { return input == null ? \"\" : input.trim().toLowerCase(Locale.ROOT); }\n}",
                "assertEquals(\"\", FixTarget.normalize(null)); assertEquals(\"hello\", FixTarget.normalize(\" HELLO \")); "
                    + "Locale previous = Locale.getDefault(); try { Locale.setDefault(Locale.forLanguageTag(\"tr\")); assertEquals(\"i\", FixTarget.normalize(\"I\")); } finally { Locale.setDefault(previous); }", false),
            new EvaluationFixture("clamp", "修复 FixTarget.clamp(value,min,max)：返回闭区间内最近的值；min 大于 max 时抛 IllegalArgumentException。检查整数极值并运行测试。",
                prefix + "public static int clamp(int value, int min, int max) { return Math.min(min, Math.max(max, value)); }\n}",
                prefix + "public static int clamp(int value, int min, int max) { if (min > max) throw new IllegalArgumentException(); return Math.max(min, Math.min(max, value)); }\n}",
                "assertEquals(5, FixTarget.clamp(5, 0, 10)); assertEquals(0, FixTarget.clamp(-1, 0, 10)); assertEquals(10, FixTarget.clamp(99, 0, 10)); "
                    + "assertEquals(Integer.MIN_VALUE, FixTarget.clamp(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE)); "
                    + "assertThrows(IllegalArgumentException.class, () -> FixTarget.clamp(2, 9, 1));", false),
            new EvaluationFixture("early-rule", "先读取 docs/rules.txt，并在开始修改前检查 docs/notes 中各文件的内容，然后依据规则修复 FixTarget.total。不要修改 pom.xml 或测试文件，完成后运行测试。",
                prefix + "public static BigDecimal total(int quantity, BigDecimal unitPrice) { return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.DOWN); }\n}",
                prefix + "public static BigDecimal total(int quantity, BigDecimal unitPrice) { if (quantity < 0 || unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException(); "
                    + "BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity)); if (amount.compareTo(new BigDecimal(\"100\")) >= 0) amount = amount.multiply(new BigDecimal(\"0.90\")); return amount.setScale(2, RoundingMode.HALF_UP); }\n}",
                "assertEquals(new BigDecimal(\"90.00\"), FixTarget.total(1, new BigDecimal(\"100\"))); assertEquals(new BigDecimal(\"99.99\"), FixTarget.total(1, new BigDecimal(\"99.99\"))); "
                    + "assertEquals(new BigDecimal(\"0.01\"), FixTarget.total(1, new BigDecimal(\"0.005\"))); assertEquals(new BigDecimal(\"0.00\"), FixTarget.total(0, new BigDecimal(\"200\"))); "
                    + "assertThrows(IllegalArgumentException.class, () -> FixTarget.total(-1, BigDecimal.ONE)); assertThrows(IllegalArgumentException.class, () -> FixTarget.total(1, null)); "
                    + "assertThrows(IllegalArgumentException.class, () -> FixTarget.total(1, new BigDecimal(\"-1\")));", true)
        );
    }
    /** Explicit coverage variant: individual review rounds make the default summary window observable. */
    static EvaluationFixture sequentialHistoryTask() {
        var base = tasks().stream().filter(task -> task.id.equals("early-rule")).findFirst().orElseThrow();
        return new EvaluationFixture("early-rule-sequential", base.objective
                + "为逐份审阅，每次模型回复最多读取一份 docs 文档；八份 notes 必须分别在不同模型轮次读取，不能同轮批量读取或用搜索代替阅读。",
                base.seed, base.solution, base.hiddenTests, true);
    }

    /** Long single-task history; exercises archive retrieval after many autonomous review rounds. */
    static EvaluationFixture longHistoryTask() {
        var base = tasks().stream().filter(task -> task.id.equals("early-rule")).findFirst().orElseThrow();
        return new EvaluationFixture("long-history", "先读取 docs/rules.txt 和 src/main/java/dev/eval/FixTarget.java。"
                + "然后逐份审阅 docs/notes 的32份文档，每次模型回复最多读取一份文档，所有32份都读完后才能修改代码。"
                + "依照最早的正式规则修复 FixTarget.total，不修改pom.xml或测试文件，运行测试。"
                + "修改后使用 search_history 找到最早读取的 FixTarget.java 记录（含 RoundingMode.DOWN），"
                + "并用 read_observation 显式提取旧代码，说明该历史记录的时效性；最后重新读取当前代码，对比旧实现和新实现。"
                + "旧代码仅用于历史对比，不能当作当前代码。最终回答说明输入校验、折扣阈值、舍入规则以及实际测试结果。",
                base.seed, base.solution, base.hiddenTests, true);
    }

    void write(Path directory, String pom, boolean correct, boolean hidden) throws IOException {
        Files.createDirectories(directory.resolve("src/main/java/dev/eval"));
        Files.createDirectories(directory.resolve("src/test/java/dev/eval"));
        Files.writeString(directory.resolve("pom.xml"), pom);
        Files.writeString(directory.resolve("src/main/java/dev/eval/FixTarget.java"), correct ? solution : seed);
        Files.writeString(directory.resolve("src/test/java/dev/eval/FixTargetTest.java"), testSource(hidden ? hiddenTests : "assertNotNull(FixTarget.class);"));
        if (historyTask && !hidden) {
            Files.createDirectories(directory.resolve("docs/notes"));
            Files.writeString(directory.resolve("docs/rules.txt"), "quantity 不能为负；unitPrice 不能为 null 或负数；这些非法输入统一抛出 IllegalArgumentException。先计算小计，小计达到 100（含等于）打九折，最后 HALF_UP 保留两位小数。\n");
            for (int i = 1; i <= (id.equals("long-history") ? 32 : 8); i++) {
                Files.writeString(directory.resolve("docs/notes/note-" + i + ".txt"), "这是背景材料 " + i + "，最终金额计算以 rules.txt 为准。\n"
                        + "历史示例：购买流程应考虑输入校验、精度与边界；此材料不覆盖正式计价规则。\n".repeat(35));
            }
        }
    }
    static String testSource(String assertions) {
        return "package dev.eval; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import java.math.*; import java.util.*; "
                + "class FixTargetTest { @Test void contract() { " + assertions + " } }\n";
    }
}
