package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.chat.service.RunJournal;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.security.core.context.SecurityContextHolder;

@Service @RequiredArgsConstructor
public class WorkspaceJobs {
    private final RunJournal journal;private final WorkspaceToolService tools;private final ToolFileStore files;private final ObjectMapper json;
    private final com.aiproject.aiassitant.module.chat.service.SessionRunGate gate;
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final ExecutorService executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),r->{Thread t=new Thread(r,"workspace-jobs");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public String fileSnapshot(String session)throws IOException{return json.writeValueAsString(files.list(session).stream().filter(a->a.kind().equals("upload")).toList());}
    public static String settings(ChatSession s){return String.join("|",Objects.toString(s.getModel(),""),Objects.toString(s.getThinkingEffort(),""),Objects.toString(s.getAnswerMode(),""),Objects.toString(s.getKnowledgeMode(),""),Objects.toString(s.getKnowledgeSelectionJson(),""),Objects.toString(s.getSystemPrompt(),""));}
    public boolean isRunning(String session){return running.contains(session);}
    public synchronized RunJournal.Task start(String session,String name,String args)throws IOException {
        tools.ownedSession(session);if(!WorkspaceToolService.NAMES.contains(name))throw new BizException(400,"未知文件工具");if(running.contains(session))throw new BizException(409,"当前文件任务尚未完成");
        if(args.length()>500000)throw new BizException(400,"工具参数超过50万字符");
        gate.claim(session);try{var t=journal.create(SecurityUtil.getCurrentUserId(),session,"TOOL",name,null,"",fileSnapshot(session),null);journal.startStep(t,name,args);dispatch(t);return t;}catch(Exception e){gate.release(session);throw e;}
    }
    public synchronized RunJournal.Task resume(String session,String id)throws IOException {
        tools.ownedSession(session);if(running.contains(session))throw new BizException(409,"当前文件任务尚未完成");
        var old=journal.get(SecurityUtil.getCurrentUserId(),session,id);if(!"TOOL".equals(old.kind))throw new BizException(400,"任务类型错误");
        // A read-only in-flight intent may be retried; completed operations always reuse their result.
        if(old.steps.isEmpty())throw new BizException(409,"中断时参数尚未登记，请重新提交文件任务");
        var previous=old.steps.get(0);String name=previous.name,args=previous.arguments;
        gate.claim(session);try{var t=journal.claim(SecurityUtil.getCurrentUserId(),session,id,"",fileSnapshot(session));if(t.steps.isEmpty())journal.startStep(t,name,args);dispatch(t);return t;}catch(Exception e){gate.release(session);throw e;}
    }
    private void dispatch(RunJournal.Task t)throws IOException {
        var context=SecurityContextHolder.getContext();running.add(t.sessionId);
        try{executor.execute(()->{SecurityContextHolder.setContext(context);try{
            var step=t.steps.get(0);Map<String,Object> result;
            if("SUCCEEDED".equals(step.status))result=json.readValue(step.result,Map.class);
            else{result=tools.execute(t.sessionId,step.name,json.readTree(step.arguments));journal.finishStep(t,step,json.writeValueAsString(result),true,List.of(),result.get("artifacts") instanceof List<?> a?new ArrayList<>(a):List.of());}
            journal.complete(t,result);
        }catch(Exception e){try{journal.fail(t,"文件步骤未完成，请核对已有成果后重试");}catch(IOException ignored){}}
        finally{running.remove(t.sessionId);gate.release(t.sessionId);SecurityContextHolder.clearContext();}});}catch(RejectedExecutionException e){running.remove(t.sessionId);journal.fail(t,"文件任务队列已满，请稍后重试");throw new BizException(503,"文件任务队列已满");}
    }
    @PreDestroy void close(){executor.shutdown();}
}
