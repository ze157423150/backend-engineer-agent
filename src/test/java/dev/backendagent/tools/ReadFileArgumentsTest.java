package dev.backendagent.tools;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReadFileArgumentsTest {
    @TempDir Path root;
    private ReadFileTool tool() throws Exception {
        Files.writeString(root.resolve("A.java"), "// source line\n".repeat(100));
        return new ReadFileTool(new Workspace(root, root.resolve("private.properties")));
    }
    @Test void bothReadCoordinatesAreRequiredAndNeverDefaulted() throws Exception {
        var tool = tool();
        assertEquals(Set.of("path", "start_line", "end_line"), tool.definition().getRequiredParameters());
        for (var args : List.of(Map.of("path", "A.java"), Map.of("path", "A.java", "start_line", "5"),
                Map.of("path", "A.java", "end_line", "5"))) {
            var result = tool.execute(args); assertFalse(result.successful());
            assertNull(result.readMetadata()); assertNull(result.fileFingerprint());
        }
    }
    @Test void explicitCoordinatesDetermineTheOnlyReturnedSourceRange() throws Exception {
        var result = tool().execute(Map.of("path", "A.java", "start_line", "81", "end_line", "100"));
        assertTrue(result.successful()); assertEquals(81, result.readMetadata().getStartLine());
        assertEquals(100, result.readMetadata().getEndLine()); assertTrue(result.readMetadata().isReachedEndOfFile());
        assertFalse(result.content().contains("80: "));
    }
    @Test void invalidRangesAndWorkspaceEscapeRemainRejected() throws Exception {
        var tool = tool();
        for (var range : List.of(List.of("0", "2"), List.of("2", "1"), List.of("1", "201"), List.of("x", "20")))
            assertFalse(tool.execute(Map.of("path", "A.java", "start_line", range.get(0), "end_line", range.get(1))).successful());
        assertFalse(tool.execute(Map.of("path", "../outside.java", "start_line", "1", "end_line", "20")).successful());
    }
}
