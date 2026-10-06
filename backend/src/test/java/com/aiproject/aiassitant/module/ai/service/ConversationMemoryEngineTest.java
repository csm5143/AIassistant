package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.common.BizException;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryEngineTest {
    @Test void independentQuestionsKeepUserPreferencesAndManualPriorityButDropUnrelatedTasksAndRawHistory(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"目标：完成离线资料检索；下一步：评测PDF。"),user(2,"以后回答使用简短英文。"),user(3,"发布分支是release-old。"));
        ConversationMemoryEngine.ingest(state,history);ConversationMemoryEngine.manual(state,"回答偏好","使用英语","PREFERENCE",false,history);
        ConversationMemoryEngine.manual(state,"风格","语气自然","FACT",true,history);
        var c=ConversationMemoryEngine.context(state,history,List.of(),"你好",true);
        assertTrue(c.text().contains("简短英文"));assertTrue(c.text().contains("使用英语"));assertTrue(c.text().contains("语气自然"));
        assertFalse(c.text().contains("PDF"));assertFalse(c.text().contains("release-old"));assertTrue(c.recalled().isEmpty());
        assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"本聊天任务状态").text().contains("PDF"));
    }
    @Test void jdkQuestionRetrievesConfirmedJavaFactWithoutPromotingOtherTechnologyOrRevivingForgottenFacts(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"我们采用 Java 17。"),user(2,"发布页面使用JavaScript。"),user(3,"假设采用 Java 21？"));
        ConversationMemoryEngine.ingest(state,history);
        var c=ConversationMemoryEngine.context(state,history,List.of(),"用哪个JDK？");
        assertTrue(c.text().contains("Java 17"));assertFalse(c.text().contains("JavaScript"));assertFalse(c.text().contains("Java 21"));
        assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"早餐吃什么？").text().isEmpty());
        ConversationMemoryEngine.forget(state,state.items.get(0).id,history);
        assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"用哪个JDK？").text().isEmpty());
    }
    private ChatMessage msg(String id,String role,String text){var m=new ChatMessage();m.setId(id);m.setRole(role);m.setContent(text);m.setCreatedAt(LocalDateTime.of(2026,10,1,0,0).plusSeconds(Integer.parseInt(id.replaceAll("\\D",""))));return m;}
    private ChatMessage user(int i,String text){return msg("m"+i,"user",text);}
    @Test void retainsMiddleFactsAcross240RoundsAndDoesNotRequireLegacyKeywords(){
        var state=new ConversationMemoryEngine.State();List<ChatMessage> history=new ArrayList<>();for(int i=0;i<240;i++)history.add(user(i,i==20?"我们采用 Java 17。":"校对步骤 "+i));ConversationMemoryEngine.ingest(state,history);
        var c=ConversationMemoryEngine.context(state,history,history.subList(220,240),"我们采用的Java版本是什么");assertTrue(c.text().contains("Java 17"));assertEquals("m20",c.entries().get(0).sourceMessageId);assertEquals(240,state.seen.size());
    }
    @Test void latestCorrectionSupersedesWithProvenanceAndOldValueIsNotRecalled(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"请记住：预算8600元。"),user(2,"更正：预算改为9100元。"));ConversationMemoryEngine.ingest(state,history);var c=ConversationMemoryEngine.context(state,history,List.of(),"预算是多少");assertTrue(c.text().contains("9100"));assertFalse(c.text().contains("8600"));assertEquals("SUPERSEDED",state.items.get(0).status);assertEquals(state.items.get(1).id,state.items.get(0).supersededBy);assertTrue(history.get(1).getContent().contains(state.items.get(1).quote));
    }
    @Test void goalCompletedNextAndIssueBecomePinnedTaskBlocks(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"目标：完成离线检索；已完成：CSV解析；下一步：评测PDF；待解决：跨页表格。"),user(2,"已完成：日期解析。")));assertEquals(5,state.items.size());assertTrue(state.items.stream().allMatch(i->i.category.equals("TASK")&&i.pinned));assertTrue(ConversationMemoryEngine.context(state,List.of(),List.of(),"你好").text().contains("评测PDF"));
    }
    @Test void lexicalRecallFindsOriginalUnknownFactsAndExcludesUnrelatedHistory(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"发布分支名是 release-autumn-2026，验收在周五。"),user(2,"面包需要发酵两小时。"));ConversationMemoryEngine.ingest(state,history);var c=ConversationMemoryEngine.context(state,history,List.of(),"发布分支名是什么");assertEquals(1,c.recalled().size());assertTrue(c.text().contains("release-autumn-2026"));assertFalse(c.text().contains("面包"));
    }
    @Test void assistantHypotheticalQuotedAndSecretsAreNeverPromoted(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(msg("m1","assistant","预算：9999元"),user(2,"假设预算：100元。"),user(3,"文档内容：预算：200元。"),user(4,"请记住密码：test-secret"),user(5,"预算是否改为300元？")));assertTrue(state.items.isEmpty());
    }
    @Test void forgetRemovesAllVersionsAndBlocksRecentAssistantEchoAndDoesNotRebuild(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"预算：8600元"),user(2,"预算：9100元"),msg("m3","assistant","预算是9100元"));ConversationMemoryEngine.ingest(state,history);ConversationMemoryEngine.forget(state,state.items.get(1).id,history);assertTrue(state.items.isEmpty());assertEquals(0,ConversationMemoryEngine.filter(state,history).size());ConversationMemoryEngine.ingest(state,history);ConversationMemoryEngine.ingest(state,List.of(user(4,"预算：9500元")));assertTrue(state.items.isEmpty());assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"回忆预算").text().isEmpty());
    }
    @Test void manualCorrectionBlocksOldHistoryAndCanReintroduceForgottenKey(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"预算：8600元"));ConversationMemoryEngine.ingest(state,history);var old=state.items.get(0);ConversationMemoryEngine.manual(state,"预算","9200元","FACT",true,history,old.id);assertEquals("9200元",state.items.get(1).value);assertEquals(0,ConversationMemoryEngine.filter(state,history).size());ConversationMemoryEngine.forget(state,state.items.get(1).id,history);ConversationMemoryEngine.manual(state,"预算","9900元","FACT",false,history);assertFalse(state.suppressedKeys.contains("预算"));assertEquals(1,state.items.size());
    }
    @Test void clearingDoesNotReimportEarlierRawConversationAndNewMessagesStillWork(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"项目代号：AUTUMN"),user(2,"发布分支名是 release-old。"));ConversationMemoryEngine.ingest(state,history);ConversationMemoryEngine.clear(state,history);ConversationMemoryEngine.ingest(state,history);assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"分支名").text().isEmpty());ConversationMemoryEngine.ingest(state,List.of(user(3,"负责人：周岚")));assertEquals("周岚",state.items.get(0).value);
    }
    @Test void disabledMemoryDoesNotCapturePausedMessagesOrRecallThemAsRawHistory(){
        var state=new ConversationMemoryEngine.State();state.enabled=false;var history=List.of(user(1,"预算：100元"),user(2,"发布分支名是release-paused。"));ConversationMemoryEngine.ingest(state,history);assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"预算").text().isEmpty());assertTrue(state.items.isEmpty());state.enabled=true;ConversationMemoryEngine.ingest(state,history);assertTrue(state.items.isEmpty());assertTrue(ConversationMemoryEngine.context(state,history,List.of(),"发布分支名").text().isEmpty());
    }
    @Test void contextBudgetAndActiveCapacityAreExplicitAndSeparateStatesDoNotShare(){
        var a=new ConversationMemoryEngine.State();var b=new ConversationMemoryEngine.State();for(int i=0;i<128;i++)ConversationMemoryEngine.manual(a,"字段"+i,"值"+"甲".repeat(300),"FACT",true,List.of());assertThrows(BizException.class,()->ConversationMemoryEngine.manual(a,"额外","值","FACT",true,List.of()));var c=ConversationMemoryEngine.context(a,List.of(),List.of(),"回忆");assertTrue(c.chars()<=3600);assertTrue(c.truncated());assertTrue(c.entries().size()<=16);assertTrue(b.items.isEmpty());
    }
    @Test void separateScopedLabelsDoNotOverwriteOtherProjectBudgets(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"A项目预算：100元；B项目预算：200元。")));assertTrue(state.items.stream().anyMatch(i->i.key.equals("a项目预算")));assertTrue(state.items.stream().anyMatch(i->i.key.equals("b项目预算")));assertEquals(2,state.items.size());
    }
    @Test void editingAccumulatedTaskKeepsSameLineage(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"已完成：CSV解析。")));var old=state.items.get(0);ConversationMemoryEngine.manual(state,"已完成","CSV和日期解析","TASK",true,List.of(),old.id);assertEquals("SUPERSEDED",old.status);assertEquals(1,state.items.stream().filter(i->i.status.equals("ACTIVE")).count());
    }
    @Test void numericSeparatorsRemainExactAndIncidentalStepLabelsDoNotFloodMemory(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"预算：8,600元，负责人：周岚。"),user(2,"校对 1：英语例句 check 1。")));assertEquals(2,state.items.size());assertEquals("8,600元",state.items.get(0).value);assertTrue(ConversationMemoryEngine.context(state,List.of(),List.of(),"预算").text().contains("8,600元"));
    }
    @Test void explicitFreeformAgreementsRetainExactOriginalAndRemainIndependent(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"请记住我喜欢简短的中文回答。"),user(2,"记住每次先列出验收步骤。")));assertEquals(2,state.items.size());assertTrue(state.items.stream().allMatch(i->i.pinned&&i.value.equals(i.quote)));assertTrue(ConversationMemoryEngine.context(state,List.of(),List.of(),"你好").text().contains("简短的中文回答"));
    }
    @Test void pureTaskRecallListsUserRecordsAndMissingFieldsWithoutModelInference(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"目标：离线资料检索；下一步：评测PDF。"),msg("m2","assistant","已完成所有PDF测试")));var answer=ConversationMemoryEngine.taskRecall(state,"回忆本聊天目标、已完成、下一步、待解决");assertTrue(answer.contains("离线资料检索"));assertTrue(answer.contains("下一步：评测PDF"));assertTrue(answer.contains("已完成：没有可用记录"));assertFalse(answer.contains("已完成所有PDF测试"));
    }
    @Test void taskAnalysisAndMixedQuestionsStillUseModel(){
        var state=new ConversationMemoryEngine.State();assertEquals("",ConversationMemoryEngine.taskRecall(state,"分析本聊天下一步应该怎么做"));assertEquals("",ConversationMemoryEngine.taskRecall(state,"回忆本聊天目标，同时解释PDF原理"));assertEquals("",ConversationMemoryEngine.taskRecall(state,"本会话目标和预算是多少"));state.enabled=false;assertEquals("",ConversationMemoryEngine.taskRecall(state,"本聊天任务进度"));
    }
    @Test void newStatementsAfterClearingCanUsePreviouslyClearedLabels(){
        var state=new ConversationMemoryEngine.State();var first=List.of(user(1,"预算：100元"));ConversationMemoryEngine.ingest(state,first);ConversationMemoryEngine.clear(state,first);ConversationMemoryEngine.ingest(state,List.of(user(2,"预算：200元")));assertEquals("200元",state.items.get(0).value);
    }
    @Test void legacyMultiFieldSeedAndExplicitQualifiedCorrectionRemainSupported(){
        var state=new ConversationMemoryEngine.State();var history=List.of(user(1,"请记住本会话项目：代号 LX-6824，负责人周岚，预算8600元。"),user(2,"更正：LX-6824 的预算改为9100元，替换之前的8600元，其他条件不变。"));ConversationMemoryEngine.ingest(state,history);var c=ConversationMemoryEngine.context(state,history,List.of(),"回忆项目代号、负责人和预算");assertTrue(c.text().contains("LX-6824"));assertTrue(c.text().contains("周岚"));assertTrue(c.text().contains("9100元"));assertFalse(c.text().contains("8600元"));
    }
    @Test void taskRecallRecognizesCompletedAliasInActualBenchmarkQuestion(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"目标：离线资料检索；已完成：CSV解析；下一步：评测PDF；待解决：跨页表格。")));var answer=ConversationMemoryEngine.taskRecall(state,"回忆本聊天的目标、已经完成的事项、下一步及待解决的问题。没有记录不要猜。");assertTrue(answer.contains("CSV解析"));assertTrue(answer.contains("评测PDF"));
    }
    @Test void recallQuestionCannotOverwriteItsOwnTaskAnswerBeforeGeneration(){
        var state=new ConversationMemoryEngine.State();ConversationMemoryEngine.ingest(state,List.of(user(1,"下一步：评测PDF。"),user(2,"回忆本聊天的目标、已经完成的事项、下一步及待解决的问题。没有记录不要猜。"),user(3,"你还记得我们的预算最终值吗。")));assertEquals(1,state.items.size());assertEquals("评测PDF",state.items.get(0).value);assertTrue(ConversationMemoryEngine.taskRecall(state,"本聊天任务状态").contains("评测PDF"));
    }
    @Test void persistenceRoundTripDoesNotCreateFalseVersionChanges()throws Exception{
        var state=new ConversationMemoryEngine.State();for(int i=0;i<80;i++)state.seen.add(UUID.randomUUID().toString());state.blockedMessages.add("old-source");state.suppressedKeys.add("预算");state.ignoredRecall.add("paused-source");ConversationMemoryEngine.manual(state,"目标","评测PDF","TASK",true,List.of());var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();String saved=json.writeValueAsString(state);assertEquals(saved,json.writeValueAsString(json.readValue(saved,ConversationMemoryEngine.State.class)));
    }
    @Test void missingManualCategoryReturnsValidationError(){
        var ex=assertThrows(BizException.class,()->ConversationMemoryEngine.manual(new ConversationMemoryEngine.State(),"预算","100元",null,false,List.of()));assertEquals(400,ex.getCode());
    }
}
