package dev.backendagent.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import dev.backendagent.runtime.AgentRuntime;
import dev.backendagent.runtime.AgentSession;

/** Terminal input only; execution, history and checkpoint semantics stay in AgentRuntime. */
public final class TerminalChat {
    private final BufferedReader input;
    private final PrintWriter output;
    private final IntFunction<AgentRuntime> runtimeFactory;
    private final Consumer<AgentSession> saveSnapshot;
    private int maxModelCalls;
    private final dev.backendagent.tools.WorkspaceSynchronizer synchronizer;
    private final java.util.function.Supplier<String> sessionListing;
    private String navigation;

    public TerminalChat(BufferedReader input, PrintWriter output, int maxModelCalls,
                        IntFunction<AgentRuntime> runtimeFactory, Consumer<AgentSession> saveSnapshot) {
        this(input, output, maxModelCalls, runtimeFactory, saveSnapshot, null);
    }

    public TerminalChat(BufferedReader input, PrintWriter output, int maxModelCalls,
                        IntFunction<AgentRuntime> runtimeFactory, Consumer<AgentSession> saveSnapshot,
                        dev.backendagent.tools.WorkspaceSynchronizer synchronizer) {
        this(input, output, maxModelCalls, runtimeFactory, saveSnapshot, synchronizer, null);
    }

    public TerminalChat(BufferedReader input, PrintWriter output, int maxModelCalls,
                        IntFunction<AgentRuntime> runtimeFactory, Consumer<AgentSession> saveSnapshot,
                        dev.backendagent.tools.WorkspaceSynchronizer synchronizer,
                        java.util.function.Supplier<String> sessionListing) {
        this.sessionListing = sessionListing;
        this.synchronizer = synchronizer;
        this.input = java.util.Objects.requireNonNull(input);
        this.output = java.util.Objects.requireNonNull(output);
        if (maxModelCalls <= 0) throw new IllegalArgumentException("Model call limit must be positive");
        this.maxModelCalls = maxModelCalls;
        this.runtimeFactory = java.util.Objects.requireNonNull(runtimeFactory);
        this.saveSnapshot = java.util.Objects.requireNonNull(saveSnapshot);
    }

    /** Runs before creating a session or accessing Docker. EOF/exit creates no empty session. */
    public static String readFirstMessage(BufferedReader input, PrintWriter output) throws IOException {
        output.println("输入第一条需求；/help 查看说明，/exit 退出。");
        while (true) {
            output.print("你> "); output.flush();
            String message = input.readLine();
            if (message == null || message.trim().equals("/exit") || message.trim().equals("/quit")) return null;
            if (message.isBlank()) continue;
            if (isSlashCommand(message)) {
                if (message.trim().equals("/help")) {
                    output.println("直接输入一行需求，允许以文件路径开头；每条最多8000字符。/exit退出；创建会话后可用/status、/budget N、/resume、/diff和/apply。");
                } else {
                    output.println("先输入一条需求以创建会话；/exit 可退出。每条需求最多8000字符。");
                }
                continue;
            }
            if (message.length() > 8000) { output.println("消息超过8000字符，请缩短后重试。"); continue; }
            return message;
        }
    }

    public void run(AgentSession session, String initialMessage, boolean retryInFlight) throws IOException {
        run(session, initialMessage, retryInFlight, true);
    }

    public void runSelected(AgentSession session) throws IOException { run(session, null, false, false); }
    public String navigation() { return navigation; }
    public int modelCallLimit() { return maxModelCalls; }

    private void run(AgentSession session, String initialMessage, boolean retryInFlight, boolean executeInitial) throws IOException {
        navigation = null;
        output.println("终端对话已启动：直接输入需求，/help 查看命令，/sessions 查看会话，/exit 退出。");
        if (!executeInitial) printStatus(session);
        else switch (session.status()) {
            case CREATED -> execute(session, () -> runtimeFactory.apply(maxModelCalls).run(session));
            case BUDGET_EXHAUSTED -> execute(session, () -> runtimeFactory.apply(maxModelCalls).resume(session));
            case FAILED -> execute(session, () -> runtimeFactory.apply(maxModelCalls).resumeFailed(session, retryInFlight));
            case COMPLETED -> {
                if (initialMessage != null) submit(session, initialMessage);
                else printStatus(session);
            }
            default -> throw new IllegalStateException("Session is not at a terminal input boundary");
        }
        while (true) {
            output.print("你> "); output.flush();
            String line = input.readLine();
            if (line == null) { output.println("输入已结束，会话记录已保留。"); return; }
            String command = line.trim();
            if (command.equals("/exit") || command.equals("/quit")) {
                output.println("已退出，会话记录已保留：" + session.id()); return;
            }
            if (line.isBlank()) continue;
            if (command.equals("/help")) {
                output.println("直接输入一行需求；/status 查看状态；/budget N 设置累计调用上限；/resume 恢复未完成轮；/diff 查看待写回改动；/apply 写回原项目；/sessions 列出会话；/open UUID 打开；/new 新建；/delete UUID 删除；/exit 退出。");
            } else if (command.equals("/sessions")) {
                output.println(sessionListing == null ? "当前终端未配置会话选择。" : sessionListing.get());
            } else if (command.equals("/new") || (isSlashCommand(command) && java.util.Set.of("/open", "/delete").contains(command.split("\\s+", 2)[0]))) {
                if (sessionListing == null) { output.println("当前终端未配置会话选择。"); continue; }
                if (!command.equals("/new")) {
                    try { java.util.UUID.fromString(command.substring(command.startsWith("/delete") ? 7 : 5).trim()); }
                    catch (IllegalArgumentException invalid) { output.println("用法：/open UUID 或 /delete UUID，使用完整会话UUID"); continue; }
                }
                navigation = command;
                return;
            } else if (command.equals("/diff") || command.equals("/apply")) {
                synchronize(session, command.equals("/apply"));
            } else if (command.equals("/status")) {
                printStatus(session);
            } else if (isSlashCommand(command) && command.split("\\s+", 2)[0].equals("/budget")) {
                try {
                    int limit = Integer.parseInt(command.substring(7).trim());
                    if (limit <= session.modelCalls()) throw new NumberFormatException();
                    maxModelCalls = limit;
                    output.println("累计调用上限已设为 " + limit + "，已使用 " + session.modelCalls() + "。");
                } catch (NumberFormatException invalid) {
                    output.println("用法：/budget N，N必须是大于已用次数的正整数。");
                }
            } else if (command.equals("/resume")) {
                resume(session);
            } else if (isSlashCommand(command)) {
                output.println("未知命令，输入 /help 查看支持的命令。");
            } else {
                submit(session, line);
            }
        }
    }

    /** A slash command is one ASCII command word, not an absolute file path or path-prefixed request. */
    private static boolean isSlashCommand(String line) {
        String token = line.strip().split("\\s+", 2)[0];
        return token.matches("/[A-Za-z][A-Za-z0-9_-]*");
    }

    private void synchronize(AgentSession session, boolean apply) {
        if (synchronizer == null) { output.println("当前终端未配置原项目同步。"); return; }
        if (apply && (session.status() != AgentSession.Status.COMPLETED || session.pendingToolBatch() != null)) {
            output.println("当前轮尚未完成，不能写回；可先用 /diff 查看，完成后再 /apply。"); return;
        }
        try { output.println(apply ? synchronizer.apply() : synchronizer.diff()); }
        catch (IOException | IllegalArgumentException failure) { output.println("同步未完成：" + failure.getMessage()); }
    }

    private void submit(AgentSession session, String message) {
        if (message.length() > 8000) { output.println("消息超过8000字符，请缩短后重试。"); return; }
        if (session.status() != AgentSession.Status.COMPLETED) {
            output.println("当前轮尚未完成；先检查失败原因或调整 /budget，再用 /resume 继续。新需求未添加。"); return;
        }
        if (session.turns().size() >= 32) { output.println("已达到32轮上限，请退出并创建新会话。"); return; }
        if (session.modelCalls() >= maxModelCalls) { output.println("调用额度不足；先用 /budget N 提高累计上限，再重新输入需求。"); return; }
        execute(session, () -> runtimeFactory.apply(maxModelCalls).continueConversation(session, message));
    }

    private void resume(AgentSession session) {
        if (session.status() != AgentSession.Status.BUDGET_EXHAUSTED && session.status() != AgentSession.Status.FAILED) {
            output.println("当前没有需要恢复的未完成轮。"); return;
        }
        if (session.modelCalls() >= maxModelCalls) { output.println("先用 /budget N 设置大于已用次数的上限。"); return; }
        if (session.pendingToolBatch() != null && session.pendingToolBatch().isInFlight()) {
            output.println("存在执行结果未确认的工具。请退出，使用 --resume-failed-session 和 --retry-incomplete-tool true 重新核验恢复；终端不自动重放。"); return;
        }
        if (session.status() == AgentSession.Status.FAILED && (session.failure() == null || !session.failure().canResume())) {
            output.println("失败边界不能直接恢复，请保留现场并检查日志。"); return;
        }
        execute(session, () -> {
            var runtime = runtimeFactory.apply(maxModelCalls);
            if (session.status() == AgentSession.Status.FAILED) runtime.resumeFailed(session, false);
            else runtime.resume(session);
        });
    }

    private void execute(AgentSession session, Runnable action) {
        int from = session.events().size();
        output.println("Agent> 正在处理，请稍候……"); output.flush();
        action.run(); // Persistence failures remain fatal; they must not become terminal commands.
        saveSnapshot.accept(session);
        session.events().subList(from, session.events().size()).stream()
                .filter(event -> event.type() == AgentSession.EventType.MODEL_CALL_FAILED)
                .forEach(event -> output.println("模型请求失败：" + event.detail()));
        if (session.status() == AgentSession.Status.COMPLETED) output.println("Agent> " + session.answer());
        else {
            session.events().subList(from, session.events().size()).stream()
                    .filter(event -> event.type() == AgentSession.EventType.SESSION_FAILED)
                    .forEach(event -> output.println("失败原因：" + event.detail()));
            output.println("Agent> 本轮停止，尚未完成。检查原因后可使用 /budget 和 /resume。");
        }
        if (synchronizer != null) output.println("回答中的文件修改位于隔离副本；/diff 查看待同步改动，/apply 写回原项目。");
        printStatus(session);
        output.flush();
    }

    private void printStatus(AgentSession session) {
        output.println("会话 " + session.id() + " | 状态 " + session.status()
                + " | 第 " + session.turns().getLast().getTurnId() + " 轮"
                + " | 调用 " + session.modelCalls() + "/" + maxModelCalls
                + " | 工作区版本 " + session.workspaceState().getRevision()
                + " | 测试 " + session.workspaceState().testStatus());
    }
}
