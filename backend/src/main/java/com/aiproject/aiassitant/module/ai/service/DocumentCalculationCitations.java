package com.aiproject.aiassitant.module.ai.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Locates decimal operands in parsed evidence; ambiguous locations are left for the model to cite. */
final class DocumentCalculationCitations {
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\p{L}\\p{N}_./-])[-+]?\\d+(?:,\\d{3})*\\.\\d+(?![\\p{L}\\p{N}_./-])");
    private DocumentCalculationCitations() {}

    static Set<Integer> locate(String expression, Map<Integer,String> evidence) {
        Set<BigDecimal> operands = new HashSet<>();
        // Operators do not form part of a number here: subtraction and unary minus have the same magnitude.
        var input = Pattern.compile("\\d+\\.\\d+").matcher(expression);
        while(input.find()) operands.add(new BigDecimal(input.group()).abs().stripTrailingZeros());
        Set<Integer> result = new TreeSet<>();
        for(BigDecimal operand : operands) {
            Set<Integer> candidates = new HashSet<>();
            for(var entry : evidence.entrySet()) {
                var numbers = DECIMAL.matcher(entry.getValue());
                while(numbers.find()) if(new BigDecimal(numbers.group().replace(",", "")).abs().stripTrailingZeros().equals(operand)) {
                    candidates.add(entry.getKey()); break;
                }
            }
            if(candidates.size()==1) result.add(candidates.iterator().next());
        }
        return result;
    }

    static String missing(String answer, Set<Integer> indices, boolean english) {
        var refs = Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        Set<Integer> cited = new HashSet<>();
        while(refs.find()) cited.add(Integer.parseInt(refs.group(1)));
        var suffix = new StringBuilder();
        for(int index : indices) if(!cited.contains(index)) suffix.append('[').append(index).append(']');
        return suffix.isEmpty() ? "" : (english ? "\n\nNumeric sources: " : "\n\n数值出处：") + suffix;
    }
}
