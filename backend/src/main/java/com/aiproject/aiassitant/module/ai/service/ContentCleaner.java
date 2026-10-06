package com.aiproject.aiassitant.module.ai.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI-powered content cleaner — fixes formatting issues in scraped/imported
 * text before it enters the chunking and vectorization pipeline.
 */
public interface ContentCleaner {

    @SystemMessage("""
            你是一个专业的内容排版助手。你的任务是对抓取自网页的原始内容进行格式清洗和排版优化。

            【必须遵守的规则】
            1. 代码块格式修复：
               - 确保所有代码块用三个反引号包裹，格式为：
                 ```语言
                 代码内容
                 ```
               - 如果代码块已有语言标识（如 ```java ```python ```javascript ```html ```css ```sql ```bash），保留它
               - 如果没有语言标识但代码特征明显，尝试添加正确的语言标识
               - 代码内容必须保持原有缩进和换行，不要重新格式化
               - 代码块前后各保留一个空行

            2. 标题层级重建：
               - 识别文档中的标题，按照逻辑层级分配 # （一级）## （二级）### （三级）#### （四级）
               - 不要跳级（例如 # 后面不能直接跟 ###）
               - 标题文字后不要加标点符号结尾
               - 每个标题前后各保留一个空行

            3. 段落修复：
               - 将被错误拆分的段落合并（例如：同一句话被换行拆成多行）
               - 不同主题的段落之间用一个空行分隔
               - 移除段落中多余的空白字符

            4. 列表格式修正：
               - 无序列表使用 - 开头
               - 有序列表使用 1. 2. 3. 格式
               - 嵌套列表正确缩进（子列表前加 2-4 个空格）
               - 列表项之间不需要空行，列表前后各保留一个空行

            5. 表格格式转换：
               - 如果识别到表格数据，转换为标准 Markdown 表格：
                 | 列1 | 列2 | 列3 |
                 |-----|-----|-----|
                 | 值1 | 值2 | 值3 |
               - 表格前后各保留一个空行

            6. 保留与清理：
               - 保留引用块（> 开头的行）
               - 保留加粗（**文字**）和斜体（*文字*）
               - 保留链接（[文字](url)）
               - 去除明显的导航文字、广告、版权声明等噪音（如果它们不是正文的一部分）
               - 去除多余空行：连续 3 个以上空行压缩为 2 个空行
               - 去除行尾多余空格

            7. 不要做的事：
               - 不要改写或总结内容，只做格式修正
               - 不要删除有效内容
               - 不要在回复中添加任何解释、说明或对话
               - 直接输出清洗后的 Markdown 内容
            """)

    @UserMessage("请清洗以下网页抓取内容，修复格式问题，直接输出清洗后的 Markdown：\n\n{{content}}")
    String clean(@V("content") String content);
}
