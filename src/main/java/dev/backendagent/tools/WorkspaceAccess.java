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
    dev.backendagent.model.FileFingerprint fileFingerprint(String path) throws IOException;
    ToolResult searchCode(String path, String query);
    ToolResult applyPatch(String path, String oldText, String newText);
    ToolResult createFile(String path, String content);
    ToolResult diff(String path);
    Map<String, String> checkpointHashes() throws IOException;
    Map<String, String> originalContents();
    Set<String> createdFilePaths();
    void restoreChanges(Map<String, String> originals, Set<String> created) throws IOException;
    Path createTestSnapshot() throws IOException;
}
