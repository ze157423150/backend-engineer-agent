package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.backendagent.history.ArchivedBody;

/** User-triggered writeback; the model continues to operate only on its isolated workspace. */
public final class WorkspaceSynchronizer {
    private final Workspace source;
    private final Workspace isolated;
    private final WorkspaceAccess changes;
    private final Path stateFile;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> appliedHashes = new TreeMap<>();

    public WorkspaceSynchronizer(Workspace source, WorkspaceAccess changes, Path sessionDirectory) throws IOException {
        this.source = Objects.requireNonNull(source);
        this.changes = Objects.requireNonNull(changes);
        this.isolated = new Workspace(Path.of(changes.rootPath()), Path.of(changes.rootPath()).resolve("agent-local.properties"));
        this.stateFile = sessionDirectory.resolve("source-sync.json");
        if (Files.isSymbolicLink(sessionDirectory) || Files.isSymbolicLink(stateFile))
            throw new IOException("同步状态路径不可用");
        if (Files.exists(stateFile)) {
            if (Files.size(stateFile) > 32768) throw new IOException("同步状态过大");
            var state = json.readTree(stateFile.toFile());
            if (!state.path("sourcePath").asText().equals(source.rootPath())
                    || !state.path("isolatedPath").asText().equals(changes.rootPath())
                    || !state.path("hashes").isObject() || state.path("hashes").size() > 32)
                throw new IOException("同步状态与当前工作区不符");
            var fields = state.path("hashes").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!entry.getValue().isTextual() || !entry.getValue().asText().matches("[0-9a-f]{64}"))
                    throw new IOException("同步哈希无效");
                appliedHashes.put(entry.getKey(), entry.getValue().asText());
            }
        }
    }

    public String diff() throws IOException {
        var output = new StringBuilder("原项目：" + source.rootPath() + "\n");
        int count = 0;
        for (var change : plan()) {
            if (!change.pending) continue;
            count++;
            if (change.conflict) output.append("[冲突：原文件与同步基准不同，/apply 将拒绝覆盖] ").append(change.path).append('\n');
            output.append(FileDiff.render(change.path, change.before == null ? "" : change.before,
                    change.after, 24000, change.before == null)).append('\n');
        }
        if (count == 0) output.append("没有待写回的改动。\n");
        else output.append("待写回文件：").append(count).append("；使用 /apply 写回原项目。\n");
        return output.toString();
    }

    public String pendingSummary() throws IOException {
        var pending = plan().stream().filter(change -> change.pending).toList();
        return pending.isEmpty() ? "未写回改动：无。" : "未写回改动：" + pending.size() + " 个文件："
                + String.join(", ", pending.stream().map(change -> change.path + (change.conflict ? "（冲突）" : "")).toList());
    }

    public String apply() throws IOException {
        var plan = plan();
        var conflicts = plan.stream().filter(change -> change.conflict).map(change -> change.path).toList();
        if (!conflicts.isEmpty()) return "写回已取消，所有文件均未写入。原文件已改变：" + String.join(", ", conflicts)
                + "。请保留双方改动并手动合并，或基于最新原项目创建新会话。";
        var written = new ArrayList<String>();
        try {
            for (var change : plan) {
                if (change.pending) {
                    source.synchronizationWrite(change.path, change.before == null ? null : ArchivedBody.hash(change.before), change.after);
                    written.add(change.path);
                }
                // Also records files already identical, so a later edit has the correct baseline.
                appliedHashes.put(change.path, ArchivedBody.hash(change.after));
                saveState();
            }
        } catch (IOException failure) {
            throw new IOException("写回中断；已写回文件：" + (written.isEmpty() ? "无" : String.join(", ", written))
                    + "。未自动回滚；请检查原项目和 /diff。原因：" + failure.getMessage(), failure);
        }
        return written.isEmpty() ? "没有待写回的改动。" : "已写回原项目：" + String.join(", ", written)
                + "。隔离副本和会话记录仍保留；此操作不等于测试通过。";
    }

    private List<Change> plan() throws IOException {
        var originals = changes.originalContents();
        if (originals.size() > 32) throw new IOException("同步文件超过32个");
        var result = new ArrayList<Change>();
        for (String path : new TreeSet<>(originals.keySet())) {
            String after = isolated.synchronizationText(path);
            if (after == null) throw new IOException("隔离副本文件缺失，不支持自动删除：" + path);
            String before = source.synchronizationText(path);
            String actual = before == null ? null : ArchivedBody.hash(before);
            String desired = ArchivedBody.hash(after);
            String expected = appliedHashes.get(path);
            if (expected == null && !changes.createdFilePaths().contains(path)) expected = ArchivedBody.hash(originals.get(path));
            boolean pending = !Objects.equals(actual, desired);
            boolean conflict = pending && !Objects.equals(actual, expected);
            result.add(new Change(path, before, after, pending, conflict));
        }
        return result;
    }

    private void saveState() throws IOException {
        if (Files.isSymbolicLink(stateFile)) throw new IOException("同步状态路径不可用");
        var state = json.createObjectNode();
        state.put("sourcePath", source.rootPath());
        state.put("isolatedPath", changes.rootPath());
        state.set("hashes", json.valueToTree(appliedHashes));
        Path temporary = Files.createTempFile(stateFile.getParent(), ".source-sync-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static final class Change {
        final String path, before, after;
        final boolean pending, conflict;
        Change(String path, String before, String after, boolean pending, boolean conflict) {
            this.path = path; this.before = before; this.after = after;
            this.pending = pending; this.conflict = conflict;
        }
    }
}
