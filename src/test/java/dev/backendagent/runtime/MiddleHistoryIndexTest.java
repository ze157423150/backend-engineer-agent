package dev.backendagent.runtime;
import dev.backendagent.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MiddleHistoryIndexTest {
    @Test void excludesAllRecentFourBatchesEvenWhenTheirBodiesWereBudgetTrimmed() {
        var history=new ArrayList<ToolExchange>();
        for(int i=0;i<20;i++)history.add(new ToolExchange(new ToolCall("c"+i,"read_file",Map.of("path","File"+i+".java")),new ToolResult(true,"x".repeat(5000)),null,i+1));
        var assembler=new ContextAssembler(new ContextBudget(8000));
        var view=assembler.project(history,7,0);
        assertTrue(view.getOmittedExchanges()>16);
        String refs=view.getRecoveryReferences();
        assertTrue(refs.contains("call_id=c15,"));
        for(int i=16;i<20;i++)assertFalse(refs.contains("call_id=c"+i+","));
        assertFalse(refs.contains("successful=true"));assertFalse(refs.contains("summaryState="));
        var ids=java.util.regex.Pattern.compile("call_id=([^,]+),").matcher(refs);var unique=new HashSet<String>();
        while(ids.find())assertTrue(unique.add(ids.group(1)),"Duplicate call ID in index");
        assertTrue(refs.length()<=2000);view.validateAgainst(history);
        var legacy=assembler.project(history,6,0);assertTrue(legacy.getRecoveryReferences().contains("call_id=c18,"));legacy.validateAgainst(history);
    }
}
