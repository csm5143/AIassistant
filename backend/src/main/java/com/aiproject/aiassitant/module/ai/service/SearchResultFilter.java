package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Filters low-quality, duplicate, and irrelevant search results.
 */
@Slf4j
public class SearchResultFilter {

    // Trusted domains (high-quality sources)
    private static final Set<String> TRUSTED_DOMAINS = Set.of(
            "fifa.com", "sports.sina.com.cn", "cctv.com", "qq.com",
            "163.com", "gmw.cn", "hubeidaily.net", "sznews.com",
            "people.com.cn", "xinhuanet.com", "bilibili.com", "zhihu.com"
    );

    // Low-quality / ad keywords in snippets
    private static final Pattern LOW_QUALITY_PATTERN = Pattern.compile(
            "(?i)(广告|推广|下载\\s*APP|点击.*领|免费.*领取|充值|优惠券|限时.*抢|加微信|扫码|关注公众号)");

    // Dates to prefer: today and this week
    private static final LocalDate TODAY = LocalDate.now();

    public record FilteredResult(
            String name,
            String snippet,
            String url,
            String siteName,
            String datePublished,
            int relevanceScore   // higher = better
    ) {}

    /**
     * Filter and score search results from Bocha API JSON.
     * Returns top N results sorted by relevance (trusted domain + recency).
     */
    public List<FilteredResult> filter(JsonNode webPages, int maxResults) {
        if (webPages == null || !webPages.isArray()) return List.of();

        List<FilteredResult> results = new ArrayList<>();

        for (JsonNode page : webPages) {
            String name = field(page, "name");
            String snippet = field(page, "snippet");
            String url = field(page, "url");
            String siteName = field(page, "siteName");
            String date = field(page, "datePublished");

            // Skip empty results
            if (name.isBlank() && snippet.isBlank()) continue;

            // Filter: low-quality snippet content
            if (LOW_QUALITY_PATTERN.matcher(snippet).find()) {
                log.debug("Filtered low-quality: {}", name);
                continue;
            }
            if (LOW_QUALITY_PATTERN.matcher(name).find()) {
                log.debug("Filtered low-quality name: {}", name);
                continue;
            }

            // Score: trusted domain +3, recent date +1 to +3
            int score = 0;
            String domain = extractDomain(url);
            if (TRUSTED_DOMAINS.contains(domain)) score += 3;

            // Recency score
            LocalDate pubDate = parseDate(date);
            if (pubDate != null) {
                long daysAgo = TODAY.toEpochDay() - pubDate.toEpochDay();
                if (daysAgo <= 1) score += 3;        // today/yesterday
                else if (daysAgo <= 3) score += 2;   // within 3 days
                else if (daysAgo <= 7) score += 1;   // within a week
            }

            results.add(new FilteredResult(name, snippet, url, siteName, date, score));
        }

        // Dedup by title similarity (>80%)
        results = dedup(results);

        // Sort by relevance score desc
        results.sort((a, b) -> Integer.compare(b.relevanceScore, a.relevanceScore));

        // Return top N
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    /** Remove near-duplicate results based on title word overlap. */
    private List<FilteredResult> dedup(List<FilteredResult> input) {
        List<FilteredResult> out = new ArrayList<>();
        for (FilteredResult r : input) {
            boolean isDup = false;
            for (FilteredResult existing : out) {
                if (titleSimilarity(r.name, existing.name) > 0.8) {
                    isDup = true;
                    break;
                }
            }
            if (!isDup) out.add(r);
        }
        return out;
    }

    /** Simple Jaccard-like word overlap for Chinese titles. */
    private double titleSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        Set<String> wordsA = tokenize(a);
        Set<String> wordsB = tokenize(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        Set<String> union = new HashSet<>(wordsA);
        union.addAll(wordsB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String s) {
        // Simple bigram tokenization for Chinese text
        Set<String> tokens = new HashSet<>();
        String clean = s.replaceAll("[\\s\\p{Punct}]", "");
        for (int i = 0; i < clean.length() - 1; i++) {
            tokens.add(clean.substring(i, i + 2));
        }
        return tokens;
    }

    private String extractDomain(String url) {
        if (url == null) return "";
        String s = url.replaceFirst("https?://", "");
        int slash = s.indexOf('/');
        if (slash > 0) s = s.substring(0, slash);
        return s.toLowerCase();
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            // ISO 8601: 2026-06-20T00:00:00+08:00
            String clean = dateStr.replaceFirst("T.*", "");
            return LocalDate.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null && !f.isNull() ? f.asText() : "";
    }
}
