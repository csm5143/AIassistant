package com.aiproject.aiassitant.module.knowledge.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative PDF prose cleanup and language-aware boundaries, before source offsets are assigned. */
final class DocumentText {
    private DocumentText() {}
    private static final Set<String> ABBREVIATIONS = Set.of("dr", "mr", "mrs", "ms", "prof", "fig", "figs",
            "eq", "eqs", "sec", "secs", "e.g", "i.e", "vs", "al", "et", "no", "approx", "inc");
    private static final Pattern WRAPPED_WORD = Pattern.compile("([A-Za-z]{2,})[-\\u00ad]\\n\\s*([a-z]{2,})");
    // Explicit vocabulary avoids turning legitimate compounds (multi-head, fine-tuning)
    // into incorrect words. Unknown hard-hyphen words retain the hyphen.
    private static final Set<String> JOINED_WORDS = Set.of("international", "information", "performance", "performances",
            "representation", "representations", "transformer", "transformers", "attention", "training", "translation",
            "translations", "retrieval", "embedding", "embeddings", "language", "languages", "context", "contexts",
            "contextualization", "architecture", "architectures", "dimensionality", "parallelization", "evaluation",
            "evaluations", "generation", "generative", "classification", "optimization", "implementation", "document",
            "documents", "experiment", "experiments", "experimental", "functionality", "functionalities", "contributions",
            "significant", "significantly", "supervised", "unsupervised", "approximately", "computation",
            "computational", "complexity", "comparison", "comparisons", "application", "applications", "investigate",
            "investigation", "investigations", "increasing", "decreasing", "respectively", "multilingual", "granularities");

    static String pdfProse(String raw) {
        if (raw == null) return "";
        String value = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
                .replace("\ufb01", "fi").replace("\ufb02", "fl");
        var matcher = WRAPPED_WORD.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String combined = matcher.group(1) + matcher.group(2);
            boolean soft = matcher.group().indexOf('\u00ad') >= 0;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    soft || JOINED_WORDS.contains(combined.toLowerCase(Locale.ROOT))
                            ? combined : matcher.group(1) + "-" + matcher.group(2)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static boolean sentenceEnd(String text, int i, int limit) {
        char c = text.charAt(i);
        if (c == '。' || c == '！' || c == '？') return true;
        if (c != '.' && c != '!' && c != '?') return false;
        if (i + 1 >= limit || !Character.isWhitespace(text.charAt(i + 1))) return false;
        if (c != '.') return true;
        int from = i;
        while (from > 0 && (Character.isLetter(text.charAt(from - 1)) || text.charAt(from - 1) == '.')) from--;
        String token = text.substring(from, i).toLowerCase(Locale.ROOT);
        if (ABBREVIATIONS.contains(token) || token.matches("(?:[a-z]\\.)*[a-z]")) return false;
        return true;
    }

    static int end(String text, int start, int proposed, int limit) {
        if (proposed >= limit) return limit;
        int floor = start + (proposed - start) / 2;
        int whitespace = -1, line = -1;
        for (int i = proposed - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '\n' && line < 0) line = i + 1;
            if (Character.isWhitespace(c) && whitespace < 0) whitespace = i + 1;
            if (sentenceEnd(text, i, limit)) return i + 1;
        }
        return line > 0 ? line : whitespace > 0 ? whitespace : proposed;
    }

    static int overlapStart(String text, int start, int end) {
        // Align Latin overlap to a word boundary; Chinese has no intervening spaces.
        if (start > 0 && start < end && isToken(text.charAt(start - 1)) && isToken(text.charAt(start))) {
            int aligned = start;
            while (aligned < end && isToken(text.charAt(aligned))) aligned++;
            if (aligned < end) start = aligned;
        }
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        return start;
    }

    private static boolean isToken(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || "_./:@?=&%+#-".indexOf(c) >= 0);
    }
}
