package com.aiproject.aiassitant.module.guard;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingKeywordMaskerTest {
    @Test
    void masksKeywordsSplitAcrossNetworkTokens() {
        var mask = new StreamingKeywordMasker(List.of("秘密", "密码"), "[已过滤]");
        assertEquals("这", mask.append("这"));
        assertEquals("是", mask.append("是秘"));
        assertEquals("[已过滤]和", mask.append("密和密"));
        assertEquals("[已过滤]", mask.append("码"));
        assertEquals("", mask.finish());
    }

    @Test
    void retainsAndReleasesIncompletePrefix() {
        var mask = new StreamingKeywordMasker(List.of("密码"), "*");
        assertEquals("", mask.append("密"));
        assertEquals("密友", mask.append("友"));
        assertEquals("", mask.append("密"));
        assertEquals("密", mask.finish());
    }
}
