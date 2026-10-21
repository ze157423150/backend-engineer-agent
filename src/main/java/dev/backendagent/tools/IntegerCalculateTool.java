package dev.backendagent.tools;

import java.math.BigInteger;
import java.util.Map;
import dev.backendagent.model.ToolResult;

/** Deterministic bounded integer grammar; no scripting engine, names, files or commands. */
public final class IntegerCalculateTool implements Tool {
    public String name(){return "integer_calculate";}
    public ToolDefinition definition(){return new ToolDefinition(name(),
            "精确计算整数表达式，支持+、-、*、/、%、括号与一元正负号；除法向零截断（Java整数语义）。"
                    + "无变量、浮点数或代码执行；先依据契约确定公式，用它核对测试期望值。",
            Map.of("expression","最多512字符的十进制整数表达式，例如(2147483647-1)*200"));}
    public ToolResult execute(Map<String,String> args){
        if(!args.keySet().equals(java.util.Set.of("expression")))return new ToolResult(false,"必须仅提供expression");
        String expression=args.get("expression");
        if(expression==null||expression.isBlank()||expression.length()>512)return new ToolResult(false,"表达式必须非空，最多512字符");
        try{return new ToolResult(true,"value="+calculate(expression));}
        catch(IllegalArgumentException|ArithmeticException invalid){return new ToolResult(false,"仅支持有界十进制整数及+-*/%括号；检查语法、除零或长度限制，不执行代码");}
    }
    public static BigInteger calculate(String expression) {
        if(expression==null||expression.isBlank()||expression.length()>512)throw new IllegalArgumentException("Invalid expression length");
        return new Parser(expression).parse();
    }
    private static final class Parser {
        private final String input;private int position,depth,operations;
        Parser(String input){this.input=input;}
        BigInteger parse(){BigInteger value=expression();spaces();if(position!=input.length())throw new IllegalArgumentException();return value;}
        void spaces(){while(position<input.length()&&Character.isWhitespace(input.charAt(position)))position++;}
        boolean take(char c){spaces();if(position<input.length()&&input.charAt(position)==c){position++;return true;}return false;}
        BigInteger expression(){BigInteger n=term();while(true){if(take('+'))n=checked(n.add(term()));else if(take('-'))n=checked(n.subtract(term()));else return n;}}
        BigInteger term(){BigInteger n=factor();while(true){if(take('*'))n=checked(n.multiply(factor()));else if(take('/'))n=checked(n.divide(factor()));else if(take('%'))n=checked(n.remainder(factor()));else return n;}}
        BigInteger factor(){
            if(++depth>32)throw new IllegalArgumentException();
            try{
                if(take('+'))return factor();if(take('-'))return checked(factor().negate());
                if(take('(')){BigInteger n=expression();if(!take(')'))throw new IllegalArgumentException();return n;}
                spaces();int start=position;
                while(position<input.length()&&input.charAt(position)>='0'&&input.charAt(position)<='9')position++;
                if(position==start||position-start>128)throw new IllegalArgumentException();
                return new BigInteger(input.substring(start,position));
            }finally{depth--;}
        }
        BigInteger checked(BigInteger n){if(++operations>128||n.toString().length()>512)throw new IllegalArgumentException();return n;}
    }
}
