package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CitationRegistryTest {
    @Test void preservesIndicesAcrossInitialRecallAndRepeatedToolSearches() {
        List<Map<String,Object>> citations = new ArrayList<>();
        var first = chunk("first", "d1", 2);
        var second = chunk("second", "d2", 4);
        assertEquals(1, CitationRegistry.register(citations, first, "甲.pdf").get("index"));
        assertEquals(2, CitationRegistry.register(citations, second, "乙.md").get("index"));
        assertEquals(1, CitationRegistry.register(citations, first, "甲.pdf").get("index"));
        assertEquals(2, citations.size());
        assertEquals("d2", citations.get(1).get("documentId"));
        assertEquals(4, citations.get(1).get("ordinal"));
        assertEquals(2, citations.get(0).get("page"));
    }
    private KbChunk chunk(String id, String document, int ordinal) {
        var chunk = new KbChunk(); chunk.setId(id); chunk.setDocumentId(document);
        chunk.setOrdinal(ordinal); chunk.setContent("证据原文"); chunk.setSourcePage(2);
        return chunk;
    }
}
