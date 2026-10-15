package dev.backendagent.verification;

import dev.backendagent.runtime.AgentSession;
import dev.backendagent.runtime.WorkspaceState;

public final class CompletionVerifier {
    /** Current-turn writes require validation when the runtime can test; inspection stays optional. */
    public String rejection(AgentSession session){
        int start=session.turns().getLast().getStartHistoryIndex();
        var current = session.history().subList(start, session.history().size());
        boolean attempted = current.stream().anyMatch(e -> e.call().name().equals("run_tests"));
        boolean changed = current.stream().anyMatch(WorkspaceState::isSuccessfulWrite);
        if (!attempted && !(session.testToolAvailable() && changed)) return null;
        if (!attempted) return "本轮已成功写入文件，但尚未运行测试；必须调用run_tests验证当前版本后才能结束。最终回答字数限制不影响工具执行，无需再次请求用户确认。";
        var state=session.workspaceState();
        if(state.testStatus()==WorkspaceState.TestStatus.STALE)return "测试后文件已修改，最近结果已过期；必须重新run_tests。";
        var report=session.latestVerificationTest();
        if(report==null||!report.isSuccessful())return "当前版本测试未通过；先依据契约核对失败断言，区分实现、测试及环境问题，再修改并验证。";
        if(!report.verifiedPassed())return "没有确认实际执行测试；NO_TESTS或UNKNOWN不能作为验证通过。";
        return null;
    }
    public static final class IncompleteVerificationException extends IllegalStateException {
        public IncompleteVerificationException(){super("验证未完成：两次纠正机会后仍请求提前结束；改动已保留，请检查测试或环境阻塞。");}
    }
}
