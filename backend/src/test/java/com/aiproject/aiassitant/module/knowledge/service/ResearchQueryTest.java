package com.aiproject.aiassitant.module.knowledge.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ResearchQueryTest {
    @Test void reportScaffoldingIsNotUsedToRankEvidence() {
        assertEquals("模型的局限是什么？", ResearchQuery.topic("请完成专题研究报告。\n研究问题：模型的局限是什么？\n重点比较维度：方法\n关键事实逐项引用"));
    }
    @Test void chineseComparisonUsesEachEnglishPapersOwnQuestion() {
        var names = List.of("bge-m3.pdf", "lost-in-the-middle.pdf");
        String question = "研究问题：比较 BGE-M3 与 Lost in the Middle 分别解决的问题：BGE-M3 的三种检索方式是什么，Lost in the Middle 对信息所在位置的发现是什么？不要把二者当作同一基准上的效果对比。";
        assertEquals("BGE-M3 的三种检索方式是什么", ResearchQuery.forDocument(question, names.get(0), names));
        assertEquals("Lost in the Middle 对信息所在位置的发现是什么", ResearchQuery.forDocument(question, names.get(1), names));
    }
    @Test void sharedComparisonQuestionRemainsIntact() {
        String topic = "Compare BGE-M3 and Lost in the Middle. Describe dense, sparse and multi-vector retrieval.";
        assertEquals(topic, ResearchQuery.forDocument("研究问题：" + topic, "bge-m3.pdf", List.of("bge-m3.pdf", "lost-in-the-middle.pdf")));
    }
    @Test void absentOrAmbiguousNamesKeepTheWholeTopic() {
        for (String filename : List.of("", "说明.pdf", "guide-v1.pdf"))
            assertEquals("比较方法与局限", ResearchQuery.forDocument("研究问题：比较方法与局限", filename, List.of(filename)));
        assertEquals("说明.pdf 的差异", ResearchQuery.forDocument("说明.pdf 的差异", "说明.pdf", List.of("说明.pdf", "说明.pdf")));
    }
}
