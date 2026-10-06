package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.module.knowledge.entity.*;
import com.aiproject.aiassitant.module.knowledge.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import java.time.Duration;
import java.util.*;
@Service
@RequiredArgsConstructor
public class KnowledgeCatalogService {
    private final KbCollectionMapper collections;
    private final KbDocumentMapper documents;
    private final KbDocumentSourceMapper sources;
    private final KnowledgeIndexVersion versions;
    private final BoundedTtlCache<String,Catalog> cache=new BoundedTtlCache<>(16,Duration.ofSeconds(30));
    public record Document(String id,String collectionId,String filename,String status,Set<String> terms,Set<String> metadataTerms){}
    public record Catalog(Map<String,String> names,List<Document> documents){
        public List<Document> ready(){return documents.stream().filter(d->"READY".equals(d.status())).toList();}
    }
    public Catalog get(String user){return cache.get(user+":"+versions.current(user),()->load(user)).value();}
    private Catalog load(String user){
        var names=new LinkedHashMap<String,String>();var descriptions=new HashMap<String,String>();
        for(var row:collections.selectList(new LambdaQueryWrapper<KbCollection>().eq(KbCollection::getUserId,user))){names.put(row.getId(),row.getName());descriptions.put(row.getId(),Objects.toString(row.getDescription(),""));}
        var docs=new ArrayList<Document>();
        for(var row:documents.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getUserId,user)
                .select(KbDocument::getId,KbDocument::getCollectionId,KbDocument::getFilename,KbDocument::getStatus,KbDocument::getSourceRelativePath,KbDocument::getRoutingKeywords))){
            if(!names.containsKey(row.getCollectionId()))continue;
            String metadata=names.get(row.getCollectionId())+" "+descriptions.get(row.getCollectionId())+" "+row.getFilename()+" "+Objects.toString(row.getSourceRelativePath(),"");
            var meta=Set.copyOf(RoutingTerms.query(metadata));var terms=new HashSet<>(meta);terms.addAll(RoutingTerms.query(row.getRoutingKeywords()));
            docs.add(new Document(row.getId(),row.getCollectionId(),Objects.toString(row.getFilename(),""),row.getStatus(),Set.copyOf(terms),meta));
        }
        return new Catalog(Collections.unmodifiableMap(names),List.copyOf(docs));
    }
    @Async("documentProcessor")
    @EventListener(ApplicationReadyEvent.class)
    public void backfillExistingProfiles(){
        // Existing parsed snapshots are reused; no PDF parsing or model requests.
        for(var doc:documents.selectList(new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getStatus,"READY").isNull(KbDocument::getRoutingKeywords))){
            try{
                var source=sources.selectById(doc.getId());String profile=RoutingTerms.profile(doc.getFilename(),source==null?"":source.getContent());
                int changed=documents.update(null,new LambdaUpdateWrapper<KbDocument>().eq(KbDocument::getId,doc.getId()).eq(KbDocument::getStatus,"READY")
                        .isNull(KbDocument::getRoutingKeywords).eq(doc.getUpdatedAt()!=null,KbDocument::getUpdatedAt,doc.getUpdatedAt()).set(KbDocument::getRoutingKeywords,profile));
                if(changed>0)versions.changed(doc.getUserId());
            }catch(Exception ignored){/* A damaged legacy snapshot cannot prevent chat startup. */}
        }
    }
}
