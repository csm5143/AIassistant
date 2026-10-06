package com.aiproject.aiassitant.module.documentqa.service;

import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService.DocSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately narrow full-text count: distinct IDs at the start of delimited record rows. */
public final class DocumentIdentifierCounter {
    private static final Pattern PREFIX = Pattern.compile("(?i)(?<![A-Z0-9])([A-Z][A-Z0-9]{1,11}[-_])");
    private static final Pattern COUNT = Pattern.compile("(?i)多少|几个|数量|count|number of|how many");
    private static final Pattern DISTINCT = Pattern.compile("(?i)不同|去重|distinct|unique");
    private static final Pattern ID = Pattern.compile("(?i)编号|identifier|\\bids?\\b");
    private static final Pattern FILTER = Pattern.compile("(?i)部门|币种|金额|日期|状态|大于|小于|以前|以后|筛选|仅统计|只统计|排除|不含|末尾|between|where|status|amount|date|currency|excluding|except|matching|ending");
    private static final int MAX_IDENTIFIERS = 300;
    private static final int MAX_CITED_CHUNKS = 80;

    private DocumentIdentifierCounter() {}

    public record Result(String prefix, Map<Integer, List<String>> idsByChunk, int matchedRows,
                         int examinedCharacters, boolean unverifiable) {
        public int count() { return idsByChunk.values().stream().mapToInt(List::size).sum(); }
    }

    public static Result inspect(DocSession session, String question) {
        if (session.source == null || session.source.text() == null || session.locations.isEmpty()) return null;
        if (!COUNT.matcher(question).find() || !DISTINCT.matcher(question).find()
                || !ID.matcher(question).find() || FILTER.matcher(question).find()) return null;
        Matcher prefixMatcher = PREFIX.matcher(question);
        if (!prefixMatcher.find()) return null;
        String prefix = prefixMatcher.group(1).toUpperCase(Locale.ROOT);
        if (prefixMatcher.find()) return null; // Ambiguous prefixes need ordinary QA.
        Pattern row = Pattern.compile("(?im)^[ \\t]*\\|?[ \\t]*(" + Pattern.quote(prefix)
                + "[A-Z0-9][A-Z0-9_-]{1,31})[ \\t]*(?:\\||\\t| {2,}|$)");
        Matcher matcher = row.matcher(session.source.text());
        Map<String, Integer> firstChunkById = new LinkedHashMap<>();
        int rows = 0;
        while (matcher.find()) {
            rows++;
            String identifier = matcher.group(1).toUpperCase(Locale.ROOT);
            if (firstChunkById.containsKey(identifier)) continue;
            int ordinal = containingChunk(session, matcher.start(1), matcher.end(1));
            if (ordinal < 0) return new Result(prefix, Map.of(), rows, session.source.text().length(), true);
            firstChunkById.put(identifier, ordinal);
        }
        if (rows == 0) return new Result(prefix, Map.of(), 0, session.source.text().length(), true);
        Map<Integer, List<String>> byChunk = new LinkedHashMap<>();
        firstChunkById.forEach((identifier, ordinal) -> byChunk.computeIfAbsent(ordinal, ignored -> new ArrayList<>()).add(identifier));
        boolean limit = firstChunkById.size() > MAX_IDENTIFIERS || byChunk.size() > MAX_CITED_CHUNKS;
        return new Result(prefix, byChunk, rows, session.source.text().length(), limit);
    }

    private static int containingChunk(DocSession session, int start, int end) {
        for (int i = 0; i < session.locations.size(); i++) {
            var location = session.locations.get(i);
            if (location.start() <= start && end <= location.end()) return i + 1;
        }
        return -1;
    }
}
