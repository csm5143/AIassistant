package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.common.SecurityUtil;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.service.VectorSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Agent tools exposed to the LLM via LangChain4j AiServices.
 * Each @Tool method becomes a function the LLM can call during conversation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTools {

    private final com.aiproject.aiassitant.module.knowledge.service.KnowledgeService knowledgeService;
    private final ApiManager apiManager;
    private final WebSummaryService webSummaryService;
    private final ExaSearchService exaSearchService;

    @Tool("读取本轮联网搜索返回的网页正文。只在搜索证据还不足时使用；url 必须来自本轮实际搜索结果。")
    public String readWebPage(@P("本轮搜索结果中的完整网页地址") String url) {
        return "请通过当前对话的来源登记器读取网页。";
    }

    @Tool("【网页摘要】当用户在聊天中发送URL链接并希望了解网页内容时调用。抓取网页正文，生成结构化摘要（核心观点、关键信息、标签）。适用于任何HTTP/HTTPS链接。")
    public String summarizeUrl(
            @P("用户发送的网页URL链接") String url) {

        if (url == null || url.isBlank()) return "请提供有效的网页URL。";
        log.info("Agent summarizeUrl: {}", url);
        return webSummaryService.summarize(url);
    }

    @Tool("【内部文档搜索】在用户已上传的资料库中检索。仅适用于：产品手册、内部文档、技术教程等用户已导入的内容。")
    public String knowledgeSearch(
            @P("搜索关键词，用中文或英文") String query,
            @P("返回结果条数(默认5，最大20)") int topK) {

        if (query == null || query.isBlank()) {
            return "错误：请提供搜索关键词";
        }
        if (topK <= 0) topK = 5;
        if (topK > 20) topK = 20;

        try {
            String userId = SecurityUtil.getCurrentUserId();
            List<KbChunk> chunks = knowledgeChunks(query, topK);

            if (chunks == null || chunks.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的内容。请尝试不同的关键词。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 找到 ")
              .append(chunks.size()).append(" 条结果：\n\n");

            for (int i = 0; i < chunks.size(); i++) {
                KbChunk chunk = chunks.get(i);
                String content = chunk.getContent();
                if (content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                sb.append("[").append(i + 1).append("] ").append(content).append("\n\n");
            }
            return sb.toString().trim();

        } catch (Exception e) {
            log.warn("Agent knowledge_search failed: {}", e.getMessage());
            return "知识库搜索失败：" + e.getMessage();
        }
    }

    public List<KbChunk> knowledgeChunks(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        return knowledgeService.searchAll(query, Math.min(20, topK <= 0 ? 5 : topK));
    }

    @Tool("核算十进制数学表达式，支持加减乘除、负数和括号，保留大整数精度。例如：2+3*4、(1+2)*3。非终止除法会标明近似，不支持函数、单位或千分位分隔符。")
    public String calculator(
            @P("数学表达式，如 2+3*4") String expression) {

        if (expression == null || expression.isBlank()) {
            return "错误：请提供要计算的表达式";
        }

        try {
            try {
                return expression + " = " + DecimalCalculator.calculateExact(expression);
            } catch (DecimalCalculator.ZeroDivisor e) {
                throw e;
            } catch (ArithmeticException e) {
                // Exact division can be non-terminating; never disguise rounding as an exact value.
                return expression + " ≈ " + DecimalCalculator.calculate(expression) + "（除法结果四舍五入至小数点后16位）";
            }
        } catch (DecimalCalculator.ZeroDivisor e) {
            return "计算失败：除数不能为0。";
        } catch (Exception e) {
            return "计算 \"" + expression + "\" 出错：" + e.getMessage();
        }
    }

    @Tool("【获取当前真实日期和时间 - 强制优先调用】你做任何时间判断前都必须先调此工具。包括但不限于：用户问'现在几点/今天几号'、分析简历上的时间段、判断某个日期是过去还是未来、计算距今多久、提到'最近/今年/明年/本周/下个月'等任何涉及时间的表述。你的训练数据日期不可信——必须用此工具获取真实时间。")
    public String currentTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool("联网搜索公开信息：本地证据不足以回答全部子问题，或需要最新信息时调用。只搜索缺失部分，优先官方资料；不要发送内部文档摘录、个人信息或密钥。结果包含可引用编号和网页来源。")
    public String webSearch(
            @P("搜索关键词，使用自然语言描述你想搜索的内容。例如：'2026年6月 NBA总决赛 赛程'、'深圳今天天气'、'最新A股大盘走势'") String query) {

        if (query == null || query.isBlank()) return "请提供搜索关键词";
        log.info("Agent webSearch (Exa): {}", query);

        // Path 1: Exa semantic search (via mcporter or direct MCP)
        String exaResult = exaSearchService.search(query, 8, "auto");
        if (exaResult != null && !exaResult.contains("暂时不可用")) {
            return exaResult;
        }

        // Path 2: Fallback to original API-based search
        log.info("Exa unavailable, falling back to legacy search for: {}", query);
        return apiManager.webSearch(query);
    }

    @Tool("【视觉识别 Vision - mimo-v2.5】分析图片内容：识别物体、场景、人物、颜色、动作。用于回答'图片里有什么''这是什么物体''描述这张图'类问题。严禁用于提取文字——提取文字必须用ocrExtract。")
    public String imageRecognition(
            @P("图片的URL地址或base64编码数据") String imageUrl) {

        if (imageUrl == null || imageUrl.isBlank()) return "请提供图片URL或base64数据";
        log.info("Agent imageRecognition (Vision) triggered");
        return apiManager.recognizeImage(imageUrl);
    }

    @Tool("【文字提取 OCR - 百度OCR】提取图片中的印刷/手写文字。专用于：截图文字、扫描件、拍照文档/合同/发票/菜单/路牌/书籍/代码截图。仅当用户明确要求'提取文字''识别图片里的字''OCR识别'时调用。严禁用于描述图片内容——描述图片用imageRecognition。")
    public String ocrExtract(
            @P("图片的URL地址或base64编码数据") String imageUrl) {

        if (imageUrl == null || imageUrl.isBlank()) return "请提供图片URL或base64数据";
        log.info("Agent ocrExtract triggered");
        return apiManager.extractText(imageUrl);
    }

}
