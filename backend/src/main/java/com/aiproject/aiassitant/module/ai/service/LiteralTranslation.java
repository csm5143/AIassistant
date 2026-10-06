package com.aiproject.aiassitant.module.ai.service;

import java.util.regex.Pattern;

/** Only a self-contained translation request; quoted words are data, not search intent. */
final class LiteralTranslation {
    private LiteralTranslation() {}
    private static final Pattern QUOTED=Pattern.compile("(?is)^把(?:“[^“”]+”|\"[^\"]+\")翻译成(?:中文|汉语|英文|英语|日文|日语|韩文|韩语|法文|法语|德文|德语|西班牙语)(?:[，,]\\s*(?:只给译文|仅给译文|只输出译文|不要解释))?[。.!！]?$");
    static boolean matches(String question) {
        return question!=null&&QUOTED.matcher(question.trim()).matches();
    }
}
