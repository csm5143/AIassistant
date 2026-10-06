package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.common.BizException;
import com.aiproject.aiassitant.common.CalendarQuestion;
import com.aiproject.aiassitant.module.chat.entity.ChatSession;
import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.aiproject.aiassitant.module.chat.mapper.ChatSessionMapper;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.mapper.KbCollectionMapper;
import com.aiproject.aiassitant.module.knowledge.mapper.KbDocumentMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
@Service
@RequiredArgsConstructor
public class KnowledgeRoutingService {
    private final KnowledgeCatalogService catalogs;
    private final HybridSearchService search;
    private final KnowledgeIndexVersion versions;
    private final KbCollectionMapper collectionMapper;
    private final KbDocumentMapper documentMapper;
    private final ChatSessionMapper sessions;
    private final ObjectMapper json;
    private record CachedRetrieval(List<KbChunk> chunks,boolean degraded){}
    private final BoundedTtlCache<String,CachedRetrieval> results=new BoundedTtlCache<>(256,Duration.ofMinutes(3));
    private static final Pattern SMALLTALK=Pattern.compile("(?i)^(你好|您好|嗨|hi|hello|(?:谢谢|感谢)(?:你|您|你们|你的帮助|您的帮助)?|再见|bye|好的|好|ok|嗯|哈哈|早上好|晚安|你是谁|你叫什么|你能做什么)[！!。.,，？?\\s]*$");
    private static final Pattern SIMPLE_TASK=Pattern.compile("(?i)^(?:翻译[：:]|translate[ :])|^(?:计算|帮我算)?[：:\\s]*[0-9.]+\\s*[-+*/%]\\s*[0-9.() +*/%-]+[？?。！!\\s]*$");
    private static final Pattern NO_KNOWLEDGE=Pattern.compile("(不要|不用|无需).{0,8}(查资料|知识库|检索文档)");
    private static final Pattern DOC_INTENT=Pattern.compile("(?i)知识库|文档|资料|本书|手册|教程|上传|根据|依据|pdf");
    private static final Pattern ONLY=Pattern.compile("(只|仅).{0,8}(根据|依据|基于|使用|查|看)|不要.{0,10}(外部知识|其他资料)");
    private static final Pattern THIS_DOC=Pattern.compile("(?i)(此|这份|这个|该|这本|本书).{0,3}(pdf|文档|资料|书)?");
    private static final Pattern NAMED_FILE=Pattern.compile("(?i)[\\p{L}\\p{N}_-]+\\.(pdf|docx?|txt|md|xlsx?)");
    private static final Pattern FOLLOWUP=Pattern.compile("^(那|那么|它|他们|这些|这个|其中|第[一二三四五六七八九十0-9]+|继续|再解释|举个|举例|详细|展开|还有呢|为什么呢)");
    private static final Pattern SHIFT=Pattern.compile("换个话题|换一个话题|另外一个问题|接下来问|说回|现在聊|不聊.*了");
    public static final Set<String> MODES=Set.of("AUTO","NONE","ALL","SELECTED");
    public record Choice(RetrievalScope scope,boolean strict,String reason,String notice,String query,List<HybridSearchService.LexicalHit> probe){}
    public static class Turn {
        public String user,mode,label,reason,notice,query;
        public RetrievalScope scope;
        public boolean strict,attempted;
        public List<KbChunk> chunks=List.of();
        public final RetrievalStats stats=new RetrievalStats();
        public final Map<String,List<KbChunk>> memo=new LinkedHashMap<>();
        public int extraSearches;
        public Map<String,String> names=Map.of();
        public Map<String,Object> view(){
            var result=new LinkedHashMap<String,Object>();result.put("mode",mode);result.put("label",label);result.put("reason",reason);result.put("notice",notice==null?"":notice);
            result.put("strict",strict);result.put("collectionIds",scope.collectionIds());result.put("documentIds",scope.documentIds());result.put("usedKnowledge",!chunks.isEmpty());return result;
        }
    }
    public void preferences(ChatSession session,String mode,List<String> collections,List<String> documents,String user){
        String normalized=mode==null?"AUTO":mode.toUpperCase(Locale.ROOT);if(!MODES.contains(normalized))throw new BizException(400,"无效的资料范围模式");
        var scope=new RetrievalScope(collections,documents,false);if(scope.collectionIds().size()>20||scope.documentIds().size()>20)throw new BizException(400,"最多选择20个资料范围");
        validate(scope,user);
        if("SELECTED".equals(normalized)&&scope.collectionIds().isEmpty()&&scope.documentIds().isEmpty())throw new BizException(400,"请选择至少一个知识库或文档");
        session.setKnowledgeMode(normalized);
        try{session.setKnowledgeSelectionJson(json.writeValueAsString(scope));}catch(Exception e){throw new IllegalStateException(e);}
        session.setKnowledgeRouteJson(null);
        sessions.update(null,new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId,session.getId()).eq(ChatSession::getUserId,user)
                .set(ChatSession::getKnowledgeMode,normalized).set(ChatSession::getKnowledgeSelectionJson,session.getKnowledgeSelectionJson()).set(ChatSession::getKnowledgeRouteJson,null));
    }
    private void validate(RetrievalScope scope,String user){
        for(String id:scope.collectionIds()){var row=collectionMapper.selectById(id);if(row==null||!user.equals(row.getUserId()))throw BizException.notFound("知识库不存在");}
        for(String id:scope.documentIds()){var row=documentMapper.selectById(id);if(row==null||!user.equals(row.getUserId()))throw BizException.notFound("文档不存在");if(!scope.collectionIds().isEmpty()&&!scope.collectionIds().contains(row.getCollectionId()))throw new BizException(400,"文档不在所选知识库中");}
    }
    private RetrievalScope scopeFrom(String raw){
        if(raw==null||raw.isBlank())return RetrievalScope.all();
        try{var node=json.readTree(raw);var collections=new ArrayList<String>();var documents=new ArrayList<String>();node.path("collectionIds").forEach(n->collections.add(n.asText()));node.path("documentIds").forEach(n->documents.add(n.asText()));return new RetrievalScope(collections,documents,false);}catch(Exception e){return RetrievalScope.all();}
    }
    public Turn prepare(ChatSession session,String user,String question,List<ChatMessage> history){
        long start=System.nanoTime();var turn=new Turn();turn.user=user;turn.mode=Objects.toString(session.getKnowledgeMode(),"AUTO");
        boolean researchMode="SELECTED".equals(turn.mode)&&session.getSystemPrompt()!=null
                &&session.getSystemPrompt().startsWith("[AIASSISTANT_RESEARCH_V1]");
        boolean ordinary="AUTO".equals(turn.mode)&&(CalendarQuestion.ordinary(question)||SMALLTALK.matcher(question.trim()).matches()||SIMPLE_TASK.matcher(question.trim()).find())&&!DOC_INTENT.matcher(question).find()&&!ONLY.matcher(question).find()&&!NAMED_FILE.matcher(question).find();
        var catalog="NONE".equals(turn.mode)||ordinary?new KnowledgeCatalogService.Catalog(Map.of(),List.of()):catalogs.get(user);turn.names=catalog.names();Choice choice;
        if("NONE".equals(turn.mode)||NO_KNOWLEDGE.matcher(question).find())choice=new Choice(RetrievalScope.none(),false,"disabled",null,question,null);
        else if("SELECTED".equals(turn.mode)){
            var scope=scopeFrom(session.getKnowledgeSelectionJson());validate(scope,user);choice=new Choice(scope,true,"selected",null,question,null);
        }else if("ALL".equals(turn.mode))choice=new Choice(RetrievalScope.all(),false,"all",null,question,null);
        else choice=choose(session,question,previousQuestion(history,question),catalog,turn.stats,user);
        // Natural language document restrictions also apply in ALL/SELECTED modes.
        if(!researchMode&&!"AUTO".equals(turn.mode)&&!"NONE".equals(turn.mode)&&explicitConstraint(question,catalog)){
            Choice explicit=choose(session,question,previousQuestion(history,question),catalog,turn.stats,user);
            if(explicit.strict()){
                if("SELECTED".equals(turn.mode)&&!explicit.scope().disabled()){
                    var allowed=choice.scope();boolean outside=explicit.scope().documentIds().stream().anyMatch(id->!allowed.documentIds().isEmpty()&&!allowed.documentIds().contains(id))
                            ||explicit.scope().collectionIds().stream().anyMatch(id->!allowed.collectionIds().isEmpty()&&!allowed.collectionIds().contains(id));
                    if(outside)explicit=new Choice(RetrievalScope.none(),true,"outside_selection","指定文档不在当前资料范围内，请调整范围。",question,null);
                }
                choice=explicit;
            }
        }
        turn.scope=choice.scope();turn.strict=choice.strict();turn.reason=choice.reason();turn.notice=choice.notice();turn.query=choice.query();
        if(!turn.scope.disabled()&&!catalog.ready().isEmpty()){
            turn.attempted=true;
            int k="ALL".equals(turn.mode)?8:complex(question)?6:4;
            if(researchMode&&!turn.scope.documentIds().isEmpty()&&turn.scope.documentIds().size()<=3){
                // A global top-k can be dominated by one document. Search each selected
                // document so comparison reports can see evidence and gaps per source.
                var selectedScope=turn.scope;
                var perDocument=new LinkedHashMap<String,KbChunk>();
                int perDocumentLimit=selectedScope.documentIds().size()==1?6:4;
                var selectedDocuments=catalog.documents().stream().filter(d->selectedScope.documentIds().contains(d.id())).toList();
                var filenames=selectedDocuments.stream().map(KnowledgeCatalogService.Document::filename).toList();
                try{
                    for(String documentId:selectedScope.documentIds()){
                        turn.scope=new RetrievalScope(List.of(),List.of(documentId),false);
                        String filename=selectedDocuments.stream().filter(d->d.id().equals(documentId)).map(KnowledgeCatalogService.Document::filename).findFirst().orElse("");
                        String focusedQuery=ResearchQuery.forDocument(turn.query,filename,filenames);
                        for(var chunk:retrieve(turn,focusedQuery,perDocumentLimit,null))perDocument.putIfAbsent(chunk.getId(),chunk);
                    }
                }finally{turn.scope=selectedScope;}
                turn.chunks=List.copyOf(perDocument.values());
                turn.memo.put(turn.query,turn.chunks);
            }else turn.chunks=retrieve(turn,turn.query,k,choice.probe());
            if(turn.chunks.isEmpty()&&!turn.strict&&!turn.scope.collectionIds().isEmpty()){
                turn.scope=RetrievalScope.all();turn.reason="expanded";turn.chunks=retrieve(turn,turn.query,k,null);
            }
            if(!turn.chunks.isEmpty()&&!turn.strict&&"AUTO".equals(turn.mode)){
                var ids=turn.chunks.stream().map(KbChunk::getCollectionId).distinct().toList();turn.scope=new RetrievalScope(ids,List.of(),false);
            }
        }
        turn.stats.evidenceChars=turn.chunks.stream().mapToInt(c->c.getContent().length()).sum();turn.stats.elapsedMs=(System.nanoTime()-start)/1_000_000;
        turn.label=label(turn,catalog);
        // Store only scope metadata. Previous questions come from session-owned history.
        var state=new LinkedHashMap<>(turn.view());
        String anchor=question;
        if("followup".equals(turn.reason)&&session.getKnowledgeRouteJson()!=null)try{anchor=json.readTree(session.getKnowledgeRouteJson()).path("anchor").asText(question);}catch(Exception ignored){}
        state.put("anchor",anchor.substring(0,Math.min(160,anchor.length())));
        if(!turn.chunks.isEmpty())state.put("documentIds",turn.chunks.stream().map(KbChunk::getDocumentId).distinct().toList());
        try{String raw=json.writeValueAsString(state);session.setKnowledgeRouteJson(raw);sessions.update(null,new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId,session.getId()).eq(ChatSession::getUserId,user).set(ChatSession::getKnowledgeRouteJson,raw));}catch(Exception e){throw new IllegalStateException("保存资料范围失败",e);}
        return turn;
    }
    public Choice choose(ChatSession session,String question,String previous,KnowledgeCatalogService.Catalog catalog,RetrievalStats stats,String user){
        var prior=scopeFrom(session.getKnowledgeRouteJson());
        if(previous.isBlank()||FOLLOWUP.matcher(previous).find())try{if(session.getKnowledgeRouteJson()!=null)previous=json.readTree(session.getKnowledgeRouteJson()).path("anchor").asText(previous);}catch(Exception ignored){}
        boolean priorStrict=false;try{priorStrict=session.getKnowledgeRouteJson()!=null&&json.readTree(session.getKnowledgeRouteJson()).path("strict").asBoolean();}catch(Exception ignored){}
        var matched=catalog.documents().stream().filter(d->!d.filename().isBlank()&&question.toLowerCase(Locale.ROOT).contains(d.filename().toLowerCase(Locale.ROOT))).toList();
        String remainingNames=question;
        for(var document:matched)remainingNames=remainingNames.replaceAll("(?i)"+Pattern.quote(document.filename()),"");
        if(NAMED_FILE.matcher(remainingNames).find())return new Choice(RetrievalScope.none(),true,"missing_document","未找到你指定的部分文档，请检查文件名或先导入。",question,null);
        if(matched.size()>1&&matched.stream().map(KnowledgeCatalogService.Document::filename).distinct().count()==matched.size()){
            if(matched.stream().anyMatch(d->!"READY".equals(d.status())))return new Choice(RetrievalScope.none(),true,"not_ready","指定文档尚未处理完成。",question,null);
            return new Choice(new RetrievalScope(matched.stream().map(KnowledgeCatalogService.Document::collectionId).toList(),matched.stream().map(KnowledgeCatalogService.Document::id).toList(),false),true,"document",null,question,null);
        }
        if(matched.size()>1){
            var qualified=matched.stream().filter(d->{String name=catalog.names().get(d.collectionId());return name!=null&&name.length()>=2&&question.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT));}).toList();
            if(qualified.size()==1)matched=qualified;
        }
        if(matched.size()>1){
            var inPrior=matched.stream().filter(d->prior.documentIds().contains(d.id())).toList();if(inPrior.size()==1)matched=inPrior;
        }
        if(matched.size()>1)return new Choice(RetrievalScope.none(),true,"ambiguous","找到多份同名文档，请注明知识库或版本。",question,null);
        if(matched.size()==1){var d=matched.get(0);if(!"READY".equals(d.status()))return new Choice(RetrievalScope.none(),true,"not_ready","指定文档尚未处理完成。",question,null);return new Choice(new RetrievalScope(List.of(d.collectionId()),List.of(d.id()),false),true,"document",null,question,null);}
        if(NAMED_FILE.matcher(question).find())return new Choice(RetrievalScope.none(),true,"missing_document","未找到你指定的文档，请检查文件名或先导入。",question,null);
        boolean shifted=SHIFT.matcher(question).find();
        boolean referring=THIS_DOC.matcher(question).find()&&DOC_INTENT.matcher(question).find();
        if((ONLY.matcher(question).find()&&DOC_INTENT.matcher(question).find())||referring){
            var candidates=catalog.ready().stream().filter(d->prior.documentIds().contains(d.id())).toList();
            if(candidates.isEmpty())candidates=catalog.ready().stream().filter(d->!question.toLowerCase(Locale.ROOT).contains("pdf")||d.filename().toLowerCase(Locale.ROOT).endsWith(".pdf")).toList();
            if(candidates.size()==1){var d=candidates.get(0);return new Choice(new RetrievalScope(List.of(d.collectionId()),List.of(d.id()),false),true,"document",null,question,null);}
            return new Choice(RetrievalScope.none(),true,"ambiguous","请注明要依据哪份文档，避免混用其他资料。",question,null);
        }
        if((CalendarQuestion.ordinary(question)||SMALLTALK.matcher(question.trim()).matches()||SIMPLE_TASK.matcher(question.trim()).find())&&!DOC_INTENT.matcher(question).find())return new Choice(RetrievalScope.none(),false,"ordinary",null,question,null);
        if(catalog.ready().isEmpty())return new Choice(RetrievalScope.none(),false,"empty",null,question,null);
        var namedCollections=catalog.names().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().trim().length()>=2&&question.toLowerCase(Locale.ROOT).contains(e.getValue().trim().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getKey).filter(id->catalog.ready().stream().anyMatch(d->d.collectionId().equals(id))).sorted().toList();
        if(!namedCollections.isEmpty())return new Choice(new RetrievalScope(namedCollections,List.of(),false),false,"collection_topic",null,question,null);
        var queryTerms=RoutingTerms.query(question);var ranked=rank(question,catalog);
        if(!shifted&&FOLLOWUP.matcher(question).find()&&!prior.collectionIds().isEmpty()&&(!strong(ranked)||ranked.get(0).id().equals(prior.collectionIds().get(0)))){
            var valid=prior.collectionIds().stream().filter(catalog.names()::containsKey).toList();
            if(!valid.isEmpty())return new Choice(new RetrievalScope(valid,priorStrict?prior.documentIds():List.of(),false),priorStrict,"followup",null,previous.substring(0,Math.min(160,previous.length()))+"\n追问："+question,null);
        }
        if(strong(ranked)){
            var chosen=ranked.stream().filter(s->s.score()>=ranked.get(0).score()*.72).limit(multiSource(question)?3:1).map(Ranked::id).toList();
            return new Choice(new RetrievalScope(chosen,List.of(),false),false,"topic",null,question,null);
        }
        var probe=search.lexical(question,user,RetrievalScope.all(),16,stats);
        if(!probe.isEmpty()){
            var scores=new HashMap<String,Double>();
            for(var hit:probe){var content=RoutingTerms.query(hit.chunk().getContent());double overlap=queryTerms.stream().filter(content::contains).mapToDouble(t->RoutingTerms.ascii(t)?3:1).sum();scores.merge(hit.chunk().getCollectionId(),overlap,Math::max);}
            var sorted=scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).toList();
            if(!sorted.isEmpty()&&sorted.get(0).getValue()>=2){double best=sorted.get(0).getValue();var ids=sorted.stream().filter(e->e.getValue()>=best*.72).limit(multiSource(question)?3:2).map(Map.Entry::getKey).toList();return new Choice(new RetrievalScope(ids,List.of(),false),false,"lexical",null,question,probe);}
            return new Choice(RetrievalScope.all(),false,"uncertain",null,question,probe);
        }
        if(DOC_INTENT.matcher(question).find()||!ranked.isEmpty()&&ranked.get(0).score()>0)return new Choice(RetrievalScope.all(),false,"semantic",null,question,null);
        return new Choice(RetrievalScope.all(),false,"semantic",null,question,null);
    }
    private record Ranked(String id,double score,int matched){}
    private List<Ranked> rank(String question,KnowledgeCatalogService.Catalog catalog){
        var ready=catalog.ready();var terms=RoutingTerms.query(question);var scores=new HashMap<String,Ranked>();var frequency=new HashMap<String,Integer>();
        for(var doc:ready)for(String term:terms)if(doc.terms().contains(term))frequency.merge(term,1,Integer::sum);
        for(var doc:ready){
            double score=0;int matched=0;
            for(String term:terms)if(doc.terms().contains(term)){matched++;double weight=RoutingTerms.ascii(term)?3:1;double idf=1+Math.log(1+(double)ready.size()/frequency.getOrDefault(term,1));score+=weight*idf*(doc.metadataTerms().contains(term)?1.5:1);}
            var value=new Ranked(doc.collectionId(),score,matched);var old=scores.get(doc.collectionId());if(old==null||score>old.score())scores.put(doc.collectionId(),value);
        }
        return scores.values().stream().filter(s->s.score()>0).sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::id)).toList();
    }
    private boolean explicitConstraint(String q,KnowledgeCatalogService.Catalog c){return NAMED_FILE.matcher(q).find()||ONLY.matcher(q).find()&&DOC_INTENT.matcher(q).find()||THIS_DOC.matcher(q).find()&&DOC_INTENT.matcher(q).find()||c.documents().stream().anyMatch(d->!d.filename().isBlank()&&q.toLowerCase(Locale.ROOT).contains(d.filename().toLowerCase(Locale.ROOT)));}
    private boolean strong(List<Ranked> ranked){return !ranked.isEmpty()&&ranked.get(0).score()>=4&&(ranked.get(0).matched()>=2||ranked.get(0).score()>=5)&&(ranked.size()==1||ranked.get(0).score()>=ranked.get(1).score()*1.25);}
    private static boolean multiSource(String q){return q.matches("(?s).*(对比|比较|各自|分别|两份|两本|跨知识库|不同|共同|联系|结合.*[和与、]).*");}
    private static boolean complex(String q){return multiSource(q)||q.matches("(?s).*(以及|有哪些|四个|五个|综述|总结|区别|和.*和).*")||q.length()>90;}
    private String previousQuestion(List<ChatMessage> history,String current){for(int i=history.size()-1;i>=0;i--){var m=history.get(i);if("user".equals(m.getRole())&&!current.equals(m.getContent()))return Objects.toString(m.getContent(),"");}return "";}
    private List<KbChunk> retrieve(Turn turn,String query,int k,List<HybridSearchService.LexicalHit> probe){
        String key=turn.user+":"+versions.current(turn.user)+":"+turn.scope+":"+k+":"+FolderPathService.digest(query.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var hit=results.get(key,()->{var chunks=List.copyOf(search.searchScoped(query,turn.user,turn.scope,k,turn.stats,probe));return new CachedRetrieval(chunks,turn.stats.degraded);},value->!value.degraded());
        turn.stats.degraded|=hit.value().degraded();
        if(hit.cached())turn.stats.retrievalCacheHits++;
        // Never hand shared cache entities to callers who may mutate them.
        var copies=hit.value().chunks().stream().map(chunk->{var copy=new KbChunk();org.springframework.beans.BeanUtils.copyProperties(chunk,copy);return copy;}).toList();
        turn.memo.put(query,copies);return copies;
    }
    public List<KbChunk> toolSearch(Turn turn,String query,int k){
        if(turn==null||turn.scope.disabled())return List.of();
        if(turn.memo.containsKey(query))return turn.memo.get(query).stream().limit(Math.min(k,20)).toList();
        if(turn.extraSearches++>=2)return List.of();
        return retrieve(turn,query,Math.max(1,Math.min(k,6)),null);
    }
    private String label(Turn turn,KnowledgeCatalogService.Catalog catalog){
        if("NONE".equals(turn.mode))return "不使用知识库";
        String prefix="AUTO".equals(turn.mode)?"自动":"ALL".equals(turn.mode)?"全部资料":"指定资料";
        if(turn.strict&&!turn.scope.documentIds().isEmpty())return prefix+" · "+catalog.documents().stream().filter(d->turn.scope.documentIds().contains(d.id())).map(KnowledgeCatalogService.Document::filename).findFirst().orElse("指定文档");
        if(turn.scope.disabled())return prefix+" · "+(turn.strict?"待确认文档":"普通聊天");
        if(turn.scope.collectionIds().isEmpty())return prefix+" · "+(turn.chunks.isEmpty()?"未找到依据":"相关资料");
        return prefix+" · "+String.join("、",turn.scope.collectionIds().stream().map(id->catalog.names().getOrDefault(id,"知识库")).toList());
    }
}
