package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ai4j.openai4j.Json;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.InternalOpenAiHelper;
import dev.langchain4j.model.output.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Official Flash adapter: current SDK predates the provider's explicit thinking parameter. */
final class DeepSeekFlashStreamingModel implements StreamingChatLanguageModel {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json=new ObjectMapper();
    private final String baseUrl,key;
    private final double temperature;
    private final int maxTokens;
    private final String effort;
    DeepSeekFlashStreamingModel(String baseUrl,String key,double temperature,int maxTokens) {
        this(baseUrl,key,temperature,maxTokens,"none");
    }
    DeepSeekFlashStreamingModel(String baseUrl,String key,double temperature,int maxTokens,String effort) {
        this.baseUrl=baseUrl;this.key=key;this.temperature=temperature;this.maxTokens=maxTokens;
        this.effort=ThinkingEffort.normalize(effort);
    }
    ObjectNode request(List<ChatMessage> messages,List<ToolSpecification> tools)throws IOException {
        var builder=ChatCompletionRequest.builder().model("deepseek-flash")
                .messages(InternalOpenAiHelper.toOpenAiMessages(messages)).stream(true).temperature(temperature).maxTokens(effort.equals("none")?maxTokens:Math.max(maxTokens,16384));
        if(!tools.isEmpty())builder.tools(InternalOpenAiHelper.toTools(tools,false));
        ObjectNode body=(ObjectNode)json.readTree(Json.toJson(builder.build()));
        body.putObject("thinking").put("type",effort.equals("none")?"disabled":"enabled");
        if(!effort.equals("none")) {
            body.put("reasoning_effort",effort);
            for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof AiMessage assistant) {
                ((ObjectNode)body.path("messages").get(i)).put("reasoning_content",assistant instanceof ReasonedMessage reasoned?reasoned.reasoning:"");
            }
        }
        body.putObject("stream_options").put("include_usage",true);
        return body;
    }
    @Override public void generate(List<ChatMessage> messages,StreamingResponseHandler<AiMessage> handler) {
        generate(messages,List.of(),handler);
    }
    @Override public void generate(List<ChatMessage> messages,List<ToolSpecification> tools,StreamingResponseHandler<AiMessage> handler) {
        try {
            var request=HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/$","")+"/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").header("Accept","text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request(messages,tools)))).build();
            http.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream()).whenComplete((response,error)->{
                if(error!=null){handler.onError(error);return;}
                try(var stream=response.body()) {
                    if(response.statusCode()!=200)throw new IOException("模型请求失败 HTTP "+response.statusCode());
                    read(stream,handler);
                }catch(Exception e){handler.onError(e);}
            });
        }catch(Exception e){handler.onError(e);}
    }
    private static final class Call {
        String id="";final StringBuilder name=new StringBuilder(),args=new StringBuilder();
    }
    private static final class ReasonedMessage extends AiMessage {
        final String reasoning;
        ReasonedMessage(String text,String reasoning){super(text);this.reasoning=reasoning;}
        ReasonedMessage(List<ToolExecutionRequest> calls,String reasoning){super(calls);this.reasoning=reasoning;}
        ReasonedMessage(String text,List<ToolExecutionRequest> calls,String reasoning){super(text,calls);this.reasoning=reasoning;}
    }
    void read(InputStream input,StreamingResponseHandler<AiMessage> handler)throws IOException {
        var text=new StringBuilder();var reasoning=new StringBuilder();Map<Integer,Call> calls=new TreeMap<>();TokenUsage usage=null;Map<String,Object> metadata=Map.of();String finish=null;boolean done=false;
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(!line.startsWith("data:"))continue;
                String data=line.substring(5).strip();if(data.equals("[DONE]")){done=true;break;}if(data.isBlank())continue;
                JsonNode root=json.readTree(data);if(root.has("error"))throw new IOException("模型流返回错误");
                JsonNode reported=root.path("usage");
                if(reported.has("prompt_tokens")&&reported.has("completion_tokens")){
                    usage=new TokenUsage(reported.path("prompt_tokens").asInt(),reported.path("completion_tokens").asInt());metadata=Map.of();
                    var hit=reported.path("prompt_cache_hit_tokens");var miss=reported.path("prompt_cache_miss_tokens");
                    if(hit.isIntegralNumber()&&miss.isIntegralNumber()&&hit.asLong()>=0&&miss.asLong()>=0&&hit.asLong()+miss.asLong()==usage.inputTokenCount())
                        metadata=Map.of("cacheHitTokens",hit.asLong(),"cacheMissTokens",miss.asLong());
                }
                for(JsonNode choice:root.path("choices")) {
                    if(!choice.path("finish_reason").isNull() && choice.has("finish_reason"))finish=choice.path("finish_reason").asText();
                    var delta=choice.path("delta");String content=delta.path("content").asText("");
                    reasoning.append(delta.path("reasoning_content").asText(""));
                    if(!content.isEmpty()){text.append(content);handler.onNext(content);}
                    for(JsonNode fragment:delta.path("tool_calls")) {
                        Call call=calls.computeIfAbsent(fragment.path("index").asInt(),i->new Call());
                        if(fragment.hasNonNull("id"))call.id=fragment.path("id").asText();
                        call.name.append(fragment.path("function").path("name").asText(""));
                        call.args.append(fragment.path("function").path("arguments").asText(""));
                    }
                }
            }
        }
        if(!done)throw new IOException("模型流意外中断，答案未完成");
        if("length".equals(finish))throw new IOException("模型输出达到上限，答案未完成");
        List<ToolExecutionRequest> requests=new ArrayList<>();
        for(Call call:calls.values()) {
            if(call.id.isBlank()||call.name.isEmpty())throw new IOException("工具调用数据不完整");
            requests.add(ToolExecutionRequest.builder().id(call.id).name(call.name.toString()).arguments(call.args.toString()).build());
        }
        if(text.isEmpty()&&requests.isEmpty())throw new IOException("模型未返回答案");
        AiMessage result=requests.isEmpty()?new ReasonedMessage(text.toString(),reasoning.toString()):text.isEmpty()?new ReasonedMessage(requests,reasoning.toString()):new ReasonedMessage(text.toString(),requests,reasoning.toString());
        handler.onComplete(Response.from(result,usage,InternalOpenAiHelper.finishReasonFrom(finish),metadata));
    }
}
