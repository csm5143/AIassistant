package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DocumentCalculationVerifierTest {
    private final Map<Integer, String> evidence = Map.of(
            1, "| ID | Amount | Tax |\n| --- | --- | --- |\n| INV-A1 | 100.00 | 10.00 |\n| INV-A2 | 10.00 | 100.00 |",
            2, "| ID | Amount | Tax |\n| --- | --- | --- |\n| INV-A3 | -10.00 | 100.00 |",
            3, "INV-A4 continuation: Amount = 321.09 CNY. This is one invoice, not two.");
    private String binding(String key, int source, String id, String field, String value) {
        return "{\"key\":\"" + key + "\",\"sourceIndex\":" + source + ",\"recordId\":\"" + id
                + "\",\"field\":\"" + field + "\",\"value\":\"" + value + "\"}";
    }
    @Test void bindsRepeatedValuesToCorrectRowsAndColumnsAndPreservesNegativeSign() {
        var result = DocumentCalculationVerifier.calculate("v1+v2", "[" + binding("v1",1,"INV-A1","Tax","10.00")
                + "," + binding("v2",2,"INV-A3","Amount","-10.00") + "]", evidence);
        assertEquals("0", result.value());
        assertEquals(2, result.records().size());
        assertEquals(java.util.Set.of(1,2), result.indices());
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",1,"INV-A1","Amount","10.00") + "]", evidence));
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",2,"INV-A3","Amount","10.00") + "]", evidence));
    }
    @Test void handlesIntegerYearColumnsAndParenthesizedNegativesWithoutMatchingNoteColumn() {
        var table = Map.of(4, "| Description | Note | 2024/25 £000 | 2023/24 £000 |\n| --- | --- | --- | --- |\n| Income | 5 | (21,190) | (25,496) |");
        assertEquals("-21190", DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",4,"Income","2024/25","-21190") + "]", table).value());
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",4,"Income","2024/25","5") + "]", table));
    }
    @Test void verifiesCrossPageNamedContinuationAndDeclinesAmbiguousOrMissingEvidence() {
        assertEquals("321.09", DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",3,"INV-A4","Amount","321.09") + "]", evidence).value());
        var ambiguous = Map.of(1, evidence.get(1) + "\n| INV-A1 | 50.00 | 10.00 |");
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",1,"INV-A1","Amount","100.00") + "]", ambiguous));
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("100+10", "[]", evidence));
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1+100",
                "[" + binding("v1",1,"INV-A1","Amount","100.00") + "]", evidence));
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1+v2",
                "[" + binding("v1",1,"INV-A1","Amount","100.00") + "," + binding("v2",1,"INV-A1","Amount","100.00") + "]", evidence));
        assertEquals("1", DocumentCalculationVerifier.calculate("v1/100",
                "[" + binding("v1",1,"INV-A1","Amount","100.00") + "]", evidence).value());
    }
    @Test void neverBorrowsAHeaderFromAnotherTableOrTreatsAFieldPrefixAsAnExactField() {
        String table = "| ID | Amount |\n| --- | --- |\n| INV-A1 | 100.00 |\n"
                + "| ID | Tax |\n| --- | --- |\n| INV-A2 | 100.00 |";
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",1,"INV-A2","Amount","100.00") + "]", Map.of(1,table)));
        assertThrows(IllegalArgumentException.class, () -> DocumentCalculationVerifier.calculate("v1",
                "[" + binding("v1",1,"INV-A2","Amount","100.00") + "]",
                Map.of(1,"| ID | Amount Tax |\n| --- | --- |\n| INV-A2 | 100.00 |")));
    }
}
