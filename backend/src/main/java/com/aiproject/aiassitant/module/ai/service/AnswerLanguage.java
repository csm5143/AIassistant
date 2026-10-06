package com.aiproject.aiassitant.module.ai.service;

/** Language hint for the current question, independent of document language. */
public final class AnswerLanguage {
    private AnswerLanguage() {}
    public static boolean english(String question) {
        if (question == null) return false;
        if (question.matches("(?is).*(?:answer|respond|explain|translate).{0,30}(?:in|into)\\s+(?:simplified\\s+)?Chinese.*") ||
                question.matches("(?s).*(?:用中文|中文回答|翻译成中文).*")) return false;
        if (question.matches("(?s).*(?:用英语|用英文|英文回答|翻译成英文).*")) return true;
        long latin=question.chars().filter(c -> c>='a' && c<='z' || c>='A' && c<='Z').count();
        long han=question.codePoints().filter(c -> Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN).count();
        return han==0 && latin>=10;
    }
    public static String hint(String question) {
        if (question != null && question.matches("(?is).*(?:answer|respond|explain|translate).{0,30}(?:in|into)\\s+(?:simplified\\s+)?Chinese.*"))
            return "请按用户要求使用中文回答，保留准确的术语和数字。";
        return english(question)
                ? "Answer this question in English, including explanations and reference labels. Retain exact technical terms and numbers. The language of reference documents must not change the answer language. Give a direct, concise answer; do not repeat the same fact."
                : "请使用用户问题的语言回答；资料为其他语言时也保持用户语言，保留术语和准确数字。直接回答问题，避免重复同一事实。";
    }
}
