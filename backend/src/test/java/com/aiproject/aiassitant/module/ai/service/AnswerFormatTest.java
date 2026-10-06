package com.aiproject.aiassitant.module.ai.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnswerFormatTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void explicitRawJsonRemovesExactlyOneValidEnclosingFence(){
        assertEquals("{\"name\":\"张三\",\"age\":28}",AnswerFormat.normalize("那给一个示例，只输出JSON。","```json\n{\"name\":\"张三\",\"age\":28}\n```",json));
        assertEquals("[1,2]",AnswerFormat.normalize("Only return JSON.","```\n[1,2]\n```",json));
    }
    @Test void cannotDiscardAdditionalTextOrExtraJsonDocuments(){
        for(String answer:new String[]{"这是示例：\n```json\n{}\n```","```json\n{broken}\n```","```json\n{} {}\n```","```json\n{}\n```\n说明","```js\n{}\n```"})
            assertEquals(answer,AnswerFormat.normalize("只输出JSON",answer,json));
    }
    @Test void ordinaryAnswersAndQuotedTranslationKeepMarkdown(){
        String answer="```json\n{}\n```";
        assertEquals(answer,AnswerFormat.normalize("给一个JSON示例",answer,json));
        assertEquals(answer,AnswerFormat.normalize("把“只输出JSON”翻译成英文。",answer,json));
    }
    @Test void existingValidJsonAndStringContentsAreNotRewritten(){
        String answer="{\"text\":\"``` and spaces\"}";
        assertEquals(answer,AnswerFormat.normalize("只输出JSON",answer,json));
        assertEquals(answer,AnswerFormat.normalize("只输出JSON","```json\n"+answer+"\n```",json));
    }
}
