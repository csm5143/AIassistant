package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.knowledge.service.WebScraperService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Scrapes a URL and generates a structured summary via LLM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSummaryService {

    private final WebScraperService webScraperService;
    private final ChatModelFactory chatModelFactory;

    /**
     * Fetch and summarize a web page.
     * @return structured summary in Markdown, or error message.
     */
    public String summarize(String url) {
        // 1. Validate URL
        if (url == null || url.isBlank()) return "请提供有效的网页 URL。";
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }

        // 2. Scrape the page
        String content;
        try {
            WebScraperService.ScrapedPage page = webScraperService.scrape(trimmed);
            if (page.error != null) {
                return "无法抓取网页：" + page.error;
            }
            // Use the raw content (already cleaned by WebScraperService)
            content = page.content != null ? page.content : page.markdown;
            if (content == null || content.isBlank() || content.length() < 30) {
                return "网页内容为空或过短，可能为纯图片页面或需 JS 渲染的动态页面。";
            }
        } catch (Exception e) {
            log.warn("Scrape failed for {}: {}", trimmed, e.getMessage());
            return "网页抓取失败：" + e.getMessage();
        }

        // 3. Truncate to ~8000 chars for LLM context
        String truncated = content.length() > 8000 ? content.substring(0, 8000) + "\n...(内容过长已截断)" : content;

        // 4. Build summarization prompt
        String prompt = """
            请对以下网页内容生成结构化摘要，用 Markdown 格式输出：

            ## 📌 标题
            (从内容中提取或概括的标题)

            ## 📝 核心观点
            - 观点1
            - 观点2
            - 观点3

            ## 📊 关键信息
            (重要数据、人物、时间、地点等)

            ## 🏷 标签
            `标签1` `标签2` `标签3` `标签4` `标签5`

            ## 💡 价值点
            (这篇文章对读者有什么帮助或启发)


            网页内容：
            %s
            """.formatted(truncated);

        // 5. Call LLM (use chat model, not streaming)
        try {
            var model = chatModelFactory.getChatModel(null); // default chat model
            String summary = model.generate(prompt);
            return summary + "\n\n---\n🌐 原文链接：" + trimmed;
        } catch (Exception e) {
            log.warn("LLM summarization failed: {}", e.getMessage());
            return "摘要生成失败：" + e.getMessage() + "\n\n原文首段：" + truncated.substring(0, Math.min(500, truncated.length()));
        }
    }
}
