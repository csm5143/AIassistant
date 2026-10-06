package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService.DocSession;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIdentifierCounterTest {
    private DocSession session(String text) {
        var source = new SourceDocumentParser.SourceDocument(text,
                List.of(new SourceDocumentParser.Block(0, text.length(), null, "Records")));
        var locations = new SourceDocumentParser().chunks(source, 100, 20);
        return new DocSession("id", "owner", "records.md", locations.stream().map(SourceDocumentParser.LocatedChunk::content).toList(),
                List.of(), 0, 0, source, locations, "v1");
    }

    @Test void countsDistinctDelimitedRowsAcrossChunksAndKeepsSourceLocations() {
        String text = "# Records\nREC-0001 | first\n" + "background ".repeat(15)
                + "\nREC-0002 | second\nREC-0001 | duplicate\nREC-0003 | third\n";
        var result = DocumentIdentifierCounter.inspect(session(text),
                "这份文档有多少个不同的 REC- 记录编号？按编号去重。");
        assertNotNull(result);
        assertEquals(3, result.count());
        assertEquals(4, result.matchedRows());
        assertEquals(List.of("REC-0001", "REC-0002", "REC-0003"),
                result.idsByChunk().values().stream().flatMap(List::stream).toList());
        assertFalse(result.unverifiable());
    }

    @Test void ignoresMentionsAndDeclinesFilteredOrAmbiguousQuestions() {
        var s = session("Mention REC-0001 in a note.\nREC-0002 | actual row\n");
        assertEquals(1, DocumentIdentifierCounter.inspect(s,
                "有多少个不同的 REC- 记录编号？按编号去重。").count());
        assertNull(DocumentIdentifierCounter.inspect(s, "Alpha 部门有多少个不同的 REC- 记录编号？"));
        assertNull(DocumentIdentifierCounter.inspect(s, "有多少个不同的 REC- 和 INV- 记录编号？"));
    }

    @Test void refusesUnauditableLargeCitationSets() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 301; i++) text.append("REC-").append(String.format("%04d", i)).append(" | row\n");
        var result = DocumentIdentifierCounter.inspect(session(text.toString()),
                "How many distinct REC- record IDs are there?");
        assertNotNull(result);
        assertEquals(301, result.count());
        assertTrue(result.unverifiable());
    }

    @Test void refusesToClaimZeroWithoutRecognizedRecordRows() {
        var result = DocumentIdentifierCounter.inspect(session("A note mentions REC-0001 but there is no row.\n"),
                "有多少个不同的 REC- 记录编号？");
        assertNotNull(result);
        assertTrue(result.unverifiable());
    }
}
