package dev.backendagent.persistence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.tools.Workspace;
import dev.backendagent.tools.WorkspaceSynchronizer;

/** Locked user-confirmed removal. Never follows links or deletes the source repository. */
public final class SessionDeletion implements AutoCloseable {
    private final FileSessionStore store;
    private final UUID id;
    private final Path directory;
    private final String preview;
    private boolean closed;
    SessionDeletion(FileSessionStore store, UUID id) throws IOException {
        this.store = store; this.id = id; this.directory = store.sessionDirectory(id);
        preview = buildPreview();
    }
    public String preview() { return preview; }
    private String buildPreview() {
        var out = new StringBuilder("待删除会话：" + id + "\n目录：" + directory + "\n");
        try {
            var saved = store.read(id);
            var snapshot = saved.path("snapshot");
            String task = snapshot.path("objective").asText().replaceAll("[\\p{Cntrl}]", " ");
            if (task.length() > 160) task = task.substring(0,160) + "…";
            out.append("任务：").append(task).append("\n状态：").append(saved.path("lastRecordedStatus").asText()).append('\n');
        } catch (IOException invalid) { out.append("元数据不可读取；删除会永久清除此目录中的残留记录。\n"); }
        try {
            var json = new ObjectMapper();
            Path checkpoint = directory.resolve("checkpoint.json");
            Path marker = directory.resolve("source-workspace.txt");
            if (Files.isSymbolicLink(checkpoint) || Files.isSymbolicLink(marker)
                    || Files.size(checkpoint) > 16 * 1024 * 1024 || Files.size(marker) > 8192)
                throw new IOException("Invalid deletion preview metadata");
            var cp = json.readValue(checkpoint.toFile(), SessionCheckpoint.class);
            if (!id.equals(cp.getSessionId()) || cp.getPendingToolBatch() != null)
                throw new IOException("Checkpoint ID mismatch or unfinished tool batch");
            if (Files.isSymbolicLink(directory.resolve("workspace"))) throw new IOException("Symlink workspace");
            var copy = new Workspace(directory.resolve("workspace"), directory.resolve("workspace/agent-local.properties"));
            if (!copy.rootPath().equals(cp.getWorkspacePath())) throw new IOException("Checkpoint workspace mismatch");
            if (!copy.checkpointHashes().equals(cp.getWorkspaceHashes())) throw new IOException("Workspace differs from checkpoint");
            copy.restoreChanges(cp.getOriginalContents(), cp.getCreatedFiles());
            Path sourcePath = Path.of(Files.readString(marker));
            var source = new Workspace(sourcePath, sourcePath.resolve("agent-local.properties"), directory.getParent());
            out.append(new WorkspaceSynchronizer(source, copy, directory).pendingSummary()).append('\n');
        } catch (IOException | IllegalArgumentException invalid) {
            out.append("未写回改动：无法核验，可能有尚未保存到原项目的修改。\n");
        }
        return out.append("将删除历史、摘要、checkpoint、隔离副本和同步状态；原项目及已写回代码保持不变。\n").toString();
    }
    public String delete() throws IOException {
        if (closed || !store.ownsDeletionLock(id)) throw new IOException("删除锁已释放，操作无效");
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("会话目录不可用");
        Path tombstone = directory.resolveSibling(".deleted-" + id + "-" + UUID.randomUUID());
        Files.move(directory, tombstone, StandardCopyOption.ATOMIC_MOVE);
        try {
            // Default walk does not follow symlinks; a link itself is removed, never its target.
            try (var paths = Files.walk(tombstone)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            return "已删除会话：" + id + "。原项目及已写回代码未改变。";
        } catch (IOException failure) {
            throw new IOException("会话已从列表移除，但删除中断，残留目录：" + tombstone
                    + "。原项目未改变。", failure);
        } finally { close(); }
    }
    public void close() {
        if (!closed) { closed = true; store.releaseDeletionLock(id); }
    }
}
