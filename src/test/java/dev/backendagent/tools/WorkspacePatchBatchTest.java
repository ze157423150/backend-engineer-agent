package dev.backendagent.tools;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class WorkspacePatchBatchTest {
 @TempDir Path root;
 private Workspace workspace() throws Exception {return new Workspace(root,root.resolve("agent-local.properties"));}
 @Test void disjointEditsUseOriginalOffsetsAndOneOriginalBaseline()throws Exception{
  Files.writeString(root.resolve("Main.java"),"first\r\nsecond\r\nlast\r\n");var w=workspace();var hash=w.fileFingerprint("Main.java").getSha256();
  var result=w.applyPatchBatch("Main.java",hash,List.of(new PatchEdit("a","first","much longer"),new PatchEdit("b","second\r\n",""),new PatchEdit("c","last","end")));
  assertTrue(result.successful(),result.content());assertEquals("much longer\r\nend\r\n",Files.readString(root.resolve("Main.java")));assertEquals(1,w.originalContents().size());
 }
 @Test void overlapRejectsWholeFileAndIdentifiesBothEdits()throws Exception{
  Files.writeString(root.resolve("Main.java"),"abcdef");var w=workspace();
  var result=w.applyPatchBatch("Main.java",w.fileFingerprint("Main.java").getSha256(),List.of(new PatchEdit("callA","bcd","x"),new PatchEdit("callB","de","y")));
  assertFalse(result.successful());assertTrue(result.content().contains("PATCH_OVERLAP"));assertTrue(result.content().contains("callA"));assertTrue(result.content().contains("callB"));assertTrue(result.content().contains("全部未写入"));assertEquals("abcdef",Files.readString(root.resolve("Main.java")));assertTrue(w.originalContents().isEmpty());
 }
 @Test void externalChangeCannotBeAuthorizedByStillMatchingOldText()throws Exception{
  Files.writeString(root.resolve("Main.java"),"first\nlast\n");var w=workspace();var hash=w.fileFingerprint("Main.java").getSha256();Files.writeString(root.resolve("Main.java"),"external\nfirst\nlast\n");
  assertFalse(w.applyPatchBatch("Main.java",hash,List.of(new PatchEdit("a","first","new"))).successful());assertTrue(Files.readString(root.resolve("Main.java")).startsWith("external"));
 }
 @Test void dependencyAmbiguityAndDuplicateIdsAreRejectedWithoutPartialWrites()throws Exception{
  Files.writeString(root.resolve("Main.java"),"first second second");var w=workspace();var hash=w.fileFingerprint("Main.java").getSha256();
  for(var edits:List.of(List.of(new PatchEdit("a","first","new"),new PatchEdit("b","new","later")),List.of(new PatchEdit("a","first","new"),new PatchEdit("b","second","later")),List.of(new PatchEdit("a","first","new"),new PatchEdit("a"," second second","")))){
   assertFalse(w.applyPatchBatch("Main.java",hash,edits).successful());assertEquals("first second second",Files.readString(root.resolve("Main.java")));
  }
 }
 @Test void adjacentRangesAreNotOverlaps()throws Exception{
  Files.writeString(root.resolve("Main.java"),"abcd");var w=workspace();assertTrue(w.applyPatchBatch("Main.java",w.fileFingerprint("Main.java").getSha256(),List.of(new PatchEdit("a","ab","x"),new PatchEdit("b","cd","y"))).successful());assertEquals("xy",Files.readString(root.resolve("Main.java")));
 }
}
