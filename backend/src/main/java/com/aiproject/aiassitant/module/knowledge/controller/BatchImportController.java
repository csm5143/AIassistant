package com.aiproject.aiassitant.module.knowledge.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbDocument;
import com.aiproject.aiassitant.module.knowledge.service.DocumentProcessingService;
import com.aiproject.aiassitant.module.knowledge.service.KnowledgeService;
import com.aiproject.aiassitant.module.knowledge.service.WebScraperService;
import com.aiproject.aiassitant.module.knowledge.service.WebScraperService.ScrapedPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@Tag(name = "Batch Import", description = "Batch file & URL import for knowledge base")
@RestController
@RequestMapping("/knowledge/collections/{collectionId}")
@RequiredArgsConstructor
public class BatchImportController {

    private final KnowledgeService knowledgeService;
    private final DocumentProcessingService documentProcessingService;
    private final WebScraperService webScraperService;

    // ── Paste text import ──

    @Operation(summary = "Import pasted text as a document")
    @PostMapping("/import-paste")
    public R<Map<String, Object>> importPaste(
            @PathVariable String collectionId,
            @RequestBody Map<String, String> body) {

        String title = body.getOrDefault("title", "Pasted Content");
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return R.fail("Content is required");
        }
        if (content.length() > 500_000) {
            return R.fail(413, "粘贴内容不能超过 50 万字符");
        }

        String userId = SecurityUtil.getCurrentUserId();
        String filename = sanitizeFilename(title) + ".md";
        String fullMd = "# " + title + "\n\n" + content;

        KbDocument doc = knowledgeService.uploadDocument(
                collectionId, filename, "text/markdown", fullMd.getBytes().length);
        knowledgeService.saveDocumentContent(doc.getId(), fullMd);
        documentProcessingService.processDocument(doc.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());
        result.put("status", "imported");
        result.put("title", title);
        result.put("chars", content.length());
        return R.ok(result);
    }

    // ── Batch file upload ──

    @Operation(summary = "Batch upload multiple files (max 20 files, 100MB each)")
    @PostMapping("/import-batch")
    public R<List<Map<String, Object>>> batchUpload(
            @PathVariable String collectionId,
            @RequestParam("files") List<MultipartFile> files) {

        if (files.size() > 20) return R.fail(413, "单次最多上传 20 个文件");
        String userId = SecurityUtil.getCurrentUserId();
        List<Map<String, Object>> results = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.getSize() > 100L * 1024 * 1024) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("filename", file.getOriginalFilename());
                err.put("status", "failed");
                err.put("error", "文件大小不能超过 100MB");
                results.add(err);
                continue;
            }
            Map<String, Object> status = new LinkedHashMap<>();
            String originalName = file.getOriginalFilename();
            status.put("filename", originalName);
            try {
                KbDocument doc = knowledgeService.uploadDocument(
                        collectionId, originalName, file.getContentType(), file.getSize());
                knowledgeService.saveDocumentFile(doc.getId(), file);
                documentProcessingService.processDocument(doc.getId());
                status.put("id", doc.getId());
                status.put("status", "imported");
            } catch (Exception e) {
                log.error("Batch upload failed for {}: {}", originalName, e.getMessage());
                status.put("status", "failed");
                status.put("error", e.getMessage());
            }
            results.add(status);
        }

        long successCount = results.stream().filter(r -> "imported".equals(r.get("status"))).count();
        log.info("Batch import: {}/{} files succeeded", successCount, results.size());
        return R.ok(results);
    }

    // ── URL import (single or multiple) ──

    @Operation(summary = "Import from URLs (one or more)")
    @PostMapping("/import-url")
    public R<Map<String, Object>> importFromUrls(
            @PathVariable String collectionId,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) body.getOrDefault("urls", List.of());
        boolean followLinks = Boolean.TRUE.equals(body.get("followLinks"));
        int maxPages = body.containsKey("maxPages") ? ((Number) body.get("maxPages")).intValue() : 20;

        String userId = SecurityUtil.getCurrentUserId();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        int totalChars = 0;

        for (String url : urls) {
            String trimmedUrl = url.trim();
            if (trimmedUrl.isBlank()) continue;

            try {
                if (followLinks) {
                    List<ScrapedPage> pages = webScraperService.scrapeAndFollow(trimmedUrl, maxPages);
                    for (ScrapedPage page : pages) {
                        if (page.error != null) {
                            failCount++;
                            results.add(Map.of("url", page.url, "status", "failed", "error", page.error));
                        } else {
                            KbDocument doc = createDocumentFromScraped(collectionId, userId, page);
                            successCount++;
                            totalChars += page.content.length();
                            results.add(Map.of(
                                    "url", page.url, "title", page.title,
                                    "id", doc.getId(), "status", "imported",
                                    "chars", page.content.length()));
                        }
                    }
                } else {
                    ScrapedPage page = webScraperService.scrape(trimmedUrl);
                    if (page.error != null) {
                        failCount++;
                        results.add(Map.of("url", trimmedUrl, "status", "failed", "error", page.error));
                    } else {
                        KbDocument doc = createDocumentFromScraped(collectionId, userId, page);
                        successCount++;
                        totalChars += page.content.length();
                        results.add(Map.of(
                                "url", trimmedUrl, "title", page.title,
                                "id", doc.getId(), "status", "imported",
                                "chars", page.content.length()));
                    }
                }
            } catch (Exception e) {
                log.error("URL import failed for {}: {}", trimmedUrl, e.getMessage());
                failCount++;
                results.add(Map.of("url", trimmedUrl, "status", "failed", "error", e.getMessage()));
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalUrls", urls.size());
        summary.put("successCount", successCount);
        summary.put("failCount", failCount);
        summary.put("totalChars", totalChars);
        summary.put("results", results);

        log.info("URL import: {}/{} succeeded, {} total chars", successCount, urls.size(), totalChars);
        return R.ok(summary);
    }

    private KbDocument createDocumentFromScraped(String collectionId, String userId, ScrapedPage page) {
        String filename = sanitizeFilename(page.title) + ".md";
        KbDocument doc = knowledgeService.uploadDocument(
                collectionId, filename, "text/markdown", page.markdown.getBytes().length);
        knowledgeService.saveDocumentContent(doc.getId(), page.markdown);
        documentProcessingService.processDocument(doc.getId());
        return doc;
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "untitled";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").substring(0, Math.min(100, name.length()));
    }
}
