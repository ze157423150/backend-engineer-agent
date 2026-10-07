package dev.backendagent.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.backendagent.runtime.ObservationEvidence.Validity.*;

class ObservationEvidenceTest {
    @TempDir Path root;
    private Workspace workspace() throws Exception { return new Workspace(root, root.resolve("agent-local.properties")); }
    private ToolCall read(String id, String path) {
        return new ToolCall(id, "read_file", Map.of("path", path, "start_line", "1", "end_line", "1"));
    }
    private void rememberRead(AgentSession session, Workspace workspace, String id, String path) {
        session.remember(new ToolExchange(read(id, path), workspace.readFile(path, "1", "1"), null, 1));
    }

    @Test
    void hashesOriginalFullBytesIncludingUnreturnedLinesAndLineEndings() throws Exception {
        byte[] bytes = "第一行😀\r\nsecond\r\nthird\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("Main.java"), bytes);
        var workspace = workspace();
        var result = workspace.readFile("./Main.java", "1", "1");
        assertTrue(result.successful());
        assertTrue(result.content().contains("1: 第一行😀"));
        assertFalse(result.content().contains("second"));
        var expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, result.fileFingerprint().getSha256());
        assertEquals("Main.java", result.fileFingerprint().getPath());
        assertEquals(result.fileFingerprint(), workspace.fileFingerprint("Main.java"));
        Files.writeString(root.resolve("Main.java"), "第一行😀\r\nchanged\r\nthird\n");
        assertNotEquals(result.fileFingerprint(), workspace.fileFingerprint("Main.java"));
    }

    @Test
    void bindsCallAndRevisionAndChecksCurrentHashWithoutTreatingUnrelatedWritesAsStale() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}\n");
        var workspace = workspace();
        var session = new AgentSession("inspect");
        rememberRead(session, workspace, "read", "./Main.java");
        var evidence = session.history().getFirst().evidence();
        assertEquals("read", evidence.getSourceCallId());
        assertEquals(0, evidence.getWorkspaceRevision());
        assertEquals(CURRENT, session.assessFileEvidence("read", workspace));
        session.remember(new ToolExchange(new ToolCall("create", "create_file", Map.of("path", "Other.java")),
                workspace.createFile("Other.java", "class Other {}"), null, 2));
        assertEquals(1, session.workspaceState().getRevision());
        assertEquals(CURRENT, session.assessFileEvidence("read", workspace));
        Files.writeString(root.resolve("Main.java"), "class Main { int n; }\n");
        assertEquals(STALE, session.assessFileEvidence("read", workspace));
        var historical = new ReadObservationTool(session, workspace).execute(Map.of("call_id", "read", "start_line", "1", "end_line", "1"));
        assertTrue(historical.content().contains("fileEvidenceValidity=STALE"));
        assertTrue(historical.content().contains("stale=true"));
        assertFalse(session.hasCurrentRead("Main.java", workspace));
    }

    @Test
    void failedLegacyAndUnavailableObservationsAreUnknownAndDoNotAuthorizePatch() throws Exception {
        var workspace = workspace();
        var session = new AgentSession("inspect");
        session.remember(new ToolExchange(read("legacy", "Main.java"), new ToolResult(true, "class Main {}")));
        session.remember(new ToolExchange(read("failed", "Missing.java"), workspace.readFile("Missing.java", "1", "1")));
        assertEquals(UNKNOWN, session.assessFileEvidence("legacy", workspace));
        assertEquals(UNKNOWN, session.assessFileEvidence("failed", workspace));
        assertEquals(UNKNOWN, session.assessFileEvidence("missing-id", workspace));
        assertFalse(session.hasCurrentRead("Main.java"));
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        rememberRead(session, workspace, "read", "Main.java");
        Files.delete(root.resolve("Main.java"));
        assertEquals(UNKNOWN, session.assessFileEvidence("read", workspace));
        assertFalse(session.hasCurrentRead("Main.java", workspace));
    }

    @Test
    void knownWriteInvalidationSurvivesRevertAndFreshReadRestoresEvidence() throws Exception {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        var workspace = workspace();
        var session = new AgentSession("modify");
        rememberRead(session, workspace, "read", "Main.java");
        session.invalidateFileEvidence("Main.java");
        // Even if bytes become equal after a revert, the existing fresh-read policy remains conservative.
        assertEquals(STALE, session.assessFileEvidence("read", workspace));
        rememberRead(session, workspace, "fresh", "Main.java");
        assertEquals(CURRENT, session.assessFileEvidence("fresh", workspace));
        assertTrue(session.hasCurrentRead("Main.java", workspace));
    }

    @Test
    void excerptsPreserveEvidenceAndInvalidFingerprintOrBindingIsRejected() throws Exception {
        Files.writeString(root.resolve("Main.java"), "x".repeat(10000));
        var workspace = workspace();
        var session = new AgentSession("inspect");
        rememberRead(session, workspace, "read", "Main.java");
        var raw = session.history().getFirst();
        var excerpt = new ToolResultCompressor().compress(raw);
        assertTrue(excerpt.result().content().length() < raw.result().content().length());
        assertEquals(raw.evidence(), excerpt.evidence());
        assertEquals(raw.result().fileFingerprint(), excerpt.result().fileFingerprint());
        assertThrows(IllegalArgumentException.class, () -> new FileFingerprint("../Main.java", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new FileFingerprint("Main.java", "bad"));
        assertThrows(IllegalArgumentException.class, () -> new ToolExchange(raw.call(), raw.result(), null, 1,
                new ObservationEvidence(ObservationEvidence.Type.FILE_CONTENT, "forged-id", raw.result().fileFingerprint(), 0)));
    }

    @Test
    void fingerprintOperationCannotReadSecretsSymlinksOrOversizedFiles() throws Exception {
        Files.writeString(root.resolve("agent-local.properties"), "dummy-secret");
        Files.createSymbolicLink(root.resolve("Alias.java"), root.resolve("agent-local.properties"));
        Files.writeString(root.resolve("Large.java"), "x".repeat(256 * 1024 + 1));
        var workspace = workspace();
        for (String path : List.of("agent-local.properties", "Alias.java", "Large.java", "../outside.java")) {
            assertNull(workspace.readFile(path, "1", "1").fileFingerprint());
            assertThrows(java.io.IOException.class, () -> workspace.fileFingerprint(path), path);
        }
    }
}
