package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePatchTest {
    @TempDir Path root;

    @Test
    void appliesUniqueReplacementAndReportsCumulativeChanges() throws IOException {
        Files.writeString(root.resolve("Main.java"), "class Main { int n = 1; }\n");
        var workspace = workspace();
        assertTrue(workspace.applyPatch("Main.java", "n = 1", "n = 2").successful());
        assertTrue(workspace.applyPatch("Main.java", "n = 2", "n = 3").successful());
        var diff = workspace.diff("Main.java");
        assertTrue(diff.successful());
        assertTrue(diff.content().contains("-class Main { int n = 1; }"));
        assertTrue(diff.content().contains("+class Main { int n = 3; }"));
        assertFalse(diff.content().contains("n = 2"));
        assertEquals("class Main { int n = 3; }\n", Files.readString(root.resolve("Main.java")));
    }

    @Test
    void ambiguousOverlappingAndMissingMatchesLeaveFileUntouched() throws IOException {
        Files.writeString(root.resolve("Main.java"), "aaaa");
        var workspace = workspace();
        assertFalse(workspace.applyPatch("Main.java", "aa", "b").successful());
        assertFalse(workspace.applyPatch("Main.java", "missing", "b").successful());
        assertFalse(workspace.applyPatch("Main.java", "aaaa", "aaaa").successful());
        assertEquals("aaaa", Files.readString(root.resolve("Main.java")));
        assertTrue(workspace.diff("Main.java").content().contains("未通过 apply_patch"));
    }

    @Test
    void supportsFragmentDeletionAndPreservesCrLfAndPermissions() throws IOException {
        Path file = Files.writeString(root.resolve("Main.java"), "first\r\nremove\r\nlast\r\n");
        var permissions = Files.getPosixFilePermissions(file);
        assertTrue(workspace().applyPatch("Main.java", "remove\r\n", "").successful());
        assertEquals("first\r\nlast\r\n", Files.readString(file));
        assertEquals(permissions, Files.getPosixFilePermissions(file));
        try (var paths = Files.list(root)) {
            assertEquals(List.of("Main.java"), paths.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void rejectsTraversalSecretsSymlinksAndHardLinks() throws IOException {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Path outside = Files.writeString(root.resolve("Outside.java"), "old");
        Path secret = Files.writeString(repo.resolve("agent-local.properties"), "old");
        Files.createSymbolicLink(repo.resolve("Link.java"), outside);
        Files.createLink(repo.resolve("Hard.java"), outside);
        Files.createDirectories(repo.resolve(".hidden"));
        Files.writeString(repo.resolve(".hidden/Main.java"), "old");
        var workspace = new Workspace(repo, secret);
        for (String path : List.of("../Outside.java", outside.toString(), "agent-local.properties", "Link.java",
                "Hard.java", ".hidden/Main.java", "New.java")) {
            assertFalse(workspace.applyPatch(path, "old", "new").successful(), path);
        }
        assertEquals("old", Files.readString(outside));
        assertEquals("old", Files.readString(secret));
        assertFalse(Files.exists(repo.resolve("New.java")));
    }

    @Test
    void rejectsOversizedBinaryAndInvalidTextWithoutMutation() throws IOException {
        Files.writeString(root.resolve("Main.java"), "old");
        Files.writeString(root.resolve("Large.java"), "x".repeat(256 * 1024 + 1));
        Files.write(root.resolve("Data.png"), new byte[] {1, 2});
        var workspace = workspace();
        assertFalse(workspace.applyPatch("Main.java", "", "new").successful());
        assertFalse(workspace.applyPatch("Main.java", "old", "x".repeat(24001)).successful());
        assertFalse(workspace.applyPatch("Main.java", "old", "\0").successful());
        assertFalse(workspace.applyPatch("Large.java", "x", "y").successful());
        assertFalse(workspace.applyPatch("Data.png", "old", "new").successful());
        assertEquals("old", Files.readString(root.resolve("Main.java")));
        Path nearLimit = Files.writeString(root.resolve("NearLimit.java"), "old" + "x".repeat(256 * 1024 - 3));
        assertFalse(workspace.applyPatch("NearLimit.java", "old", "new text").successful());
        assertEquals(256 * 1024, Files.size(nearLimit));
    }

    @Test
    void boundsDiffOutputAndShowsRestoredFileAsUnchanged() throws IOException {
        Files.writeString(root.resolve("Main.java"), "old\n");
        var workspace = workspace();
        assertTrue(workspace.applyPatch("Main.java", "old", "x".repeat(23999)).successful());
        assertTrue(workspace.diff("Main.java").content().contains("差异已截断"));
        assertTrue(workspace.applyPatch("Main.java", "x".repeat(23999), "old").successful());
        assertTrue(workspace.diff("Main.java").content().contains("没有差异"));
    }

    private Workspace workspace() throws IOException {
        return new Workspace(root, root.resolve("agent-local.properties"));
    }
}
