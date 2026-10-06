package com.aiproject.aiassitant.module.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Dynamic search budget allocator.
 *
 * Instead of a hard-coded "2 webSearch per session" limit, this service
 * classifies the user's query and assigns a search budget tier.
 *
 * The LLM receives this budget as a system hint and the agent loop enforces it.
 *
 * Budget tiers:
 *   NONE       (0) — trivial queries, known facts, calculations, translations
 *   LIGHT      (2) — general knowledge, definitions, semi-current topics
 *   STANDARD   (4) — news, events, moderately time-sensitive
 *   DEEP       (6) — multi-faceted research, comparisons, predictions
 */
@Slf4j
@Service
public class SearchBudgetService {

    public enum Tier {
        NONE(0, "无需联网搜索"),
        LIGHT(2, "轻量搜索"),
        STANDARD(4, "标准搜索"),
        DEEP(6, "深度搜索");

        public final int quota;
        public final String label;

        Tier(int quota, String label) {
            this.quota = quota;
            this.label = label;
        }
    }

    // ── Classification patterns ──

    /** Time-sensitive keywords: today, tomorrow, this week, latest, current, etc. */
    private static final Pattern TIME_SENSITIVE = Pattern.compile(
            "(?i)(今天|明天|昨天|本周|这周|下周|最近|最新|刚刚|刚才|现在|当前"
            + "|today|tomorrow|yesterday|this week|next week|latest|recent|current|now)");

    /** Real-time data keywords: weather, stock, price, score, etc. */
    private static final Pattern REALTIME_DATA = Pattern.compile(
            "(?i)(天气|气温|股价|股票|汇率|金价|油价|行情|大盘|指数"
            + "|比分|赛程|赛果|排名|积分|战绩|数据|走势|报价"
            + "|weather|stock|price|score|ranking|rate|index)");

    /** Comparison / research keywords: compare, vs, difference, best, etc. */
    private static final Pattern RESEARCH_HEAVY = Pattern.compile(
            "(?i)(对比|比较|哪个好|推荐|排名|排行|最好|最强|热门|评测"
            + "|分析|预测|展望|趋势|前景|深度|详解|攻略|总结"
            + "|compare|vs|versus|best|recommend|review|analysis|predict|trend)");

    /** Multi-entity keywords: and, or, multiple items */
    private static final Pattern MULTI_ENTITY = Pattern.compile(
            "(?i)(和|与|以及|还有|或者|分别|各自|都|全部|所有|每个"
            + "|列表|清单|汇总|一览|合集|整理|统计)");

    /** Trivial query — no search needed */
    private static final Pattern TRIVIAL = Pattern.compile(
            "(?i)^(你好|hi|hello|谢谢|再见|bye|好的|ok|嗯|哦|哈哈|呵呵"
            + "|\\d+[+\\-*/]\\d+|翻译|translate"
            + "|帮我写|写一个|写一段|生成|代码|帮我算|计算)");

    // ── Public API ──

    /**
     * Classify a user query and return the appropriate search budget tier.
     */
    public Tier classify(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Tier.NONE;
        }

        String msg = userMessage.trim();
        int len = msg.length();

        if (ResearchPolicy.systemDateQuestion(msg)) return Tier.NONE;
        // An explicit research request wins over prefixes such as “帮我写”.
        if (ResearchPolicy.explicitWeb(msg) || REALTIME_DATA.matcher(msg).find()) return Tier.STANDARD;

        // Quick guard: very short non-question → no search
        if (len < 4 && !msg.contains("?") && !msg.contains("？")) {
            return Tier.NONE;
        }

        // Trivial patterns → no search
        if (TRIVIAL.matcher(msg).find() && len < 50) {
            log.debug("Search budget: NONE (trivial pattern) — {}", msg.substring(0, Math.min(40, len)));
            return Tier.NONE;
        }

        int score = 0;

        // Time sensitivity (+2)
        if (TIME_SENSITIVE.matcher(msg).find()) {
            score += 2;
            log.debug("Search budget: +2 for time sensitivity");
        }

        // Real-time data (+2)
        if (REALTIME_DATA.matcher(msg).find()) {
            score += 2;
            log.debug("Search budget: +2 for real-time data");
        }

        // Research-heavy (+1)
        if (RESEARCH_HEAVY.matcher(msg).find()) {
            score += 1;
            log.debug("Search budget: +1 for research keywords");
        }

        // Multi-entity (+1) — comparing multiple things needs more searches
        if (MULTI_ENTITY.matcher(msg).find()) {
            score += 1;
            log.debug("Search budget: +1 for multi-entity");
        }

        // Longer queries tend to need more context (+1 if >80 chars)
        if (len > 80) {
            score += 1;
            log.debug("Search budget: +1 for long query ({} chars)", len);
        }

        // Multiple questions? (+1)
        int questionMarks = 0;
        for (char c : msg.toCharArray()) {
            if (c == '?' || c == '？') questionMarks++;
        }
        if (questionMarks >= 2) {
            score += 1;
            log.debug("Search budget: +1 for {} question marks", questionMarks);
        }

        // Map score to tier
        Tier tier = switch (score) {
            case 0, 1   -> Tier.LIGHT;
            case 2, 3   -> Tier.STANDARD;
            default     -> Tier.DEEP;
        };

        log.info("Search budget: {} (quota={}, score={}) — {}",
                tier.label, tier.quota, score, msg.substring(0, Math.min(60, len)));
        return tier;
    }

    /**
     * Build a system hint telling the LLM how many web searches it can use.
     */
    public String buildBudgetHint(Tier tier) {
        return switch (tier) {
            case NONE -> "本次对话不需要联网搜索，请基于你的知识直接回答。";
            case LIGHT -> "你可以进行最多 2 次联网搜索来核实信息。搜索要精准，一次搜到就停。";
            case STANDARD, DEEP -> "联网搜索最多2次，已有证据足够时停止，只核实缺失部分。";
        };
    }
}
