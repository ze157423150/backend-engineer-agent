package dev.backendagent.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.backendagent.runtime.*;
import dev.backendagent.model.*;
import dev.backendagent.tools.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionDeletionTest {
    @TempDir Path root;
    Path data() { return root.resolve("sessions"); }
    private UUID fixture(boolean applied) throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("Main.java"), "old");
        try (var store = new FileSessionStore(data())) {
            var session = new AgentSession("repair Main.java", store); store.create(session);
            var dir = store.sessionDirectory(session.id());
            var copyRoot = Files.createDirectory(dir.resolve("workspace"));
            Files.writeString(copyRoot.resolve("Main.java"), "old");
            Files.writeString(dir.resolve("source-workspace.txt"), source.toRealPath().toString());
            var copy = new Workspace(copyRoot,copyRoot.resolve("agent-local.properties"));
            assertTrue(copy.applyPatch("Main.java","old","new").successful());
            store.bindWorkspace(copy);
            new AgentRuntime(r->ModelResponse.finish("done"), List.of(),10).run(session);
            store.saveSnapshot(session);
            if (applied) new WorkspaceSynchronizer(new Workspace(source,source.resolve("agent-local.properties")),copy,dir).apply();
            return session.id();
        }
    }
    @Test void previewsPendingAndDeletesOnlyChosenSession() throws Exception {
        UUID id=fixture(false);
        UUID other=UUID.randomUUID();Files.createDirectories(data().resolve(other.toString()));
        Files.writeString(data().resolve(other.toString()).resolve("keep.txt"),"other");
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) {
            assertTrue(deletion.preview().contains("repair Main.java"));
            assertTrue(deletion.preview().contains("未写回改动：1 个文件：Main.java"));
            assertTrue(deletion.delete().contains("已删除会话"));
        }
        assertFalse(Files.exists(data().resolve(id.toString())));
        assertEquals("other",Files.readString(data().resolve(other.toString()).resolve("keep.txt")));
        assertEquals("old",Files.readString(root.resolve("source/Main.java")));
    }
    @Test void alreadyAppliedCodeSurvivesDeletion() throws Exception {
        UUID id=fixture(true);
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) {
            assertTrue(deletion.preview().contains("未写回改动：无"));deletion.delete();
        }
        assertEquals("new",Files.readString(root.resolve("source/Main.java")));
    }
    @Test void cancellationReleasesLockAndKeepsAllFiles() throws Exception {
        UUID id=fixture(false);Path checkpoint=data().resolve(id.toString()).resolve("checkpoint.json");
        byte[] before=Files.readAllBytes(checkpoint);
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) {
            try(var contender=new FileSessionStore(data())) { assertThrows(IOException.class,()->contender.prepareDeletion(id)); }
        }
        assertArrayEquals(before,Files.readAllBytes(checkpoint));
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) {
            assertTrue(deletion.preview().contains(id.toString()));
        }
    }
    @Test void occupiedByExistingWriterCannotBeDeleted() throws Exception {
        try(var writer=new FileSessionStore(data())) {
            var session=new AgentSession("active",writer);writer.create(session);
            try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.prepareDeletion(session.id())); }
            assertTrue(Files.exists(writer.sessionDirectory(session.id())));
        }
    }
    @Test void deletionDoesNotFollowNestedSymlinks() throws Exception {
        UUID id=fixture(false);Path outside=Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"),"outside");
        Files.createSymbolicLink(data().resolve(id.toString()).resolve("outside-link"),outside);
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) { deletion.delete(); }
        assertEquals("outside",Files.readString(outside.resolve("keep.txt")));
    }
    @Test void sessionDirectorySymlinkCannotBeDeleted() throws Exception {
        Files.createDirectories(data());UUID id=UUID.randomUUID();Path outside=Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(data().resolve(id.toString()),outside);
        try(var store=new FileSessionStore(data())) { assertThrows(IOException.class,()->store.prepareDeletion(id)); }
        assertTrue(Files.exists(outside));assertTrue(Files.isSymbolicLink(data().resolve(id.toString())));
    }
    @Test void missingSessionAndClosedTicketCannotDelete() throws Exception {
        UUID id=fixture(false);
        try(var store=new FileSessionStore(data())) {
            assertThrows(IOException.class,()->store.prepareDeletion(UUID.randomUUID()));
            var deletion=store.prepareDeletion(id);deletion.close();
            assertThrows(IOException.class,deletion::delete);
            assertTrue(Files.exists(store.sessionDirectory(id)));
        }
    }
    @Test void damagedMetadataWarnsAboutUnknownPendingChanges() throws Exception {
        UUID id=fixture(false);Files.writeString(data().resolve(id.toString()).resolve("checkpoint.json"),"broken");
        try(var store=new FileSessionStore(data());var deletion=store.prepareDeletion(id)) {
            assertTrue(deletion.preview().contains("未写回改动：无法核验"));deletion.delete();
        }
        assertEquals("old",Files.readString(root.resolve("source/Main.java")));
    }
}
