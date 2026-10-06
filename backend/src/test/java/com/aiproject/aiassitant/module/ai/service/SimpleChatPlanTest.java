package com.aiproject.aiassitant.module.ai.service;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimpleChatPlanTest {
    @Test void chineseLiteralArithmeticRetainsPrecisionAndOperatorOrder(){
        assertEquals("91",SimpleChatPlan.classify(session(),"7乘13等于多少？",false,clock).answer());
        assertEquals("3.125",SimpleChatPlan.classify(session(),"计算：12.5除以4",false,clock).answer());
        assertEquals("4",SimpleChatPlan.classify(session(),"(8加4)除以3",false,clock).answer());
        assertEquals("4",SimpleChatPlan.classify(session(),"10减去3乘以2",false,clock).answer());
        assertEquals("9007199254741002",SimpleChatPlan.classify(session(),"9007199254740993加上9",false,clock).answer());
        assertTrue(SimpleChatPlan.classify(session(),"12.5除以0",false,clock).answer().contains("不能为0"));
        for(String q:new String[]{"1除以3","3乘4元","根据文档3乘4","3乘4，然后删除文件","3乘4用英文解释","三乘四"})
            assertFalse(SimpleChatPlan.classify(session(),q,false,clock).direct(),q);
    }
    final Clock clock=Clock.fixed(Instant.parse("2026-10-01T17:00:00Z"),ZoneOffset.UTC);
    ChatSession session(){var s=new ChatSession();s.setThinkingEffort("none");s.setKnowledgeMode("AUTO");s.setAnswerMode("AUTO");return s;}
    @Test void datesUseShanghaiAndOnlyExactSystemDateRequests(){
        assertTrue(SimpleChatPlan.classify(session(),"今天几号？",false,clock).answer().contains("2026-10-02"));
        assertTrue(SimpleChatPlan.classify(session(),"明天星期几？",false,clock).answer().contains("2026-10-03"));
        assertTrue(SimpleChatPlan.classify(session(),"今天啥日子",false,clock).answer().contains("2026-10-02"));
        assertTrue(SimpleChatPlan.classify(session(),"请问明天是什么日子？",false,clock).answer().contains("2026-10-03"));
        for(String q:new String[]{"今天是什么节日？","今天农历几号","文档发布日期是什么？","今天几号？再查一下天气"})assertFalse(SimpleChatPlan.classify(session(),q,false,clock).independent(),q);
    }
    @Test void decimalExpressionsDoNotGuessUnitsVariablesOrSourceValues(){
        assertEquals("12354.57",SimpleChatPlan.classify(session(),"计算：12345.67 + 8.9，只给结果。",false,clock).answer());
        assertEquals("7",SimpleChatPlan.classify(session(),"1 + 2 × 3 = ?",false,clock).answer());
        assertEquals("3",SimpleChatPlan.classify(session(),"1+2？",false,clock).answer());
        assertEquals("0.25",SimpleChatPlan.classify(session(),"1/4",false,clock).answer());
        assertFalse(SimpleChatPlan.classify(session(),"1/3*3",false,clock).direct());
        assertTrue(SimpleChatPlan.classify(session(),"1 / 0",false,clock).answer().contains("不能为0"));
        for(String q:new String[]{"1 2+3","预算加100元是多少","根据文档计算1+2","把1+2解释一下","a+2","1e3+2","1,000+2","(1+2"})assertFalse(SimpleChatPlan.classify(session(),q,false,clock).direct(),q);
    }
    @Test void independentQuestionsUseShortRequestsButReferencesAndChangingFactsDoNot(){
        for(String q:new String[]{"你好","把“Good morning”翻译成中文，只给译文。","HTTP与HTTPS的区别是什么？","JSON是什么？"})assertTrue(SimpleChatPlan.classify(session(),q,false,clock).independent(),q);
        for(String q:new String[]{"把刚才那句英文翻译成中文","继续","用哪个JDK？","我们的JSON配置是什么","查证HTTP的最新版本","只根据资料解释HTTPS","JSON是什么意思，帮我联网核实","请详细分析算法复杂度"})assertFalse(SimpleChatPlan.classify(session(),q,false,clock).independent(),q);
    }
    @Test void attachmentsCustomPromptsModesAndExplicitThinkingRetainFullBehavior(){
        var s=session();assertFalse(SimpleChatPlan.classify(s,"你好",true,clock).independent());
        s.setThinkingEffort("high");assertFalse(SimpleChatPlan.classify(s,"你好",false,clock).independent());s.setThinkingEffort("none");
        s.setKnowledgeMode("SELECTED");assertFalse(SimpleChatPlan.classify(s,"1+2",false,clock).direct());s.setKnowledgeMode("NONE");
        s.setAnswerMode("LOCAL");assertFalse(SimpleChatPlan.classify(s,"1+2",false,clock).direct());s.setAnswerMode("AUTO");
        s.setSystemPrompt("只使用英文");assertFalse(SimpleChatPlan.classify(s,"你好",false,clock).independent());
    }
    @Test void topicMentionsAreNotIndependentDefinitions(){
        for(String q:new String[]{"JSON接口的超时时间是什么？只答秒数。","HTTP服务的端口是什么？","SQL项目的目标是什么？","CSV导出的列名是什么？","JSON与XML的配置区别是什么？"})
            assertFalse(SimpleChatPlan.classify(session(),q,false,clock).independent(),q);
        for(String q:new String[]{"JSON是什么？","什么是HTTP？","HTTP与HTTPS的主要区别是什么？用一句话回答。","What is JSON?","difference between HTTP and HTTPS"})
            assertEquals("stable_fact",SimpleChatPlan.classify(session(),q,false,clock).kind(),q);
    }
    @Test void literalTranslationRetainsQuotedReferencesButCannotSwallowExtraActions(){
        for(String q:new String[]{"把“The current price is unknown.”翻译成中文，只给译文。","把“Remember this document.”翻译成中文。"})
            assertEquals("translation",SimpleChatPlan.classify(session(),q,false,clock).kind());
        for(String q:new String[]{"把刚才那句英文翻译成中文。","把“current price”翻译成中文，并搜索最新价格。"})
            assertFalse(SimpleChatPlan.classify(session(),q,false,clock).independent());
    }
}
