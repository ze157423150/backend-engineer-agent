package dev.backendagent.tools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.ByteBuffer;

import dev.backendagent.model.ToolResult;

/** Bounded repository access. No model-generated shell commands. */
public final class Workspace {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_OUTPUT_CHARS = 24_000;
    private static final int MAX_SEARCH_FILES = 1000;
    private static final int MAX_MATCHES = 50;
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of("target", "build", "node_modules");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "xml", "md", "txt", "properties", "yml", "yaml", "json", "sql", "gradle");

    private final Path root;
    private final Set<Path> excludedFiles;
    private final Set<Path> excludedDirectories;
    private final Map<Path, String> originals = new LinkedHashMap<>();
    private final Set<Path> createdFiles = new HashSet<>();

    public Workspace(Path root, Path configFile) throws IOException {
        this(root, configFile, null);
    }

    public Workspace(Path root, Path configFile, Path dataDirectory) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) {
            throw new IllegalArgumentException("Workspace must be a directory");
        }
        var excluded = new HashSet<Path>();
        excluded.add(configFile.toAbsolutePath().normalize());
        if (Files.exists(configFile)) {
            excluded.add(configFile.toRealPath());
        }
        this.excludedFiles = Set.copyOf(excluded);
        var directories = new HashSet<Path>();
        if (dataDirectory != null) {
            directories.add(dataDirectory.toAbsolutePath().normalize());
            if (Files.exists(dataDirectory)) { directories.add(dataDirectory.toRealPath()); }
            if (directories.stream().anyMatch(this.root::startsWith)) {
                throw new IllegalArgumentException("Session data directory must not contain the workspace");
            }
        }
        this.excludedDirectories = Set.copyOf(directories);
    }

    public String rootPath() { return root.toString(); }

    public Map<String, String> originalContents() {
        var result = new LinkedHashMap<String, String>();
        originals.forEach((path, content) -> result.put(relative(path), content));
        return Map.copyOf(result);
    }

    public Set<String> createdFilePaths() {
        return createdFiles.stream().map(this::relative).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Fingerprint the visible tree; build output and credentials are never read. */
    public Map<String, String> checkpointHashes() throws IOException {
        var hashes = new java.util.TreeMap<String, String>();
        long[] bytes = {0};
        int[] entries = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void count() throws IOException {
                if (++entries[0] > 1000) { throw new IOException("Checkpoint workspace exceeds 1000 entries"); }
            }
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                if (!directory.equals(root) && !visible(directory)) { return FileVisitResult.SKIP_SUBTREE; }
                count();
                hashes.put(relative(directory), "directory");
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!visible(file)) { return FileVisitResult.CONTINUE; }
                count();
                if (!readableText(file)) { hashes.put(relative(file), "unreadable"); return FileVisitResult.CONTINUE; }
                if ((bytes[0] += Files.size(file)) > 16 * 1024 * 1024) {
                    throw new IOException("Checkpoint workspace exceeds 16 MiB");
                }
                try {
                    hashes.put(relative(file), java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                return FileVisitResult.CONTINUE;
            }
        });
        return Map.copyOf(hashes);
    }

    /** Restore only bookkeeping; this method does not write project files. */
    public void restoreChanges(Map<String, String> contents, Set<String> created) throws IOException {
        if (contents.size() > 32 || !contents.keySet().containsAll(created)) {
            throw new IOException("Invalid checkpoint file changes");
        }
        var restored = new LinkedHashMap<Path, String>();
        var restoredCreated = new HashSet<Path>();
        for (var entry : contents.entrySet()) {
            Path file = resolve(entry.getKey());
            if (!readableText(file) || entry.getValue().getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES
                    || (created.contains(entry.getKey()) && !entry.getValue().isEmpty())) {
                throw new IOException("Invalid checkpoint baseline");
            }
            restored.put(file, entry.getValue());
            if (created.contains(entry.getKey())) { restoredCreated.add(file); }
        }
        originals.clear();
        originals.putAll(restored);
        createdFiles.clear();
        createdFiles.addAll(restoredCreated);
    }

    public ToolResult listFiles(String relativePath) {
        try {
            Path directory = resolve(relativePath);
            if (!Files.isDirectory(directory)) {
                return failure("路径不是目录");
            }
            try (var entries = Files.list(directory)) {
                var paths = entries.filter(this::visible).limit(101).sorted().toList();
                StringBuilder output = new StringBuilder();
                for (Path path : paths.stream().limit(100).toList()) {
                    output.append(relative(path));
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        output.append('/');
                    }
                    output.append('\n');
                }
                if (paths.size() > 100) {
                    output.append("[目录结果已截断，请缩小到子目录或使用 search_code]\n");
                }
                return new ToolResult(true, output.isEmpty() ? "[空目录]" : output.toString());
            }
        } catch (IOException | IllegalArgumentException failure) {
            return failure("目录不可访问：只允许工作区内的普通路径，不允许隐藏目录、构建目录、密钥文件或符号链接");
        }
    }

    public ToolResult readFile(String relativePath, String start, String end) {
        try {
            int startLine = Integer.parseInt(start);
            int endLine = Integer.parseInt(end);
            if (startLine < 1 || endLine < startLine || (long) endLine - startLine >= 200) {
                return failure("行号必须从 1 开始，每次最多读取 200 行");
            }
            Path file = resolve(relativePath);
            if (!readableText(file)) {
                return failure("只支持不超过 256 KiB 的普通文本文件");
            }
            List<String> lines = Files.readAllLines(file);
            StringBuilder output = new StringBuilder("file=" + relative(file) + ", totalLines=" + lines.size() + "\n");
            for (int i = startLine - 1; i < Math.min(endLine, lines.size()); i++) {
                String line = (i + 1) + ": " + lines.get(i) + "\n";
                if (output.length() + line.length() > MAX_OUTPUT_CHARS) {
                    output.append("[内容已截断，请缩小行范围]\n");
                    break;
                }
                output.append(line);
            }
            if (startLine > lines.size()) {
                output.append("[起始行超出文件范围]\n");
            }
            return new ToolResult(true, output.toString());
        } catch (IOException | IllegalArgumentException failure) {
            return failure("文件不可读取，请检查工作区相对路径和行号；密钥文件、隐藏路径和符号链接禁止读取");
        }
    }

    public ToolResult searchCode(String relativePath, String query) {
        if (query == null || query.isBlank() || query.length() > 200) {
            return failure("query 必须是 1 到 200 字符的非空关键词，按字面匹配");
        }
        try {
            Path start = resolve(relativePath);
            StringBuilder output = new StringBuilder();
            int[] visitedFiles = {0};
            int[] matches = {0};
            boolean[] truncated = {false};
            Files.walkFileTree(start, Set.of(), 12, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    return directory.equals(start) || visible(directory)
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!visible(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.isDirectory()) {
                        truncated[0] = true;
                        return FileVisitResult.CONTINUE;
                    }
                    if (++visitedFiles[0] > MAX_SEARCH_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!readableText(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file);
                    } catch (IOException unreadable) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        if (!lines.get(i).contains(query)) {
                            continue;
                        }
                        String text = lines.get(i);
                        int fragmentStart = Math.max(0, text.indexOf(query) - 100);
                        String match = relative(file) + ":" + (i + 1) + ": "
                                + (fragmentStart > 0 ? "…" : "")
                                + text.substring(fragmentStart, Math.min(text.length(), fragmentStart + 500)) + "\n";
                        if (output.length() + match.length() > MAX_OUTPUT_CHARS || matches[0] >= MAX_MATCHES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        output.append(match);
                        matches[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    truncated[0] = true;
                    return FileVisitResult.CONTINUE;
                }
            });
            if (truncated[0]) {
                output.append("[搜索不完整：达到结果/文件限制或有不可读路径，请缩小搜索目录]\n");
            }
            return new ToolResult(true, output.isEmpty() ? "[未找到匹配]" : output.toString());
        } catch (IOException | IllegalArgumentException failure) {
            return failure("搜索路径不可访问，请使用工作区内的相对路径");
        }
    }

    /** One exact replacement in an existing file; empty new text permits deletion of a fragment. */
    public ToolResult applyPatch(String relativePath, String oldText, String newText) {
        if (oldText == null || oldText.isEmpty() || newText == null
                || oldText.length() > 24_000 || newText.length() > 24_000) {
            return failure("old_text 必须非空，old_text 和 new_text 各最多 24000 字符");
        }
        if (oldText.equals(newText)) {
            return failure("新旧文本相同，没有实际修改");
        }
        Path temporary = null;
        try {
            Path file = resolve(relativePath);
            if (!readableText(file)) {
                return failure("只能修改已存在且不超过 256 KiB 的普通文本文件");
            }
            if (Files.getFileStore(file).supportsFileAttributeView("unix")
                    && ((Number) Files.getAttribute(file, "unix:nlink")).intValue() > 1) {
                return failure("不允许修改硬链接文件");
            }
            if (!originals.containsKey(file) && originals.size() >= 32) {
                return failure("本次运行最多修改 32 个文件");
            }
            String before = Files.readString(file);
            int position = before.indexOf(oldText);
            if (position < 0 || before.indexOf(oldText, position + 1) >= 0) {
                return failure("旧文本必须在文件中精确匹配一次，请重新读取并提供足够上下文；文件未修改");
            }
            String after = before.substring(0, position) + newText + before.substring(position + oldText.length());
            byte[] bytes = after.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES || after.indexOf('\0') >= 0) {
                return failure("修改后内容超过 256 KiB 或包含空字符；文件未修改");
            }
            temporary = Files.createTempFile(file.getParent(), ".agent-patch-", ".tmp");
            Files.write(temporary, bytes);
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            }
            if (!resolve(relativePath).equals(file) || !Files.readString(file).equals(before)) {
                return failure("写入前文件发生变化，请重新读取；文件未修改");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            originals.putIfAbsent(file, before);
            return new ToolResult(true, "file=" + relative(file)
                    + "\n已完成一次精确替换。请用 workspace_diff 检查改动；尚未运行编译或测试。");
        } catch (IOException | IllegalArgumentException failure) {
            return failure("补丁未应用：检查相对路径、文本编码、文件权限和原子写入支持；禁止密钥、隐藏路径和符号链接");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Temporary file remains hidden. */ }
            }
        }
    }

    /** CREATE_NEW guarantees that an existing file is never overwritten, even if it appears concurrently. */
    public ToolResult createFile(String relativePath, String content) {
        if (content == null || content.length() > 24_000 || content.indexOf('\0') >= 0) {
            return failure("content 必须是最多 24000 字符且不包含空字符的文本");
        }
        Path file = null;
        boolean created = false;
        boolean completed = false;
        try {
            if (relativePath == null || relativePath.isBlank()) {
                return failure("必须指定工作区相对文件路径");
            }
            Path input = Path.of(relativePath);
            Path candidate = root.resolve(input).normalize();
            if (input.isAbsolute() || !candidate.startsWith(root) || candidate.equals(root)) {
                return failure("只允许工作区内的相对文件路径");
            }
            Path parent = resolve(relative(candidate.getParent()));
            file = parent.resolve(candidate.getFileName());
            if (!Files.isDirectory(parent) || !visible(file) || !textExtension(file)) {
                return failure("父目录必须已存在，文件必须使用允许的文本扩展名；禁止密钥、隐藏路径和符号链接");
            }
            if (originals.size() >= 32) {
                return failure("本次运行最多创建或修改 32 个文件");
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) {
                return failure("文件内容超过 256 KiB");
            }
            try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) { channel.write(buffer); }
            }
            completed = true;
            originals.put(file, "");
            createdFiles.add(file);
            return new ToolResult(true, "file=" + relative(file)
                    + "\n已创建新文件，未覆盖已有文件。请用 workspace_diff 检查并 read_file 确认；尚未编译或测试。");
        } catch (FileAlreadyExistsException exists) {
            return failure("文件已存在，未覆盖；修改已有文件请先读取并使用 apply_patch");
        } catch (IOException | IllegalArgumentException failure) {
            return failure("文件创建未完成：检查父目录、相对路径和写入权限");
        } finally {
            if (created && !completed) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { /* Incomplete file may remain. */ }
            }
        }
    }

    /** Snapshot comparison for files changed by this workspace; independent of Git repository state. */
    public ToolResult diff(String relativePath) {
        try {
            Path file = resolve(relativePath);
            String original = originals.get(file);
            if (original == null) {
                return new ToolResult(true, "[本次运行未通过 apply_patch 修改此文件，也未通过 create_file 创建此文件]");
            }
            if (!readableText(file)) {
                return failure("修改后的文件无法读取");
            }
            String current = Files.readString(file);
            return new ToolResult(true, FileDiff.render(relative(file), original, current, MAX_OUTPUT_CHARS,
                    createdFiles.contains(file)));
        } catch (IOException | IllegalArgumentException failure) {
            return failure("差异不可读取，请使用工作区内可访问的相对文件路径");
        }
    }

    /** Protected configs, symlinks, hidden files and build output are excluded from the snapshot. */
    public Path createTestSnapshot() throws IOException {
        Path snapshot = Files.createTempDirectory("backend-agent-test-");
        try {
            Files.setPosixFilePermissions(snapshot, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            int[] count = {0};
            long[] bytes = {0};
            Files.walkFileTree(root, Set.of(), 16, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (!directory.equals(root) && !visible(directory)) { return FileVisitResult.SKIP_SUBTREE; }
                    Path target = snapshot.resolve(root.relativize(directory));
                    Files.createDirectories(target);
                    Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!visible(file)) { return FileVisitResult.CONTINUE; }
                    if (attrs.isDirectory()) { throw new IOException("Snapshot depth limit exceeded"); }
                    if (!textExtension(file)) { return FileVisitResult.CONTINUE; }
                    if (!readableText(file) || ++count[0] > 1000 || (bytes[0] += attrs.size()) > 16 * 1024 * 1024) {
                        throw new IOException("Snapshot size limit exceeded");
                    }
                    Path target = snapshot.resolve(root.relativize(file));
                    Files.copy(file, target);
                    Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                    return FileVisitResult.CONTINUE;
                }
            });
            if (!Files.isRegularFile(snapshot.resolve("pom.xml"))) { throw new IOException("Missing root pom.xml"); }
            // A nested tmpfs mount needs an existing mountpoint inside the read-only source mount.
            Files.createDirectory(snapshot.resolve("target"));
            Files.setPosixFilePermissions(snapshot.resolve("target"),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            return snapshot;
        } catch (IOException | RuntimeException failure) {
            try (var paths = Files.walk(snapshot)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
            throw failure;
        }
    }

    private Path resolve(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Missing path");
        }
        Path input = Path.of(relativePath);
        Path candidate = root.resolve(input).normalize();
        if (input.isAbsolute() || !candidate.startsWith(root)) {
            throw new IllegalArgumentException("Outside workspace");
        }
        Path current = root;
        for (Path component : root.relativize(candidate)) {
            current = current.resolve(component);
            if (!visible(current)) {
                throw new IllegalArgumentException("Blocked path");
            }
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("Outside workspace");
        }
        return real;
    }

    private boolean visible(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && !SKIPPED_DIRECTORIES.contains(name)
                && !name.equals("agent-local.properties") && !name.startsWith("application-local.")
                && !excludedFiles.contains(path.toAbsolutePath().normalize())
                && excludedDirectories.stream().noneMatch(path.toAbsolutePath().normalize()::startsWith)
                && !Files.isSymbolicLink(path);
    }

    private boolean readableText(Path file) throws IOException {
        return visible(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && textExtension(file) && Files.size(file) <= MAX_FILE_BYTES;
    }

    private boolean textExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(extension);
    }

    private String relative(Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private static ToolResult failure(String message) {
        return new ToolResult(false, message);
    }
}
