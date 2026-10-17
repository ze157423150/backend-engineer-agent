package dev.backendagent.tools;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRawReadTest {
    @TempDir Path root;
    @Test void defaultNumberedMetadataRoundTripsWithoutReinterpretingSourceLinePrefixes() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));
        Files.writeString(root.resolve("note.txt"),"1: literal source text\r\nnext\r\n");
        var result=new ReadFileTool(w).execute(java.util.Map.of("path","note.txt","start_line","1","end_line","2"));
        assertEquals("file=note.txt, totalLines=2\n1: 1: literal source text\n2: next\n",result.content());
        assertEquals("numbered",result.readMetadata().getFormat());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var restored=json.readValue(json.writeValueAsString(result),dev.backendagent.model.ToolResult.class);
        assertEquals(result,restored);
        assertEquals("numbered",restored.readMetadata().repeated(true).getFormat());
        assertEquals("numbered",restored.readMetadata().excerpt().getFormat());
        var legacy=json.readValue("{\"totalLines\":2,\"startLine\":1,\"endLine\":2,\"reachedEndOfFile\":true,\"bodyComplete\":true,\"previouslyRead\":false}",dev.backendagent.model.FileReadMetadata.class);
        assertNull(legacy.getFormat());
        assertTrue(legacy.usesRawText("raw"));
        assertFalse(restored.readMetadata().usesRawText("raw"));
        assertFalse(json.readTree(json.writeValueAsString(legacy)).has("format"));
    }
    @Test void rawPreservesIndentationCrLfTabsAndFinalNewlineForExactPatch() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));
        String original="class X {\r\n\t void m() {\r\n  int n = 1;\r\n }\r\n}";
        Files.writeString(root.resolve("X.java"),original);
        var raw=w.readFile("X.java","2","4","raw");
        assertTrue(raw.successful());
        assertEquals("\t void m() {\r\n  int n = 1;\r\n }\r\n",raw.content());
        assertEquals(5,raw.readMetadata().getTotalLines());
        assertEquals(2,raw.readMetadata().getStartLine());
        assertEquals(4,raw.readMetadata().getEndLine());
        assertFalse(raw.readMetadata().isReachedEndOfFile());
        assertEquals(w.readFile("X.java","1","5").fileFingerprint(),raw.fileFingerprint());
        assertTrue(w.applyPatch("X.java",raw.content(),raw.content().replace("n = 1","n = 2")).successful());
        assertEquals(original.replace("n = 1","n = 2"),Files.readString(root.resolve("X.java")));
        assertEquals("}",w.readFile("X.java","5","5","raw").content());
        assertTrue(w.readFile("X.java","5","5","raw").readMetadata().isReachedEndOfFile());
    }
    @Test void rawNeverInjectsTruncationMarkersOrNumberedHeaders() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));
        Files.writeString(root.resolve("X.java"),"x".repeat(24001));
        assertFalse(w.readFile("X.java","1","1","raw").successful());
        assertFalse(w.readFile("X.java","2","2","raw").successful());
        assertFalse(w.readFile("X.java","1","1","other").successful());
        assertTrue(w.readFile("X.java","1","1","numbered").successful());
    }
    @Test void indentationFailureSuggestsReadButNeverChangesFile() throws Exception {
        var w=new Workspace(root,root.resolve("agent-local.properties"));
        Files.writeString(root.resolve("X.java"),"  int n = 1;\n");
        var result=w.applyPatch("X.java","   int n = 1;\n","replaced");
        assertFalse(result.successful()); assertTrue(result.content().contains("疑似缩进"));
        assertTrue(result.content().contains("read_file"));
        assertEquals("  int n = 1;\n",Files.readString(root.resolve("X.java")));
    }
}
