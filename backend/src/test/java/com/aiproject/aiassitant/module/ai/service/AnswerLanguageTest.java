package com.aiproject.aiassitant.module.ai.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnswerLanguageTest {
    @Test void languageFollowsQuestionRatherThanTechnicalTerms() {
        assertTrue(AnswerLanguage.english("What is the default ContextConfig max_tokens value?"));
        assertFalse(AnswerLanguage.english("ContextConfig 的 max_tokens 默认值是多少？"));
        assertFalse(AnswerLanguage.english("512"));
        assertFalse(AnswerLanguage.english("Explain this paragraph in Chinese."));
        assertTrue(AnswerLanguage.english("请用英文回答这个问题。"));
        assertTrue(AnswerLanguage.hint("Which model supports English?").startsWith("Answer this question in English"));
    }
}
