package com.aiproject.aiassitant.module.chat.service;

import com.aiproject.aiassitant.common.BizException;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Durable application checkpoints, not provider token/KV-cache continuation. Single local server. */
@Service
@lombok.extern.slf4j.Slf4j
public class RunJournal {
    public static final Set<String> READ_ONLY=Set.of("inspectTable","readDocuments","calculator","currentTime","knowledgeSearch","webSearch","readWebPage","summarizeUrl","imageRecognition","ocrExtract");
    public static class Step {
        public String name,arguments,status="RUNNING",result,startedAt=Instant.now().toString(),finishedAt;
    }
    public static class Task {
        public String id=UUID.randomUUID().toString().replace("-",""),answerId=UUID.randomUUID().toString().replace("-",""),sessionId,userId,kind;
        public String status="RUNNING",question="",model,settings="",files="",prompt,partial="",note="",createdAt=Instant.now().toString(),updatedAt=createdAt;
        public List<String> images=new ArrayList<>();
        public List<Step> steps=new ArrayList<>();
        public List<Map<String,Object>> citations=new ArrayList<>();
        public List<Object> artifacts=new ArrayList<>();
        public Map<String,Object> result;
        public Map<Integer,String> evidence=new LinkedHashMap<>();
        public int attempts=1,modelRequests,reportedModelRequests;
        public long promptTokens,completionTokens;
    }
    private final Path root; private final ObjectMapper json;
    public RunJournal(@Value("${ai.chat.runs-dir:D:/AIassistant-env/runtime/chat-runs}") String root,ObjectMapper json){this.root=Path.of(root).toAbsolutePath().normalize();this.json=json;}
    private static void id(String value){if(value==null||!value.matches("[a-f0-9]{32}"))throw new BizException(400,"任务编号无效");}
    private static void sessionId(String value){if(value==null||!value.matches("(?:[a-f0-9]{12}|[a-f0-9]{32})"))throw new BizException(400,"会话编号无效");}
    private Path dir(String user)throws IOException {
        if(user==null||user.isBlank()||user.equals("anonymous"))throw BizException.unauthorized("请先登录");
        try{Path p=root.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(user.getBytes(StandardCharsets.UTF_8))));Files.createDirectories(p);return p;}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void save(Task t)throws IOException {
        t.updatedAt=Instant.now().toString();id(t.id);Path path=dir(t.userId).resolve(t.id+".json"),tmp=path.resolveSibling(t.id+".tmp");
        if(Files.exists(path)){Task old=json.readValue(path.toFile(),Task.class);t.modelRequests=Math.max(t.modelRequests,old.modelRequests);t.reportedModelRequests=Math.max(t.reportedModelRequests,old.reportedModelRequests);t.promptTokens=Math.max(t.promptTokens,old.promptTokens);t.completionTokens=Math.max(t.completionTokens,old.completionTokens);}
        byte[] data=json.writeValueAsBytes(t);if(data.length>8*1024*1024)throw new IOException("任务检查点超过8MB上限");
        Files.write(tmp,data,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public synchronized Task create(String user,String session,String kind,String question,String model,String settings,String files,List<String> images)throws IOException {
        sessionId(session);var old=list(user,session);if(old.size()>=200){var oldest=old.stream().filter(t->"COMPLETED".equals(t.status)).reduce((a,b)->b).orElse(null);if(oldest==null)throw new BizException(429,"未完成任务记录已达200条上限，请使用新聊天");Files.deleteIfExists(dir(user).resolve(oldest.id+".json"));}
        Task t=new Task();t.userId=user;t.sessionId=session;t.kind=kind;t.question=question;t.model=model;t.settings=settings;t.files=files;if(images!=null)t.images=new ArrayList<>(images);save(t);return t;
    }
    public synchronized Task get(String user,String session,String key)throws IOException {
        sessionId(session);id(key);Path path=dir(user).resolve(key+".json");if(!Files.isRegularFile(path))throw BizException.notFound("任务不存在或已过期");
        Task t=json.readValue(path.toFile(),Task.class);if(!user.equals(t.userId)||!session.equals(t.sessionId))throw BizException.notFound("任务不存在");
        if(expired(t)){Files.deleteIfExists(path);throw BizException.notFound("任务不存在或已过期");}return t;
    }
    private boolean expired(Task t){return !"RUNNING".equals(t.status)&&Instant.parse(t.createdAt).isBefore(Instant.now().minus(Duration.ofDays(7)));}
    public synchronized List<Task> list(String user,String session)throws IOException {
        sessionId(session);List<Task> out=new ArrayList<>();try(var paths=Files.newDirectoryStream(dir(user),"*.json")){for(Path p:paths){try{Task t=json.readValue(p.toFile(),Task.class);if(!user.equals(t.userId))continue;
            if(expired(t)){Files.deleteIfExists(p);continue;}
            if(session.equals(t.sessionId))out.add(t);}catch(IOException|RuntimeException e){log.warn("Skipped unreadable task checkpoint {}",p.getFileName());}}}
        out.sort(Comparator.comparing((Task t)->t.createdAt).reversed());return out;
    }
    public synchronized Task claim(String user,String session,String key,String settings,String files)throws IOException {
        Task t=get(user,session,key);if(!Set.of("INTERRUPTED","FAILED","CANCELLED").contains(t.status))throw new BizException(409,"当前任务不能恢复，请刷新查看状态");
        if(t.attempts>=5)throw new BizException(409,"任务已达5次处理尝试上限");
        if(!Objects.equals(settings,t.settings)||!Objects.equals(files,t.files))throw new BizException(409,"模型、资料范围或附件已变化，请还原设置后恢复，或重新提问");
        if(t.steps.stream().anyMatch(s->"RUNNING".equals(s.status)&&!READ_ONLY.contains(s.name)))throw new BizException(409,"中断步骤的结果无法确认，请先核对已有成果，不能自动重复执行");
        t.steps.removeIf(s->"RUNNING".equals(s.status));t.status="RUNNING";t.attempts++;t.note="";save(t);return t;
    }
    public synchronized void prepared(Task t,String prompt,List<Map<String,Object>> citations)throws IOException {t.prompt=prompt;t.citations=new ArrayList<>(citations==null?List.of():citations);save(t);}
    public synchronized void preparedDocument(Task t,String prompt,List<Map<String,Object>> citations,Map<Integer,String> evidence)throws IOException {t.evidence=new LinkedHashMap<>(evidence);prepared(t,prompt,citations);}
    public synchronized void partial(Task t,String text)throws IOException {t.partial=text.substring(0,Math.min(text.length(),100000));save(t);}
    public synchronized Step completed(Task t,String name,String args){return t.steps.stream().filter(s->"SUCCEEDED".equals(s.status)&&s.name.equals(name)&&sameArgs(s.arguments,args)).findFirst().orElse(null);}
    private boolean sameArgs(String a,String b){try{return json.readTree(a).equals(json.readTree(b));}catch(Exception e){return Objects.equals(a,b);}}
    public synchronized Step startStep(Task t,String name,String args)throws IOException {if(t.steps.size()>=40)throw new BizException(409,"任务步骤超过40项上限");Step s=new Step();s.name=name;s.arguments=args;t.steps.add(s);save(t);return s;}
    public synchronized void finishStep(Task t,Step s,String result,boolean ok,List<Map<String,Object>> citations,List<Object> artifacts)throws IOException {
        s.result=result;s.status=ok?"SUCCEEDED":"FAILED";s.finishedAt=Instant.now().toString();t.citations=new ArrayList<>(citations==null?List.of():citations);t.artifacts=new ArrayList<>(artifacts==null?List.of():artifacts);save(t);
    }
    public synchronized void modelStarted(Task t)throws IOException {t.modelRequests++;save(t);}
    public synchronized void modelReported(Task t,long input,long output)throws IOException {
        // A provider may finish an old cancelled attempt after a newer attempt was claimed.
        Task latest=get(t.userId,t.sessionId,t.id);latest.reportedModelRequests++;latest.promptTokens+=input;latest.completionTokens+=output;save(latest);
        t.reportedModelRequests=latest.reportedModelRequests;t.promptTokens=latest.promptTokens;t.completionTokens=latest.completionTokens;
    }
    public synchronized void complete(Task t,Map<String,Object> result)throws IOException {t.result=result;t.status="COMPLETED";t.note="";save(t);}
    public synchronized void interrupt(Task t,String note)throws IOException {
        if(!"RUNNING".equals(t.status))return;
        t.status=t.steps.stream().anyMatch(s->"RUNNING".equals(s.status)&&!READ_ONLY.contains(s.name))?"UNCERTAIN":"INTERRUPTED";t.note=note;save(t);
    }
    public synchronized void fail(Task t,String note)throws IOException {interrupt(t,note);if("INTERRUPTED".equals(t.status)){t.status="FAILED";save(t);}}
    public synchronized void remove(String user,String session)throws IOException {for(Task t:list(user,session))Files.deleteIfExists(dir(user).resolve(t.id+".json"));}
    @PostConstruct public synchronized void recover()throws IOException {
        Files.createDirectories(root);try(var owners=Files.newDirectoryStream(root)){for(Path owner:owners){if(!Files.isDirectory(owner)||!owner.getFileName().toString().matches("[a-f0-9]{64}"))continue;
            try(var paths=Files.newDirectoryStream(owner,"*.json")){for(Path path:paths){try{Task t=json.readValue(path.toFile(),Task.class);if("RUNNING".equals(t.status))interrupt(t,"服务中断，已保存检查点；由你选择后恢复");}catch(IOException|RuntimeException e){log.warn("Skipped unreadable task checkpoint {}",path.getFileName());}}}}}
    }
    public Map<String,Object> summary(Task t){var v=view(t);v.remove("partial");v.remove("result");v.put("question",t.question.substring(0,Math.min(t.question.length(),160)));return v;}
    public Map<String,Object> view(Task t){
        Map<String,Object> v=new LinkedHashMap<>();v.put("id",t.id);v.put("kind",t.kind);v.put("status",t.status);v.put("question",t.question);v.put("partial",t.partial);v.put("note",t.note);v.put("attempts",t.attempts);v.put("createdAt",t.createdAt);v.put("updatedAt",t.updatedAt);
        v.put("steps",t.steps.stream().map(s->Map.of("name",s.name,"status",s.status)).toList());v.put("artifacts",t.artifacts);v.put("result",t.result);
        v.put("modelRequests",t.modelRequests);v.put("reportedModelRequests",t.reportedModelRequests);v.put("promptTokens",t.promptTokens);v.put("completionTokens",t.completionTokens);v.put("usageIncomplete",t.modelRequests>t.reportedModelRequests);return v;
    }
}
