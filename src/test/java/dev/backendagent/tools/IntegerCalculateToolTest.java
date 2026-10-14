package dev.backendagent.tools;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IntegerCalculateToolTest {
    private final IntegerCalculateTool tool=new IntegerCalculateTool();
    @Test void computesPreviouslyWrongPagingExpectationsExactly(){
        assertTrue(tool.execute(Map.of("expression","(2147483647-1)*200")).content().contains("value=429496729200"));
        assertTrue(tool.execute(Map.of("expression","(1-1)*200")).content().contains("value=0"));
        assertTrue(tool.execute(Map.of("expression","2500-(3-1)*1000")).content().contains("value=500"));
    }
    @Test void respectsPrecedenceParenthesesAndJavaSignedDivision(){
        assertTrue(tool.execute(Map.of("expression","2+3*4")).content().contains("value=14"));
        assertTrue(tool.execute(Map.of("expression","-(7/3) + (-7%3)")).content().contains("value=-3"));
        assertTrue(tool.execute(Map.of("expression","(2+3)*4")).content().contains("value=20"));
    }
    @Test void rejectsCodeDecimalsAndResourceAbuse(){
        for(String input:java.util.List.of("1/0","1%0","2.5+1","System.exit(0)","1e1000","(1+2","1 2","9".repeat(129),"(".repeat(40)+"1"+")".repeat(40)))
            assertFalse(tool.execute(Map.of("expression",input)).successful(),input);
    }
}
