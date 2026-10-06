package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.module.chat.entity.*;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.knowledge.entity.*;
import com.aiproject.aiassitant.module.knowledge.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class KnowledgeRoutingTest {
    KnowledgeCatalogService catalogs;HybridSearchService search;KnowledgeIndexVersion version;
    KnowledgeRoutingService router;KbCollectionMapper collections;KbDocumentMapper documents;ChatSessionMapper sessions;
    KnowledgeCatalogService.Catalog catalog;ChatSession session;
    @BeforeAll static void tables(){var a=new MapperBuilderAssistant(new MybatisConfiguration(),"route-test");for(var c:List.of(ChatSession.class,KbCollection.class,KbDocument.class,KbChunk.class))TableInfoHelper.initTableInfo(a,c);}
    @BeforeEach void setup(){
        catalogs=mock(KnowledgeCatalogService.class);search=mock(HybridSearchService.class);version=new KnowledgeIndexVersion();collections=mock(KbCollectionMapper.class);documents=mock(KbDocumentMapper.class);sessions=mock(ChatSessionMapper.class);
        router=new KnowledgeRoutingService(catalogs,search,version,collections,documents,sessions,new ObjectMapper());
        catalog=new KnowledgeCatalogService.Catalog(Map.of("ai","AI教程","cook","菜谱","travel","旅行资料"),List.of(
                doc("a","ai","Hello-Agents.pdf","GSSC Gather Select Structure Compress max_tokens reserve_ratio GRPO Value Model 智能体教程"),
                doc("c","cook","家常菜.md","番茄炒蛋 番茄 鸡蛋 调味 用量 炒菜"),doc("t","travel","行程.txt","旅游 酒店 景点 天气 预算 费用")));
        when(catalogs.get(anyString())).thenReturn(catalog);when(search.lexical(anyString(),anyString(),any(),anyInt(),any())).thenReturn(List.of());
        when(search.searchScoped(anyString(),anyString(),any(),anyInt(),any(),any())).thenReturn(List.of());
        session=new ChatSession();session.setId("session");session.setUserId("owner");session.setKnowledgeMode("AUTO");
    }
    KnowledgeCatalogService.Document doc(String id,String collection,String filename,String text){return new KnowledgeCatalogService.Document(id,collection,filename,"READY",Set.copyOf(RoutingTerms.query(text)),Set.copyOf(RoutingTerms.query(filename+" "+collection)));}
    KbChunk chunk(String id,String collection,String doc,String owner,String content){var c=new KbChunk();c.setId(id);c.setCollectionId(collection);c.setDocumentId(doc);c.setUserId(owner);c.setContent(content);return c;}
    KnowledgeRoutingService.Choice choice(String q){return router.choose(session,q,"",catalog,new RetrievalStats(),"owner");}
    @Test void selectsTechnicalTopicWithoutCallingAnLLM(){var c=choice("GSSC 和 max_tokens 的默认值是多少？");assertEquals(List.of("ai"),c.scope().collectionIds());assertFalse(c.strict());verifyNoInteractions(search);}
    @Test void switchesTopicRatherThanPermanentlyLockingPreviousScope(){session.setKnowledgeRouteJson("{\"collectionIds\":[\"ai\"],\"documentIds\":[\"a\"]}");var c=choice("换个话题，番茄炒蛋需要多少番茄和鸡蛋？");assertEquals(List.of("cook"),c.scope().collectionIds());}
    @Test void followupsKeepScopeAndResolveEllipsis(){session.setKnowledgeRouteJson("{\"collectionIds\":[\"ai\"],\"documentIds\":[\"a\"],\"anchor\":\"GSSC 的四个阶段是什么\"}");var c=choice("第二个阶段举个例子");assertEquals("followup",c.reason());assertEquals(List.of("ai"),c.scope().collectionIds());assertTrue(c.query().contains("GSSC"));}
    @Test void namedPdfIsStrictlyLimitedToThatDocument(){var c=choice("只根据 Hello-Agents.pdf 解释 GSSC");assertTrue(c.strict());assertEquals(List.of("a"),c.scope().documentIds());}
    @Test void missingNamedDocumentNeverFallsBackToAllKnowledge(){var c=choice("根据 missing.pdf 回答问题");assertTrue(c.strict());assertTrue(c.scope().disabled());verifyNoInteractions(search);}
    @Test void duplicateFilenamesRequireDisambiguation(){catalog=new KnowledgeCatalogService.Catalog(catalog.names(),List.of(doc("a","ai","说明.pdf","GSSC"),doc("b","travel","说明.pdf","酒店")));var c=choice("只根据说明.pdf回答");assertEquals("ambiguous",c.reason());assertTrue(c.scope().disabled());}
    @Test void explicitComparisonCanUseMultipleNamedDocuments(){var c=choice("结合 Hello-Agents.pdf 和家常菜.md 对比内容");assertTrue(c.strict());assertEquals(Set.of("a","c"),new HashSet<>(c.scope().documentIds()));}
    @Test void priorDocumentResolvesThisPdfWithoutBroadening(){session.setKnowledgeRouteJson("{\"collectionIds\":[\"ai\"],\"documentIds\":[\"a\"]}");assertEquals(List.of("a"),choice("只根据此 PDF 给出默认值").scope().documentIds());}
    @Test void allModeStillRespectsNaturalLanguageDocumentRestrictions(){session.setKnowledgeMode("ALL");var turn=router.prepare(session,"owner","只根据 Hello-Agents.pdf 回答",List.of());assertTrue(turn.strict);assertEquals(List.of("a"),turn.scope.documentIds());}
    @Test void smalltalkDoesNotReadCatalogOrCallEmbeddingServices(){var turn=router.prepare(session,"owner","你好！",List.of());assertTrue(turn.scope.disabled());assertEquals(0,turn.stats.embeddingRequests);verifyNoInteractions(catalogs,search);}
    @Test void currentCalendarQuestionsDoNotRetrieveHistoricalKnowledge(){
        session.setKnowledgeRouteJson("{\"collectionIds\":[\"ai\"],\"documentIds\":[\"a\"],\"strict\":true}");
        for(String q:List.of("今天啥日子","今天是什么日子？","今天几号？","今天是什么节日？","今天农历几号？")){
            var turn=router.prepare(session,"owner",q,List.of());assertTrue(turn.scope.disabled(),q);assertFalse(turn.attempted);assertEquals(0,turn.stats.embeddingRequests);
        }
        verifyNoInteractions(catalogs,search);
    }
    @Test void datesAskedAboutNamedDocumentsRetainDocumentScope(){assertEquals(List.of("a"),choice("只根据 Hello-Agents.pdf，文中的今天是什么日子？").scope().documentIds());}
    @Test void ordinaryTasksAvoidKnowledgeRetrieval(){for(String q:List.of("谢谢你","2+3","翻译：hello")){var turn=router.prepare(session,"owner",q,List.of());assertTrue(turn.scope.disabled());}verifyNoInteractions(search);}
    @Test void repeatedQueryReusesResultsAndRevisionChangeInvalidatesThem(){var c=chunk("chunk","ai","a","owner","GSSC 默认值");when(search.searchScoped(anyString(),anyString(),any(),anyInt(),any(),any())).thenReturn(List.of(c));
        var first=router.prepare(session,"owner","GSSC max_tokens 默认值",List.of());var second=router.prepare(session,"owner","GSSC max_tokens 默认值",List.of());assertEquals(1,second.stats.retrievalCacheHits);assertNotSame(first.chunks.get(0),second.chunks.get(0));
        version.changed("owner");router.prepare(session,"owner","GSSC max_tokens 默认值",List.of());verify(search,times(2)).searchScoped(anyString(),anyString(),any(),anyInt(),any(),any());}
    @Test void retrievalCacheIsPartitionedByOwner(){when(search.searchScoped(anyString(),anyString(),any(),anyInt(),any(),any())).thenReturn(List.of(chunk("chunk","ai","a","owner","GSSC max_tokens")));router.prepare(session,"owner","GSSC max_tokens",List.of());session.setUserId("other");router.prepare(session,"other","GSSC max_tokens",List.of());verify(search,times(2)).searchScoped(anyString(),anyString(),any(),anyInt(),any(),any());}
    @Test void toolsReuseInitialEvidenceAndNeverEscapeDocumentScope(){var turn=new KnowledgeRoutingService.Turn();turn.user="owner";turn.scope=new RetrievalScope(List.of("ai"),List.of("a"),false);turn.strict=true;turn.memo.put("GSSC",List.of(chunk("x","ai","a","owner","original")));
        assertEquals(1,router.toolSearch(turn,"GSSC",4).size());verifyNoInteractions(search);router.toolSearch(turn,"another query",4);verify(search).searchScoped(eq("another query"),eq("owner"),eq(turn.scope),eq(4),any(),isNull());}
    @Test void disabledScopeBlocksKnowledgeTools(){var turn=new KnowledgeRoutingService.Turn();turn.scope=RetrievalScope.none();assertTrue(router.toolSearch(turn,"anything",4).isEmpty());verifyNoInteractions(search);}
    @Test void onlyTwoDistinctToolSearchesAreAllowed(){var turn=new KnowledgeRoutingService.Turn();turn.user="owner";turn.scope=RetrievalScope.collection("ai");for(String q:List.of("one","two","three"))router.toolSearch(turn,q,4);verify(search,times(2)).searchScoped(anyString(),anyString(),any(),anyInt(),any(),any());}
    @Test void unownedSelectionCannotBeSaved(){var other=new KbCollection();other.setUserId("other");when(collections.selectById("secret")).thenReturn(other);assertEquals(404,assertThrows(BizException.class,()->router.preferences(session,"SELECTED",List.of("secret"),List.of(),"owner")).getCode());verifyNoInteractions(sessions);}
    @Test void selectedModeRequiresARealScope(){assertEquals(400,assertThrows(BizException.class,()->router.preferences(session,"SELECTED",List.of(),List.of(),"owner")).getCode());}
    @Test void cacheSharesConcurrentLoadsAndDoesNotCacheFailure() throws Exception{
        var cache=new BoundedTtlCache<String,String>(2,Duration.ofSeconds(10));var count=new AtomicInteger();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->cache.get("same",()->{count.incrementAndGet();started.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return "value";}));started.await();var two=pool.submit(()->cache.get("same",()->{count.incrementAndGet();return "wrong";}));release.countDown();assertEquals("value",one.get().value());assertEquals("value",two.get().value());assertEquals(1,count.get());}finally{pool.shutdownNow();}
        assertThrows(IllegalStateException.class,()->cache.get("bad",()->{throw new IllegalStateException();}));assertEquals("retry",cache.get("bad",()->"retry").value());
    }
    @Test void boundedCacheEvictsEntriesAndExpiresThem() throws Exception{var cache=new BoundedTtlCache<String,String>(1,Duration.ofMillis(20));cache.get("a",()->"a");cache.get("b",()->"b");assertEquals(1,cache.size());assertFalse(cache.get("a",()->"new").cached());Thread.sleep(30);assertFalse(cache.get("a",()->"expired").cached());}
    @Test void lexicalRecallRejectsForeignRowsAndOutOfScopeDocuments(){
        var mapper=mock(KbChunkMapper.class);when(mapper.bm25SearchScoped(anyString(),eq("owner"),eq(List.of("ai")),eq(List.of("a")),eq(5))).thenReturn(List.of(Map.of("chunk_id","owned","score",1),Map.of("chunk_id","foreign","score",2),Map.of("chunk_id","wrongdoc","score",3)));
        when(mapper.selectList(any())).thenReturn(List.of(chunk("owned","ai","a","owner","GSSC"),chunk("foreign","ai","a","other","secret"),chunk("wrongdoc","ai","otherdoc","owner","different")));
        var hybrid=new HybridSearchService(mock(EmbeddingModel.class),mapper,mock(JdbcTemplate.class));var hits=hybrid.lexical("GSSC","owner",new RetrievalScope(List.of("ai"),List.of("a"),false),5,new RetrievalStats());assertEquals(List.of("owned"),hits.stream().map(h->h.chunk().getId()).toList());
    }
    @Test void scopedVectorSqlUsesBoundParametersAndReusesEmbedding() throws Exception{
        var model=mock(EmbeddingModel.class);when(model.embed("GSSC")).thenReturn(Response.from(Embedding.from(new float[]{1,0})));
        var jdbc=mock(JdbcTemplate.class);var ds=mock(DataSource.class);var conn=mock(Connection.class);var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);when(jdbc.getDataSource()).thenReturn(ds);when(ds.getConnection()).thenReturn(conn);when(conn.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        var mapper=mock(KbChunkMapper.class);when(mapper.bm25SearchScoped(anyString(),anyString(),anyList(),anyList(),anyInt())).thenReturn(List.of());
        when(mapper.readyDocumentIds(eq("owner"),eq(List.of("ai")),eq(List.of("a")))).thenReturn(List.of("a"));
        when(conn.createArrayOf(eq("varchar"), any())).thenReturn(mock(java.sql.Array.class));
        var hybrid=new HybridSearchService(model,mapper,jdbc);ReflectionTestUtils.setField(hybrid,"vectorTopK",10);ReflectionTestUtils.setField(hybrid,"bm25TopK",10);
        var scope=new RetrievalScope(List.of("ai"),List.of("a"),false);var first=new RetrievalStats();var second=new RetrievalStats();hybrid.searchScoped("GSSC","owner",scope,4,first,null);hybrid.searchScoped("GSSC","owner",scope,4,second,null);
        assertEquals(1,first.embeddingRequests);assertEquals(0,second.embeddingRequests);assertEquals(1,second.embeddingCacheHits);verify(model).embed("GSSC");
        verify(conn,times(2)).prepareStatement(contains("collection_id IN (?) AND document_id IN (?) AND document_id = ANY (?::varchar[])"));verify(statement,times(2)).setString(2,"owner");verify(statement,times(2)).setString(3,"ai");verify(statement,times(2)).setString(4,"a");
        var readyIds=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(conn,times(2)).createArrayOf(eq("varchar"),readyIds.capture());
        assertArrayEquals(new String[]{"a"},readyIds.getAllValues().get(0));
    }
    @Test void noReadyDocumentsAvoidsEmbeddingAndVectorQuery(){
        var model=mock(EmbeddingModel.class);var mapper=mock(KbChunkMapper.class);var jdbc=mock(JdbcTemplate.class);
        when(mapper.readyDocumentIds(eq("owner"),anyList(),anyList())).thenReturn(List.of());
        when(mapper.bm25SearchScoped(anyString(),anyString(),anyList(),anyList(),anyInt())).thenReturn(List.of());
        var hybrid=new HybridSearchService(model,mapper,jdbc);
        assertTrue(hybrid.searchScoped("GSSC","owner",RetrievalScope.collection("ai"),4,new RetrievalStats(),null).isEmpty());
        verifyNoInteractions(model,jdbc);
    }
    @Test void explicitCollectionNameWinsOverSharedParameters(){
        var original=doc("a","ai","guide.md","ContextConfig max_tokens reserve_ratio 默认参数");
        var custom=doc("b","course","lab.md","ContextConfig max_tokens reserve_ratio 默认参数 实验配置");
        catalog=new KnowledgeCatalogService.Catalog(Map.of("ai","AI教程","course","AI实验课"),List.of(original,custom));
        assertEquals(List.of("ai"),choice("AI教程的 ContextConfig max_tokens 默认值是多少").scope().collectionIds());
        assertEquals(List.of("ai","course"),choice("比较 AI教程与AI实验课的 ContextConfig 参数").scope().collectionIds());
    }
    @Test void unknownParaphraseUsesSemanticRecallInsteadOfSilentlySkippingKnowledge(){
        assertEquals("semantic",choice("有哪些适合傍晚下厨的选择").reason());assertFalse(choice("有哪些适合傍晚下厨的选择").scope().disabled());
    }
    @Test void degradedRetrievalIsRetriedRatherThanCached(){
        when(search.searchScoped(anyString(),anyString(),any(),anyInt(),any(),any())).thenAnswer(call->{call.<RetrievalStats>getArgument(4).degraded=true;return List.of();});
        router.prepare(session,"owner","GSSC max_tokens",List.of());router.prepare(session,"owner","GSSC max_tokens",List.of());
        assertTrue(router.prepare(session,"owner","GSSC max_tokens",List.of()).stats.degraded);verify(search,atLeast(3)).searchScoped(anyString(),anyString(),any(),anyInt(),any(),any());
    }
    @Test void versionCompactionNeverReusesAnOldInitialCacheKey(){
        assertEquals(0,version.current("initial"));for(int i=0;i<4100;i++)version.changed("user"+i);assertTrue(version.current("initial")>0);
    }

    @Test void thanksFollowedByAQuestionStillRetrievesEvidence(){assertEquals(List.of("ai"),choice("谢谢你，GSSC max_tokens 默认值是什么").scope().collectionIds());}
    @Test void calculatingFromDocumentsStillUsesKnowledge(){var turn=router.prepare(session,"owner","计算这个文档中的 max_tokens 配置预算",List.of());verify(catalogs).get("owner");assertTrue(turn.strict);}
    @Test void numericArithmeticSkipsKnowledge(){for(String q:List.of("计算 23+19","23 + 19","帮我算2*3")){assertTrue(router.prepare(session,"owner",q,List.of()).scope.disabled());}verifyNoInteractions(catalogs,search);}
    @Test void collectionNameResolvesSameNamedDocuments(){
        catalog=new KnowledgeCatalogService.Catalog(catalog.names(),List.of(doc("a","ai","说明.pdf","GSSC"),doc("b","travel","说明.pdf","酒店")));
        assertEquals(List.of("a"),choice("只根据AI教程的说明.pdf回答GSSC").scope().documentIds());
    }
    @Test void missingPartOfAnExplicitDocumentComparisonIsNotSilentlyIgnored(){assertTrue(choice("根据 Hello-Agents.pdf 和 missing.pdf 比较参数").scope().disabled());assertEquals("missing_document",choice("根据 Hello-Agents.pdf 和 missing.pdf 比较参数").reason());}

    void selectedResearch(String... ids){
        session.setKnowledgeMode("SELECTED");session.setSystemPrompt("[AIASSISTANT_RESEARCH_V1]\nResearch");
        try{session.setKnowledgeSelectionJson(new ObjectMapper().writeValueAsString(Map.of("documentIds",List.of(ids))));}catch(Exception e){throw new IllegalStateException(e);}
        for(String id:ids){var d=new KbDocument();d.setId(id);d.setUserId("owner");when(documents.selectById(id)).thenReturn(d);}
    }
    @Test void researchSearchesEverySelectedDocumentAndReusesCombinedEvidence(){
        selectedResearch("a","c");
        when(search.searchScoped(anyString(),eq("owner"),any(),eq(4),any(),isNull())).thenAnswer(call->{
            RetrievalScope scope=call.getArgument(2);String id=scope.documentIds().get(0);
            return List.of(chunk("evidence-"+id,id.equals("a")?"ai":"cook",id,"owner","relevant evidence"));
        });
        var turn=router.prepare(session,"owner","比较所选资料的方法与局限",List.of());
        assertEquals(List.of("a","c"),turn.scope.documentIds());assertEquals(2,turn.chunks.size());
        assertEquals(Set.of("a","c"),new HashSet<>(turn.chunks.stream().map(KbChunk::getDocumentId).toList()));
        assertEquals(2,router.toolSearch(turn,turn.query,6).size());
        verify(search,times(2)).searchScoped(anyString(),eq("owner"),any(),eq(4),any(),isNull());
    }
    @Test void researchSelectionCannotBeBroadenedByMentioningAnotherFilename(){
        selectedResearch("a");var turn=router.prepare(session,"owner","比较 Hello-Agents.pdf 与家常菜.md",List.of());
        assertEquals(List.of("a"),turn.scope.documentIds());
        verify(search).searchScoped(anyString(),eq("owner"),eq(new RetrievalScope(List.of(),List.of("a"),false)),eq(6),any(),isNull());
    }
    @Test void researchWithNoEvidenceInOneDocumentKeepsSelectedScopeAndReportsOnlyRealHits(){
        selectedResearch("a","c");
        when(search.searchScoped(anyString(),eq("owner"),eq(new RetrievalScope(List.of(),List.of("a"),false)),eq(4),any(),isNull()))
                .thenReturn(List.of(chunk("evidence-a","ai","a","owner","GSSC evidence")));
        var turn=router.prepare(session,"owner","比较 GSSC 参数",List.of());
        assertEquals(List.of("a","c"),turn.scope.documentIds());assertEquals(List.of("a"),turn.chunks.stream().map(KbChunk::getDocumentId).toList());
        verify(search).searchScoped(anyString(),eq("owner"),eq(new RetrievalScope(List.of(),List.of("c"),false)),eq(4),any(),isNull());
    }
    @Test void researchStillRejectsForeignDocumentBeforeSearching(){
        selectedResearch("a");var foreign=new KbDocument();foreign.setUserId("other");when(documents.selectById("a")).thenReturn(foreign);
        assertEquals(404,assertThrows(BizException.class,()->router.prepare(session,"owner","研究资料",List.of())).getCode());verifyNoInteractions(search);
    }

}
