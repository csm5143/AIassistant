package com.aiproject.aiassitant.module.tools.workspace;

import com.aiproject.aiassitant.common.*;
import com.aiproject.aiassitant.module.ai.service.AiChatService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/workspace-tools")
@RequiredArgsConstructor
public class WorkspaceToolController {
    private final ToolFileStore files;
    private final WorkspaceToolService tools;
    private final AiChatService chat;
    private final WorkspaceJobs jobs;
    private final com.aiproject.aiassitant.module.chat.service.RunJournal journal;
    private final com.aiproject.aiassitant.module.chat.service.SessionRunGate gate;
    @GetMapping("/sessions/{session}/files")
    public R<List<ToolFileStore.Asset>>list(@PathVariable String session)throws IOException{tools.ownedSession(session);return R.ok(files.list(session));}
    @PostMapping("/sessions/{session}/files")
    public R<ToolFileStore.Asset>upload(@PathVariable String session,@RequestParam MultipartFile file)throws IOException{tools.ownedSession(session);idle(session);return R.ok(files.upload(session,file));}
    @DeleteMapping("/sessions/{session}/files/{id}")
    public R<Void>delete(@PathVariable String session,@PathVariable String id)throws IOException{tools.ownedSession(session);idle(session);files.delete(id,session);return R.ok();}
    @PostMapping("/sessions/{session}/execute/{name}")
    public R<Map<String,Object>>execute(@PathVariable String session,@PathVariable String name,@RequestBody JsonNode body)throws IOException{tools.ownedSession(session);gate.claim(session);try{return R.ok(tools.execute(session,name,body));}finally{gate.release(session);}}
    @PostMapping("/sessions/{session}/jobs/{name}")
    public R<Map<String,Object>>start(@PathVariable String session,@PathVariable String name,@RequestBody JsonNode body)throws IOException {tools.ownedSession(session);idle(session);return R.ok(journal.view(jobs.start(session,name,body.toString())));}
    @PostMapping("/sessions/{session}/jobs/{id}/resume")
    public R<Map<String,Object>>resume(@PathVariable String session,@PathVariable String id)throws IOException {tools.ownedSession(session);idle(session);return R.ok(journal.view(jobs.resume(session,id)));}
    private void idle(String session){if(gate.isRunning(session))throw new BizException(409,"请先等待当前任务完成或停止回答");}
    @GetMapping("/files/{id}")
    public ResponseEntity<FileSystemResource>download(@PathVariable String id,@RequestParam(defaultValue="false")boolean inline)throws IOException{
        var a=files.get(id,null);tools.ownedSession(a.sessionId());boolean preview=inline&&(a.mime().equals("application/pdf")||a.mime().equals("image/png"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.mime())).contentLength(a.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,(preview?ContentDisposition.inline():ContentDisposition.attachment()).filename(a.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(new FileSystemResource(files.path(a)));
    }
}
