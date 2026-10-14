package dev.backendagent.model;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.history.ArchivedBody;
import dev.backendagent.tools.*;
import dev.backendagent.runtime.NoProgressDetector;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ReadMetadataTest {
    @TempDir Path root;
    @Test void rangeUnionDetectsOverlapButNewBytesResetReadKnowledge(){
        var coverage=new ReadCoverage();var file=new FileFingerprint("X.java","a".repeat(64));
        assertTrue(coverage.observe(file,meta(1,10)));
        assertTrue(coverage.observe(file,meta(11,20)));
        assertFalse(coverage.observe(file,meta(5,15)));
        assertTrue(coverage.observe(file,meta(18,25)));
        assertTrue(coverage.observe(new FileFingerprint("X.java","b".repeat(64)),meta(5,15)));
    }
    @Test void repeatHintsRetainSingleCopyOfSourceAndMetadataRoundTrips() throws Exception {
        Files.writeString(root.resolve("X.java"),"a\nb\nc\n");var tool=new ReadFileTool(new Workspace(root,root.resolve("agent-local.properties")));
        tool.execute(Map.of("path","X.java","start_line","1","end_line","3","format","raw"));
        var repeat=tool.execute(Map.of("path","X.java","start_line","2","end_line","3","format","raw"));
        assertTrue(repeat.readMetadata().isPreviouslyRead());assertEquals("b\nc\n",repeat.content());
        var json=new ObjectMapper();assertEquals(repeat,json.readValue(json.writeValueAsString(repeat),ToolResult.class));
        Files.writeString(root.resolve("X.java"),"changed\nb\nc\n");
        assertFalse(tool.execute(Map.of("path","X.java","start_line","2","end_line","3","format","raw")).readMetadata().isPreviouslyRead());
    }
    @Test void changingCoveredRangesDoesNotCountAsProgress(){
        var detector=new NoProgressDetector();var file=new FileFingerprint("X.java","a".repeat(64));
        detector.observeBatch(List.of(exchange(file,meta(1,20))));
        assertEquals(1,detector.observeBatch(List.of(exchange(file,meta(2,8)))).getConsecutiveBatches());
        assertEquals(2,detector.observeBatch(List.of(exchange(file,meta(5,10)))).getConsecutiveBatches());
        assertEquals(0,detector.observeBatch(List.of(exchange(file,meta(18,25)))).getConsecutiveBatches());
    }
    @Test void archivedBodyNeverClaimsCopyableSourceIsComplete(){
        var original=exchange(new FileFingerprint("X.java","a".repeat(64)),meta(1,20));
        var saved=ArchivedBody.unload(1,original);
        assertFalse(saved.result().readMetadata().isBodyComplete());
        assertEquals(20,saved.result().readMetadata().getEndLine());
        saved.archivedBody().verify(1,original,saved);
    }
    private FileReadMetadata meta(int start,int end){return new FileReadMetadata(100,start,end,false,true,false);}
    private ToolExchange exchange(FileFingerprint f,FileReadMetadata m){return new ToolExchange(new ToolCall("r","read_file",Map.of("path","X.java","start_line",""+m.getStartLine(),"end_line",""+m.getEndLine())),new ToolResult(true,"x".repeat(2200),f,null,m));}
}
