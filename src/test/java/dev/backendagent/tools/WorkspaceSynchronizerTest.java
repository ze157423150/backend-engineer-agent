package dev.backendagent.tools;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSynchronizerTest {
    @TempDir Path root;
    private Workspace source, copy;
    private Path original, isolated, session;
    private WorkspaceSynchronizer setup() throws Exception {
        original = Files.createDirectory(root.resolve("source"));
        session = Files.createDirectory(root.resolve("session"));
        isolated = Files.createDirectory(session.resolve("workspace"));
        Files.writeString(original.resolve("Main.java"), "class Main { int value = 1; }\n");
        Files.copy(original.resolve("Main.java"), isolated.resolve("Main.java"));
        source = new Workspace(original, original.resolve("agent-local.properties"));
        copy = new Workspace(isolated, isolated.resolve("agent-local.properties"));
        assertTrue(copy.applyPatch("Main.java", "value = 1", "value = 2").successful());
        return reopen();
    }
    private WorkspaceSynchronizer reopen() throws Exception { return new WorkspaceSynchronizer(source, copy, session); }
    @Test void previewDoesNotWriteAndApplyWritesOnlyChangedFile() throws Exception {
        var sync = setup();
        Files.writeString(original.resolve("Unrelated.java"), "keep me");
        assertTrue(sync.diff().contains("value = 2"));
        assertTrue(Files.readString(original.resolve("Main.java")).contains("value = 1"));
        assertTrue(sync.apply().contains("已写回原项目：Main.java"));
        assertEquals(Files.readString(isolated.resolve("Main.java")), Files.readString(original.resolve("Main.java")));
        assertEquals("keep me", Files.readString(original.resolve("Unrelated.java")));
        assertTrue(sync.diff().contains("没有待写回"));
        assertTrue(sync.apply().contains("没有待写回"));
    }
    @Test void oneConflictCancelsAllWritesIncludingNewFiles() throws Exception {
        var sync = setup();
        assertTrue(copy.createFile("Added.java", "class Added {}").successful());
        Files.writeString(original.resolve("Main.java"), "IDE changed this");
        assertTrue(sync.diff().contains("冲突"));
        assertTrue(sync.apply().contains("所有文件均未写入"));
        assertEquals("IDE changed this", Files.readString(original.resolve("Main.java")));
        assertFalse(Files.exists(original.resolve("Added.java")));
    }
    @Test void repeatedEditsAndReopenedSynchronizerUseLastAppliedHash() throws Exception {
        var sync = setup();
        sync.apply();
        assertTrue(copy.applyPatch("Main.java", "value = 2", "value = 3").successful());
        sync = reopen();
        assertTrue(sync.diff().contains("value = 2"));
        assertTrue(sync.diff().contains("value = 3"));
        assertTrue(sync.apply().contains("已写回"));
        assertTrue(Files.readString(original.resolve("Main.java")).contains("value = 3"));
        Files.writeString(original.resolve("Main.java"), "new IDE edit");
        assertTrue(copy.applyPatch("Main.java", "value = 3", "value = 4").successful());
        assertTrue(reopen().apply().contains("写回已取消"));
        assertEquals("new IDE edit", Files.readString(original.resolve("Main.java")));
    }
    @Test void newEmptyFileIsAnActualChangeAndCanBeEditedAfterWriteback() throws Exception {
        var sync = setup();
        assertTrue(copy.createFile("Empty.java", "").successful());
        assertTrue(sync.diff().contains("新增空文件"));
        sync.apply();
        assertTrue(Files.exists(original.resolve("Empty.java")));
        Files.writeString(isolated.resolve("Empty.java"), "class Empty {}");
        assertTrue(reopen().apply().contains("Empty.java"));
        assertEquals("class Empty {}", Files.readString(original.resolve("Empty.java")));
    }
    @Test void existingNewFileIsNotOverwritten() throws Exception {
        var sync = setup();
        assertTrue(copy.createFile("Added.java", "class Added {}").successful());
        Files.writeString(original.resolve("Added.java"), "user content");
        assertTrue(sync.apply().contains("写回已取消"));
        assertTrue(Files.readString(original.resolve("Main.java")).contains("value = 1"));
        assertEquals("user content", Files.readString(original.resolve("Added.java")));
    }
    @Test void sourceSymlinkIsRejectedBeforeAnyWrite() throws Exception {
        var sync = setup();
        assertTrue(copy.createFile("Added.java", "class Added {}").successful());
        Path outside = root.resolve("outside.java"); Files.writeString(outside, "outside");
        Files.delete(original.resolve("Main.java"));
        Files.createSymbolicLink(original.resolve("Main.java"), outside);
        assertThrows(Exception.class, sync::apply);
        assertEquals("outside", Files.readString(outside));
        assertFalse(Files.exists(original.resolve("Added.java")));
    }
    @Test void missingSourceFileAndMissingIsolatedFileDoNotCauseDeletionOrOverwrite() throws Exception {
        var sync = setup();
        Files.delete(original.resolve("Main.java"));
        assertTrue(sync.apply().contains("写回已取消"));
        assertFalse(Files.exists(original.resolve("Main.java")));
        Files.writeString(original.resolve("Main.java"), "original");
        Files.delete(isolated.resolve("Main.java"));
        assertThrows(Exception.class, sync::apply);
        assertEquals("original", Files.readString(original.resolve("Main.java")));
    }
    @Test void sourcePermissionsArePreservedAndWrongHashRejected() throws Exception {
        setup();
        var file = original.resolve("Main.java");
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(file, permissions);
        assertThrows(java.io.IOException.class, () -> source.synchronizationWrite("Main.java", "f".repeat(64), "new"));
        reopen().apply();
        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }
}
