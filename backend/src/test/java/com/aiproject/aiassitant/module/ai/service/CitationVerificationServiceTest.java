package com.aiproject.aiassitant.module.ai.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CitationVerificationServiceTest {
    final ChatModelFactory factory=mock(ChatModelFactory.class);
    final StreamingChatLanguageModel model=mock(StreamingChatLanguageModel.class);
    final CitationVerificationService service=new CitationVerificationService(factory,new ObjectMapper());
    List<Map<String,Object>> sources(){return List.of(Map.of("index",1,"_evidence","最终预算为9100元，旧值8600元已作废。","fileName","演示.md"));}
    void respond(String raw){when(factory.getVerificationStreamingModel(any())).thenReturn(model);doAnswer(call->{call.<StreamingResponseHandler<AiMessage>>getArgument(1).onComplete(Response.from(AiMessage.from(raw),new TokenUsage(200,40)));return null;}).when(model).generate(anyList(),any(StreamingResponseHandler.class));}
    String row(String status,String quote){return "{\"results\":[{\"id\":1,\"status\":\""+status+"\",\"reason\":\"演示\",\"quotes\":[{\"index\":1,\"text\":\""+quote+"\"}]}]}";}
    @Test void supportedQuoteIsLocatedAndInternalEvidenceNeverPersisted(){respond(row("supported","最终预算为9100元"));var r=service.verify("","预算为9100元[1]。",sources(),"m",()->false);assertEquals(1L,r.audit().get("supported"));assertEquals("预算为9100元[1]。",r.answer());assertFalse(r.citations().get(0).containsKey("_evidence"));assertEquals(200,r.usage().promptTokens());assertFalse(r.usage().estimated());}
    @Test void contradictionProducesRefusalPreviewAndRetainsProvenance(){respond(row("contradicted","最终预算为9100元，旧值8600元已作废。"));var r=service.verify("","预算为8600元[1]。",sources(),"m",()->false);assertEquals(1L,r.audit().get("rejected"));assertTrue(r.answer().contains("矛盾"));assertFalse(r.answer().contains("预算为8600"));assertTrue(r.answer().contains("[1]"));}
    @Test void fabricatedQuoteCannotBecomeSupported(){respond(row("supported","最终预算为8600元"));var r=service.verify("","预算为8600元[1]。",sources(),"m",()->false);assertEquals(0L,r.audit().get("supported"));assertEquals(1L,r.audit().get("unchecked"));}
    @Test void duplicateOrOmittedRowsDoNotPartiallyLabelSupported(){respond("{\"results\":[{\"id\":1,\"status\":\"supported\",\"quotes\":[]},{\"id\":1,\"status\":\"supported\",\"quotes\":[]}]}");var r=service.verify("","预算[1]。",sources(),"m",()->false);assertEquals(1L,r.audit().get("unchecked"));assertEquals(200,r.usage().promptTokens());}
    @Test void modelErrorIsUnverifiedAndUsageExplicitlyEstimated(){when(factory.getVerificationStreamingModel(any())).thenReturn(model);doAnswer(call->{call.<StreamingResponseHandler<AiMessage>>getArgument(1).onError(new RuntimeException("unavailable"));return null;}).when(model).generate(anyList(),any(StreamingResponseHandler.class));var r=service.verify("","预算[1]。",sources(),"m",()->false);assertEquals(1L,r.audit().get("unchecked"));assertEquals(1,r.usage().modelRequests());assertTrue(r.usage().estimated());}
    @Test void noCitationOrOnlyCodeHasNoModelCall(){for(String a:List.of("日期2026-10-02。","[1]","{\"x\":[1]}","```text\n[1]\n```","`[1]`，链接[1](https://example.com)。"))assertEquals(0,service.verify("",a,sources(),"m",()->false).usage().modelRequests());verifyNoInteractions(factory,model);}
    @Test void missingOrOversizedEvidenceIsNotTruncatedAndPassed(){for(var s:List.of(List.<Map<String,Object>>of(),List.<Map<String,Object>>of(Map.of("index",1,"_evidence","a".repeat(8001)))))assertEquals(1L,service.verify("","结论[1]。",s,"m",()->false).audit().get("unchecked"));verifyNoInteractions(factory);}
    @Test void adjacentReferencesAndCodeOffsetsArePreserved(){var c=CitationVerificationService.claims("```text\n[1]\n```\n预算[4][6]。\n```\n[4]\n```");assertEquals(1,c.size());assertEquals(List.of(4,6),c.get(0).refs);assertEquals("预算[4][6]。",c.get(0).text);}
    @Test void everyCitedSourceNeedsALocatableQuote(){respond(row("supported","最终预算为9100元"));var s=new ArrayList<>(sources());s.add(Map.of("index",2,"_evidence","无关的资料"));var r=service.verify("","预算[1][2]。",s,"m",()->false);assertEquals(1L,r.audit().get("unchecked"));}
}
