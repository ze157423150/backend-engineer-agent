package dev.backendagent.model;

import dev.backendagent.runtime.SummaryNote;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SummaryQualityEvaluationTest {
    @Test
    void flagsExtraPlanningEvenWhenQuoteExists() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().getFirst();
        var note = new SummaryNote(SummaryNote.Kind.NEXT_STEP, "建议读取其他版本，当前 RATE=225", "read-rate-0", "RATE=225");
        var result = SummaryQualityEvaluation.assess(f, List.of(note));
        assertFalse(result.path("fixtureChecksPassed").asBoolean());
        assertEquals(1, result.path("unsupportedNextStepSources").size());
        assertTrue(result.path("invalidQuoteSources").isEmpty());
    }
    @Test
    void reportsSuspectedParaphrasesWithoutFailingQuality() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().getFirst();
        var a = new SummaryNote(SummaryNote.Kind.PROGRESS, "费率为225", "read-rate-0", "RATE=225");
        var b = new SummaryNote(SummaryNote.Kind.PROGRESS, "再次读取，费率仍为225", "read-rate-1", "RATE=225");
        var result = SummaryQualityEvaluation.assess(f, List.of(a, b));
        assertEquals(0, result.path("duplicateReferenceCount").asInt());
        assertEquals(1, result.path("suspectedDuplicatePairs").size());
        assertTrue(result.path("excessiveNotes").asBoolean());
        assertTrue(result.path("fixtureChecksPassed").asBoolean());
    }
    @Test
    void flagsLostFactsAndUnsupportedGlobalConclusions() throws Exception {
        var fixtures = SummaryQualityEvaluation.fixtures();
        var failed = new SummaryNote(SummaryNote.Kind.PROGRESS, "已修改为225", "patch-price", "从958改为225");
        assertTrue(SummaryQualityEvaluation.assess(fixtures.get(1), List.of(failed))
                .path("missingRequiredMarkers").toString().contains("FAILED"));
        var global = new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "尚未获得业务规则，需要继续处理", "padding-0", "不含业务规则");
        assertFalse(SummaryQualityEvaluation.assess(fixtures.get(3), List.of(global)).path("unsupportedMarkerHits").isEmpty());
    }
    @Test
    void keepsAnExistingPendingItemWithoutCreatingNextStep() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().get(2);
        var notes = List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "金额不得为负数", "read-contract", "金额不得为负数"),
                new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "记录中的零金额用例尚未执行", "explicit-todo", "补充零金额用例，尚未执行"),
                new SummaryNote(SummaryNote.Kind.PROGRESS, "金额上限1000", "read-limit", "输入金额上限为1000"));
        assertTrue(SummaryQualityEvaluation.assess(f, notes).path("fixtureChecksPassed").asBoolean());
    }
    @Test
    void acceptsEquivalentChineseTestStatusWithoutRequiringEnglishLiteral() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().get(1);
        var note = new SummaryNote(SummaryNote.Kind.OPEN_ISSUE, "测试失败，期望225但实际958", "test-price", "expected: 225 but was: 958");
        assertTrue(SummaryQualityEvaluation.assess(f, List.of(note)).path("missingRequiredMarkers").isEmpty());
    }

    @Test
    void oldPlanningNotesRemainReadableAsHistoricalData() throws Exception {
        var note = new SummaryNote(SummaryNote.Kind.NEXT_STEP, "Old planned action", "old", "old evidence");
        var summary = new dev.backendagent.runtime.ContextSummary(1, 1, List.of(note));
        var saved = SummaryQualityEvaluation.JSON.writeValueAsString(summary);
        var restored = SummaryQualityEvaluation.JSON.readValue(saved, dev.backendagent.runtime.ContextSummary.class);
        assertEquals(SummaryNote.Kind.NEXT_STEP, restored.getNotes().getFirst().getKind());
    }

    @Test
    void acceptsExplicitToolFailureStatusWithoutEnglishFailedLiteral() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().get(1);
        var note = new SummaryNote(SummaryNote.Kind.OPEN_ISSUE,
                "run_tests 失败：期望225但实际958", "test-price", "expected: 225 but was: 958");
        assertTrue(SummaryQualityEvaluation.assess(f, List.of(note)).path("fixtureChecksPassed").asBoolean());
    }

    @Test
    void backgroundNoteCountIsDiagnosticRatherThanFailure() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().get(3);
        var notes = List.of(
                new SummaryNote(SummaryNote.Kind.PROGRESS, "读取0号文件，不含业务规则", "padding-0", "不含业务规则"),
                new SummaryNote(SummaryNote.Kind.PROGRESS, "读取1号文件，不含业务规则", "padding-1", "不含业务规则"));
        var result = SummaryQualityEvaluation.assess(f, notes);
        assertTrue(result.path("excessiveNotes").asBoolean());
        assertTrue(result.path("noteCountIsDiagnosticOnly").asBoolean());
        assertTrue(result.path("fixtureChecksPassed").asBoolean());
    }
    @Test
    void stillRejectsProvenDuplicateNotes() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().getFirst();
        var note = new SummaryNote(SummaryNote.Kind.PROGRESS, "RATE=225", "read-rate-0", "RATE=225");
        var result = SummaryQualityEvaluation.assess(f, List.of(note, note));
        assertEquals(1, result.path("duplicateReferenceCount").asInt());
        assertFalse(result.path("fixtureChecksPassed").asBoolean());
    }
    @Test
    void stillRejectsAnInvalidSourceEvenWithoutNoteCountLimit() throws Exception {
        var f = SummaryQualityEvaluation.fixtures().getFirst();
        var note = new SummaryNote(SummaryNote.Kind.PROGRESS, "RATE=225", "missing", "RATE=225");
        var result = SummaryQualityEvaluation.assess(f, List.of(note));
        assertFalse(result.path("fixtureChecksPassed").asBoolean());
        assertFalse(result.path("invalidQuoteSources").isEmpty());
    }

}
