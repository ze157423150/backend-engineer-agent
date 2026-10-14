package dev.backendagent.runtime;

import dev.backendagent.history.ArchivedBody;
import dev.backendagent.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NoProgressDetectorTest {
    @Test void changedPatchTextAndFreshReadsDoNotEraseSameFileFailures() {
        var detector=new NoProgressDetector();
        for(int i=1;i<=4;i++) {
            var failed=new ToolExchange(new ToolCall("p"+i,"apply_patch",Map.of("path","./Main.java","old_text","old"+i,"new_text","new"+i)),new ToolResult(false,"no match"));
            var report=detector.observeBatch(List.of(failed));
            assertEquals(i,report.getPatchFailures().get("Main.java"));
            assertEquals(i>=3?2:0,report.getWarningLevel());
            assertEquals(i==4,report.shouldStop());
            detector.observeBatch(List.of(read("fresh"+i,"new range"+i)));
        }
        detector.observeBatch(List.of(new ToolExchange(new ToolCall("good","apply_patch",Map.of("path","Main.java")),new ToolResult(true,"applied"))));
        assertTrue(detector.report().getPatchFailures().isEmpty());
        assertFalse(detector.report().shouldStop());
    }
    private ToolExchange read(String id, String text) {
        return new ToolExchange(new ToolCall(id, "read_file", Map.of("path", "Main.java")), new ToolResult(true, text));
    }

    @Test void ignoresCallIdsAndParameterOrderButRecognizesNewRangesAndContent() {
        var detector = new NoProgressDetector();
        detector.observeBatch(List.of(read("first", "old")));
        assertEquals(1, detector.observeBatch(List.of(read("new-id", "old"))).getConsecutiveBatches());
        assertEquals(0, detector.observeBatch(List.of(read("changed", "new"))).getConsecutiveBatches());
        var range = new ToolExchange(new ToolCall("range", "read_file",
                Map.of("path", "./Main.java", "start_line", "2")), new ToolResult(true, "new"));
        assertEquals(0, detector.observeBatch(List.of(range)).getConsecutiveBatches());
        var arguments = new java.util.LinkedHashMap<String, String>();
        arguments.put("start_line", "2"); arguments.put("path", "Main.java");
        assertEquals(1, detector.observeBatch(List.of(new ToolExchange(new ToolCall("reordered", "read_file", arguments),
                new ToolResult(true, "new")))).getConsecutiveBatches());
    }

    @Test void alternatingKnownToolsStillCountAsStagnationOncePerWholeBatch() {
        var detector = new NoProgressDetector();
        var list = new ToolExchange(new ToolCall("list", "list_files", Map.of()), new ToolResult(true, "Main.java"));
        var read = read("read", "source");
        detector.observeBatch(List.of(list, read));
        for (int i = 1; i <= 12; i++) {
            var report = detector.observeBatch(List.of(list, read, list));
            assertEquals(i, report.getConsecutiveBatches());
            assertEquals(i >= 8 ? 2 : i >= 4 ? 1 : 0, report.getWarningLevel());
            assertEquals(i == 12, report.shouldStop());
        }
    }

    @Test void successfulWriteResetsWindowAndMakesRereadAndRetestEligibleAgain() {
        var detector = new NoProgressDetector();
        var read = read("read", "source");
        var tests = new ToolExchange(new ToolCall("tests", "run_tests", Map.of()), new ToolResult(false, "assertion failed"));
        detector.observeBatch(List.of(read, tests));
        assertEquals(1, detector.observeBatch(List.of(read, tests)).getConsecutiveBatches());
        var patch = new ToolExchange(new ToolCall("patch", "apply_patch", Map.of()), new ToolResult(true, "applied"));
        assertEquals(0, detector.observeBatch(List.of(patch)).getConsecutiveBatches());
        assertEquals(1, detector.report().getWorkspaceRevision());
        assertEquals(0, detector.observeBatch(List.of(read, tests)).getConsecutiveBatches());
        assertEquals(1, detector.observeBatch(List.of(read, tests)).getConsecutiveBatches());
        assertEquals(0, detector.observeBatch(List.of(new ToolExchange(tests.call(), new ToolResult(true, "passed"))))
                .getConsecutiveBatches());
    }

    @Test void changedFullFileHashIsNewEvenIfVisibleRangeIsUnchanged() {
        var detector = new NoProgressDetector();
        var call = read("read", "unchanged range").call();
        detector.observeBatch(List.of(new ToolExchange(call, new ToolResult(true, "unchanged range",
                new FileFingerprint("Main.java", "a".repeat(64))))));
        assertEquals(0, detector.observeBatch(List.of(new ToolExchange(call, new ToolResult(true, "unchanged range",
                new FileFingerprint("Main.java", "b".repeat(64)))))).getConsecutiveBatches());
    }

    @Test void archivedBodyUsesOriginalHashInsteadOfExcerptOrCallId() {
        var detector = new NoProgressDetector();
        var original = read("old", "x".repeat(6000));
        detector.observeBatch(List.of(ArchivedBody.unload(1, original)));
        assertEquals(1, detector.observeBatch(List.of(read("new-id", original.result().content()))).getConsecutiveBatches());
    }

    @Test void novelInformationWithinBatchResetsButMemoryBookkeepingDoesNot() {
        var detector = new NoProgressDetector();
        detector.observeBatch(List.of(read("old", "old")));
        assertEquals(0, detector.observeBatch(List.of(read("repeat", "old"), read("new", "new"))).getConsecutiveBatches());
        var memory = new ToolExchange(new ToolCall("memory", "remember_fact", Map.of("statement", "new wording")),
                new ToolResult(true, "saved"));
        assertEquals(1, detector.observeBatch(List.of(memory)).getConsecutiveBatches());
    }

    @Test void repeatedFailureIsNotProgressButNewFailureCanProvideInformation() {
        var detector = new NoProgressDetector();
        var failed = new ToolExchange(new ToolCall("patch", "apply_patch", Map.of()), new ToolResult(false, "not found"));
        detector.observeBatch(List.of(failed));
        assertEquals(1, detector.observeBatch(List.of(failed)).getConsecutiveBatches());
        assertEquals(0, detector.observeBatch(List.of(new ToolExchange(failed.call(), new ToolResult(false, "ambiguous"))))
                .getConsecutiveBatches());
    }

    @Test void partialRecoveredBatchIsOnlyCountedAfterCompletion() {
        var session = new AgentSession("inspect");
        var first = new ToolExchange(read("first", "old").call(), new ToolResult(true, "old"), null, 1);
        session.remember(first);
        var partial = new ToolExchange(read("partial", "new").call(), new ToolResult(true, "new"), null, 2);
        session.remember(partial);
        session.setPendingToolBatch(new PendingToolBatch(2, null, List.of(partial.call(), read("last", "old").call()), 1, false));
        var detector = NoProgressDetector.start(session);
        session.remember(new ToolExchange(read("last", "old").call(), new ToolResult(true, "old"), null, 2));
        session.setPendingToolBatch(null);
        assertEquals(0, detector.observeCompletedHistory(session).getConsecutiveBatches());
        assertEquals(0, detector.observeCompletedHistory(session).getConsecutiveBatches());
    }
}
