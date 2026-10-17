package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import dev.backendagent.model.ToolResult;

/** Repository operations independent of where the filesystem is executed. */
public interface WorkspaceAccess {
    String rootPath();
    ToolResult listFiles(String path);
    ToolResult readFile(String path, String start, String end);
    default ToolResult readFile(String path, String start, String end, String format) {
        if (format == null || format.equals("numbered")) return readFile(path, start, end);
        return new ToolResult(false, "默认文件读取仅支持带行号格式");
    }
    dev.backendagent.model.FileFingerprint fileFingerprint(String path) throws IOException;
    ToolResult searchCode(String path, String query);
    ToolResult applyPatch(String path, String oldText, String newText);
    default ToolResult applyPatchBatch(String path, String expectedSha256, java.util.List<PatchEdit> edits) {
        return new ToolResult(false, "PATCH_BATCH_UNSUPPORTED: 当前工作区不支持批次原子补丁；本组未写入。");
    }
    ToolResult createFile(String path, String content);
    ToolResult diff(String path);
    Map<String, String> checkpointHashes() throws IOException;
    Map<String, String> originalContents();
    Set<String> createdFilePaths();
    void restoreChanges(Map<String, String> originals, Set<String> created) throws IOException;
    Path createTestSnapshot() throws IOException;
}
