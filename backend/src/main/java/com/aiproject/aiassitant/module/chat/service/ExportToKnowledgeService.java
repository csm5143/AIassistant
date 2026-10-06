package com.aiproject.aiassitant.module.chat.service;

import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.mapper.ChatMessageMapper;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.knowledge.entity.KbCollection;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.mapper.KbCollectionMapper;
import com.aiproject.aiassitant.module.knowledge.service.DocumentProcessingService;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Export a chat session as a Markdown document and store it in the knowledge base.
 * The document is processed asynchronously (chunking + vectorization) just like
 * any other uploaded document.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportToKnowledgeService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final KbCollectionMapper collectionMapper;
    private final KnowledgeService knowledgeService;
    private final DocumentProcessingService documentProcessingService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ExportResult exportToKnowledge(String sessionId, String requestTitle, String category,
                                          java.util.List<String> messageIds) {
        String userId = SecurityUtil.getCurrentUserId();

        // 1. Load session + messages (verify ownership)
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new ExportService.ExportException(404, "会话不存在");
        if (!userId.equals(session.getUserId())) throw new ExportService.ExportException(403, "无权访问该会话");

        List<ChatMessage> allMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt));
        if (allMessages.isEmpty()) throw new ExportService.ExportException(400, "该会话暂无消息可导出");

        // Filter by selected message IDs
        List<ChatMessage> messages;
        if (messageIds != null && !messageIds.isEmpty()) {
            java.util.Set<String> idSet = new java.util.HashSet<>(messageIds);
            messages = allMessages.stream()
                    .filter(m -> idSet.contains(m.getId()))
                    .toList();
            if (messages.isEmpty()) throw new ExportService.ExportException(400, "未选中任何消息");
        } else {
            messages = allMessages;
        }

        // 2. Convert to structured Markdown
        String title = resolveTitle(session, requestTitle, messages);
        String markdown = buildMarkdown(title, session, messages, category);

        // 3. Get or create target collection
        String collectionId = resolveCollection(userId);

        // 4. Create document and save content
        String docId = UUID.randomUUID().toString().replace("-", "");
        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setCollectionId(collectionId);
        doc.setUserId(userId);
        doc.setFilename(sanitizeFilename(title) + ".md");
        doc.setMimeType("text/markdown");
        doc.setSizeBytes((long) markdown.getBytes().length);
        doc.setStatus("PENDING");
        knowledgeService.createDocument(doc);
        knowledgeService.saveDocumentContent(docId, markdown);

        // 5. Async processing
        documentProcessingService.processDocument(docId);

        log.info("Exported session {} to knowledge base: docId={}, {} chars, {} messages",
                sessionId, docId, markdown.length(), messages.size());

        return new ExportResult(docId, title, markdown.length(), messages.size());
    }

    /** Resolve title: request param > session title > first user message > fallback. */
    private String resolveTitle(ChatSession session, String requestTitle, List<ChatMessage> messages) {
        if (requestTitle != null && !requestTitle.isBlank()) return requestTitle.trim();
        if (session.getTitle() != null && !session.getTitle().isBlank() && session.getTitle().length() >= 3) {
            return session.getTitle();
        }
        return messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .findFirst()
                .map(m -> m.getContent().length() > 50 ? m.getContent().substring(0, 50) : m.getContent())
                .orElse("对话导出");
    }

    /** Build structured Markdown from chat messages. */
    private String buildMarkdown(String title, ChatSession session,
                                  List<ChatMessage> messages, String category) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");

        // Metadata block
        sb.append("> **来源**：对话导出\n");
        sb.append("> **导出时间**：").append(LocalDateTime.now().format(FMT)).append("\n");
        sb.append("> **原始会话**：").append(session.getTitle() != null ? session.getTitle() : title).append("\n");
        sb.append("> **消息数量**：").append(messages.size()).append(" 条\n");
        if (category != null && !category.isBlank()) {
            sb.append("> **分类**：").append(category).append("\n");
        }
        if (session.getModel() != null) {
            sb.append("> **模型**：").append(session.getModel()).append("\n");
        }
        sb.append("\n---\n\n");

        // Messages
        boolean hasAssistant = false;
        for (ChatMessage msg : messages) {
            String time = msg.getCreatedAt() != null ? msg.getCreatedAt().format(FMT) : "";
            String role = msg.getRole();

            if ("user".equals(role)) {
                sb.append("## 用户提问 (").append(time).append(")\n\n");
                // Quote user content — preserve original formatting
                String content = msg.getContent();
                if (content != null) {
                    for (String line : content.split("\n")) {
                        sb.append("> ").append(line).append("\n");
                    }
                }
                sb.append("\n");
            } else if ("assistant".equals(role)) {
                hasAssistant = true;
                sb.append("## AI 回答 (").append(time).append(")\n\n");
                String content = msg.getContent();
                if (content != null) {
                    sb.append(content).append("\n\n");
                }
            } else if ("system".equals(role)) {
                sb.append("## 系统提示 (").append(time).append(")\n\n");
                sb.append("*").append(msg.getContent()).append("*\n\n");
            }
            sb.append("---\n\n");
        }

        // Footer with stats
        int userMsgs = (int) messages.stream().filter(m -> "user".equals(m.getRole())).count();
        int aiMsgs = (int) messages.stream().filter(m -> "assistant".equals(m.getRole())).count();
        sb.append("\n> 📊 对话统计：用户 ").append(userMsgs).append(" 轮 · AI ").append(aiMsgs).append(" 轮 · 共 ")
          .append(messages.size()).append(" 条消息\n");

        return sb.toString();
    }

    /** Get or create a collection for exported chats. */
    private String resolveCollection(String userId) {
        // Try existing collections first
        List<KbCollection> collections = collectionMapper.selectList(
                new LambdaQueryWrapper<KbCollection>()
                        .eq(KbCollection::getUserId, userId)
                        .orderByDesc(KbCollection::getCreatedAt));
        if (!collections.isEmpty()) {
            return collections.get(0).getId();
        }
        // Create default collection
        KbCollection coll = new KbCollection();
        coll.setId(UUID.randomUUID().toString().replace("-", ""));
        coll.setUserId(userId);
        coll.setName("导出的对话");
        coll.setDescription("从聊天会话导出的对话记录");
        collectionMapper.insert(coll);
        log.info("Created default collection '导出的对话' for user {}", userId);
        return coll.getId();
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "对话导出";
        return name.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_")
                   .replaceAll("^_|_$", "");
    }

    public record ExportResult(String documentId, String title, int chars, int messageCount) {}
}
