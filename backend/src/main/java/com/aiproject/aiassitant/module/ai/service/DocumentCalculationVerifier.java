package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Verifies row/column bindings before substituting signed document values into arithmetic. */
final class DocumentCalculationVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("(?<![A-Za-z0-9_])v[1-9][0-9]*(?![A-Za-z0-9_])");
    private static final Pattern LITERAL = Pattern.compile("(?<![A-Za-z0-9_])[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern NUMBER = Pattern.compile("\\(?[-+]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?\\)?");

    record Binding(String key, int sourceIndex, String recordId, String field, String value) {}
    record Result(String value, List<Binding> records) {
        Set<Integer> indices() {
            Set<Integer> indices = new TreeSet<>();
            records.forEach(record -> indices.add(record.sourceIndex()));
            return indices;
        }
    }
    private DocumentCalculationVerifier() {}

    static Result calculate(String expression, String recordsJson, Map<Integer, String> evidence) {
        if (expression == null || expression.length() > 2048
                || !expression.matches("[v0-9. +*/()\\-]+")) throw new IllegalArgumentException("表达式仅使用 v1、v2 等变量和加减乘除、括号");
        JsonNode records;
        try { records = JSON.readTree(recordsJson == null ? "[]" : recordsJson); }
        catch (Exception e) { throw new IllegalArgumentException("recordsJson 必须是 JSON 数组"); }
        if (!records.isArray() || records.isEmpty() || records.size() > 64)
            throw new IllegalArgumentException("须为每个文档数值提供记录、字段、原文编号，最多64项");
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Set<List<String>> boundFields = new HashSet<>();
        for (JsonNode record : records) {
            String key = record.path("key").asText();
            int source = record.path("sourceIndex").asInt(-1);
            String id = record.path("recordId").asText().strip();
            String field = record.path("field").asText().strip();
            BigDecimal expected = number(record.path("value").asText());
            if (!key.matches("v[1-9][0-9]*") || bindings.containsKey(key) || !evidence.containsKey(source)
                    || id.isBlank() || id.length() > 200 || field.isBlank() || field.length() > 150 || expected == null)
                throw new IllegalArgumentException("无效的变量、记录标识、字段、数值或原文编号");
            if (!boundFields.add(List.of(normalized(id), normalized(field))))
                throw new IllegalArgumentException("同一记录的同一字段只能绑定一次，避免跨页或重复行重复计入");
            Set<BigDecimal> candidates = locate(evidence.get(source), id, field);
            if (candidates.size() != 1 || !candidates.contains(expected.stripTrailingZeros()))
                throw new IllegalArgumentException("原文[" + source + "]无法唯一核验记录“" + id + "”的“" + field + "”为 " + expected.toPlainString());
            bindings.put(key, new Binding(key, source, id, field, expected.stripTrailingZeros().toPlainString()));
        }
        Set<String> used = new HashSet<>();
        var variables = VARIABLE.matcher(expression);
        StringBuilder expanded = new StringBuilder();
        while (variables.find()) {
            Binding binding = bindings.get(variables.group());
            if (binding == null) throw new IllegalArgumentException("表达式中的变量没有原文核验记录");
            used.add(binding.key());
            variables.appendReplacement(expanded, "(" + binding.value() + ")");
        }
        variables.appendTail(expanded);
        if (!used.equals(bindings.keySet())) throw new IllegalArgumentException("每条核验记录须被表达式使用");
        // Only arithmetic factors are literals; document amounts must be bound variables.
        var literals = LITERAL.matcher(expression);
        while (literals.find()) {
            BigDecimal value = new BigDecimal(literals.group());
            int prior = literals.start() - 1;
            while (prior >= 0 && Character.isWhitespace(expression.charAt(prior))) prior--;
            boolean factor = prior >= 0 && (expression.charAt(prior) == '*' || expression.charAt(prior) == '/');
            if (value.compareTo(BigDecimal.ONE) != 0 && !(factor &&
                    (value.compareTo(BigDecimal.valueOf(100)) == 0 || value.compareTo(BigDecimal.valueOf(bindings.size())) == 0)))
                throw new IllegalArgumentException("文档中的操作数须使用变量；字面常量仅支持1、百分比因子100或记录数除数");
        }
        return new Result(DecimalCalculator.calculate(expanded.toString()), List.copyOf(bindings.values()));
    }

    private static Set<BigDecimal> locate(String text, String recordId, String field) {
        Set<BigDecimal> candidates = new HashSet<>();
        String[] lines = text.split("\\R");
        for (int row = 0; row < lines.length; row++) {
            List<String> cells = cells(lines[row]);
            if (cells != null && cells.stream().anyMatch(cell -> normalized(cell).equals(normalized(recordId)))) {
                for (int header = row - 1; header >= 0; header--) {
                    if (lines[header].isBlank()) break;
                    List<String> names = cells(lines[header]);
                    if (names == null || names.size() != cells.size()) continue;
                    boolean separator = names.stream().allMatch(name -> name.matches(":?-{3,}:?"));
                    if (separator) {
                        names = header > 0 ? cells(lines[header - 1]) : null;
                        if (names == null || names.size() != cells.size()) break;
                    }
                    List<Integer> columns = new ArrayList<>();
                    for (int column = 0; column < names.size(); column++) {
                        String name = normalized(names.get(column)), requested = normalized(field);
                        if (name.equals(requested) || name.startsWith(requested + " ")
                                && name.substring(requested.length()).strip().matches("\\(?(?:[£$€¥]|usd\\b|cny\\b|gbp\\b|rmb\\b|元|万元|units\\b).*")) columns.add(column);
                    }
                    if (columns.isEmpty()) { if (separator) break; else continue; }
                    if (columns.size() != 1) return Set.of();
                    BigDecimal value = number(cells.get(columns.get(0)));
                    if (value != null) candidates.add(value.stripTrailingZeros());
                    break;
                }
            }
            // Cross-page continuation: one explicitly named field on a line beginning with the record ID.
            String line = lines[row].strip();
            var starts = Pattern.compile("(?i)^" + Pattern.quote(recordId) + "(?![\\p{L}\\p{N}_-])").matcher(line);
            if (cells == null && starts.find()) {
                String rest = line.substring(starts.end());
                if (Pattern.compile("(?i)\\b[A-Z]+-[A-Z]?[0-9]+\\b").matcher(rest).find()) continue;
                var named = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])" + Pattern.quote(field)
                        + "\\s*[:=]\\s*(" + NUMBER.pattern() + ")(?![0-9.,])").matcher(rest);
                while (named.find()) {
                    BigDecimal value = number(named.group(1));
                    if (value != null) candidates.add(value.stripTrailingZeros());
                }
            }
        }
        return candidates;
    }

    private static List<String> cells(String line) {
        String delimiter = line.contains("|") ? "\\|" : line.contains("\t") ? "\t" : null;
        if (delimiter == null) return null;
        List<String> cells = new ArrayList<>(Arrays.asList(line.strip().split(delimiter, -1)));
        if (line.strip().startsWith("|")) cells.remove(0);
        if (line.strip().endsWith("|")) cells.remove(cells.size() - 1);
        return cells.stream().map(String::strip).toList();
    }
    private static String normalized(String text) {
        return text.replace("**", "").replace("<br>", " ").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static BigDecimal number(String text) {
        String value = text.strip().replace("**", "");
        if (!value.matches("\\(?[-+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?\\)?")) return null;
        boolean negative = value.startsWith("(") && value.endsWith(")");
        if (value.startsWith("(") != value.endsWith(")")) return null;
        try {
            BigDecimal number = new BigDecimal(value.replace(",", "").replace("(", "").replace(")", ""));
            return negative ? number.negate() : number;
        } catch (NumberFormatException e) { return null; }
    }
}
