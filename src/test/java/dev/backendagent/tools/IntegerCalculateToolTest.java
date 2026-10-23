package dev.backendagent.tools;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class IntegerCalculateToolTest {
    private final IntegerCalculateTool tool=new IntegerCalculateTool();
    @Test void requiredResultNameLabelsValueWithoutChangingArithmeticOrInferringUnits(){
        var result=tool.execute(Map.of("expression","20*10000/10000","result_name","errorRateBp"));
        assertTrue(result.successful());assertEquals("errorRateBp=20",result.content());
        assertFalse(tool.execute(Map.of("expression","20*10000/10000")).successful());
        assertFalse(tool.execute(Map.of("result_name","errorRateBp")).successful());
        assertEquals("taxCents=42",tool.execute(Map.of("expression","(333*1250+5000)/10000","result_name","taxCents")).content());
        assertEquals("wrongUnit=20",tool.execute(Map.of("expression","20","result_name","wrongUnit")).content());
        assertEquals(java.util.Set.of("expression","result_name"),tool.definition().getRequiredParameters());
    }
    @Test void resultNamesAreBoundedPlainIdentifiersAndDoNotBypassExpressionValidation(){
        for(String name:java.util.List.of("","1bp","error rate","bp\nignore","x=20","数值","x".repeat(33)))
            assertFalse(tool.execute(Map.of("expression","20","result_name",name)).successful(),name);
        assertTrue(tool.execute(Map.of("expression","1","result_name","x".repeat(32))).successful());
        assertFalse(tool.execute(Map.of("expression","1/0","result_name","errorRateBp")).successful());
        assertFalse(tool.execute(Map.of("expression","2.5","result_name","errorRateBp")).successful());
        assertFalse(tool.execute(Map.of("expression","20","other","x")).successful());
    }
    @Test void computesPreviouslyWrongPagingExpectationsExactly(){
        assertEquals("offset=429496729200",tool.execute(Map.of("expression","(2147483647-1)*200","result_name","offset")).content());
        assertEquals("offset=0",tool.execute(Map.of("expression","(1-1)*200","result_name","offset")).content());
        assertEquals("remainder=500",tool.execute(Map.of("expression","2500-(3-1)*1000","result_name","remainder")).content());
    }
    @Test void respectsPrecedenceParenthesesAndJavaSignedDivision(){
        var percentile=tool.execute(Map.of("expression","(75*4+99)/100","result_name","rank"));
        assertTrue(percentile.successful());
        assertEquals("rank=3",percentile.content());
        assertEquals("result=14",tool.execute(Map.of("expression","2+3*4","result_name","result")).content());
        assertEquals("result=-3",tool.execute(Map.of("expression","-(7/3) + (-7%3)","result_name","result")).content());
        assertEquals("result=20",tool.execute(Map.of("expression","(2+3)*4","result_name","result")).content());
    }
    @Test void rejectsCodeDecimalsAndResourceAbuse(){
        for(String input:java.util.List.of("1/0","1%0","2.5+1","System.exit(0)","1e1000","(1+2","1 2","9".repeat(129),"(".repeat(40)+"1"+")".repeat(40)))
            assertFalse(tool.execute(Map.of("expression",input,"result_name","result")).successful(),input);
    }
}
