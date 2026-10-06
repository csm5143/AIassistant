package com.aiproject.aiassitant.module.chat.service;
import com.aiproject.aiassitant.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunJournalTest {
    @TempDir Path temp;final ObjectMapper json=new ObjectMapper();RunJournal journal;final String session="a".repeat(32);
    @BeforeEach void setup()throws Exception{journal=new RunJournal(temp.toString(),json);journal.recover();}
    RunJournal.Task create()throws Exception{return journal.create("alice",session,"CHAT","分析两个文件","deepseek-chat","scope","files",List.of());}
    @Test void restartPreservesPartialCompletedStepsAndUsageWithoutAutoRunning()throws Exception{
        var t=create();journal.prepared(t,"original prompt",List.of(Map.of("index",1,"contentSnippet","证据")));journal.partial(t,"部分回答");journal.modelStarted(t);journal.modelReported(t,120,20);var s=journal.startStep(t,"generateDocument","{\"title\":\"报告\"}");journal.finishStep(t,s,"{\"ok\":true}",true,t.citations,List.of(Map.of("id","saved-file")));
        var restarted=new RunJournal(temp.toString(),json);restarted.recover();var saved=restarted.get("alice",session,t.id);assertEquals("INTERRUPTED",saved.status);assertEquals("部分回答",saved.partial);assertEquals("SUCCEEDED",saved.steps.get(0).status);assertEquals(120,saved.promptTokens);assertEquals("saved-file",((Map<?,?>)saved.artifacts.get(0)).get("id"));
        var resumed=restarted.claim("alice",session,t.id,"scope","files");assertEquals(2,resumed.attempts);assertNotNull(restarted.completed(resumed,"generateDocument","{\"title\":\"报告\"}"));
    }
    @Test void uncertainEffectsBlockRetryButReadOnlyWorkCanRetry()throws Exception{
        var t=create();journal.startStep(t,"pdfTools","{}");journal.recover();assertEquals("UNCERTAIN",journal.get("alice",session,t.id).status);assertThrows(BizException.class,()->journal.claim("alice",session,t.id,"scope","files"));
        var read=create();journal.startStep(read,"readDocuments","{}");journal.recover();var r=journal.claim("alice",session,read.id,"scope","files");assertTrue(r.steps.isEmpty());
    }
    @Test void ownedTaskAndSnapshotChecks()throws Exception{
        var t=create();journal.interrupt(t,"断开");assertThrows(BizException.class,()->journal.get("bob",session,t.id));assertThrows(BizException.class,()->journal.get("alice","b".repeat(32),t.id));assertThrows(BizException.class,()->journal.get("alice",session,"../escape"));assertThrows(BizException.class,()->journal.claim("alice",session,t.id,"changed","files"));assertThrows(BizException.class,()->journal.claim("alice",session,t.id,"scope","changed"));
    }
    @Test void concurrentClaimAndAttemptLimit()throws Exception{
        var t=create();for(int i=0;i<4;i++){journal.interrupt(t,"暂停");t=journal.claim("alice",session,t.id,"scope","files");}final var fifth=t;journal.interrupt(t,"暂停");assertThrows(BizException.class,()->journal.claim("alice",session,fifth.id,"scope","files"));
        var a=create();journal.interrupt(a,"暂停");journal.claim("alice",session,a.id,"scope","files");assertThrows(BizException.class,()->journal.claim("alice",session,a.id,"scope","files"));
    }
    @Test void lateCancelledUsageCannotOverwriteNewAttemptState()throws Exception{
        var old=create();journal.modelStarted(old);journal.interrupt(old,"暂停");var resumed=journal.claim("alice",session,old.id,"scope","files");journal.modelStarted(resumed);journal.modelReported(old,100,10);journal.modelReported(resumed,200,20);journal.complete(resumed,Map.of("answer","最终回答"));var fresh=journal.get("alice",session,old.id);assertEquals("COMPLETED",fresh.status);assertEquals(300,fresh.promptTokens);assertEquals(2,fresh.reportedModelRequests);journal.modelReported(old,50,5);assertEquals("COMPLETED",journal.get("alice",session,old.id).status);assertEquals(350,journal.get("alice",session,old.id).promptTokens);
    }
    @Test void partialBoundsSummaryAndDeletion()throws Exception{
        var t=create();journal.partial(t,"字".repeat(100010));assertEquals(100000,journal.get("alice",session,t.id).partial.length());assertFalse(journal.summary(t).containsKey("partial"));journal.remove("alice",session);assertTrue(journal.list("alice",session).isEmpty());
    }
    @Test void argsReuseIsStructuralAndOnlySuccessful()throws Exception{
        var t=create();var s=journal.startStep(t,"calculator","{\"a\":1,\"b\":2}");journal.finishStep(t,s,"3",true,List.of(),List.of());assertNotNull(journal.completed(t,"calculator","{\"b\":2,\"a\":1}"));assertNull(journal.completed(t,"calculator","{\"a\":2,\"b\":2}"));var f=journal.startStep(t,"readDocuments","{}");journal.finishStep(t,f,"error",false,List.of(),List.of());assertNull(journal.completed(t,"readDocuments","{}"));
    }
    @Test void sessionGateRejectsOverlapAndReleases(){var gate=new SessionRunGate();gate.claim(session);assertThrows(BizException.class,()->gate.claim(session));gate.claim("b".repeat(32));gate.release(session);assertDoesNotThrow(()->gate.claim(session));}
    @Test void expiredTaskCannotBeResumedWithoutListingFirst()throws Exception{
        var t=create();journal.interrupt(t,"中断");var file=Files.walk(temp).filter(p->p.toString().endsWith(t.id+".json")).findFirst().orElseThrow();t.createdAt=java.time.Instant.now().minus(java.time.Duration.ofDays(8)).toString();Files.write(file,json.writeValueAsBytes(t));assertThrows(BizException.class,()->journal.claim("alice",session,t.id,"scope","files"));assertFalse(Files.exists(file));
    }
    @Test void corruptCheckpointDoesNotPreventOtherTasksOrServiceStartup()throws Exception{
        var t=create();var file=Files.walk(temp).filter(p->p.toString().endsWith(t.id+".json")).findFirst().orElseThrow();var damaged=file.resolveSibling("b".repeat(32)+".json");Files.writeString(damaged,"{broken");assertDoesNotThrow(()->journal.recover());assertEquals(1,journal.list("alice",session).size());assertEquals("INTERRUPTED",journal.get("alice",session,t.id).status);assertEquals("{broken",Files.readString(damaged));
    }
}
