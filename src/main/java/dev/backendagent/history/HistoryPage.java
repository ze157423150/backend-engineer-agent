package dev.backendagent.history;

import java.util.List;

public final class HistoryPage {
    private final List<HistoryReference> matches;
    private final long nextBeforeSequence;
    private final long workspaceRevision;
    public HistoryPage(List<HistoryReference> matches, long nextBeforeSequence, long workspaceRevision) {
        this.matches = List.copyOf(matches);
        this.nextBeforeSequence = nextBeforeSequence;
        this.workspaceRevision = workspaceRevision;
    }
    public String getKind() { return "historical_observation_index"; }
    public String getNotice() { return "仅为历史索引；CURRENT 是核验时刻的状态，测试 CURRENT 只表示源码版本匹配，不表示通过。UNKNOWN 未确认。"
            + "过期片段已省略；用 callId、matchedLine 调用 read_observation 显式追溯，不能重放工具或证明当前代码。"; }
    public List<HistoryReference> getMatches() { return matches; }
    public long getNextBeforeSequence() { return nextBeforeSequence; }
    public long getWorkspaceRevision() { return workspaceRevision; }
}
