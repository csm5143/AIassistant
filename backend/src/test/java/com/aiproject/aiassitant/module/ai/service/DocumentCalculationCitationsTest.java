package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentCalculationCitationsTest {
    @Test void locatesDecimalOperandsWithoutInventingCitationsForAmbiguousNumbers() {
        var evidence=Map.of(2,"INV-A101 1234.56; INV-A102 -89.10",5,"INV-A104 321.09",6,"INV-A105 0.00",8,"INV-A109 200.00");
        assertEquals(Set.of(2,5,6,8),DocumentCalculationCitations.locate("1234.56-89.10+321.09+0.00+200.00",evidence));
        assertEquals(Set.of(),DocumentCalculationCitations.locate("10.00",Map.of(1,"10.00",2,"10.00")));
        assertEquals(Set.of(),DocumentCalculationCitations.locate("3.00",Map.of(1,"INV-A3.00, version3.00; date 2026-03.00")));
        assertEquals(Set.of(1),DocumentCalculationCitations.locate("1008.35",Map.of(1,"USD 1,008.35")));
    }
    @Test void onlySupplementsMissingNumericLocationsAndKeepsResponseLanguage() {
        assertEquals("\n\n数值出处：[2][8]",DocumentCalculationCitations.missing("合计1666.55[5][6]",new TreeSet<>(Set.of(2,5,6,8)),false));
        assertEquals("",DocumentCalculationCitations.missing("Total[2][8]",Set.of(2,8),true));
        assertEquals("\n\nNumeric sources: [8]",DocumentCalculationCitations.missing("Total",Set.of(8),true));
    }
}
