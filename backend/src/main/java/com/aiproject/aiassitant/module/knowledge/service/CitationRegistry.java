package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import java.util.*;

/** One numbering space for initial retrieval and all subsequent tool searches in a reply. */
public final class CitationRegistry {
    private CitationRegistry() {}
    public static Map<String, Object> register(List<Map<String, Object>> citations, KbChunk chunk, String filename) {
        for (var citation : citations) {
            if (chunk.getId().equals(citation.get("chunkId"))) return citation;
        }
        int index = citations.size() + 1;
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("index", index);
        citation.put("documentId", chunk.getDocumentId());
        citation.put("fileName", filename);
        citation.put("chunkId", chunk.getId());
        citation.put("ordinal", chunk.getOrdinal() == null ? index : chunk.getOrdinal());
        citation.put("sourceType", "knowledge");
        citation.put("page", chunk.getSourcePage());
        citation.put("section", chunk.getSourceTitle());
        citation.put("contentSnippet", chunk.getContent().length() > 200 ? chunk.getContent().substring(0, 200) + "..." : chunk.getContent());
        citations.add(citation);
        return citation;
    }
}
