package com.aiproject.aiassitant.module.ai.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResearchPolicyTest {
    @Test void negativeWebRequestsCannotTriggerPrefetchOrGrantFollowupPermission(){
        for(String q:new String[]{"用哪个JDK？无需联网。","解释HTTPS，不需要联网。","What is JSON? No internet.","不要联网查询最新版本"}){
            assertFalse(ResearchPolicy.explicitWeb(q));assertFalse(ResearchPolicy.decide("AUTO",q,false,false,false,null).prefetch());assertFalse(ResearchPolicy.decide("AUTO",q,false,false,false,null).allowed());
        }
        assertEquals("LOCAL",ResearchPolicy.followupMode("AUTO","那无需联网回答","local_only"));
        assertTrue(ResearchPolicy.decide("AUTO","请联网核实最新版本",false,false,false,null).prefetch());
    }
    @Test void selectedKnowledgeDoesNotAutomaticallyDisablePublicSupplement(){assertTrue(ResearchPolicy.decide("AUTO","解释 GRPO，并补充它的实际应用",true,true,true,null).allowed());}
    @Test void localOnlyAndNaturalLanguageConstraintsWinOverWebKeywords(){
        for(String q:new String[]{"只根据此 PDF 给出最新安装方法", "仅依据本地资料回答", "Answer only based on this PDF; what is the latest version?", "不要联网回答这个问题"})
            assertFalse(ResearchPolicy.decide("AUTO",q,true,true,false,null).allowed(),q);
        assertFalse(ResearchPolicy.decide("LOCAL","请联网搜索",false,true,false,null).allowed());
    }
    @Test void privateFactsCannotBeFilledWithGenericOnlineValues(){
        assertFalse(ResearchPolicy.decide("AUTO","我们公司的报销上限是多少",false,true,false,null).allowed());
        assertFalse(ResearchPolicy.decide("AUTO","What is our company reimbursement limit?",false,true,false,null).allowed());
    }
    @Test void specificSourceValueIsNotReplacedByOtherImplementations(){assertFalse(ResearchPolicy.decide("AUTO","本书 RRF 默认 k 值是多少",true,true,false,null).allowed());}
    @Test void missingEvidenceOrCurrentInformationPrefetchesButCompleteEvidenceDoesNot(){
        assertTrue(ResearchPolicy.decide("AUTO","GRPO 如何使用",false,true,false,null).prefetch());
        assertTrue(ResearchPolicy.decide("AUTO","查询最新 PostgreSQL 文档",false,true,true,null).prefetch());
        assertFalse(ResearchPolicy.decide("AUTO","GRPO 如何使用",false,true,true,null).prefetch());
    }
    @Test void factualDefinitionsRetainSearchCapability(){assertNotEquals(SearchBudgetService.Tier.NONE,new SearchBudgetService().classify("什么是 GRPO？"));}
    @Test void followupsKeepLocalBoundaryUntilUserExplicitlyAllowsWeb(){
        assertEquals("LOCAL",ResearchPolicy.followupMode("AUTO","那它默认值是多少？","local_only"));
        assertEquals("AUTO",ResearchPolicy.followupMode("AUTO","那请联网补充说明","local_only"));
        assertEquals("AUTO",ResearchPolicy.followupMode("AUTO","换个问题，介绍 GRPO","local_only"));
        assertEquals("LOCAL",ResearchPolicy.followupMode("LOCAL","那请联网补充说明","local_only"));
    }
    @Test void noWebConversationDoesNotRequireUploadedDocuments(){
        var first=ResearchPolicy.decide("AUTO","接口超时37秒。请确认，不要联网。",false,false,false,null);
        assertEquals("no_web",first.reason());
        var next=ResearchPolicy.forTurn("AUTO","那最终超时是多少？",first.reason(),false,false,false,null);
        assertFalse(next.allowed());assertFalse(next.prefetch());assertEquals("no_web",next.reason());
        assertEquals("AUTO",ResearchPolicy.followupMode("AUTO","那给一个JSON示例","no_web"));
        assertTrue(ResearchPolicy.hint(next).contains("用户在本聊天提供的信息"));
        assertTrue(ResearchPolicy.forTurn("AUTO","那请联网核实","no_web",false,false,false,null).allowed());
        assertFalse(ResearchPolicy.forTurn("AUTO","那最新版本是多少？","no_web",false,false,false,null).allowed());
        assertFalse(ResearchPolicy.forTurn("AUTO","Then what is the current price?","no_web",false,false,false,null).prefetch());
        assertTrue(ResearchPolicy.forTurn("AUTO","那请联网核实最新版本","literal_translation",false,false,false,null).prefetch());
    }
    @Test void privateFollowupKeepsPrivacyWithoutInventingADocumentRequirement(){
        var next=ResearchPolicy.forTurn("AUTO","那剩余预算呢？","internal_fact",false,false,false,null);
        assertEquals("internal_fact",next.reason());assertFalse(next.allowed());
        assertTrue(ResearchPolicy.hint(next).contains("没有记录就明确不知道"));
    }
    @Test void quotedTranslationKeywordsAreDataButAdditionalResearchIsAnAction(){
        for(String q:new String[]{"把“The current price is unknown.”翻译成中文，只给译文。","把“今天联网搜索”翻译成英文。"}){
            assertFalse(ResearchPolicy.explicitWeb(q));assertFalse(ResearchPolicy.decide("AUTO",q,false,true,false,null).prefetch());
        }
        assertTrue(ResearchPolicy.explicitWeb("把“The current price is unknown.”翻译成中文，并联网核实价格。"));
    }
    @Test void legacyNoWebRoutesCanBeRecoveredWhileSourceRestrictionsPersist(){
        assertEquals("no_web",ResearchPolicy.previousBoundary("AUTO","解释JSON，不要联网。","local_only"));
        assertEquals("local_only",ResearchPolicy.previousBoundary("AUTO","只根据本地资料解释JSON，不要联网。","local_only"));
        assertEquals("local_only",ResearchPolicy.previousBoundary("LOCAL","不要联网。","local_only"));
        assertEquals("source_fact",ResearchPolicy.previousBoundary("AUTO","本书参数是多少","source_fact"));
    }
}
