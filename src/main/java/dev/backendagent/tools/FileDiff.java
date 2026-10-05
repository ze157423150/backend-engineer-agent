package dev.backendagent.tools;

import java.util.Arrays;

/** Display-only line comparison, with a single hunk and three context lines. */
final class FileDiff {
    private FileDiff() {}

    static String render(String path, String before, String after, int limit, boolean newFile) {
        if (newFile && after.isEmpty()) { return "file=" + path + "\n[新增空文件]"; }
        if (before.equals(after)) {
            return "[相对本次首次修改前的快照，没有差异]";
        }
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        int prefix = 0;
        while (prefix < Math.min(oldLines.length, newLines.length)
                && oldLines[prefix].equals(newLines[prefix])) { prefix++; }
        int suffix = 0;
        while (suffix < Math.min(oldLines.length, newLines.length) - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) { suffix++; }
        int start = Math.max(0, prefix - 3);
        int oldEnd = Math.min(oldLines.length, oldLines.length - suffix + 3);
        int newEnd = Math.min(newLines.length, newLines.length - suffix + 3);
        var output = new StringBuilder("file=" + path + "\n--- " + (newFile ? "/dev/null" : "before/" + path)
                + "\n+++ after/" + path + "\n@@ -" + (oldEnd == start ? start : start + 1) + "," + (oldEnd - start)
                + " +" + (newEnd == start ? start : start + 1) + "," + (newEnd - start) + " @@\n");
        for (int i = start; i < prefix; i++) { output.append(' ').append(oldLines[i]).append('\n'); }
        for (int i = prefix; i < oldLines.length - suffix; i++) { output.append('-').append(oldLines[i]).append('\n'); }
        for (int i = prefix; i < newLines.length - suffix; i++) { output.append('+').append(newLines[i]).append('\n'); }
        for (int i = oldLines.length - suffix; i < oldEnd; i++) { output.append(' ').append(oldLines[i]).append('\n'); }
        output.append("[末尾换行：before=").append(before.endsWith("\n"))
                .append(", after=").append(after.endsWith("\n")).append("]\n");
        return output.length() <= limit ? output.toString()
                : output.substring(0, limit) + "\n[差异已截断，请 read_file 按行核查]\n";
    }

    private static String[] lines(String text) {
        if (text.isEmpty()) { return new String[0]; }
        String[] lines = text.split("\n", -1);
        return text.endsWith("\n") ? Arrays.copyOf(lines, lines.length - 1) : lines;
    }
}
