package com.aiproject.aiassitant.module.ai.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DecimalCalculatorTest {
    @Test void exactFastPathDoesNotSilentlyRoundWhileExistingToolDivisionAndErrorFeedbackRemainUsable(){
        assertEquals("2.5",DecimalCalculator.calculateExact("10/4"));
        assertThrows(ArithmeticException.class,()->DecimalCalculator.calculateExact("1/3*3"));
        assertEquals("0.3333333333333333",DecimalCalculator.calculate("1/3"));
        assertEquals("Division by zero",assertThrows(ArithmeticException.class,()->DecimalCalculator.calculate("1/0")).getMessage());
    }
    @Test void decimalsSignsPrecedenceAndParenthesesAreExact() {
        assertEquals("1666.55",DecimalCalculator.calculate("1234.56+(-89.10)+321.09+0.00+200.00"));
        assertEquals("0.3",DecimalCalculator.calculate("0.1+0.2"));
        assertEquals("30",DecimalCalculator.calculate("(0.1+0.2)*100"));
        assertEquals("264.95",DecimalCalculator.calculate("250+19.95-5"));
        assertEquals("2.5",DecimalCalculator.calculate("10/4"));
    }
    @Test void invalidAndUnsafeExpressionsAreRejected() {
        for(String input:new String[]{"","1,234.56","USD 10","1+","1..2","1 2","1+(2","1/0","(".repeat(65)+"1"+")".repeat(65)})assertThrows(RuntimeException.class,()->DecimalCalculator.calculate(input));
    }
}
