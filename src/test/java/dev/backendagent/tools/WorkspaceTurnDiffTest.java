package dev.backendagent.tools;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTurnDiffTest {
    @TempDir Path root;
    private Workspace workspace() throws Exception {return new Workspace(root,root.resolve("agent-local.properties"));}

    @Test void previousTurnCreationIsAnExistingFileInTheNextTurnAndUndoHasNoNetDiff() throws Exception {
        var w=workspace();w.beginTurn(1);
        assertTrue(w.createFile("Main.java","class Main { int n=1; }\n").successful());
        assertTrue(w.diff("Main.java","turn").content().contains("/dev/null"));
        w.beginTurn(2);
        assertTrue(w.diff("Main.java","turn").content().contains("本轮未成功修改"));
        assertTrue(w.applyPatch("Main.java","n=1","n=2").successful());
        var tool=new WorkspaceDiffTool(w);
        var diff=tool.execute(Map.of("path","Main.java")).content();
        assertTrue(diff.contains("scope=turn, turn=2"));assertFalse(diff.contains("/dev/null"));
        assertTrue(diff.contains("-class Main { int n=1; }"));assertTrue(diff.contains("+class Main { int n=2; }"));
        assertTrue(tool.execute(Map.of("path","Main.java","scope","session")).content().contains("/dev/null"));
        assertTrue(w.applyPatch("Main.java","n=2","n=1").successful());
        assertTrue(w.diff("Main.java","turn").content().contains("没有净差异"));
        assertEquals("",w.originalContents().get("Main.java"));
    }

    @Test void failedWritesDoNotCaptureBaselineAndInvalidScopesAndPathsAreRejected() throws Exception {
        Files.writeString(root.resolve("Main.java"),"class Main {}\n");var w=workspace();w.beginTurn(1);
        assertFalse(w.applyPatch("Main.java","missing","new").successful());
        assertTrue(w.turnDiffState().getOriginals().isEmpty());
        assertFalse(w.diff("../private.java","turn").successful());
        assertFalse(w.diff("Main.java","unknown").successful());
        assertThrows(Exception.class,()->w.restoreTurnDiff(new TurnDiffState(1,true,Map.of("../private.java","x"),Set.of())));
        w.restoreTurnDiff(TurnDiffState.unavailable(1));w.beginTurn(1);
        assertFalse(w.diff("Main.java","turn").successful());
        assertTrue(w.applyPatch("Main.java","Main","Renamed").successful());
        assertFalse(w.turnDiffState().isAvailable());
        w.beginTurn(2);assertTrue(w.turnDiffState().isAvailable());
    }

    @Test void distantChangesHaveSeparateHunksInsteadOfCopyingUnchangedMiddle() {
        String before=java.util.stream.IntStream.range(0,100).mapToObj(i->"line"+i+"\n").collect(java.util.stream.Collectors.joining());
        String after=before.replace("line5\n","changed5\n").replace("line90\n","changed90\n");
        String diff=FileDiff.render("Main.java",before,after,24000,false);
        assertEquals(2,diff.lines().filter(l->l.startsWith("@@ ")).count());
        assertTrue(diff.contains("-line5\n+changed5"));assertTrue(diff.contains("-line90\n+changed90"));
        assertFalse(diff.contains("line50"));assertTrue(diff.length()<before.length());
    }

    @Test void insertionDeletionAndFinalNewlineAreRepresentedWithBoundedOutput() {
        assertTrue(FileDiff.render("a.txt","a\nb\n","a\nx\nb\n",1000,false).contains("+x"));
        assertTrue(FileDiff.render("a.txt","a\nx\nb\n","a\nb\n",1000,false).contains("-x"));
        assertTrue(FileDiff.render("a.txt","a","a\n",1000,false).contains("before=false, after=true"));
        assertTrue(FileDiff.render("a.txt","a\n","x".repeat(30000),1000,false).contains("差异已截断"));
    }
}
