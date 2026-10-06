package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import java.util.*;
/** Empty lists mean all OWNED ready documents. Disabled means no retrieval. */
public record RetrievalScope(List<String> collectionIds, List<String> documentIds, boolean disabled) {
    public RetrievalScope {
        collectionIds=collectionIds==null?List.of():collectionIds.stream().distinct().sorted().toList();
        documentIds=documentIds==null?List.of():documentIds.stream().distinct().sorted().toList();
    }
    public static RetrievalScope all(){return new RetrievalScope(List.of(),List.of(),false);}
    public static RetrievalScope none(){return new RetrievalScope(List.of(),List.of(),true);}
    public static RetrievalScope collection(String id){return id==null||id.isBlank()?all():new RetrievalScope(List.of(id),List.of(),false);}
    public boolean contains(KbChunk chunk){return !disabled&&(collectionIds.isEmpty()||collectionIds.contains(chunk.getCollectionId()))&&(documentIds.isEmpty()||documentIds.contains(chunk.getDocumentId()));}
}
