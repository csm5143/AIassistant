package com.aiproject.aiassitant.module.ai.service;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects query type and extracts structured key points from search results.
 */
@Slf4j
public class SearchResultExtractor {

    public enum QueryType {
        SPORTS,     // 体育赛事
        NEWS,       // 新闻热点
        WEATHER,    // 天气
        FINANCE,    // 金融股价
        GENERAL     // 通用
    }

    /** Detect query type from user question keywords. */
    public static QueryType detectType(String query) {
        String q = query.toLowerCase();
        if (matches(q, "赛程|比分|对阵|球队|世界杯|NBA|欧冠|英超|中超")) return QueryType.SPORTS;
        if (matches(q, "天气|气温|下雨|台风|空气质量")) return QueryType.WEATHER;
        if (matches(q, "股票|股价|汇率|金价|基金|A股|港股|美股")) return QueryType.FINANCE;
        if (matches(q, "新闻|最新|热点|事件|政策|发布|宣布")) return QueryType.NEWS;
        return QueryType.GENERAL;
    }

    /**
     * Build a structured summary from filtered search results,
     * customized for the detected query type.
     */
    public static String summarize(List<SearchResultFilter.FilteredResult> results, QueryType type) {
        if (results.isEmpty()) return "未找到相关信息。建议：① 使用更具体的关键词重新搜索；② 访问官方网站查看最新内容。";

        StringBuilder sb = new StringBuilder();
        sb.append("搜索到 ").append(results.size()).append(" 条有效结果：\n\n");

        int idx = 1;
        for (var r : results) {
            sb.append("[").append(idx).append("] ").append(r.name()).append("\n");

            // Type-specific extraction hints
            switch (type) {
                case SPORTS -> appendSports(sb, r);
                case NEWS   -> appendNews(sb, r);
                default     -> appendGeneric(sb, r);
            }

            if (!r.siteName().isBlank()) sb.append("   🏷 来源：").append(r.siteName()).append("\n");
            sb.append("\n");
            idx++;
        }

        // Type-specific guidance
        sb.append(switch (type) {
            case SPORTS  -> "【体育查询建议】赛程信息以官方（fifa.com、球队官网）为准。如有具体比赛日期未显示，建议访问上述来源官网确认。";
            case WEATHER -> "【天气查询建议】天气信息时效性强，建议查看当地气象部门发布的最新预报。";
            case FINANCE -> "【金融查询建议】股价和汇率实时波动，以上数据仅供参考。投资决策请以交易所实时数据为准。";
            case NEWS    -> "【新闻查询建议】以上结果为搜索聚合。重要新闻请多方核实，以官方通报为准。";
            default      -> "";
        });

        return sb.toString();
    }

    private static void appendSports(StringBuilder sb, SearchResultFilter.FilteredResult r) {
        // Extract teams and times from snippet
        String snippet = r.snippet();
        // Try to find time patterns (HH:MM or HH：MM)
        var timeMatcher = Pattern.compile("(\\d{2}[:：]\\d{2})").matcher(snippet);
        if (timeMatcher.find()) {
            sb.append("   ⏰ 时间：").append(timeMatcher.group(1)).append("\n");
        }
        // Try to find team pairs (X vs Y or X队 vs Y队 or X对阵Y)
        var teamMatcher = Pattern.compile("([\\u4e00-\\u9fa5]+(?:队)?)\\s*(?:VS|vs|对阵)\\s*([\\u4e00-\\u9fa5]+(?:队)?)").matcher(snippet);
        if (teamMatcher.find()) {
            sb.append("   ⚽ 对阵：").append(teamMatcher.group(1)).append(" VS ").append(teamMatcher.group(2)).append("\n");
        }
        if (!r.datePublished().isBlank()) sb.append("   📅 发布时间：").append(r.datePublished().substring(0, Math.min(10, r.datePublished().length()))).append("\n");
        if (!snippet.isBlank()) {
            sb.append("   📝 ").append(snippet).append("\n");
        }
    }

    private static void appendNews(StringBuilder sb, SearchResultFilter.FilteredResult r) {
        if (!r.datePublished().isBlank()) sb.append("   📅 ").append(r.datePublished().substring(0, Math.min(10, r.datePublished().length()))).append("\n");
        if (!r.snippet().isBlank()) sb.append("   📝 ").append(r.snippet()).append("\n");
    }

    private static void appendGeneric(StringBuilder sb, SearchResultFilter.FilteredResult r) {
        if (!r.datePublished().isBlank()) sb.append("   📅 ").append(r.datePublished().substring(0, Math.min(10, r.datePublished().length()))).append("\n");
        if (!r.snippet().isBlank()) sb.append("   📝 ").append(r.snippet()).append("\n");
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }
}
