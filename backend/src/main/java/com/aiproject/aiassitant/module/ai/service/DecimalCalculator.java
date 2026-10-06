package com.aiproject.aiassitant.module.ai.service;
import java.math.*;
/** Decimal arithmetic for document statistics. Values still need to be extracted from evidence. */
public final class DecimalCalculator {
    private DecimalCalculator() {}
    public static String calculate(String expression) {
        return calculate(expression,false);
    }
    public static String calculateExact(String expression) {
        return calculate(expression,true);
    }
    public static final class ZeroDivisor extends ArithmeticException {
        public ZeroDivisor(){super("Division by zero");}
    }
    private static String calculate(String expression,boolean exact) {
        if(expression==null||expression.length()>2048||expression.matches("(?s).*\\d\\s+\\d.*")||!expression.matches("[0-9. +*/()\\-]+"))throw new IllegalArgumentException("仅支持数字、加减乘除和括号，不带单位或千分位分隔符");
        Parser parser=new Parser(expression.replaceAll("\\s+",""),exact);BigDecimal value=parser.sum();
        if(parser.position!=parser.text.length())throw new IllegalArgumentException("表达式未完整解析");
        return value.stripTrailingZeros().toPlainString();
    }
    private static final class Parser {
        final String text;final boolean exact;int position,depth;
        Parser(String text,boolean exact){this.text=text;this.exact=exact;}
        boolean take(char c){if(position<text.length()&&text.charAt(position)==c){position++;return true;}return false;}
        BigDecimal sum(){BigDecimal value=product();while(position<text.length()){if(take('+'))value=value.add(product());else if(take('-'))value=value.subtract(product());else break;}return value;}
        BigDecimal product(){BigDecimal value=atom();while(position<text.length()){if(take('*'))value=value.multiply(atom());else if(take('/')){BigDecimal divisor=atom();if(divisor.signum()==0)throw new ZeroDivisor();value=exact?value.divide(divisor):value.divide(divisor,16,RoundingMode.HALF_UP);}else break;}return value;}
        BigDecimal atom(){
            if(++depth>64)throw new IllegalArgumentException("表达式嵌套过深");
            try {
                if(take('+'))return atom();if(take('-'))return atom().negate();
                if(take('(')){BigDecimal value=sum();if(!take(')'))throw new IllegalArgumentException("括号不匹配");return value;}
                int start=position;while(position<text.length()&&(Character.isDigit(text.charAt(position))||text.charAt(position)=='.'))position++;
                if(start==position)throw new IllegalArgumentException("缺少数字");return new BigDecimal(text.substring(start,position));
            }finally{depth--;}
        }
    }
}
