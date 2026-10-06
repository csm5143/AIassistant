package com.aiproject.aiassitant.module.chat.controller;

import com.aiproject.aiassitant.module.ai.service.AiChatService;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.chat.dto.ChatRequest;
import com.aiproject.aiassitant.module.chat.dto.CreateSessionRequest;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Tag(name = "Chat", description = "Chat sessions and streaming messages")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AiChatService aiChatService;
    private final com.aiproject.aiassitant.module.tools.workspace.ToolFileStore toolFiles;
    private final com.aiproject.aiassitant.module.knowledge.service.KnowledgeRoutingService knowledgeRouting;
    private final com.aiproject.aiassitant.module.documentqa.service.DocumentQaService documentQaService;
    private final com.aiproject.aiassitant.module.documentqa.service.DocumentUploadJobs documentUploadJobs;
    private final com.aiproject.aiassitant.module.ai.service.ConversationMemoryService conversationMemory;
    private final com.aiproject.aiassitant.module.chat.service.RunJournal runJournal;

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final Path IMAGE_UPLOAD_DIR = Paths.get("uploads/chat-images").toAbsolutePath().normalize();

    static {
        try { Files.createDirectories(IMAGE_UPLOAD_DIR); } catch (IOException ignored) {}
    }

    @Operation(summary = "List chat sessions for current user")
    @GetMapping("/sessions")
    public R<Page<ChatSession>> listSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = SecurityUtil.getCurrentUserId();
        Page<ChatSession> p = new Page<>(Math.max(1, page), Math.max(1, Math.min(100, size)));
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        q.eq(ChatSession::getUserId, userId).orderByDesc(ChatSession::getUpdatedAt);
        Page<ChatSession> result = sessionMapper.selectPage(p, q);
        return R.ok(result);
    }

    @Operation(summary = "Get one session owned by current user")
    @GetMapping("/sessions/{id}")
    public R<ChatSession> getSession(@PathVariable String id) {
        String userId = SecurityUtil.getCurrentUserId();
        ChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, id).eq(ChatSession::getUserId, userId));
        if (session == null) throw BizException.notFound("会话不存在");
        return R.ok(session);
    }

    @Operation(summary = "Create a new chat session")
    @PostMapping("/sessions")
    public R<ChatSession> createSession(@RequestBody(required = false) CreateSessionRequest req) {
        String userId = SecurityUtil.getCurrentUserId();
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(userId);
        session.setTitle(req != null && req.getTitle() != null ? req.getTitle() : "New Chat");
        session.setModel(req != null && req.getModel() != null ? req.getModel() : "deepseek-chat");
        session.setSystemPrompt(req != null ? req.getSystemPrompt() : null);
        session.setThinkingEffort(com.aiproject.aiassitant.module.ai.service.ThinkingEffort.normalize(req == null ? null : req.getThinkingEffort()));
        sessionMapper.insert(session);
        return R.ok(session);
    }

    @Operation(summary = "Delete a chat session")
    @DeleteMapping("/sessions/{id}")
    public R<Void> deleteSession(@PathVariable String id) throws IOException {
        String userId = SecurityUtil.getCurrentUserId();
        LambdaUpdateWrapper<ChatSession> u = new LambdaUpdateWrapper<>();
        u.eq(ChatSession::getId, id).eq(ChatSession::getUserId, userId);
        ChatSession session = sessionMapper.selectOne(u);
        if (session == null) throw BizException.notFound("会话不存在");
        aiChatService.clearSession(id);
        runJournal.remove(userId,id);
        documentUploadJobs.removeForSession(id, userId);
        try { toolFiles.deleteSession(id); } catch (IOException e) { throw new BizException(500, "工具文件清理失败，请重试"); }
        documentQaService.removeSession(id, userId);
        conversationMemory.remove(id, userId);
        sessionMapper.deleteById(id);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, id));
        return R.ok();
    }

    @Operation(summary = "Get messages for a session")
    @GetMapping("/sessions/{id}/messages")
    public R<List<ChatMessage>> getMessages(@PathVariable String id) {
        String userId = SecurityUtil.getCurrentUserId();
        LambdaQueryWrapper<ChatSession> sq = new LambdaQueryWrapper<>();
        sq.eq(ChatSession::getId, id).eq(ChatSession::getUserId, userId);
        if (sessionMapper.selectCount(sq) == 0) {
            throw BizException.notFound("会话不存在");
        }
        LambdaQueryWrapper<ChatMessage> q = new LambdaQueryWrapper<>();
        q.eq(ChatMessage::getSessionId, id).orderByAsc(ChatMessage::getCreatedAt);
        return R.ok(messageMapper.selectList(q));
    }

    @Operation(summary = "Stream chat response via SSE")
    @PostMapping("/sessions/{id}/stream")
    public SseEmitter streamChat(@PathVariable String id, @RequestBody ChatRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        if (request.getContent() == null || request.getContent().isBlank() || request.getContent().length() > 16000) {
            throw new BizException(400, "消息不能为空且不超过 16000 字");
        }
        if (request.getImages() != null && request.getImages().size() > 5) throw new BizException(400, "最多上传 5 张图片");

        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        q.eq(ChatSession::getId, id).eq(ChatSession::getUserId, userId);
        if (sessionMapper.selectCount(q) == 0) {
            throw BizException.notFound("会话不存在");
        }

        if(request.getKnowledgeMode()!=null||request.getCollectionIds()!=null||request.getDocumentIds()!=null){
            ChatSession owned=sessionMapper.selectById(id);
            String mode=request.getKnowledgeMode()!=null?request.getKnowledgeMode():"SELECTED";
            knowledgeRouting.preferences(owned,mode,request.getCollectionIds(),request.getDocumentIds(),userId);
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onCompletion(() -> log.debug("SSE completed for session {}", id));
        emitter.onTimeout(() -> log.debug("SSE timeout for session {}", id));
        emitter.onError(e -> log.debug("SSE error for session {}: {}", id, e.getMessage()));

        aiChatService.streamChat(id, request.getContent(), request.getImages(), request.getModel(), emitter);
        return emitter;
    }

    private void owned(String id){if(sessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getId,id).eq(ChatSession::getUserId,SecurityUtil.getCurrentUserId()))==0)throw BizException.notFound("会话不存在");}
    @GetMapping("/sessions/{id}/runs")
    public R<List<Map<String,Object>>> runs(@PathVariable String id)throws IOException {owned(id);return R.ok(runJournal.list(SecurityUtil.getCurrentUserId(),id).stream().map(runJournal::summary).toList());}
    @GetMapping("/sessions/{id}/runs/{run}")
    public R<Map<String,Object>> run(@PathVariable String id,@PathVariable String run)throws IOException {owned(id);return R.ok(runJournal.view(runJournal.get(SecurityUtil.getCurrentUserId(),id,run)));}
    @PostMapping("/sessions/{id}/runs/{run}/resume")
    public SseEmitter resume(@PathVariable String id,@PathVariable String run)throws IOException {owned(id);SseEmitter emitter=new SseEmitter(SSE_TIMEOUT_MS);aiChatService.resumeChat(id,run,emitter);return emitter;}

    /** Inspect the same retrieval plan used by chat without invoking the chat model. */
    @PostMapping("/sessions/{id}/knowledge/preview")
    public R<java.util.Map<String,Object>> previewKnowledge(@PathVariable String id,@RequestBody ChatRequest request){
        String user=SecurityUtil.getCurrentUserId();ChatSession session=sessionMapper.selectById(id);
        if(session==null||!user.equals(session.getUserId()))throw BizException.notFound("会话不存在");
        if(request.getContent()==null||request.getContent().isBlank()||request.getContent().length()>16000)throw new BizException(400,"问题长度无效");
        if(request.getKnowledgeMode()!=null)knowledgeRouting.preferences(session,request.getKnowledgeMode(),request.getCollectionIds(),request.getDocumentIds(),user);
        var history=messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId,id).orderByDesc(ChatMessage::getCreatedAt).last("LIMIT 6"));
        java.util.Collections.reverse(history);
        var turn=knowledgeRouting.prepare(session,user,request.getContent(),history);
        var result=new java.util.LinkedHashMap<String,Object>();result.put("scope",turn.view());result.put("metrics",turn.stats.view());result.put("chunks",turn.chunks);return R.ok(result);
    }

    @PostMapping("/sessions/{id}/cancel")
    public R<Void> cancel(@PathVariable String id) {
        ChatSession session = sessionMapper.selectById(id);
        if (session == null || !SecurityUtil.getCurrentUserId().equals(session.getUserId())) {
            throw BizException.notFound("会话不存在");
        }
        aiChatService.cancel(id);
        return R.ok();
    }

    @Operation(summary = "Upload an image for chat (supported: png, jpg, gif, webp, bmp)")
    @PostMapping("/images/upload")
    public R<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw new BizException(400, "图片不能为空且不能超过 10MB");
        try {
            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.isBlank()) {
                return R.fail("文件名不能为空");
            }
            String ext = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase();
            if (!List.of("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(ext)) {
                return R.fail("不支持的图片格式: " + ext + "（支持: png, jpg, gif, webp, bmp）");
            }

            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = IMAGE_UPLOAD_DIR.resolve(storedName);
            file.transferTo(target.toFile());

            String imageUrl = "/api/chat/images/" + storedName;
            Map<String, Object> result = Map.of(
                    "url", imageUrl,
                    "fileName", originalName,
                    "size", file.getSize()
            );
            log.info("Chat image uploaded: {} -> {}", originalName, storedName);
            return R.ok(result);
        } catch (Exception e) {
            log.error("Image upload failed", e);
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "Serve an uploaded chat image")
    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> serveImage(@PathVariable String filename) {
        try {
            Path file = IMAGE_UPLOAD_DIR.resolve(filename).normalize();
            if (!file.startsWith(IMAGE_UPLOAD_DIR)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new UrlResource(file.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            String contentType = Files.probeContentType(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType != null ? contentType : "image/png"))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "Update session title or model")
    @PatchMapping("/sessions/{id}")
    public R<Void> updateSession(@PathVariable String id, @RequestBody ChatSession updates) {
        String userId = SecurityUtil.getCurrentUserId();
        LambdaUpdateWrapper<ChatSession> u = new LambdaUpdateWrapper<>();
        u.eq(ChatSession::getId, id).eq(ChatSession::getUserId, userId);
        ChatSession session = sessionMapper.selectOne(u);
        if (session == null) {
            throw BizException.notFound("会话不存在");
        }
        if(updates.getKnowledgeMode()!=null){
            java.util.List<String> chosen=java.util.List.of(),documents=java.util.List.of();
            if(updates.getKnowledgeSelectionJson()!=null){
                try{
                    var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(updates.getKnowledgeSelectionJson());
                    var ids=new java.util.ArrayList<String>();tree.path("collectionIds").forEach(n->ids.add(n.asText()));chosen=ids;
                    var docIds=new java.util.ArrayList<String>();tree.path("documentIds").forEach(n->docIds.add(n.asText()));documents=docIds;
                }catch(Exception e){throw new BizException(400,"资料范围格式无效");}
            }
            knowledgeRouting.preferences(session,updates.getKnowledgeMode(),chosen,documents,userId);
        }
        if (updates.getTitle() != null) session.setTitle(updates.getTitle());
        if (updates.getModel() != null) session.setModel(updates.getModel());
        if (updates.getSystemPrompt() != null) session.setSystemPrompt(updates.getSystemPrompt());
        if (updates.getAnswerMode() != null) session.setAnswerMode(com.aiproject.aiassitant.module.ai.service.ResearchPolicy.mode(updates.getAnswerMode()));
        if (updates.getThinkingEffort() != null) session.setThinkingEffort(com.aiproject.aiassitant.module.ai.service.ThinkingEffort.normalize(updates.getThinkingEffort()));
        sessionMapper.updateById(session);
        return R.ok();
    }
}
