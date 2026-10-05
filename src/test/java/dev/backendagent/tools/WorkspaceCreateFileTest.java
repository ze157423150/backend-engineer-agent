package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCreateFileTest {
    @TempDir Path root;

    @Test
    void createsFileThatCanBeReadSearchedAndShownAsAddition() throws IOException {
        var workspace = workspace();
        Files.createDirectory(root.resolve("src"));
        assertTrue(workspace.createFile("src/Added.java", "class Added {}\n").successful());
        assertEquals("class Added {}\n", Files.readString(root.resolve("src/Added.java")));
        assertTrue(workspace.readFile("src/Added.java", "1", "10").content().contains("1: class Added {}"));
        assertTrue(workspace.searchCode(".", "Added").content().contains("src/Added.java:1:"));
        String diff = workspace.diff("src/Added.java").content();
        assertTrue(diff.contains("--- /dev/null"));
        assertTrue(diff.contains("@@ -0,0 +1,1 @@"));
        assertTrue(diff.contains("+class Added {}"));
        assertTrue(workspace.applyPatch("src/Added.java", "Added", "Renamed").successful());
        assertTrue(workspace.diff("src/Added.java").content().contains("+class Renamed {}"));
        assertFalse(workspace.diff("src/Added.java").content().contains("-class Added"));
    }

    @Test
    void neverOverwritesExistingFilesIncludingEmptyFiles() throws IOException {
        var workspace = workspace();
        for (String content : List.of("", "original")) {
            Files.writeString(root.resolve("Existing.java"), content);
            assertFalse(workspace.createFile("Existing.java", "replacement").successful());
            assertEquals(content, Files.readString(root.resolve("Existing.java")));
        }
    }

    @Test
    void rejectsOutsideHiddenSecretSymlinkAndMissingParentPaths() throws IOException {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Path config = repo.resolve("custom.properties");
        var workspace = new Workspace(repo, config);
        Files.createSymbolicLink(repo.resolve("linked"), root);
        Files.createSymbolicLink(repo.resolve("Alias.java"), root.resolve("Missing.java"));
        for (String path : List.of("../Escape.java", root.resolve("Absolute.java").toString(),
                ".hidden/New.java", "agent-local.properties", "custom.properties", "application-local.yml",
                "linked/Escape.java", "Alias.java", "missing/New.java", "target/New.java", "New.png")) {
            assertFalse(workspace.createFile(path, "content").successful(), path);
        }
        assertFalse(Files.exists(root.resolve("Escape.java")));
        assertFalse(Files.exists(repo.resolve("missing")));
        assertFalse(Files.exists(config));
    }

    @Test
    void allowsEmptyFileAndRejectsInvalidContent() throws IOException {
        var workspace = workspace();
        assertFalse(workspace.createFile("Bad.java", "\0").successful());
        assertFalse(workspace.createFile("Bad.java", "x".repeat(24001)).successful());
        assertFalse(workspace.createFile("Bad.java", null).successful());
        assertFalse(Files.exists(root.resolve("Bad.java")));
        assertTrue(workspace.createFile("Empty.java", "").successful());
        assertEquals(0, Files.size(root.resolve("Empty.java")));
        assertTrue(workspace.diff("Empty.java").content().contains("新增空文件"));
    }

    @Test
    void creationAndModificationShareFileCountBudget() throws IOException {
        var workspace = workspace();
        for (int i = 0; i < 32; i++) {
            assertTrue(workspace.createFile("File" + i + ".java", "old").successful());
        }
        assertFalse(workspace.createFile("Extra.java", "new").successful());
        assertFalse(Files.exists(root.resolve("Extra.java")));
        assertTrue(workspace.applyPatch("File0.java", "old", "new").successful());
    }

    private Workspace workspace() throws IOException {
        return new Workspace(root, root.resolve("agent-local.properties"));
    }
}
