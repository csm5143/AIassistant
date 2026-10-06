package com.aiproject.aiassitant.module.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Candidate retrievals are not citations until the final prose references their original indices. */
public final class AnswerCitations {
    private AnswerCitations() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern REF=Pattern.compile("(?<![\\\\!])\\[(\\d+)\\](?!\\()");
    public static List<Map<String,Object>> used(String answer,List<Map<String,Object>> candidates){
        if(answer==null||candidates==null||candidates.isEmpty())return List.of();
        try {
            var root=JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(answer);
            if(root!=null&&(root.isObject()||root.isArray()))return List.of();
        }catch(Exception ignored){}
        String prose=answer.replaceAll("(?ms)^[ \\t]*(?:`{3,}|~{3,})[^\\r\\n]*\\R.*?^[ \\t]*(?:`{3,}|~{3,})[ \\t]*(?:\\R|$)","")
            .replaceAll("(?s)(`+).*?\\1", "");
        Set<Integer> refs=new HashSet<>();var match=REF.matcher(prose);
        while(match.find())try{refs.add(Integer.parseInt(match.group(1)));}catch(NumberFormatException ignored){}
        return candidates.stream().filter(c->c.get("index") instanceof Number n&&refs.contains(n.intValue())).toList();
    }
}
