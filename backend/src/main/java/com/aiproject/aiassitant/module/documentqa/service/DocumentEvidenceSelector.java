package com.aiproject.aiassitant.module.documentqa.service;

import java.util.*;
import java.util.regex.Pattern;
import com.aiproject.aiassitant.module.documentqa.service.DocumentQaService.*;

/** Small documents need complete evidence, not a top-k sample presented as the whole file. */
public final class DocumentEvidenceSelector {
    private DocumentEvidenceSelector() {}
    public static boolean useComplete(DocSession session) {
        return session.chunks.stream().mapToInt(String::length).sum() <= 6000;
    }
    public static List<SearchHit> complete(DocSession session) {
        List<SearchHit> hits = new ArrayList<>();
        for (int i=0; i<session.chunks.size(); i++) hits.add(new SearchHit(session.chunks.get(i),1,i+1));
        return hits;
    }
    public static List<SearchHit> expand(DocSession session, List<SearchHit> ranked, String question) {
        Set<Integer> indices = new TreeSet<>();
        // Exact identifiers win over embedding similarity; include adjoining chunks for continued rows.
        var matcher=Pattern.compile("(?i)\\b[a-z]+[a-z0-9]*[-_][a-z0-9_-]+\\b").matcher(question);
        Set<String> ids=new HashSet<>(); while(matcher.find())ids.add(matcher.group().toLowerCase(Locale.ROOT));
        for(int i=0;i<session.chunks.size();i++) {
            String text=session.chunks.get(i).toLowerCase(Locale.ROOT);
            if(ids.stream().anyMatch(text::contains))for(int j=Math.max(0,i-1);j<=Math.min(session.chunks.size()-1,i+1);j++)indices.add(j);
        }
        for(SearchHit hit:ranked)indices.add(hit.chunkIndex()-1);
        List<SearchHit> result=new ArrayList<>();int characters=0;
        for(int i:indices) {
            String text=session.chunks.get(i);
            if(characters+text.length()>12000)continue;
            characters+=text.length();result.add(new SearchHit(text,1,i+1));
        }
        return result;
    }
}
