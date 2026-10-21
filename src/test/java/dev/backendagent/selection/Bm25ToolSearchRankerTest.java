package dev.backendagent.selection;

import java.util.*;
import org.junit.jupiter.api.Test;
import dev.backendagent.tools.ToolDefinition;
import static org.junit.jupiter.api.Assertions.*;

class Bm25ToolSearchRankerTest {
    @Test void scoreMatchesBm25Formula() {
        var catalog=List.of(new ToolDefinition("alpha","needle needle",Map.of()),new ToolDefinition("beta","other",Map.of()));
        var matches=new Bm25ToolSearchRanker().rank("needle",catalog,false);
        double expected=Math.log(2)*2*2.2/(2+1.2*(0.25+0.75*3/2.5));
        assertEquals(1,matches.size()); assertEquals(expected,matches.getFirst().getScore(),1e-12);
    }
    @Test void longerDescriptionsAreNormalizedAndRareTermsRemainUseful() {
        var shortTool=new ToolDefinition("alpha","needle",Map.of());
        var longTool=new ToolDefinition("beta","needle "+"filler ".repeat(100),Map.of());
        assertEquals("alpha",new Bm25ToolSearchRanker().rank("needle",List.of(longTool,shortTool),false).getFirst().getTool().getName());
        var rare=new ToolDefinition("rare_tool","common rare",Map.of());
        var frequent=new ToolDefinition("other_tool","common ".repeat(20),Map.of());
        assertEquals("rare_tool",new Bm25ToolSearchRanker().rank("common rare",List.of(frequent,rare),false).getFirst().getTool().getName());
    }
    @Test void unknownTermsDoNotFillResultsAndEmptyCatalogIsSupported() {
        var catalog=List.of(new ToolDefinition("alpha","读取文件",Map.of()));
        assertTrue(new Bm25ToolSearchRanker().rank("quasar",catalog,false).isEmpty());
        assertTrue(new Bm25ToolSearchRanker().rank("query",List.of(),false).isEmpty());
    }
    @Test void registeredNameBeatsGenericDescriptions() {
        var catalog=List.of(new ToolDefinition("search_code","搜索代码",Map.of()),new ToolDefinition("search_history","搜索代码搜索代码搜索代码",Map.of()));
        assertEquals("search_code",new Bm25ToolSearchRanker().rank("需要 search_code",catalog,true).getFirst().getTool().getName());
    }
    @Test void parameterTermsOnlyParticipateInExpandedSearchAndOrderIsDeterministic() {
        var a=new ToolDefinition("alpha","能力",Map.of("path","文件路径","query","方法名称"));
        var b=new ToolDefinition("alpha","能力",new LinkedHashMap<>(Map.of("query","方法名称","path","文件路径")));
        var ranker=new Bm25ToolSearchRanker();
        assertTrue(ranker.rank("方法名称",List.of(a),false).isEmpty());
        assertFalse(ranker.rank("方法名称",List.of(a),true).isEmpty());
        assertEquals(ranker.rank("方法名称",List.of(a),true).getFirst().getScore(),ranker.rank("方法名称",List.of(b),true).getFirst().getScore());
    }
}
