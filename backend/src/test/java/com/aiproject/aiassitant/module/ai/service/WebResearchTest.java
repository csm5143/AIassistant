package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.*;
import dev.langchain4j.agent.tool.*;
import com.aiproject.aiassitant.module.guard.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebResearchTest {
    ExaSearchService search; ResearchPageReader reader; WebResearchService service;
    final String raw="Title: PostgreSQL official documentation\nURL: https://www.postgresql.org/docs/current/index.html\nPublished Date: 2026-09-01\nText: PostgreSQL supports transactions and indexing. This is a sufficiently long search excerpt.\n";
    @BeforeEach void setup() throws Exception {
        search=mock(ExaSearchService.class);reader=mock(ResearchPageReader.class);
        service=new WebResearchService(search,reader,new ObjectMapper());
        when(search.searchEvidence(anyString(),anyInt())).thenReturn(raw);
        when(reader.read(anyString())).thenReturn(new ResearchPageReader.Page("https://www.postgresql.org/docs/current/index.html","Official PostgreSQL","Verified PostgreSQL documentation text describing transactions, indexes and SQL commands."));
    }
    @AfterEach void cleanup(){service.close();}
    WebResearchService.Turn turn(){return new WebResearchService.Turn(new ResearchPolicy.Decision(true,true,"test"));}
    @Test void localAndWebShareNumberingAndRepeatedUrlsAreUpdatedRatherThanDuplicated(){
        var sources=new ArrayList<Map<String,Object>>();sources.add(new LinkedHashMap<>(Map.of("index",1,"chunkId","local-1","sourceType","knowledge")));
        var turn=turn();String result=service.search("PostgreSQL transactions",turn,sources);
        assertTrue(result.contains("[2]"));assertEquals(2,sources.size());assertEquals("web",sources.get(1).get("sourceType"));assertEquals("page_text",sources.get(1).get("evidenceKind"));
        service.search("PostgreSQL indexes",turn,sources);assertEquals(2,sources.size());
        assertTrue(service.search("third question",turn,sources).contains("上限"));verify(search,times(2)).searchEvidence(anyString(),anyInt());
    }
    @Test void cacheSharesPublicEvidenceButNeverSharesCitationNumbersAcrossChats(){
        var first=turn();var a=new ArrayList<Map<String,Object>>();service.search("PostgreSQL",first,a);
        var second=turn();var b=new ArrayList<Map<String,Object>>();b.add(new LinkedHashMap<>(Map.of("index",7,"sourceType","knowledge")));
        service.search("PostgreSQL",second,b);assertEquals(8,b.get(1).get("index"));assertEquals(1,second.cacheHits);verify(search,times(1)).searchEvidence(anyString(),anyInt());
    }
    @Test void failedReadIsExplicitlyMarkedAsSearchExcerpt() throws Exception {
        when(reader.read(anyString())).thenThrow(new java.io.IOException("timeout"));
        var sources=new ArrayList<Map<String,Object>>();assertTrue(service.search("PostgreSQL",turn(),sources).contains("未独立读取正文"));
        assertEquals("search_excerpt",sources.get(0).get("evidenceKind"));
    }
    @Test void unavailableSearchHasNoFabricatedCitationAndFailureIsNotCached(){
        when(search.searchEvidence(anyString(),anyInt())).thenReturn("DIRECT_FAIL:unavailable");
        var sources=new ArrayList<Map<String,Object>>();var state=turn();assertTrue(service.search("PostgreSQL",state,sources).contains("无法确认"));assertTrue(sources.isEmpty());assertTrue(state.failed);
        when(search.searchEvidence(anyString(),anyInt())).thenReturn(raw);service.search("PostgreSQL",turn(),sources);assertEquals(1,sources.size());
    }
    @Test void providerExceptionDegradesWithoutLosingLocalAnswer(){
        when(search.searchEvidence(anyString(),anyInt())).thenThrow(new IllegalArgumentException("Invalid provider URL"));
        var sources=new ArrayList<Map<String,Object>>();sources.add(new LinkedHashMap<>(Map.of("index",1,"sourceType","knowledge")));
        var state=turn();assertTrue(service.search("PostgreSQL",state,sources).contains("保留本地"));assertEquals(1,sources.size());assertTrue(state.failed);
    }
    @Test void restrictionsAreEnforcedBeforeAnyNetworkCall() throws Exception {
        var denied=new WebResearchService.Turn(new ResearchPolicy.Decision(false,false,"local"));var sources=new ArrayList<Map<String,Object>>();
        service.search("PostgreSQL",denied,sources);service.read("https://www.postgresql.org",denied,sources);
        service.search("api key sk-abcdefghijklmnop",turn(),sources);service.search("联系 test@example.com",turn(),sources);
        service.read("http://127.0.0.1/admin",turn(),sources);service.read("https://unrequested.example.org/",turn(),sources);
        verifyNoInteractions(search,reader);assertTrue(sources.isEmpty());
    }
    @Test void mixedResearchSearchesOnlyExternalQuestionAndStillRejectsSecretsAnywhere(){
        String report="请完成专题研究报告。\n研究问题：说明 BGE-M3 的三种检索方式，再联网查阅 PostgreSQL 官方文档确认默认事务隔离级别。将论文事实与网页事实明确区分。\n重点比较维度：论文方法、外部补充";
        assertEquals("再联网查阅 PostgreSQL 官方文档确认默认事务隔离级别",WebResearchService.safeQuery(report));
        service.search(report,turn(),new ArrayList<>());
        verify(search).searchEvidence("再联网查阅 PostgreSQL 官方文档确认默认事务隔离级别 site:postgresql.org",4);
        assertEquals("",WebResearchService.safeQuery(report+"\n密钥 sk-abcdefghijklmnop"));
    }
    @Test void parserHandlesJsonAndUnsafeSources(){
        assertEquals(1,service.parse(raw.replace("Text:","Highlights:").replace("Published Date: 2026-09-01","Published: N/A")).size());
        assertEquals(1,service.parse("{\"results\":[{\"title\":\"Official\",\"url\":\"https://www.postgresql.org/docs/\",\"text\":\"This is enough evidence to cite the official SQL documentation accurately.\"}]}\n\n【来源：Exa 语义搜索】").size());
        assertTrue(service.parse(raw.replace("https://www.postgresql.org/docs/current/index.html","javascript:alert(1)")).isEmpty());
        assertNull(WebResearchService.publicUrl("http://localhost/admin"));assertNull(WebResearchService.publicUrl("http://192.168.1.1/config"));
    }
    @Test void mcpSseTransportAndSearchBodyBothParse() throws Exception {
        var exa=new ExaSearchService(new ObjectMapper());
        String json="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}]}}";
        assertEquals("OK",exa.parseTransport("event: message\ndata: "+json+"\n\n").at("/result/content/0/text").asText());
        assertEquals(1,exa.parseTransport(json).path("id").asInt());
    }
    @Test void sufficientLocalEvidenceFinishesWithoutSearch() throws Exception {
        var model=mock(StreamingChatLanguageModel.class);var guard=mock(GuardrailService.class);
        when(guard.newOutputFilter()).thenAnswer(x->new StreamingKeywordMasker(List.of(),"***"));
        doAnswer(call->{StreamingResponseHandler<AiMessage> h=call.getArgument(2);h.onNext("本地答案[1]");h.onComplete(Response.from(AiMessage.from("本地答案[1]"),new TokenUsage(12,6)));return null;}).when(model).generate(anyList(),anyList(),any());
        var loop=new DocumentResearchLoop(service,guard,new ObjectMapper());var state=turn();
        var response=loop.run(model,new ArrayList<>(List.of(UserMessage.from("参数是多少"))),state,new ArrayList<>(),(t,d)->{},()->false);
        assertEquals("本地答案[1]",response.content().text());assertEquals(0,state.searches);verifyNoInteractions(search,reader);
    }
    @Test void partialLocalEvidenceTriggersSearchAndTokensAreAccountedAcrossIterations() throws Exception {
        var model=mock(StreamingChatLanguageModel.class);var guard=mock(GuardrailService.class);when(guard.newOutputFilter()).thenAnswer(x->new StreamingKeywordMasker(List.of(),"***"));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{StreamingResponseHandler<AiMessage> h=call.getArgument(2);
            if(count.getAndIncrement()==0)h.onComplete(Response.from(AiMessage.from(List.of(ToolExecutionRequest.builder().id("s1").name("webSearch").arguments("{\"query\":\"PostgreSQL transactions\"}").build())),new TokenUsage(10,3)));
            else{h.onNext("本地部分[1]，联网补充[2]");h.onComplete(Response.from(AiMessage.from("本地部分[1]，联网补充[2]"),new TokenUsage(20,8)));}return null;
        }).when(model).generate(anyList(),anyList(),any());
        var sources=new ArrayList<Map<String,Object>>();sources.add(new LinkedHashMap<>(Map.of("index",1,"sourceType","knowledge")));
        var state=turn();var response=new DocumentResearchLoop(service,guard,new ObjectMapper()).run(model,new ArrayList<>(List.of(UserMessage.from("补充第二个问题"))),state,sources,(t,d)->{},()->false);
        assertEquals(1,state.searches);assertEquals(2,sources.size());assertEquals(30,response.tokenUsage().inputTokenCount());assertEquals(11,response.tokenUsage().outputTokenCount());
    }
}
