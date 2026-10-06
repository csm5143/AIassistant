package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.security.AppPrincipal;
import com.aiproject.aiassitant.ai.config.EmbeddingModelConfig;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.knowledge.service.SourceDocumentParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import dev.langchain4j.data.embedding.Embedding;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class DocumentUploadJobs {
    public static final int PARSER_VERSION = 4;
    public static class Job {
        public String id,ownerId,sessionId,fileName,sha256,sourceVersion,status,phase,error;
        public int completed,total,attempts,recoveries;
        public long createdAt,updatedAt;
        public Map<String,Object> result;
        public boolean deleteRequested;
    }
    record SavedEmbeddings(int parserVersion,String fingerprint,String sha256,List<DocumentQaSessionStore.StoredChunk> chunks,DocumentEmbeddingBatcher.Stats stats) {}
    record SavedSource(int parserVersion,String sha256,SourceDocumentParser.SourceDocument document) {}
    private final DocumentUploadJobStore store;
    private final DocumentQaService service;
    private final ChatSessionMapper sessions;
    private final EmbeddingModelConfig embeddingConfig;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Map<String,Thread> threads=new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public DocumentUploadJobs(DocumentQaService service,ChatSessionMapper sessions,EmbeddingModelConfig embeddingConfig,ObjectMapper mapper,
            @Value("${ai.documentqa.jobs-dir:D:/AIassistant-env/runtime/uploads/document-jobs}") String directory){
        this.service=service;this.sessions=sessions;this.embeddingConfig=embeddingConfig;store=new DocumentUploadJobStore(Path.of(directory),mapper);
        for(String id:store.ids()){
            try{Job job=store.read(id,"job.json",Job.class);if(job==null){store.delete(id);continue;}if(!id.equals(job.id))continue;if(job.deleteRequested){store.delete(id);continue;}
                if(!terminal(job)){job.status="QUEUED";job.phase="QUEUED";job.recoveries++;save(job);}jobs.put(id,job);
            }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Document task metadata unavailable: {}",id);}
        }
    }
    public synchronized Map<String,Object> submit(MultipartFile file,String sessionId,String id,String owner)throws IOException{
        ownSession(sessionId,owner);if(file.isEmpty())throw new BizException(400,"文档不能为空");if(file.getSize()>100L*1024*1024)throw new BizException(413,"文件大小不能超过100MB");
        String name=file.getOriginalFilename();if(name==null||!name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|xlsx|csv|txt|md)$"))throw new BizException(400,"不支持的文档格式");
        if(id==null||id.isBlank())id=UUID.randomUUID().toString();store.directory(id);
        Job old=jobs.get(id);if(old!=null){if(!owner.equals(old.ownerId)||!sessionId.equals(old.sessionId))throw new BizException(409,"任务编号已使用");try(var input=file.getInputStream()){if(!digest(input).equals(old.sha256))throw new BizException(409,"同一任务编号不能替换源文件");}return view(old);}
        long active=jobs.values().stream().filter(j->!terminal(j)).count();if(active>=100||jobs.values().stream().filter(j->owner.equals(j.ownerId)&&!terminal(j)).count()>=4)throw new BizException(429,"文档处理任务已达上限");
        if(jobs.values().stream().anyMatch(j->sessionId.equals(j.sessionId)&&!terminal(j)))throw new BizException(409,"此会话已有文档正在处理");
        store.create(id);try{
            Path inputPath=store.file(id,"input.bin");try(var input=file.getInputStream()){Files.copy(input,inputPath);}
            try(var input=Files.newInputStream(inputPath)){
                Job job=new Job();job.id=id;job.ownerId=owner;job.sessionId=sessionId;job.fileName=name;job.sha256=digest(input);job.sourceVersion=UUID.randomUUID().toString();job.status="QUEUED";job.phase="QUEUED";job.createdAt=job.updatedAt=System.currentTimeMillis();save(job);jobs.put(id,job);return view(job);
            }
        }catch(Exception e){store.delete(id);throw e;}
    }
    public Map<String,Object> get(String id,String owner){Job job=owned(id,owner);synchronized(job){return view(job);}}
    public List<Map<String,Object>> list(String sessionId,String owner){ownSession(sessionId,owner);return jobs.values().stream().filter(j->owner.equals(j.ownerId)&&sessionId.equals(j.sessionId)).sorted(Comparator.comparingLong((Job j)->j.createdAt).reversed()).map(j->{synchronized(j){return view(j);}}).toList();}
    public Map<String,Object> retry(String id,String owner){Job job=owned(id,owner);synchronized(job){ownSession(job.sessionId,owner);if(running.contains(id)||!List.of("FAILED","CANCELLED").contains(job.status))throw new BizException(409,"任务当前不能重试");if(job.attempts>=5)throw new BizException(429,"重试次数已达上限，请新建任务");job.status="QUEUED";job.phase="QUEUED";job.error=null;save(job);return view(job);}}
    public Map<String,Object> cancel(String id,String owner){Job job=owned(id,owner);synchronized(job){if(terminal(job))return view(job);if("SAVING".equals(job.phase))throw new BizException(409,"任务正在保存，请等待完成");job.status="CANCELLED";job.phase="CANCELLED";save(job);Thread thread=threads.get(id);if(thread!=null)thread.interrupt();return view(job);}}
    public void removeForSession(String sessionId,String owner){for(Job job:new ArrayList<>(jobs.values()))if(owner.equals(job.ownerId)&&sessionId.equals(job.sessionId)){synchronized(job){job.deleteRequested=true;job.status="CANCELLED";job.phase="CANCELLED";save(job);Thread thread=threads.get(job.id);if(thread!=null)thread.interrupt();if(!running.contains(job.id)){store.delete(job.id);jobs.remove(job.id);}}}}
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void dispatch(){if(stopping)return;for(Job job:jobs.values()){synchronized(job){if(!"QUEUED".equals(job.status)||running.size()>=2||!running.add(job.id))continue;if(job.attempts>=5){job.status="FAILED";job.error="任务恢复次数已达上限";save(job);running.remove(job.id);continue;}job.status="RUNNING";job.attempts++;save(job);workers.execute(()->run(job));}}}
    private void run(Job job){threads.put(job.id,Thread.currentThread());var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new AppPrincipal(job.ownerId,"document-job","user",List.of("USER")),null,List.of()));SecurityContextHolder.setContext(context);
        try{
            ownSession(job.sessionId,job.ownerId);try(var input=Files.newInputStream(store.file(job.id,"input.bin"))){if(!job.sha256.equals(digest(input)))throw new IllegalStateException("任务源文件校验失败");}
            var checkpoint=new DocumentQaService.UploadCheckpoint(){
                public SourceDocumentParser.SourceDocument source(){check(job);var saved=store.read(job.id,"source.json",SavedSource.class);return saved!=null&&saved.parserVersion()==PARSER_VERSION&&job.sha256.equals(saved.sha256())?saved.document():null;}
                public void saveSource(SourceDocumentParser.SourceDocument source){synchronized(job){check(job);store.write(job.id,"source.json",new SavedSource(PARSER_VERSION,job.sha256,source));}}
                public DocumentEmbeddingBatcher.Result embeddings(){check(job);var saved=store.read(job.id,"embeddings.json",SavedEmbeddings.class);if(saved==null||saved.parserVersion()!=PARSER_VERSION||!Objects.equals(saved.fingerprint(),embeddingConfig.fingerprint())||!job.sha256.equals(saved.sha256()))return null;return new DocumentEmbeddingBatcher.Result(saved.chunks().stream().map(c->new DocumentQaService.ChunkEmbedding(c.text(),Embedding.from(c.vector()))).toList(),saved.stats());}
                public void saveEmbeddings(DocumentEmbeddingBatcher.Result result){synchronized(job){check(job);store.write(job.id,"embeddings.json",new SavedEmbeddings(PARSER_VERSION,embeddingConfig.fingerprint(),job.sha256,result.embeddings().stream().map(c->new DocumentQaSessionStore.StoredChunk(c.text,c.embedding.vector())).toList(),result.stats()));}}
                public String sourceVersion(){return job.sourceVersion;}
                public void publish(Runnable action){synchronized(job){check(job);ownSession(job.sessionId,job.ownerId);action.run();}}
            };
            Map<String,Object> result=service.upload(new StoredFile(store.file(job.id,"input.bin"),job.fileName),job.sessionId,progress->{synchronized(job){check(job);job.phase=progress.phase();job.completed=progress.completed();job.total=progress.total();save(job);}},checkpoint);
            synchronized(job){check(job);var session=ownSession(job.sessionId,job.ownerId);session.setTitle("📄 "+job.fileName);sessions.updateById(session);job.result=result;job.status="COMPLETE";job.phase="COMPLETE";job.error=null;save(job);}
        }catch(Exception e){synchronized(job){if(!"CANCELLED".equals(job.status)){job.status=stopping?"QUEUED":"FAILED";job.phase=job.status;job.error=stopping?null:(e instanceof BizException?e.getMessage():"处理失败，可重试；已保存的解析及嵌入批次会复用");save(job);}}}
        finally{SecurityContextHolder.clearContext();threads.remove(job.id);running.remove(job.id);Thread.interrupted();if(job.deleteRequested||sessions.selectById(job.sessionId)==null){synchronized(job){store.delete(job.id);jobs.remove(job.id);}}}
    }
    @Scheduled(fixedDelay=60_000)
    public void prune(){for(Job job:new ArrayList<>(jobs.values()))if(System.currentTimeMillis()-job.createdAt>24*60*60_000L){synchronized(job){if(running.contains(job.id)){job.status="CANCELLED";job.phase="CANCELLED";save(job);Thread t=threads.get(job.id);if(t!=null)t.interrupt();}else{store.delete(job.id);jobs.remove(job.id);}}}}
    @jakarta.annotation.PreDestroy public void shutdown(){stopping=true;workers.shutdownNow();try{workers.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private Job owned(String id,String owner){Job job=jobs.get(id);if(job==null||!owner.equals(job.ownerId))throw BizException.notFound("文档任务不存在");return job;}
    private com.aiproject.aiassitant.module.chat.entity.ChatSession ownSession(String id,String owner){var session=id==null?null:sessions.selectById(id);if(session==null||!owner.equals(session.getUserId()))throw BizException.notFound("会话不存在");return session;}
    private void check(Job job){if(stopping||Thread.currentThread().isInterrupted()||"CANCELLED".equals(job.status))throw new CancellationException();}
    private boolean terminal(Job job){return List.of("COMPLETE","FAILED","CANCELLED").contains(job.status);}
    private void save(Job job){job.updatedAt=System.currentTimeMillis();store.write(job.id,"job.json",job);}
    private Map<String,Object> view(Job job){Map<String,Object> view=new LinkedHashMap<>();view.put("id",job.id);view.put("sessionId",job.sessionId);view.put("fileName",job.fileName);view.put("status",job.status);view.put("phase",job.phase);view.put("completed",job.completed);view.put("total",job.total);view.put("attempts",job.attempts);view.put("recoveries",job.recoveries);view.put("createdAt",job.createdAt);view.put("updatedAt",job.updatedAt);view.put("error",job.error);view.put("result",job.result);view.put("retryable",!running.contains(job.id)&&List.of("FAILED","CANCELLED").contains(job.status)&&job.attempts<5);return view;}
    private static String digest(InputStream input)throws IOException{try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private record StoredFile(Path path,String filename) implements MultipartFile {
        public String getName(){return "file";}public String getOriginalFilename(){return filename;}public String getContentType(){return "application/octet-stream";}public boolean isEmpty(){return getSize()==0;}public long getSize(){try{return Files.size(path);}catch(IOException e){throw new IllegalStateException(e);}}public byte[] getBytes()throws IOException{return Files.readAllBytes(path);}public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}public void transferTo(File destination)throws IOException{Files.copy(path,destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
