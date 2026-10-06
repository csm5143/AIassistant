package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekFlashStreamingModelTest {
    @Test void cacheUsageIsReportedSeparatelyAndMissingPartialOrInvalidCountsStayUnknown()throws Exception {
        for(String fields:List.of(",\"prompt_cache_hit_tokens\":512,\"prompt_cache_miss_tokens\":265", "", ",\"prompt_cache_hit_tokens\":0", ",\"prompt_cache_hit_tokens\":512,\"prompt_cache_miss_tokens\":100", ",\"prompt_cache_hit_tokens\":-1,\"prompt_cache_miss_tokens\":778")){
            var result=new AtomicReference<Response<AiMessage>>();
            String data="data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":777,\"completion_tokens\":1"+fields+"}}\ndata: [DONE]\n";
            model.read(stream(data),handler(new StringBuilder(),result));assertEquals(777,result.get().tokenUsage().inputTokenCount());
            if(fields.endsWith(":265")){assertEquals(512L,result.get().metadata().get("cacheHitTokens"));assertEquals(265L,result.get().metadata().get("cacheMissTokens"));}
            else assertTrue(result.get().metadata().isEmpty());
        }
    }
    private final DeepSeekFlashStreamingModel model=new DeepSeekFlashStreamingModel("https://api.deepseek.com/v1","test-key",.3,4096);
    static class Tools {@Tool("Add decimal numbers") public String calculate(@P("expression")String expression){return "";}}
    private InputStream stream(String data){return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));}
    private StreamingResponseHandler<AiMessage> handler(StringBuilder tokens,AtomicReference<Response<AiMessage>> result) {
        return new StreamingResponseHandler<>() {
            public void onNext(String text){tokens.append(text);}
            public void onComplete(Response<AiMessage> response){result.set(response);}
            public void onError(Throwable error){fail(error);}
        };
    }
    @Test void sendsNativeImagesToolsUsageAndExplicitNonThinkingInOneRequest()throws Exception {
        var user=UserMessage.from(TextContent.from("read amount"),ImageContent.from("YWJj","image/png"));
        var request=model.request(List.of(user),ToolSpecifications.toolSpecificationsFrom(new Tools()));
        assertEquals("deepseek-flash",request.path("model").asText());assertEquals("disabled",request.at("/thinking/type").asText());
        assertTrue(request.at("/stream_options/include_usage").asBoolean());assertEquals("data:image/png;base64,YWJj",request.at("/messages/0/content/1/image_url/url").asText());
        assertEquals("calculate",request.at("/tools/0/function/name").asText());
    }
    @Test void accumulatesStreamTextAndUsesLastUsageChunk()throws Exception {
        String data="data: {\"choices\":[{\"delta\":{\"content\":\"金额\"},\"finish_reason\":null}]}\n\n"
            +"data: {\"choices\":[{\"delta\":{\"content\":\"123.45\"},\"finish_reason\":\"stop\"}]}\n\n"
            +"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":777,\"completion_tokens\":9}}\n\ndata: [DONE]\n\n";
        var result=new AtomicReference<Response<AiMessage>>();var text=new StringBuilder();model.read(stream(data),handler(text,result));
        assertEquals("金额123.45",text.toString());assertEquals(text.toString(),result.get().content().text());assertEquals(777,result.get().tokenUsage().inputTokenCount());
    }
    @Test void assemblesFragmentedToolCallsWithoutReturningArgumentsAsAnswer()throws Exception {
        String data="data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"calculate\",\"arguments\":\"{\\\"expression\\\":\\\"\"}}]}}]}\n"
            +"data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"1+2\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\ndata: [DONE]\n";
        var result=new AtomicReference<Response<AiMessage>>();model.read(stream(data),handler(new StringBuilder(),result));
        var call=result.get().content().toolExecutionRequests().get(0);assertEquals("call-1",call.id());assertEquals("calculate",call.name());assertEquals("{\"expression\":\"1+2\"}",call.arguments());assertNull(result.get().content().text());
    }
    @Test void truncatedErroredAndIncompleteStreamsCannotBeSuccessfulAnswers() {
        for(String data:List.of("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n", "data: {\"error\":{\"message\":\"failed\"}}\n", "data: {\"choices\":[{\"delta\":{\"content\":\"cut\"},\"finish_reason\":\"length\"}]}\ndata: [DONE]\n", "data: [DONE]\n")) {
            var result=new AtomicReference<Response<AiMessage>>();assertThrows(IOException.class,()->model.read(stream(data),handler(new StringBuilder(),result)));assertNull(result.get());
        }
    }
    @Test void supportedLevelsHaveDistinctProviderParametersAndToolThoughtsStayInsideTheRequest()throws Exception {
        for(String effort:List.of("none","low","high","max")) {
            var configured=new DeepSeekFlashStreamingModel("https://api.deepseek.com/v1","test-key",.3,4096,effort);
            var body=configured.request(List.of(UserMessage.from("hi")),List.of());
            assertEquals(effort.equals("none")?"disabled":"enabled",body.at("/thinking/type").asText());
            if(!effort.equals("none"))assertEquals(effort,body.path("reasoning_effort").asText());
        }
        assertThrows(com.aiproject.aiassitant.common.BizException.class,()->ThinkingEffort.normalize("ultra"));
        String data="data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private test reasoning\",\"content\":\"visible answer\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n";
        var result=new AtomicReference<Response<AiMessage>>();var visible=new StringBuilder();model.read(stream(data),handler(visible,result));
        assertEquals("visible answer",visible.toString());
        var thinking=new DeepSeekFlashStreamingModel("https://api.deepseek.com/v1","test-key",.3,4096,"high");
        var next=thinking.request(List.of(UserMessage.from("hi"),result.get().content()),ToolSpecifications.toolSpecificationsFrom(new Tools()));
        assertEquals("private test reasoning",next.at("/messages/1/reasoning_content").asText());
    }
}
