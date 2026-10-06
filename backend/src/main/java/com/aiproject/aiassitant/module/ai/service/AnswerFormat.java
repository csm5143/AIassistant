package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;

/** Zero-model repair of a single enclosing fence when the user explicitly requests raw JSON. */
final class AnswerFormat {
    private AnswerFormat() {}
    private static final Pattern REQUEST=Pattern.compile("(?i)(?:只|仅)(?:输出|返回|给出)\\s*JSON|\\bonly\\s+(?:output|return)\\s+(?:a\\s+)?JSON\\b");
    private static final Pattern FENCE=Pattern.compile("(?is)^```(?:json)?[ \\t]*\\r?\\n(.+?)\\r?\\n```$");
    static String normalize(String question,String answer,ObjectMapper json) {
        if(question==null||answer==null||LiteralTranslation.matches(question)||!REQUEST.matcher(question).find())return answer;
        var match=FENCE.matcher(answer.trim());if(!match.matches())return answer;
        String content=match.group(1).trim();
        try {
            var value=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
            return value==null?answer:content;
        } catch(Exception ignored) {return answer;}
    }
}
