package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.guard.GuardrailService;
import com.aiproject.aiassitant.module.guard.StreamingKeywordMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentCalculationLoopTest {
    private final ObjectMapper json = new ObjectMapper();
    private GuardrailService guard() {
        var guard = mock(GuardrailService.class);
        when(guard.newOutputFilter()).thenAnswer(call -> new StreamingKeywordMasker(List.of(),"***"));
        return guard;
    }
    private WebResearchService.Turn local() { return new WebResearchService.Turn(new ResearchPolicy.Decision(false,false,"local")); }
    @Test void rejectsWrongFieldValueThenCorrectsWithoutGuessingRepeatedNumericSources() throws Exception {
        var model = mock(StreamingChatLanguageModel.class);
        var iteration = new AtomicInteger();
        doAnswer(call -> {
            StreamingResponseHandler<AiMessage> handler = call.getArgument(2);
            int step = iteration.getAndIncrement();
            if (step < 2) {
                String records = step == 0
                        ? "[{\"key\":\"v1\",\"sourceIndex\":1,\"recordId\":\"INV-A1\",\"field\":\"Amount\",\"value\":\"10.00\"}]"
                        : "[{\"key\":\"v1\",\"sourceIndex\":1,\"recordId\":\"INV-A1\",\"field\":\"Amount\",\"value\":\"100.00\"}]";
                handler.onComplete(Response.from(AiMessage.from(List.of(ToolExecutionRequest.builder().id("calc"+step).name("calculator")
                        .arguments(json.writeValueAsString(Map.of("expression","v1","recordsJson",records))).build())),new TokenUsage(10,5)));
            } else { handler.onNext("合计100.00");handler.onComplete(Response.from(AiMessage.from("合计100.00"),new TokenUsage(10,5))); }
            return null;
        }).when(model).generate(anyList(),anyList(),any());
        List<String> stats = new ArrayList<>(), toolResults = new ArrayList<>();
        List<Map<String,Object>> sources = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("index",1))));
        var response = new DocumentResearchLoop(mock(WebResearchService.class),guard(),json).run(model,
                new ArrayList<>(List.of(UserMessage.from("只根据文档计算 Amount 合计"))),local(),sources,
                (type,data) -> { if(type.equals("calculation_stats"))stats.add(data); if(type.equals("tool_result"))toolResults.add(data); },
                () -> false,new AtomicInteger(),Map.of(1,"| ID | Amount | Tax |\n| --- | --- | --- |\n| INV-A1 | 100.00 | 10.00 |"));
        assertTrue(toolResults.get(0).contains("核验失败"));
        assertTrue(response.content().text().contains("已核验的计算出处：[1]"));
        assertEquals(30,response.tokenUsage().inputTokenCount());
        assertEquals(1,json.readTree(stats.get(0)).path("rejectedCalculations").asInt());
        assertEquals(1,json.readTree(stats.get(0)).path("records").size());
        assertNotNull(sources.get(0).get("verifiedCalculationRecords"));
    }
    @Test void returnsAnExplicitRefusalWhenTheModelNeverVerifiesTheCalculation() throws Exception {
        var model = mock(StreamingChatLanguageModel.class);
        doAnswer(call -> { StreamingResponseHandler<AiMessage> handler = call.getArgument(2);
            handler.onNext("合计110"); handler.onComplete(Response.from(AiMessage.from("合计110"),new TokenUsage(10,5)));return null;
        }).when(model).generate(anyList(),anyList(),any());
        var response = new DocumentResearchLoop(mock(WebResearchService.class),guard(),json).run(model,
                new ArrayList<>(List.of(UserMessage.from("计算合计"))),local(),new ArrayList<>(),(type,data)->{},()->false,
                new AtomicInteger(),Map.of(1,"原文缺少明确字段"));
        assertTrue(response.content().text().contains("无法确认"));
        assertFalse(response.content().text().contains("110"));
        assertEquals(50,response.tokenUsage().inputTokenCount());
    }
}
