package com.aiproject.aiassitant.module.guard;

import java.util.Comparator;
import java.util.List;

/** Holds keyword prefixes until the next token arrives, so a split keyword never leaks. */
public final class StreamingKeywordMasker {
    private final List<String> keywords;
    private final String replacement;
    private final StringBuilder pending = new StringBuilder();

    public StreamingKeywordMasker(List<String> keywords, String replacement) {
        this.keywords = keywords.stream().filter(k -> k != null && !k.isEmpty())
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        this.replacement = replacement;
    }

    public synchronized String append(String token) {
        pending.append(token);
        return drain(false);
    }

    public synchronized String finish() { return drain(true); }

    private String drain(boolean finished) {
        StringBuilder result = new StringBuilder();
        while (!pending.isEmpty()) {
            String value = pending.toString();
            // A longer keyword may share the prefix of a shorter one.
            if (!finished && keywords.stream().anyMatch(k -> k.startsWith(value) && k.length() > value.length())) break;
            String matched = keywords.stream().filter(value::startsWith).findFirst().orElse(null);
            if (matched != null) {
                result.append(replacement);
                pending.delete(0, matched.length());
            } else {
                result.append(pending.charAt(0));
                pending.deleteCharAt(0);
            }
        }
        return result.toString();
    }
}
