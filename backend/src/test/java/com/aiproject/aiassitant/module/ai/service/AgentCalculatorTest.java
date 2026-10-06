package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentCalculatorTest {
    final AgentTools tools=new AgentTools(null,null,null,null);
    @Test void arithmeticPreservesLargeIntegersAndSmallDecimalAmounts(){
        assertEquals("9007199254740993+2 = 9007199254740995",tools.calculator("9007199254740993+2"));
        assertEquals("999999999999999999999+1 = 1000000000000000000000",tools.calculator("999999999999999999999+1"));
        assertEquals("0.0000001+0.0000002 = 0.0000003",tools.calculator("0.0000001+0.0000002"));
        assertEquals("-(2+3)*4 = -20",tools.calculator("-(2+3)*4"));
    }
    @Test void malformedExpressionsCannotReturnAValidLookingPartialResult(){
        for(String expr:new String[]{"(1+2","2+3junk","1 2+3","2+","sqrt(16)","2+3)","1,000+2"})
            assertTrue(tools.calculator(expr).contains("出错"),expr);
        assertTrue(tools.calculator("1/0").contains("不能为0"));
        assertTrue(tools.calculator("1/3+1/0").contains("不能为0"));
    }
    @Test void nonTerminatingDivisionIsExplicitlyApproximate(){
        assertEquals("1/4 = 0.25",tools.calculator("1/4"));
        assertTrue(tools.calculator("1/3").contains("≈ 0.3333333333333333"));
        assertTrue(tools.calculator("1/3").contains("四舍五入"));
    }
}
