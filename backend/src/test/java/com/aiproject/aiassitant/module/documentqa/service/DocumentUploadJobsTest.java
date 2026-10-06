package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.ai.config.EmbeddingModelConfig;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentUploadJobsTest {
    @TempDir Path root;
    private final List<DocumentUploadJobs> instances=new ArrayList<>();
    private final ChatSessionMapper sessions=mock(ChatSessionMapper.class);
    private final DocumentQaService service=mock(DocumentQaService.class);
    @BeforeEach void setup(){ChatSession chat=new ChatSession();chat.setId("chat");chat.setUserId("owner");when(sessions.selectById("chat")).thenReturn(chat);}
    @AfterEach void close(){instances.forEach(DocumentUploadJobs::shutdown);}
    private DocumentUploadJobs jobs(){var instance=new DocumentUploadJobs(service,sessions,mock(EmbeddingModelConfig.class),new ObjectMapper(),root.toString());instances.add(instance);return instance;}
    private MockMultipartFile file(String content){return new MockMultipartFile("file","input.md","text/markdown",content.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test void queuedJobSurvivesNewServiceInstanceWithOwnerIsolation()throws Exception{
        var first=jobs();first.submit(file("# text"),"chat","task-123456","owner");first.shutdown();
        var restored=jobs();assertEquals("QUEUED",restored.get("task-123456","owner").get("status"));assertEquals(1,restored.get("task-123456","owner").get("recoveries"));
        assertThrows(BizException.class,()->restored.get("task-123456","foreign"));assertThrows(BizException.class,()->restored.retry("task-123456","foreign"));
        verifyNoInteractions(service);
    }
    @Test void sameIdIsIdempotentOnlyForSameFileAndSession()throws Exception{
        var jobs=jobs();jobs.submit(file("same"),"chat","task-123456","owner");assertEquals("task-123456",jobs.submit(file("same"),"chat","task-123456","owner").get("id"));
        assertThrows(BizException.class,()->jobs.submit(file("different"),"chat","task-123456","owner"));
        assertThrows(BizException.class,()->jobs.submit(file("same"),"chat","../../escape","owner"));
    }
    @Test void cancellationPersistsAndRetryRequiresExplicitAction()throws Exception{
        var jobs=jobs();jobs.submit(file("same"),"chat","task-123456","owner");jobs.cancel("task-123456","owner");
        var restored=jobs();assertEquals("CANCELLED",restored.get("task-123456","owner").get("status"));assertEquals("QUEUED",restored.retry("task-123456","owner").get("status"));
    }
    @Test void deletingSessionRemovesOwnedJobFiles()throws Exception{
        var jobs=jobs();jobs.submit(file("same"),"chat","task-123456","owner");jobs.removeForSession("chat","foreign");assertTrue(Files.exists(root.resolve("task-123456/input.bin")));
        jobs.removeForSession("chat","owner");assertFalse(Files.exists(root.resolve("task-123456")));assertThrows(BizException.class,()->jobs.get("task-123456","owner"));
    }
    @Test void atomicStoreReadsCommittedCheckpointAndRejectsUnsafePaths()throws Exception{
        var store=new DocumentUploadJobStore(root,new ObjectMapper());store.create("task-123456");store.write("task-123456","source.json",Map.of("text","first"));store.write("task-123456","source.json",Map.of("text","second"));
        assertEquals("second",store.read("task-123456","source.json",Map.class).get("text"));assertFalse(Files.exists(root.resolve("task-123456/source.json.part")));
        assertThrows(BizException.class,()->store.directory("../escape"));
    }
    @Test void rejectsOldParserAndModelCheckpointsBeforeUploadContinues()throws Exception{
        var jobs=jobs();jobs.submit(file("same"),"chat","task-123456","owner");var store=new DocumentUploadJobStore(root,new ObjectMapper());var job=store.read("task-123456","job.json",DocumentUploadJobs.Job.class);
        var oldSource=new com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.SourceDocument("old",List.of());
        store.write(job.id,"source.json",new DocumentUploadJobs.SavedSource(DocumentUploadJobs.PARSER_VERSION-1,job.sha256,oldSource));
        store.write(job.id,"embeddings.json",new DocumentUploadJobs.SavedEmbeddings(DocumentUploadJobs.PARSER_VERSION,"old-model",job.sha256,List.of(),new DocumentEmbeddingBatcher.Stats(0,0,0,true,0,0,0)));
        var observedSource=new java.util.concurrent.atomic.AtomicReference<>(oldSource);
        var observedVectors=new java.util.concurrent.atomic.AtomicReference<DocumentEmbeddingBatcher.Result>(new DocumentEmbeddingBatcher.Result(List.of(),null));
        var called=new java.util.concurrent.CountDownLatch(1);
        when(service.upload(any(),eq("chat"),any(),any(DocumentQaService.UploadCheckpoint.class))).thenAnswer(invocation->{var cp=invocation.getArgument(3,DocumentQaService.UploadCheckpoint.class);observedSource.set(cp.source());observedVectors.set(cp.embeddings());called.countDown();return Map.of("sessionId","chat");});
        jobs.dispatch();assertTrue(called.await(3,java.util.concurrent.TimeUnit.SECONDS));assertNull(observedSource.get());assertNull(observedVectors.get());
    }
    @Test void legacyVectorsCannotSurviveSourceUpgradeButCurrentVectorsCanResume()throws Exception{
        var jobs=jobs();jobs.submit(file("same"),"chat","task-123456","owner");
        var store=new DocumentUploadJobStore(root,new ObjectMapper());var job=store.read("task-123456","job.json",DocumentUploadJobs.Job.class);
        // Prior on-disk schema has no parserVersion, with the same file and model fingerprint.
        var legacy=new LinkedHashMap<String,Object>();legacy.put("fingerprint",null);legacy.put("sha256",job.sha256);
        legacy.put("chunks",List.of(new DocumentQaSessionStore.StoredChunk("old unsplit CSV",new float[]{1,0})));
        legacy.put("stats",new DocumentEmbeddingBatcher.Stats(0,0,0,true,0,0,0));store.write(job.id,"embeddings.json",legacy);
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();var called=new java.util.concurrent.CountDownLatch(1);
        when(service.upload(any(),eq("chat"),any(),any(DocumentQaService.UploadCheckpoint.class))).thenAnswer(invocation->{
            try {
                var cp=invocation.getArgument(3,DocumentQaService.UploadCheckpoint.class);
                cp.saveSource(new com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser.SourceDocument("new split CSV",List.of()));
                assertNull(cp.embeddings());
                cp.saveEmbeddings(new DocumentEmbeddingBatcher.Result(List.of(new DocumentQaService.ChunkEmbedding("new split CSV",dev.langchain4j.data.embedding.Embedding.from(new float[]{1,0}))),new DocumentEmbeddingBatcher.Stats(0,0,0,true,0,0,0)));
                assertEquals("new split CSV",cp.embeddings().embeddings().get(0).text);
            } catch(Throwable e){error.set(e);} finally{called.countDown();}
            return Map.of("sessionId","chat");
        });
        jobs.dispatch();assertTrue(called.await(3,java.util.concurrent.TimeUnit.SECONDS));assertNull(error.get());
    }
}
