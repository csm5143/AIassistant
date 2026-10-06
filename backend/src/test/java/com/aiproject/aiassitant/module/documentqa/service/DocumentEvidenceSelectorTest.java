package com.aiproject.aiassitant.module.documentqa.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService.*;

class DocumentEvidenceSelectorTest {
    @Test void smallDocumentProvidesEveryChunkInPhysicalOrder() {
        var s=new DocSession("id","owner","ledger.pdf",List.of("header","rows","continuation","final records"),List.of());
        assertTrue(DocumentEvidenceSelector.useComplete(s));
        assertEquals(List.of(1,2,3,4),DocumentEvidenceSelector.complete(s).stream().map(SearchHit::chunkIndex).toList());
    }
    @Test void exactIdAddsBothSidesOfCrossPageContinuation() {
        var s=new DocSession("id","owner","large.pdf",List.of("header","INV-A104 original row","continuation amount 321.09","unrelated text"),List.of());
        assertEquals(List.of(1,2,3,4),DocumentEvidenceSelector.expand(s,List.of(new SearchHit("unrelated text",.8,4)),"What is INV-A104?").stream().map(SearchHit::chunkIndex).toList());
    }
    @Test void largeEvidenceRemainsBounded() {
        var s=new DocSession("id","owner","large.pdf",List.of("INV-A104"+"a".repeat(6500),"b".repeat(6500),"c".repeat(6500)),List.of());
        assertFalse(DocumentEvidenceSelector.useComplete(s));
        assertTrue(DocumentEvidenceSelector.expand(s,List.of(),"INV-A104").stream().mapToInt(h->h.text().length()).sum()<=12000);
    }
}
