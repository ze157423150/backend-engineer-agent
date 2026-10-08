package dev.backendagent.cli;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import dev.backendagent.runtime.*;
import dev.backendagent.persistence.*;
import dev.backendagent.tools.*;
import dev.backendagent.model.*;
import dev.backendagent.sandbox.*;
import dev.backendagent.config.AgentConfigLoader;

/** One active attachment at a time; each attachment owns a fresh store, tools and workspace. */
public final class InteractiveSessions {
    public interface Factory {
        Attachment create(String task) throws IOException;
        Attachment open(UUID id, int limit, boolean retryInFlight) throws IOException;
        String list() throws IOException;
        default Deletion prepareDelete(UUID id) throws IOException { throw new IOException("未配置删除会话功能"); }
    }
    public interface Deletion extends AutoCloseable {
        String preview();
        String delete() throws IOException;
        void close() throws IOException;
    }
    public static final class Attachment implements AutoCloseable {
        final AgentSession session;
        final IntFunction<AgentRuntime> runtimeFactory;
        final Consumer<AgentSession> save;
        final WorkspaceSynchronizer synchronizer;
        final AutoCloseable resource;
        int limit;
        public Attachment(AgentSession session, int limit, IntFunction<AgentRuntime> runtimeFactory,
                          Consumer<AgentSession> save, WorkspaceSynchronizer synchronizer, AutoCloseable resource) {
            this.session = session; this.limit = limit; this.runtimeFactory = runtimeFactory;
            this.save = save; this.synchronizer = synchronizer; this.resource = resource;
        }
        public void close() throws IOException {
            try { resource.close(); } catch (Exception failure) { throw new IOException("无法关闭旧会话", failure); }
        }
    }
    private final BufferedReader input;
    private final PrintWriter output;
    private final Factory factory;
    private final AgentOptions options;
    private final Map<UUID, Integer> limits = new HashMap<>();
    public InteractiveSessions(AgentOptions options, BufferedReader input, PrintWriter output) {
        this(options, input, output, new ProductionFactory(options, output));
    }
    public InteractiveSessions(AgentOptions options, BufferedReader input, PrintWriter output, Factory factory) {
        this.options = options; this.input = input; this.output = output; this.factory = factory;
    }
    public void run() throws IOException {
        Attachment current = null;
        boolean initialize = false;
        boolean awaitingNew = true;
        String initialMessage = options.getMessage();
        try {
            UUID initialId = options.getContinueSession() != null ? options.getContinueSession()
                    : options.getResumeFailedSession() != null ? options.getResumeFailedSession() : options.getResumeSession();
            if (initialId != null) {
                current = factory.open(initialId, options.getMaxModelCalls(), options.isRetryIncompleteTool());
                awaitingNew = false;
                initialize = options.getContinueSession() == null || initialMessage != null;
            } else if (options.getTask() != null) {
                current = factory.create(options.getTask()); awaitingNew = false; initialize = true;
            }
            output.println("/sessions 查看会话；/open UUID 打开旧会话；/new 新建；/delete UUID 删除；/exit 退出。");
            while (true) {
                if (awaitingNew) {
                    output.println("输入新会话的第一条需求；也可 /sessions 或 /open UUID。未输入需求不会创建空会话。");
                    output.print("你> "); output.flush();
                    String line = input.readLine();
                    if (line == null || line.trim().equals("/exit") || line.trim().equals("/quit")) return;
                    if (line.isBlank()) continue;
                    String command = line.trim();
                    if (command.equals("/sessions")) { output.println(list()); continue; }
                    if (command.equals("/help")) { output.println("直接输入一行需求（最多8000字符）；/sessions；/open UUID；/new；/delete UUID；/exit。"); continue; }
                    if (command.equals("/new")) continue;
                    if (command.split("\\s+", 2)[0].equals("/delete")) { delete(command, current); continue; }
                    boolean opening = command.split("\\s+", 2)[0].equals("/open");
                    if (command.matches("/[A-Za-z][A-Za-z0-9_-]*(\\s.*)?") && !opening) {
                        output.println("先输入需求，或用 /sessions、/open UUID 选择会话。"); continue;
                    }
                    Attachment candidate;
                    try {
                        if (opening) {
                            UUID id = UUID.fromString(command.substring(5).trim());
                            if (current != null && current.session.id().equals(id)) {
                                awaitingNew = false; initialize = false; continue;
                            }
                            candidate = factory.open(id, limits.getOrDefault(id, options.getMaxModelCalls()), false);
                            initialize = false;
                        } else {
                            if (line.length() > 8000) { output.println("消息超过8000字符，请缩短后重试。"); continue; }
                            candidate = factory.create(line); initialize = true;
                        }
                    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                        output.println("会话未切换：" + failure.getMessage()); continue;
                    }
                    if (current != null) { limits.put(current.session.id(), current.limit); current.close(); }
                    current = candidate; awaitingNew = false;
                }
                announce(current);
                var chat = new TerminalChat(input, output, current.limit, current.runtimeFactory,
                        current.save, current.synchronizer, this::list);
                if (initialize && current.session.status() != AgentSession.Status.CREATED
                        && current.session.status() != AgentSession.Status.COMPLETED && current.session.modelCalls() >= current.limit) {
                    output.println("调用额度不足；用 /budget N 调整当前会话额度后 /resume。");
                    initialize = false;
                }
                if (initialize) chat.run(current.session, initialMessage, options.isRetryIncompleteTool());
                else chat.runSelected(current.session);
                initialize = false; initialMessage = null;
                current.limit = chat.modelCallLimit(); limits.put(current.session.id(), current.limit);
                String command = chat.navigation();
                if (command == null) return;
                if (command.equals("/new")) { awaitingNew = true; continue; }
                if (command.split("\\s+", 2)[0].equals("/delete")) { delete(command, current); continue; }
                UUID id = UUID.fromString(command.substring(5).trim());
                if (id.equals(current.session.id())) { output.println("已在该会话中。"); continue; }
                Attachment candidate;
                try { candidate = factory.open(id, limits.getOrDefault(id, options.getMaxModelCalls()), false); }
                catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                    output.println("会话未切换，仍在 " + current.session.id() + "：" + failure.getMessage()); continue;
                }
                current.close(); current = candidate;
            }
        } finally { if (current != null) current.close(); }
    }
    private void delete(String command, Attachment current) throws IOException {
        UUID id;
        try { id = UUID.fromString(command.substring(7).trim()); }
        catch (IllegalArgumentException invalid) { output.println("用法：/delete 完整的会话UUID"); return; }
        if (current != null && current.session.id().equals(id)) {
            output.println("不能删除当前会话；请先打开其他会话，或退出后重启至会话选择界面。"); return;
        }
        try (var deletion = factory.prepareDelete(id)) {
            output.println(deletion.preview());
            output.println("确认永久删除请输入 DELETE " + id + "；其他输入或EOF取消（确认内容不会发送给模型）。");
            output.print("确认> "); output.flush();
            String confirmation = input.readLine();
            if (!Objects.equals(confirmation, "DELETE " + id)) { output.println("已取消删除。"); return; }
            output.println(deletion.delete()); limits.remove(id);
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            output.println("删除未完成：" + failure.getMessage());
        }
    }

    private String list() {
        try { return factory.list(); } catch (IOException failure) { return "无法列出会话：" + failure.getMessage(); }
    }
    private void announce(Attachment current) {
        output.println("当前会话：" + current.session.id() + "；历史、摘要、工具及修改仅属于此会话。");
        output.println("Session ID: " + current.session.id());
    }

    private static final class ProductionFactory implements Factory {
        final AgentOptions options;
        final PrintWriter output;
        ModelClient model;
        boolean initialCreation = true;
        ProductionFactory(AgentOptions options, PrintWriter output) { this.options = options; this.output = output; }
        public Attachment create(String task) throws IOException { return attach(null, task, options.getMaxModelCalls(), false); }
        public Attachment open(UUID id, int limit, boolean retry) throws IOException { return attach(id, null, limit, retry); }
        private Attachment attach(UUID id, String task, int limit, boolean retry) throws IOException {
            var store = new FileSessionStore(options.getDataDirectory());
            try {
                if (model == null) model = new DeepSeekModelClient(AgentConfigLoader.load(options.getConfigFile()));
                Path protectedConfig = options.getConfigFile() == null ? Path.of("agent-local.properties") : options.getConfigFile();
                var runner = new LocalProcessRunner();
                DockerWorkspace workspace;
                AgentSession session;
                if (id == null) {
                    session = new AgentSession(task, store); store.create(session);
                    workspace = DockerWorkspace.create(new Workspace(options.getWorkspace(), protectedConfig, options.getDataDirectory()),
                            store.sessionDirectory(session.id()), runner, options.getSandboxImage());
                } else {
                    var saved = store.read(id);
                    var snapshot = saved.path("snapshot");
                    String status = saved.path("events").isEmpty() ? snapshot.path("status").asText()
                            : saved.path("events").get(saved.path("events").size() - 1).path("status").asText();
                    int used = saved.path("events").isEmpty() ? snapshot.path("modelCalls").asInt()
                            : saved.path("events").get(saved.path("events").size() - 1).path("modelCalls").asInt();
                    int restoreLimit = Math.max(limit, Math.addExact(used, 1)); // Attachment alone does not authorize extra calls.
                    workspace = DockerWorkspace.open(options.getWorkspace(), store.sessionDirectory(id), runner, options.getSandboxImage());
                    session = switch (status) {
                        case "COMPLETED" -> store.openCompleted(id, workspace, restoreLimit);
                        case "BUDGET_EXHAUSTED" -> store.restore(id, workspace, restoreLimit);
                        case "FAILED" -> store.restoreFailed(id, workspace, restoreLimit, retry);
                        default -> throw new IOException("该会话未处于可打开的终止边界；请使用显式失败恢复命令检查现场");
                    };
                }
                store.bindWorkspace(workspace);
                var sandbox = new DockerSandboxExecutor(runner, options.getSandboxImage(), Duration.ofSeconds(options.getTestTimeoutSeconds()));
                var archive = store.observationArchive(session);
                var tools = List.of(new ListFilesTool(workspace), new ReadFileTool(workspace),
                        new ReadObservationTool(session, workspace, archive), new SearchHistoryTool(new dev.backendagent.history.HistoryArchiveReader(archive, workspace)),
                        new SearchCodeTool(workspace), new SearchTurnsTool(session), new ReadTurnTool(session), new RememberFactTool(session),
                        new ApplyPatchTool(workspace, session), new CreateFileTool(workspace, session), new WorkspaceDiffTool(workspace), new RunTestsTool(workspace, sandbox));
                IntFunction<AgentRuntime> runtime = cap -> new AgentRuntime(model, tools, cap,
                        new ContextBudget(options.getMaxHistoryCharacters()), options.getMinimumSummaryInputCharacters(), output::println);
                if (id == null && initialCreation && options.getMemoryFrom() != null) {
                    new dev.backendagent.memory.MemoryLoader().load(store, options.getMemoryFrom(), workspace, session);
                }
                if (id == null) { store.saveSnapshot(session); initialCreation = false; }
                var synchronizer = new WorkspaceSynchronizer(new Workspace(options.getWorkspace(), protectedConfig, options.getDataDirectory()),
                        workspace, store.sessionDirectory(session.id()));
                output.println("Session files: " + store.sessionDirectory(session.id()));
                output.println("Isolated workspace: " + workspace.rootPath());
                return new Attachment(session, limit, runtime, store::saveSnapshot, synchronizer, store);
            } catch (IOException | RuntimeException failure) { store.close(); throw failure; }
        }
        public Deletion prepareDelete(UUID id) throws IOException {
            var store = new FileSessionStore(options.getDataDirectory());
            try {
                var operation = store.prepareDeletion(id);
                return new Deletion() {
                    public String preview() { return operation.preview(); }
                    public String delete() throws IOException { return operation.delete(); }
                    public void close() { operation.close(); store.close(); }
                };
            } catch (IOException | RuntimeException failure) { store.close(); throw failure; }
        }
        public String list() throws IOException {
            Path root = options.getDataDirectory().toAbsolutePath().normalize();
            if (!Files.exists(root)) return "暂无会话。";
            if (Files.isSymbolicLink(root)) throw new IOException("会话目录不能是符号链接");
            var entries = new ArrayList<String>();
            try (var paths = Files.list(root)) {
                for (Path directory : paths.sorted().toList()) {
                    UUID id;
                    try { id = UUID.fromString(directory.getFileName().toString()); } catch (IllegalArgumentException ignored) { continue; }
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (var store = new FileSessionStore(root)) {
                        var snapshot = store.read(id).path("snapshot");
                        String task = snapshot.path("objective").asText().replaceAll("[\\p{Cntrl}]", " ");
                        if (task.length() > 80) task = task.substring(0,80) + "…";
                        entries.add(id + " | " + snapshot.path("status").asText() + " | " + snapshot.path("savedAt").asText() + " | " + task);
                    } catch (IOException invalid) { entries.add(id + " | 不可读取（不影响其他会话）"); }
                }
            }
            return entries.isEmpty() ? "暂无会话。" : String.join("\n", entries);
        }
    }
}
