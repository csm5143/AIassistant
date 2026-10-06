package com.aiproject.aiassitant.module.chat.controller;

import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.chat.service.ExportService;
import com.aiproject.aiassitant.module.chat.service.ExportToKnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Tag(name = "Export", description = "Export chat conversations")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;
    private final ExportToKnowledgeService exportToKnowledgeService;

    @Operation(summary = "Export a session in the specified format, optionally filtering by message IDs")
    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        String format = (String) body.get("format");
        @SuppressWarnings("unchecked")
        List<String> messageIds = (List<String>) body.get("messageIds");

        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "sessionId is required"));
        }
        if (format == null || format.isBlank()) {
            format = "md";
        }

        try {
            ExportService.ExportResult result = exportService.export(sessionId, format, messageIds);

            ContentDisposition cd = ContentDisposition.attachment()
                    .filename(result.filename(), StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .body(result.data());

        } catch (ExportService.ExportException e) {
            return ResponseEntity.status(e.status)
                    .body(Map.of("code", e.status, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Export failed for session {}", sessionId, e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "导出失败：" + e.getMessage()));
        }
    }

    @Operation(summary = "Export a session to the knowledge base as a Markdown document")
    @PostMapping("/export-to-knowledge")
    public ResponseEntity<?> exportToKnowledge(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        String title = (String) body.get("title");
        String category = (String) body.get("category");
        @SuppressWarnings("unchecked")
        List<String> messageIds = (List<String>) body.get("messageIds");

        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", 400, "message", "sessionId is required"));
        }

        try {
            ExportToKnowledgeService.ExportResult result =
                    exportToKnowledgeService.exportToKnowledge(sessionId, title, category, messageIds);
            return ResponseEntity.ok(Map.of(
                    "code", 200, "message", "已存入知识库",
                    "data", Map.of(
                            "documentId", result.documentId(),
                            "title", result.title(),
                            "chars", result.chars(),
                            "messageCount", result.messageCount())));
        } catch (ExportService.ExportException e) {
            return ResponseEntity.status(e.status)
                    .body(Map.of("code", e.status, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Export to knowledge failed for session {}", sessionId, e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "存入知识库失败：" + e.getMessage()));
        }
    }
}
