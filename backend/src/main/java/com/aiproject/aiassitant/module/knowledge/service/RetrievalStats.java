package com.aiproject.aiassitant.module.knowledge.service;
/** Counts real requests; zero-token cache hits do not count as provider requests. */
public class RetrievalStats {
    public int embeddingRequests, embeddingCacheHits, rerankRequests, rerankDocuments, retrievalCacheHits, lexicalQueries;
    public long embeddingTokens, rerankInputChars, elapsedMs;
    public int evidenceChars;
    public boolean degraded;
    public java.util.Map<String,Object> view(){
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("embeddingRequests",embeddingRequests);result.put("embeddingTokens",embeddingTokens);result.put("embeddingCacheHits",embeddingCacheHits);
        result.put("rerankRequests",rerankRequests);result.put("rerankDocuments",rerankDocuments);result.put("rerankInputChars",rerankInputChars);
        result.put("retrievalCacheHits",retrievalCacheHits);result.put("lexicalQueries",lexicalQueries);result.put("retrievalMs",elapsedMs);result.put("evidenceChars",evidenceChars);result.put("degraded",degraded);
        return result;
    }
}
