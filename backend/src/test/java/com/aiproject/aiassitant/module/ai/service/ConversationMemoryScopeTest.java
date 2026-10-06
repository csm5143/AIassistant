package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryScopeTest {
    ChatMessage user(int n,String text){var m=new ChatMessage();m.setId("scope"+n);m.setRole("user");m.setContent(text);m.setCreatedAt(LocalDateTime.of(2026,10,2,0,0).plusSeconds(n));return m;}
    List<ChatMessage> two(){return List.of(user(1,"云帆项目的预算是8600元，负责人是周岚。"),user(2,"海棠项目的预算是20000元，负责人是李澄。"),user(3,"更正：云帆项目的预算调整为9100元。"));}
    String context(ConversationMemoryEngine.State state,List<ChatMessage> history,String q){return ConversationMemoryEngine.context(state,history,List.of(),q).text();}
    @Test void naturalClausesCarryProjectWithinSentenceAndCorrectionKeepsOtherProject(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,two());
        String c=context(state,two(),"云帆项目经费上限是多少？");assertTrue(c.contains("云帆项目预算：9100元"));assertTrue(c.contains("云帆项目负责人：周岚"));assertFalse(c.contains("海棠项目"));assertFalse(c.contains("8600"));
        assertTrue(context(state,two(),"海棠项目由谁牵头？").contains("海棠项目负责人：李澄"));
        var current=state.items.stream().filter(i->i.label.equals("云帆项目预算")&&i.status.equals("ACTIVE")).findFirst().orElseThrow();assertEquals("scope3",current.sourceMessageId);assertTrue(two().get(2).getContent().contains(current.quote));
    }
    @Test void sameSentenceSwitchAndFullStopResetAreExplicit(){
        var xs=ConversationMemoryEngine.extract("云帆项目预算：100元；负责人：周岚；海棠项目预算：200元；负责人：李澄。预算：300元。");
        assertTrue(xs.stream().anyMatch(x->x.label().equals("云帆项目负责人")&&x.value().equals("周岚")));
        assertTrue(xs.stream().anyMatch(x->x.label().equals("海棠项目负责人")&&x.value().equals("李澄")));
        assertTrue(xs.stream().anyMatch(x->x.label().equals("预算")&&x.value().equals("300元")));
    }
    @Test void qualifiedAssignmentWithPossessiveHasOneCanonicalKey(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目的预算：100元。"),user(2,"云帆项目预算改为200元。"));ConversationMemoryEngine.ingest(state,h);
        assertEquals(1,state.items.stream().filter(i->i.status.equals("ACTIVE")).count());assertEquals("云帆项目预算",state.items.get(1).label);assertEquals("SUPERSEDED",state.items.get(0).status);
    }
    @Test void paraphrasesRetrieveWithoutGenericRecallWordsAndUnrelatedFactStaysOut(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"预算：9100元；负责人：周岚；技术选型：Java 17。"));ConversationMemoryEngine.ingest(state,h);
        assertTrue(context(state,h,"经费上限是多少？").contains("9100"));assertFalse(context(state,h,"经费上限是多少？").contains("周岚"));
        assertTrue(context(state,h,"由谁牵头？").contains("周岚"));assertTrue(context(state,h,"用哪个JDK？").contains("Java 17"));
        assertEquals("",context(state,h,"早餐吃什么？"));
    }
    @Test void unqualifiedCorrectionIsNeitherFactNorRawOrRecentEvidence(){
        var state=new ConversationMemoryEngine.State();var h=new ArrayList<>(two());h.add(user(4,"更正：预算改为9500元。"));ConversationMemoryEngine.ingest(state,h);
        assertFalse(state.items.stream().anyMatch(i->i.value.equals("9500元")));assertTrue(state.ambiguousMessages.contains("scope4"));
        assertFalse(context(state,h,"云帆项目预算").contains("9500"));assertFalse(ConversationMemoryEngine.filter(state,h).stream().anyMatch(m->m.getId().equals("scope4")));
        assertTrue(context(state,h,"预算是多少？").contains("未指定项目"));
    }
    @Test void namedUnknownProjectCannotBorrowPinnedTasksOrRawUnscopedFacts(){
        var state=new ConversationMemoryEngine.State();var h=new ArrayList<>(two());h.add(user(4,"云帆项目下一步：评测PDF；海棠项目下一步：评测CSV。"));h.add(user(5,"发布分支是release-private。"));ConversationMemoryEngine.ingest(state,h);
        assertEquals("",context(state,h,"星河项目预算和发布分支是什么？"));
        assertFalse(context(state,h,"海棠项目后续安排是什么？").contains("PDF"));assertTrue(context(state,h,"海棠项目后续安排是什么？").contains("CSV"));
    }
    @Test void compareTwoExplicitProjectsKeepsBothWithLabels(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,two());String c=context(state,two(),"比较云帆项目与海棠项目的预算");
        assertTrue(c.contains("云帆项目预算：9100元"));assertTrue(c.contains("海棠项目预算：20000元"));
    }
    @Test void deletingSameValueDoesNotBlockOtherExplicitProjectSource(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目预算：9100元。"),user(2,"海棠项目预算：9100元。"),user(3,"预算是9100元。"));ConversationMemoryEngine.ingest(state,h);
        String id=state.items.stream().filter(i->i.label.equals("云帆项目预算")).findFirst().orElseThrow().id;ConversationMemoryEngine.forget(state,id,h);
        var safe=ConversationMemoryEngine.filter(state,h);assertEquals(1,safe.size());assertEquals("scope2",safe.get(0).getId());assertTrue(context(state,h,"海棠项目预算").contains("9100"));
        ConversationMemoryEngine.ingest(state,List.of(user(4,"云帆项目预算：9999元。")));assertFalse(state.items.stream().anyMatch(i->i.label.equals("云帆项目预算")));
    }
    @Test void manualEditKeepsScopedLineageAndOtherSameValue(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目预算：9100元。"),user(2,"海棠项目预算：9100元。"));ConversationMemoryEngine.ingest(state,h);var old=state.items.get(0);
        ConversationMemoryEngine.manual(state,old.label,"9200元","FACT",false,h,old.id);
        assertTrue(context(state,h,"云帆项目预算").contains("9200"));assertFalse(context(state,h,"云帆项目预算").contains("9100"));assertEquals("scope2",ConversationMemoryEngine.filter(state,h).get(0).getId());
    }
    @Test void taskRecallRetainsProjectLabelsForGlobalStatus(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"云帆项目下一步：PDF；海棠项目下一步：CSV。")));
        var reply=ConversationMemoryEngine.taskRecall(state,"本聊天任务状态");assertTrue(reply.contains("云帆项目：PDF"));assertTrue(reply.contains("海棠项目：CSV"));
    }
    @Test void localUpgradeRecoversScopesAndKeepsOldSourceIdsWhenUnchanged()throws Exception{
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目的预算是9100元。"),user(2,"海棠项目的预算是20000元。"),user(3,"负责人：周岚。"));
        // Simulate old unscoped extraction, with both original messages already marked seen.
        ConversationMemoryEngine.ingest(state,List.of(user(1,"预算：9100元。"),user(2,"预算：20000元。"),h.get(2)));String sourceId=state.items.get(2).id;state.scanCursor=3L;state.extractionVersion=0;
        ConversationMemoryEngine.upgrade(state,h);assertTrue(context(state,h,"云帆项目预算").contains("9100"));assertTrue(context(state,h,"海棠项目预算").contains("20000"));assertTrue(state.items.stream().anyMatch(i->i.id.equals(sourceId)&&i.status.equals("ACTIVE")));
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();String before=json.writeValueAsString(state);ConversationMemoryEngine.upgrade(state,h);assertEquals(before,json.writeValueAsString(state));
    }
    @Test void upgradeNeverResurrectsDeletionManualOverrideOrPausedMessages(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目预算：9100元。"),user(2,"海棠项目负责人：李澄。"),user(3,"海棠项目预算：20000元。"));
        ConversationMemoryEngine.ingest(state,h.subList(0,2));ConversationMemoryEngine.forget(state,state.items.get(0).id,h);
        var item=state.items.stream().filter(i->i.status.equals("ACTIVE")).findFirst().orElseThrow();ConversationMemoryEngine.manual(state,item.label,"周岚","FACT",false,h,item.id);String manualId=state.items.get(state.items.size()-1).id;
        state.enabled=false;ConversationMemoryEngine.ingest(state,h.subList(2,3));state.extractionVersion=0;ConversationMemoryEngine.upgrade(state,h);
        assertFalse(state.enabled);assertTrue(state.items.stream().noneMatch(i->i.status.equals("ACTIVE")&&i.label.contains("预算")));assertTrue(state.items.stream().anyMatch(i->i.id.equals(manualId)&&i.status.equals("ACTIVE")&&i.value.equals("周岚")));
    }
    @Test void legacyGlobalDeletionCannotReappearAsScopedBackfill(){
        var state=new ConversationMemoryEngine.State();state.suppressedKeys.add("预算");state.seen.add("scope1");ConversationMemoryEngine.upgrade(state,List.of(user(1,"云帆项目预算：9100元。")));
        assertTrue(state.items.isEmpty());assertTrue(state.suppressedKeys.contains("预算"));
    }
    @Test void chronologicalScanAndUpgradeCannotUndoBackdatedIncrementalCorrection(){
        var old=user(1,"云帆项目预算：8600元。");var correction=user(2,"云帆项目预算改为9100元。");old.setMemorySeq(1L);correction.setMemorySeq(2L);correction.setCreatedAt(old.getCreatedAt().minusDays(1));
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(correction,old));assertTrue(context(state,List.of(),"云帆项目预算").contains("9100"));
        state.extractionVersion=0;ConversationMemoryEngine.upgrade(state,List.of(correction,old));assertTrue(context(state,List.of(),"云帆项目预算").contains("9100"));assertFalse(context(state,List.of(),"云帆项目预算").contains("8600"));
    }
    @Test void upgradingAfterEditingOneFieldPreservesOtherActiveFieldsInBlockedSource(){
        var state=new ConversationMemoryEngine.State();var h=List.of(user(1,"云帆项目的预算是9100元，负责人是周岚；已完成CSV解析。"));ConversationMemoryEngine.ingest(state,h);
        var budget=state.items.stream().filter(i->i.label.equals("云帆项目预算")).findFirst().orElseThrow();var owner=state.items.stream().filter(i->i.label.equals("云帆项目负责人")).findFirst().orElseThrow();String ownerId=owner.id;
        ConversationMemoryEngine.manual(state,budget.label,"9200元","FACT",false,h,budget.id);assertTrue(state.blockedMessages.contains("scope1"));state.extractionVersion=0;ConversationMemoryEngine.upgrade(state,h);
        assertTrue(context(state,h,"云帆项目由谁负责？").contains("周岚"));assertTrue(state.items.stream().anyMatch(i->i.id.equals(ownerId)&&i.status.equals("ACTIVE")&&h.get(0).getContent().contains(i.quote)));
        assertTrue(state.items.stream().anyMatch(i->i.label.equals("云帆项目已完成")&&i.status.equals("ACTIVE")&&i.value.equals("CSV解析")&&h.get(0).getContent().contains(i.quote)));
        String c=context(state,h,"云帆项目预算");assertTrue(c.contains("9200"));assertFalse(c.contains("9100"));assertTrue(ConversationMemoryEngine.filter(state,h).isEmpty());
    }
}
