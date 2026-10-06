package com.aiproject.aiassitant.module.knowledge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic-aware text chunker.
 *
 * Strategy (in priority order):
 *   1. Split by Markdown headers (#, ##, ### ...) — keep header + content together
 *   2. Recursive split: \n\n (paragraph) → \n (line) → 。(sentence) → char fallback
 *   3. Context overlap: prefix each chunk with last 50 chars of previous chunk
 */
@Slf4j
@Service
public class Chunker {

    // ── Public API ──

    public List<String> split(String text, int chunkSize, int chunkOverlap) {
        if (text == null || text.isBlank()) return List.of();

        // 1) Pre-split at Markdown headers
        List<String> sections = splitByHeaders(text);

        // 2) For each section, recursively split to target size
        List<String> chunks = new ArrayList<>();
        for (String section : sections) {
            chunks.addAll(recursiveSplit(section.trim(), chunkSize));
        }

        // 3) Merge tiny chunks (< chunkSize/4) with neighbors when possible
        chunks = mergeSmallChunks(chunks, chunkSize);

        // 4) Apply context overlap — prefix each chunk with last N chars of previous
        if (chunkOverlap > 0 && chunks.size() > 1) {
            chunks = applyOverlap(chunks, chunkOverlap);
        }

        return chunks;
    }

    // ── 1) Header-aware pre-split ──

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("(?=^#{1,6}\\s)", Pattern.MULTILINE);

    private List<String> splitByHeaders(String text) {
        String[] parts = HEADER_PATTERN.split(text);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty()) {
            result.add(text);
        }
        return result;
    }

    // ── 2) Recursive split ──

    private static final String[] SEPARATORS = { "\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " " };

    private List<String> recursiveSplit(String text, int targetSize) {
        List<String> result = new ArrayList<>();
        if (text.length() <= targetSize) {
            result.add(text);
            return result;
        }

        // Try each separator in priority order
        for (String sep : SEPARATORS) {
            String[] parts = text.split(Pattern.quote(sep), -1);
            if (parts.length > 1) {
                StringBuilder current = new StringBuilder();
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) continue;

                    if (current.length() > 0 &&
                            current.length() + sep.length() + trimmed.length() > targetSize) {
                        // Current chunk is full — flush it
                        result.add(current.toString().trim());
                        current = new StringBuilder();
                    }

                    if (current.length() > 0) {
                        current.append(sep);
                    }
                    current.append(trimmed);
                }
                // Flush remaining
                if (current.length() > 0) {
                    result.add(current.toString().trim());
                }

                // If some chunks are still too large, recurse with next separator
                List<String> refined = new ArrayList<>();
                for (String chunk : result) {
                    if (chunk.length() > targetSize) {
                        // Try next separator (indexOf + 1)
                        int nextIdx = indexOf(sep) + 1;
                        if (nextIdx < SEPARATORS.length) {
                            refined.addAll(recurseOneLevel(chunk, targetSize, nextIdx));
                        } else {
                            // Fallback: force split at targetSize
                            refined.addAll(forceSplit(chunk, targetSize));
                        }
                    } else {
                        refined.add(chunk);
                    }
                }
                return refined;
            }
        }
        // Last resort: force split by character count
        return forceSplit(text, targetSize);
    }

    private List<String> recurseOneLevel(String text, int targetSize, int sepIdx) {
        if (sepIdx >= SEPARATORS.length) {
            return forceSplit(text, targetSize);
        }
        String sep = SEPARATORS[sepIdx];
        String[] parts = text.split(Pattern.quote(sep), -1);
        if (parts.length <= 1) {
            return recurseOneLevel(text, targetSize, sepIdx + 1);
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() > 0 &&
                    current.length() + sep.length() + trimmed.length() > targetSize) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(sep);
            current.append(trimmed);
        }
        if (current.length() > 0) result.add(current.toString().trim());

        // Recurse further for oversized chunks
        List<String> refined = new ArrayList<>();
        for (String chunk : result) {
            if (chunk.length() > targetSize) {
                refined.addAll(recurseOneLevel(chunk, targetSize, sepIdx + 1));
            } else {
                refined.add(chunk);
            }
        }
        return refined;
    }

    private List<String> forceSplit(String text, int size) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            result.add(text.substring(start, end).trim());
            start = end;
        }
        return result;
    }

    private int indexOf(String sep) {
        for (int i = 0; i < SEPARATORS.length; i++) {
            if (SEPARATORS[i].equals(sep)) return i;
        }
        return -1;
    }

    // ── 3) Merge tiny chunks ──

    private List<String> mergeSmallChunks(List<String> chunks, int targetSize) {
        if (chunks.size() <= 1) return chunks;
        int minSize = targetSize / 4;

        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String chunk : chunks) {
            if (buffer.length() == 0) {
                buffer.append(chunk);
            } else if (buffer.length() + chunk.length() <= targetSize && chunk.length() < minSize) {
                buffer.append("\n\n").append(chunk);
            } else {
                merged.add(buffer.toString().trim());
                buffer = new StringBuilder(chunk);
            }
        }
        if (buffer.length() > 0) merged.add(buffer.toString().trim());

        return merged;
    }

    // ── 4) Context overlap ──

    private List<String> applyOverlap(List<String> chunks, int overlap) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String current = chunks.get(i);
            if (i > 0) {
                String prev = chunks.get(i - 1);
                int prefixLen = Math.min(overlap, prev.length());
                String prefix = prev.substring(prev.length() - prefixLen);
                current = prefix + "\n" + current;
            }
            result.add(current);
        }
        return result;
    }
}
