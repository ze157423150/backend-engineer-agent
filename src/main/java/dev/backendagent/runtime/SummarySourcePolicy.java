package dev.backendagent.runtime;

import dev.backendagent.model.ToolExchange;

/** Retrieval bookkeeping already has dedicated context areas; it is not task state. */
public final class SummarySourcePolicy {
    private SummarySourcePolicy() { }
    public static boolean substantive(ToolExchange exchange) {
        return switch (exchange.call().name()) {
            case "search_history", "read_observation", "search_turns", "read_turn", "pin_verification_basis", "remember_fact" -> false;
            default -> true;
        };
    }
    /** Only the latest test result can describe an unresolved failure of this snapshot. */
    public static int pendingTestFailure(java.util.List<ToolExchange> history, java.util.Set<String> stale) {
        for (int i=history.size()-1;i>=0;i--) {
            var entry=history.get(i);
            if (entry.call().name().equals("run_tests"))
                return !entry.result().successful()&&!stale.contains(entry.call().id()) ? i : -1;
        }
        return -1;
    }
    public static boolean isFailureNote(SummaryNote note,String callId) {
        return callId!=null&&callId.equals(note.getSourceCallId())
                &&(note.getSection()==SummarySection.OPEN_ISSUES||note.getKind()==SummaryNote.Kind.OPEN_ISSUE);
    }
    /** Retire only the literal not-yet-tested issue when a later executed test exists. */
    public static boolean resolvedExecutionIssue(SummaryNote note,java.util.List<ToolExchange> history) {
        if(note.getSection()!=SummarySection.OPEN_ISSUES&&note.getKind()!=SummaryNote.Kind.OPEN_ISSUE)return false;
        String text=note.getStatement();
        if(!(text.contains("尚未")||text.contains("未运行"))||!text.contains("测试"))return false;
        int source=-1;
        for(int i=0;i<history.size();i++)if(history.get(i).call().id().equals(note.getSourceCallId())){source=i;break;}
        if(source<0||!WorkspaceState.isSuccessfulWrite(history.get(source)))return false;
        for(int i=source+1;i<history.size();i++) {
            var e=history.get(i);
            if(e.call().name().equals("run_tests")&&e.result().content().contains("testExecution=EXECUTED"))return true;
        }
        return false;
    }
}
