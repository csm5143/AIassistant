package com.aiproject.aiassitant.module.knowledge.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

class HybridSearchQueryTest {
    @Test void removesDocumentInstructionBeforeRecall() {
        assertEquals("彩虹兽最喜欢什么？", HybridSearchService.normalizeQuery("根据我上传的文档，彩虹兽最喜欢什么？"));
        assertEquals("彩虹兽最喜欢什么", HybridSearchService.normalizeQuery("彩虹兽最喜欢什么"));
    }

    @Test void splitsParallelQuestionForIndependentRecall() {
        assertEquals(List.of("图书馆逾期一天的费用是多少？", "差旅每天餐补是多少？"),
                HybridSearchService.splitParallelQuestion("图书馆逾期一天的费用和差旅每天餐补分别是多少？"));
        assertEquals(List.of("计算属性怎样处理依赖与副作用？", "watchEffect怎样处理依赖与副作用？"),
                HybridSearchService.splitParallelQuestion("计算属性和watchEffect分别怎样处理依赖与副作用？"));
        assertEquals(List.of(), HybridSearchService.splitParallelQuestion("海岚项目的负责人是谁？"));
    }

    @Test void tableReferencesDoNotConfuseOtherNumbersOrCrossTableQuestions() {
        String pattern=HybridSearchService.tableReferencePattern("What are the mContriever scores in Table 1?");
        assertNotNull(pattern);
        // Java's approximation uses whitespace; the database uses the same POSIX pattern.
        var regex=java.util.regex.Pattern.compile(pattern.replace("[[:space:]]","\\s").replace("[^[:alnum:].]","[^A-Za-z0-9.]"),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.MULTILINE);
        assertTrue(regex.matcher("Table 1: MIRACL scores\n| Model | Avg |").find());
        assertFalse(regex.matcher("Table 15: Ablations").find());
        assertFalse(regex.matcher("Table 1.2: Other section").find());
        assertNull(HybridSearchService.tableReferencePattern("Compare Table 1 and Table 2."));
        assertNull(HybridSearchService.tableReferencePattern("What is the default learning rate?"));
        assertNotNull(HybridSearchService.tableReferencePattern("表 1 中的中文检索分数是多少？"));
    }

    @Test void exactRequestedRowsSurviveSemanticRankingAndCompoundModelNamesStayDistinct() {
        var baseline=table("baseline","| mContriever | 43.1 | 36.4 |");
        var unrelated=table("unrelated","| Dense | 69.2 | 56.9 |");
        assertEquals("baseline",HybridSearchService.prioritizeTableRows(
                "In Table 1, what are the Avg and en scores for mContriever?",List.of(unrelated,baseline),List.of(unrelated),1).get(0).getId());
        var compound=table("compound","| Dense+Sparse | 70.4 | 58.8 |");
        assertEquals("compound",HybridSearchService.prioritizeTableRows(
                "In Table 1, what is the Dense+Sparse score?",List.of(unrelated,compound),List.of(unrelated),1).get(0).getId());
        assertEquals("unrelated",HybridSearchService.prioritizeTableRows(
                "What are the conclusions of Table 1?",List.of(baseline),List.of(unrelated),1).get(0).getId());
    }

    private static com.aiproject.aiassitant.module.knowledge.entity.KbChunk table(String id,String row) {
        var chunk=new com.aiproject.aiassitant.module.knowledge.entity.KbChunk();chunk.setId(id);
        chunk.setContent("Table 1: Scores\n| Model | Avg | en |\n|---|---|---|\n"+row);
        return chunk;
    }
}
