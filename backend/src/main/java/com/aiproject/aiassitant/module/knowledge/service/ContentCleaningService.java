package com.aiproject.aiassitant.module.knowledge.service;

import com.aiproject.aiassitant.module.ai.service.ChatModelFactory;
import com.aiproject.aiassitant.module.ai.service.ContentCleaner;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI content cleaning — formats scraped/imported text before chunking.
 *
 * Uses a dedicated model (configurable via ai.cleaning.model) for best formatting results.
 * Falls back to the default chat model if the named model is not found.
 *
 * Strategy:
 * - Content <= 8000 chars → clean in one call
 * - Content >  8000 chars → split on paragraph boundaries, clean each segment, re-join
 * - AI failure → return original content (degraded but not broken)
 */
@Slf4j
@Service
public class ContentCleaningService {

    private static final int MAX_CHARS_PER_CALL = 8000;
    private static final int SPLIT_OVERLAP = 200;

    private final ChatModelFactory chatModelFactory;

    /** Model name for content cleaning. mimo-v2.5 is optimized for formatting tasks. */
    @Value("${ai.cleaning.model:mimo-v2.5}")
    private String cleaningModelName;

    public ContentCleaningService(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * Clean raw content. Returns cleaned Markdown, or the original content on failure.
     */
    public String clean(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return rawContent;
        }

        try {
            if (rawContent.length() <= MAX_CHARS_PER_CALL) {
                return cleanSegment(rawContent);
            }
            return cleanInSegments(rawContent);
        } catch (Exception e) {
            log.warn("AI content cleaning failed ({} chars): {}. Falling back to original.", rawContent.length(), e.getMessage());
            return rawContent;
        }
    }

    /** Resolve the cleaning model — prefer the named model, fall back to default. */
    private ChatLanguageModel resolveCleaningModel() {
        try {
            ChatLanguageModel model = chatModelFactory.getChatModel(cleaningModelName);
            log.debug("Using cleaning model: {}", cleaningModelName);
            return model;
        } catch (Exception e) {
            log.warn("Cleaning model '{}' not available ({}), falling back to default", cleaningModelName, e.getMessage());
            return chatModelFactory.getDefaultChatModel();
        }
    }

    /** Clean a single segment via AI. */
    private String cleanSegment(String content) {
        ChatLanguageModel chatModel = resolveCleaningModel();
        ContentCleaner cleaner = AiServices.create(ContentCleaner.class, chatModel);
        String result = cleaner.clean(content);
        if (result == null || result.isBlank()) {
            throw new RuntimeException("AI returned empty result");
        }
        log.info("AI cleaned segment with {} ({} → {} chars)", cleaningModelName, content.length(), result.length());
        return result;
    }

    /** Split large content into overlapping segments, clean each, re-join. */
    private String cleanInSegments(String rawContent) {
        List<String> segments = splitOnParagraphBoundary(rawContent, MAX_CHARS_PER_CALL);
        log.info("Splitting {} chars into {} segments for AI cleaning", rawContent.length(), segments.size());

        List<String> cleaned = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            try {
                String result = cleanSegment(segment);
                cleaned.add(result);
            } catch (Exception e) {
                log.warn("AI cleaning failed for segment {}/{}: {}. Using original.", i + 1, segments.size(), e.getMessage());
                cleaned.add(segment);
            }
        }

        // Re-join segments, removing overlap deduplication
        return joinCleanedSegments(cleaned);
    }

    /**
     * Split content at paragraph boundaries (double newline), trying to keep each segment
     * under maxChars while not breaking mid-paragraph.
     */
    private List<String> splitOnParagraphBoundary(String content, int maxChars) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = content.split("\n\n");

        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (current.length() + para.length() + 2 > maxChars && current.length() > 100) {
                // Flush current segment
                segments.add(current.toString().trim());
                // Add overlap: carry last paragraph forward
                current.setLength(0);
                if (segments.size() > 0) {
                    String last = segments.get(segments.size() - 1);
                    String[] lastParas = last.split("\n\n");
                    if (lastParas.length > 0) {
                        String carryOver = lastParas[lastParas.length - 1];
                        if (carryOver.length() < SPLIT_OVERLAP) {
                            current.append(carryOver).append("\n\n");
                        }
                    }
                }
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(para);
        }
        if (current.length() > 0) {
            segments.add(current.toString().trim());
        }
        return segments;
    }

    /**
     * Join cleaned segments, removing duplicated overlap content at boundaries.
     */
    private String joinCleanedSegments(List<String> segments) {
        if (segments.isEmpty()) return "";
        if (segments.size() == 1) return segments.get(0);

        StringBuilder result = new StringBuilder(segments.get(0));
        for (int i = 1; i < segments.size(); i++) {
            String prev = segments.get(i - 1);
            String curr = segments.get(i);

            // Simple dedup: if curr starts with text from the end of prev, trim it
            int overlapLen = Math.min(100, prev.length());
            String prevTail = prev.substring(prev.length() - overlapLen);
            if (curr.startsWith(prevTail)) {
                result.append("\n\n").append(curr.substring(prevTail.length()).trim());
            } else {
                result.append("\n\n").append(curr.trim());
            }
        }
        return result.toString().trim();
    }
}
