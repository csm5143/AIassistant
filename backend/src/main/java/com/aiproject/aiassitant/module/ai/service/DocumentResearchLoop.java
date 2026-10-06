package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.guard.GuardrailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Temporary document chats use the same search evidence service as persistent knowledge chats. */
@Service
@RequiredArgsConstructor
public class DocumentResearchLoop {
    private final WebResearchService research;
    private final GuardrailService guard;
    private final ObjectMapper json;
    static class Tools {
        @Tool("精确计算文档数值并核验记录和字段。文档数值用变量 v1、v2 表示，工具按原文核验并代入带正负号的值。避免重复计入小计，不同币种分别计算。")
        public String calculator(@P("文档计算用 v1+v2、(v1-v2)/v2*100 等表达式；没有文档证据时可用纯数字") String expression,
                                 @P("JSON数组字符串，每项包含key(如v1)、sourceIndex(原文引用编号)、recordId(完整行标识/名称)、field(原文列名或具名字段)、value(带正负号的原文数值)。没有文档证据时传[]") String recordsJson) { return ""; }
        @Tool("Search missing public knowledge, preferring official sources. Never send private document text. Use the returned citation numbers.")
        public String webSearch(@P("Public knowledge query for the missing subquestion") String query) { return ""; }
        @Tool("Read a URL from this turn's actual search results when evidence remains incomplete.")
        public String readWebPage(@P("URL returned by this turn's search") String url) { return ""; }
    }
    public Response<AiMessage> run(StreamingChatLanguageModel model, List<ChatMessage> messages,
                                  WebResearchService.Turn turn, List<Map<String,Object>> citations,
                                  BiConsumer<String,String> event, BooleanSupplier cancelled) throws Exception {
        return run(model,messages,turn,citations,event,cancelled,new java.util.concurrent.atomic.AtomicInteger());
    }
    public Response<AiMessage> run(StreamingChatLanguageModel model, List<ChatMessage> messages,
                                  WebResearchService.Turn turn, List<Map<String,Object>> citations,
                                  BiConsumer<String,String> event, BooleanSupplier cancelled, java.util.concurrent.atomic.AtomicInteger requests) throws Exception {
        return run(model,messages,turn,citations,event,cancelled,requests,Map.of());
    }
    public Response<AiMessage> run(StreamingChatLanguageModel model, List<ChatMessage> messages,
                                  WebResearchService.Turn turn, List<Map<String,Object>> citations,
                                  BiConsumer<String,String> event, BooleanSupplier cancelled, java.util.concurrent.atomic.AtomicInteger requests,
                                  Map<Integer,String> evidence) throws Exception {
        return run(model,messages,turn,citations,event,cancelled,requests,evidence,null);
    }
    public interface Checkpoints {
        void modelStarted() throws Exception;
        void reported(Response<AiMessage> response) throws Exception;
        String cached(String name,String arguments);
        void started(String name,String arguments) throws Exception;
        void finished(String name,String arguments,String result) throws Exception;
    }
    public Response<AiMessage> run(StreamingChatLanguageModel model,List<ChatMessage> messages,WebResearchService.Turn turn,List<Map<String,Object>> citations,
            BiConsumer<String,String> event,BooleanSupplier cancelled,java.util.concurrent.atomic.AtomicInteger requests,Map<Integer,String> evidence,Checkpoints checkpoints)throws Exception {
        String question=messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
            .reduce((a,b)->b).map(u->u.contents().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast).map(TextContent::text).findFirst().orElse("")).orElse("");
        boolean arithmetic=question.matches("(?is).*(合计|求和|计算|加总|加起来|平均值|差额|增幅|\\b(sum|compute|calculate|average)\\b).*");
        var specs=new ArrayList<>(ToolSpecifications.toolSpecificationsFrom(new Tools()));
        specs.removeIf(s->s.name().equals("calculator")?!arithmetic:!turn.policy.allowed());
        if(arithmetic)messages.add(messages.size()-1,SystemMessage.from("本轮数值计算必须先调用 calculator 核算。从证据中选取完整、去重的明细，分别处理各币种；最终金额与工具结果保持一致。"
                + (evidence.isEmpty() ? "" : "文档操作数必须用v1、v2等变量，recordsJson逐项提供原文编号、完整记录标识、精确列名和带符号数值。例如expression=v1+v2，recordsJson=[{\"key\":\"v1\",\"sourceIndex\":1,\"recordId\":\"INV-A101\",\"field\":\"Amount\",\"value\":\"100.00\"},{\"key\":\"v2\",\"sourceIndex\":2,\"recordId\":\"INV-A102\",\"field\":\"Amount\",\"value\":\"-10.00\"}]。表格field用原文列名，年度表用2024/25这类列名；跨页续行用Amount这类具名字段。核验失败必须修正或明确无法确认，不得改用纯数字绕过核验。")));
        boolean calculated=false;
        boolean pendingFailure=false;
        int verifiedCalculations=0, rejectedCalculations=0;
        List<DocumentCalculationVerifier.Binding> verifiedRecords=new ArrayList<>();
        Set<Integer> calculationSources=new TreeSet<>();
        int input=0,output=0;
        for(int iteration=0;iteration<5;iteration++) {
            if(cancelled.getAsBoolean())throw new CancellationException();
            var text=new StringBuilder(); var filter=guard.newOutputFilter();
            var done=new CountDownLatch(1);var response=new AtomicReference<Response<AiMessage>>();var error=new AtomicReference<Throwable>();
            requests.incrementAndGet();
            if(checkpoints!=null)checkpoints.modelStarted();
            model.generate(messages, iteration==4?List.of():specs, new StreamingResponseHandler<AiMessage>() {
                public void onNext(String token){if(!cancelled.getAsBoolean()){text.append(token);String safe=filter.append(token);if(!safe.isEmpty())event.accept("token",safe);}}
                public void onComplete(Response<AiMessage> value){try{if(checkpoints!=null)checkpoints.reported(value);response.set(value);}catch(Exception e){error.set(e);}finally{done.countDown();}}
                public void onError(Throwable value){error.set(value);done.countDown();}
            });
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
            while(!done.await(200,TimeUnit.MILLISECONDS)) {
                if(cancelled.getAsBoolean())throw new CancellationException();
                if(System.nanoTime()>deadline)throw new TimeoutException("Document answer timed out");
            }
            if(cancelled.getAsBoolean())throw new CancellationException();
            if(error.get()!=null)throw new IllegalStateException("Model failed",error.get());
            var result=response.get();var usage=result.tokenUsage();
            if(usage!=null){input+=usage.inputTokenCount()==null?0:usage.inputTokenCount();output+=usage.outputTokenCount()==null?0:usage.outputTokenCount();}
            if(!result.content().hasToolExecutionRequests()) {
                if(arithmetic&&(!calculated||pendingFailure)) {
                    if(iteration==4) {
                        String missing=AnswerLanguage.english(question)?"I could not verify the document values against their records and fields, so I cannot confirm this calculation.":"未能核验文档数值与记录、字段的对应关系，因此无法确认本次计算结果。";
                        event.accept("replace",missing);
                        event.accept("calculation_stats",json.writeValueAsString(Map.of("verifiedCalculations",verifiedCalculations,"rejectedCalculations",rejectedCalculations,"records",verifiedRecords,"complete",false)));
                        return Response.from(AiMessage.from(missing),new TokenUsage(input,output));
                    }
                    event.accept("replace","");messages.add(result.content());messages.add(UserMessage.from("请先调用 calculator 核算上述计算式，再给出最终结果。"));continue;
                }
                String tail=filter.finish();if(!tail.isEmpty())event.accept("token",tail);
                String answer=text.isEmpty()?Objects.toString(result.content().text(),""):text.toString();
                String supplement=DocumentCalculationCitations.missing(answer,calculationSources,AnswerLanguage.english(question));
                if(!verifiedRecords.isEmpty())supplement=supplement.replace("数值出处：","已核验的计算出处：").replace("Numeric sources:","Verified calculation sources:");
                if(!supplement.isEmpty()){event.accept("token",supplement);answer+=supplement;}
                if(arithmetic)event.accept("calculation_stats",json.writeValueAsString(Map.of("verifiedCalculations",verifiedCalculations,"rejectedCalculations",rejectedCalculations,"records",verifiedRecords,"complete",true)));
                return Response.from(AiMessage.from(answer),new TokenUsage(input,output));
            }
            if(!text.isEmpty())event.accept("replace","");
            messages.add(result.content());
            for(var request:result.content().toolExecutionRequests()) {
                if(cancelled.getAsBoolean())throw new CancellationException();
                String value;
                if((!turn.policy.allowed()&&!request.name().equals("calculator"))||iteration==4||specs.stream().noneMatch(s->s.name().equals(request.name())))value="本轮不允许该工具，请使用已有证据。";
                else {
                    event.accept("tool_call",json.writeValueAsString(Map.of("name",request.name(),"arguments",request.arguments())));
                    var args=json.readTree(request.arguments());
                    String cached=checkpoints==null||request.name().equals("calculator")?null:checkpoints.cached(request.name(),request.arguments());
                    if(checkpoints!=null&&cached==null)checkpoints.started(request.name(),request.arguments());
                    if(cached!=null)value=cached;
                    else{
                    if(request.name().equals("calculator")) {
                        try{
                            String expression=args.path("expression").asText();
                            if(evidence.isEmpty())value=DecimalCalculator.calculate(expression);
                            else {
                                var verified=DocumentCalculationVerifier.calculate(expression,args.path("recordsJson").asText(),evidence);
                                value=json.writeValueAsString(Map.of("result",verified.value(),"verifiedRecords",verified.records()));
                                verifiedCalculations++;
                                verifiedRecords.addAll(verified.records());
                                calculationSources.addAll(verified.indices());
                                for(var citation:citations)if(citation.get("index") instanceof Number index) {
                                    var bindings=verifiedRecords.stream().filter(record->record.sourceIndex()==index.intValue()).distinct().toList();
                                    if(!bindings.isEmpty())citation.put("verifiedCalculationRecords",bindings);
                                }
                            }
                            calculated=true;pendingFailure=false;
                        }
                        catch(RuntimeException e){value="计算核验失败："+e.getMessage();pendingFailure=true;rejectedCalculations++;}
                    }else value=request.name().equals("webSearch")?research.search(args.path("query").asText(),turn,citations)
                            :research.read(args.path("url").asText(),turn,citations);
                    if(checkpoints!=null)checkpoints.finished(request.name(),request.arguments(),value);
                    }
                    event.accept("tool_result",json.writeValueAsString(Map.of("name",request.name(),"result",request.name().equals("calculator")?value:"已完成资料检查")));
                }
                messages.add(ToolExecutionResultMessage.from(request,value));
            }
        }
        throw new IllegalStateException("No final answer");
    }
}
