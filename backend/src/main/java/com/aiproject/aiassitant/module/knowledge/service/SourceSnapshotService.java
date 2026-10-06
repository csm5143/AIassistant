package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.knowledge.entity.KbDocumentSource;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentSourceMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SourceSnapshotService {
    private final KbDocumentSourceMapper mapper;
    private final ObjectMapper json;

    @Transactional
    public void save(String documentId, SourceDocumentParser.SourceDocument source) throws Exception {
        KbDocumentSource row = new KbDocumentSource();
        row.setDocumentId(documentId);
        row.setContent(source.text());
        row.setBlocksJson(json.writeValueAsString(source.blocks()));
        mapper.deleteById(documentId);
        mapper.insert(row);
    }

    public SourceDocumentParser.SourceDocument load(String documentId) {
        KbDocumentSource row = mapper.selectById(documentId);
        if (row == null) return null;
        try {
            return new SourceDocumentParser.SourceDocument(row.getContent(), json.readValue(row.getBlocksJson(),
                    new TypeReference<List<SourceDocumentParser.Block>>() {}));
        } catch (Exception e) { throw new IllegalStateException("原文快照损坏", e); }
    }
}
