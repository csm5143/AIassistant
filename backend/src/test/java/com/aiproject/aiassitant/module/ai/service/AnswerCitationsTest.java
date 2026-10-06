package com.aiproject.aiassitant.module.ai.service;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnswerCitationsTest {
    final List<Map<String,Object>> candidates=List.of(Map.of("index",1,"fileName","无关.pdf"),Map.of("index",4,"url","https://example.com/calendar"),Map.of("index",6,"fileName","实际.md"));
    @Test void onlyReferencedSourcesRemainAndIndicesAreNotRenumbered(){var used=AnswerCitations.used("日期说明[4]，附加依据[6][4]。",candidates);assertEquals(List.of(4,6),used.stream().map(x->x.get("index")).toList());}
    @Test void uncitedAnswersAndUnknownIndicesDoNotAcquireSources(){assertTrue(AnswerCitations.used("今天是2026年10月2日。",candidates).isEmpty());assertTrue(AnswerCitations.used("依据[99]。",candidates).isEmpty());}
    @Test void jsonCodeLinksAndEscapedLiteralsAreNotReferences(){for(String a:List.of("[1]","{\"array\":[1]}","```json\n[1]\n```","~~~text\n[1]\n~~~","代码 `[1]`。","链接[1](https://example.com)，图片![6](a.png)，\\[4]"))assertTrue(AnswerCitations.used(a,candidates).isEmpty(),a);}
    @Test void realProseAfterCodeStillKeepsItsSource(){assertEquals(4,AnswerCitations.used("```text\n[1]\n```\n依据[4]。",candidates).get(0).get("index"));}
}
