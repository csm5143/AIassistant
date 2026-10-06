package com.aiproject.aiassitant.module.knowledge.service;

import java.util.List;
import java.util.regex.Pattern;

/** Keep report instructions out of relevance scoring, without an extra model request. */
final class ResearchQuery {
    private ResearchQuery() {}

    static String topic(String question) {
        var match = Pattern.compile("(?m)^研究问题[：:]\\s*(.+)$").matcher(question);
        return match.find() ? match.group(1).strip() : question.strip();
    }

    static String forDocument(String question, String filename, List<String> filenames) {
        String topic = topic(question);
        Pattern name = title(filename);
        if (name == null) return topic;
        var others = filenames.stream().filter(f -> !f.equals(filename)).map(ResearchQuery::title)
                .filter(java.util.Objects::nonNull).toList();
        // A comparison often includes a question for each named paper. Prefer its own
        // question over unrelated clauses that can lower cross-language rerank scores.
        var clauses = topic.split("[。！？!?;；：:，\\n]");
        var focused = new java.util.ArrayList<String>();
        for (String clause : clauses) {
            if (name.matcher(clause).find() && others.stream().noneMatch(p -> p.matcher(clause).find()))
                focused.add(clause.strip());
        }
        return focused.isEmpty() ? topic : String.join("；", focused);
    }

    private static Pattern title(String filename) {
        if (filename == null) return null;
        String stem = filename.replaceFirst("(?i)\\.(pdf|docx?|txt|md|xlsx?)$", "");
        if (stem.length() < 4) return null;
        String pattern = java.util.Arrays.stream(stem.split("[\\s._-]+"))
                .filter(s -> !s.isBlank()).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("[\\s._-]*"));
        return pattern.isBlank() ? null : Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
