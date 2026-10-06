package com.aiproject.aiassitant.module.knowledge.service;
import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.aiproject.aiassitant.module.knowledge.mapper.KbChunkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {
    private final EmbeddingModel embeddingModel;
    private final KbChunkMapper chunkMapper;
    private final org.springframework.jdbc.core.JdbcTemplate pgJdbcTemplate;
    private final ObjectMapper objectMapper=new ObjectMapper();
    private final HttpClient httpClient=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final BoundedTtlCache<String,List<Float>> embeddings=new BoundedTtlCache<>(512,Duration.ofMinutes(10));
    @Value("${ai.embedding.base-url:https://api.siliconflow.cn/v1}") private String embeddingBaseUrl;
    @Value("${ai.embedding.api-key:sk-placeholder}") private String embeddingApiKey;
    @Value("${ai.rag.hybrid.vector-top-k:10}") private int vectorTopK;
    @Value("${ai.rag.hybrid.bm25-top-k:10}") private int bm25TopK;
    @Value("${ai.rag.hybrid.rerank-model:BAAI/bge-reranker-v2-m3}") private String rerankModel;
    @Value("${ai.rag.hybrid.min-vector-score:0.3}") private double minVectorScore;
    @Value("${ai.rag.hybrid.min-rerank-score:0.15}") private double minRerankScore;
    public record LexicalHit(KbChunk chunk,double score){}
    private record Scored(String id,double score){}
    public List<KbChunk> search(String query,String user,String collection,int k){return searchLegacy(query,user,RetrievalScope.collection(collection),k,new RetrievalStats());}
    public List<KbChunk> searchAll(String query,String user,int k){return search(query,user,null,k);}
    public List<KbChunk> searchLegacy(String query,String user,RetrievalScope scope,int k,RetrievalStats stats){return measured(query,user,scope,k,stats,false,null);}
    public List<KbChunk> searchScoped(String query,String user,RetrievalScope scope,int k,RetrievalStats stats,List<LexicalHit> probe){return measured(query,user,scope,k,stats,true,probe);}
    private List<KbChunk> measured(String query,String user,RetrievalScope scope,int k,RetrievalStats stats,boolean cached,List<LexicalHit> probe){
        long start=System.nanoTime();
        try{
            if(query==null||query.isBlank()||scope.disabled())return List.of();
            if(user==null||user.isBlank()||k<1||k>20)throw new IllegalArgumentException("Invalid search parameters");
            return retrieve(normalizeQuery(query),user,scope,k,stats,cached,probe);
        }finally{stats.elapsedMs+=(System.nanoTime()-start)/1_000_000;}
    }
    private List<KbChunk> retrieve(String query,String user,RetrievalScope scope,int k,RetrievalStats stats,boolean cached,List<LexicalHit> probe){
        var parallel=splitParallelQuestion(query);
        if(!parallel.isEmpty()){
            List<List<KbChunk>> branches=new ArrayList<>();
            for(String part:parallel){
                var branch=new LinkedHashMap<String,KbChunk>();
                lexical(part,user,scope,2,stats).forEach(c->branch.putIfAbsent(c.chunk().getId(),c.chunk()));
                retrieve(part,user,scope,Math.min(k,3),stats,cached,null).forEach(c->branch.putIfAbsent(c.getId(),c));
                branches.add(new ArrayList<>(branch.values()));
            }
            var merged=new LinkedHashMap<String,KbChunk>();
            for(int rank=0;rank<3&&merged.size()<k;rank++)for(var branch:branches)if(rank<branch.size())merged.putIfAbsent(branch.get(rank).getId(),branch.get(rank));
            return merged.values().stream().limit(k).toList();
        }
        var vector=vector(query,user,scope,vectorTopK,stats,cached);
        List<Scored> lexical;
        if(probe!=null)lexical=probe.stream().filter(h->scope.contains(h.chunk())).limit(bm25TopK).map(h->new Scored(h.chunk().getId(),h.score())).toList();
        else lexical=lexicalScores(query,user,scope,bm25TopK,stats,cached);
        var fused=fuse(vector,lexical);
        List<KbChunk> anchoredTables=List.of();
        // A named table is a useful local structural anchor. Repeated model names
        // in other table headers must not crowd its rows out of candidate recall.
        // This adds bounded SQL recall to the existing single rerank request.
        String tablePattern=tableReferencePattern(query);
        if(tablePattern!=null) {
            var candidates=new LinkedHashMap<String,Scored>();
            var tableHits=tableScores(query,tablePattern,user,scope,stats);
            anchoredTables=load(tableHits,8,user,scope);
            tableHits.forEach(hit->candidates.put(hit.id(),hit));
            fused.forEach(hit->candidates.putIfAbsent(hit.id(),hit));
            fused=new ArrayList<>(candidates.values());
        }
        if(fused.isEmpty())return List.of();
        var ranked=load(rerank(query,fused,Math.min(k*3,fused.size()),user,scope,stats),k,user,scope);
        return prioritizeTableRows(query,anchoredTables,ranked,k);
    }
    /** Explicit table+row names are precise evidence anchors, even if semantic reranking misses them. */
    static List<KbChunk> prioritizeTableRows(String query,List<KbChunk> tables,List<KbChunk> ranked,int k) {
        var preferred=new LinkedHashMap<String,KbChunk>();
        for(var chunk:tables) {
            var lines=chunk.getContent().lines().filter(line->line.strip().startsWith("|")).toList();
            for(int i=2;i<lines.size();i++) {
                String[] cells=lines.get(i).split("\\|",-1);
                if(cells.length<3)continue;
                String label=cells[1].strip();
                if(label.length()<2||label.length()>80||label.codePoints().noneMatch(Character::isLetter))continue;
                String exact="(?i)(?<![A-Za-z0-9_.+\\-])"+java.util.regex.Pattern.quote(label)+"(?![A-Za-z0-9_.+\\-])";
                if(java.util.regex.Pattern.compile(exact).matcher(query).find()) {preferred.putIfAbsent(chunk.getId(),chunk);break;}
            }
        }
        ranked.forEach(chunk->preferred.putIfAbsent(chunk.getId(),chunk));
        return preferred.values().stream().limit(k).toList();
    }
    private List<Scored> tableScores(String query,String pattern,String user,RetrievalScope scope,RetrievalStats stats) {
        stats.lexicalQueries++;
        try {
            var terms=RoutingTerms.query(query);
            var rows=chunkMapper.tableSearchScoped(pattern,String.join(" ",terms),user,scope.collectionIds(),scope.documentIds(),8);
            if(rows==null)return List.of();
            return rows.stream().map(row->new Scored((String)row.get("chunk_id"),row.get("score") instanceof Number n?n.doubleValue():0)).toList();
        }catch(Exception e){stats.degraded=true;log.warn("Table recall unavailable: {}",e.getMessage());return List.of();}
    }

    static String tableReferencePattern(String query) {
        var matcher=java.util.regex.Pattern.compile("(?i)(?:\\btable\\s*|表\\s*)([A-Z]?[1-9][0-9]*(?:\\.[0-9]+)?)(?![0-9A-Za-z]|\\.[0-9])").matcher(query);
        var references=new LinkedHashSet<String>();
        while(matcher.find())references.add(matcher.group(1).toUpperCase(Locale.ROOT));
        if(references.size()!=1)return null; // Preserve comparative queries across tables.
        String number=references.iterator().next().replace(".","\\.");
        return "^(?:Table[[:space:]]*|表[[:space:]]*)"+number+"([^[:alnum:].]|$)";
    }
    public List<LexicalHit> lexical(String query,String user,RetrievalScope scope,int k,RetrievalStats stats){
        if(scope.disabled()||query==null||query.isBlank())return List.of();
        var scores=lexicalScores(normalizeQuery(query),user,scope,k,stats,true);
        var byId=new HashMap<String,Double>();scores.forEach(s->byId.put(s.id(),s.score()));
        return load(scores,k,user,scope).stream().map(c->new LexicalHit(c,byId.getOrDefault(c.getId(),0d))).toList();
    }
    private List<Scored> lexicalScores(String query,String user,RetrievalScope scope,int k,RetrievalStats stats,boolean modern){
        Collection<String> terms;
        if(modern)terms=RoutingTerms.query(query);
        else{
            var original=new ArrayList<String>();String clean=query.replaceAll("[^\\p{L}\\p{N}\\s]"," ").trim();
            for(String word:clean.split("\\s+"))if(word.matches("[\\p{IsHan}]{3,}")){for(int i=0;i<Math.min(word.length()-1,32);i++)original.add(word.substring(i,i+2));}else if(!word.isBlank())original.add(word);
            terms=original;
        }
        if(terms.isEmpty())return List.of();String boolQuery=String.join(" ",terms);stats.lexicalQueries++;
        try{
            var rows=chunkMapper.bm25SearchScoped(boolQuery,user,scope.collectionIds(),scope.documentIds(),k);
            if(rows==null)return List.of();
            return rows.stream().map(row->new Scored((String)row.get("chunk_id"),row.get("score") instanceof Number n?n.doubleValue():0)).toList();
        }catch(Exception e){stats.degraded=true;log.warn("Lexical recall unavailable: {}",e.getMessage());return List.of();}
    }
    private List<Scored> vector(String query,String user,RetrievalScope scope,int k,RetrievalStats stats,boolean cached){
        try{
            // Partial vectors are visible in PostgreSQL before their document becomes READY.
            // Restrict recall up front so they cannot crowd completed documents out of topK.
            List<String> readyDocuments=chunkMapper.readyDocumentIds(user,scope.collectionIds(),scope.documentIds());
            if(readyDocuments==null||readyDocuments.isEmpty())return List.of();
            java.util.function.Supplier<List<Float>> supplier=()->{
                stats.embeddingRequests++;var response=embeddingModel.embed(query);
                if(response.tokenUsage()!=null&&response.tokenUsage().totalTokenCount()!=null)stats.embeddingTokens+=response.tokenUsage().totalTokenCount();
                return List.copyOf(response.content().vectorAsList());
            };
            List<Float> embedding;
            if(cached){var hit=embeddings.get(user+":"+FolderPathService.digest(query.getBytes(java.nio.charset.StandardCharsets.UTF_8)),supplier);embedding=hit.value();if(hit.cached())stats.embeddingCacheHits++;}
            else embedding=supplier.get();
            String vector="["+embedding.stream().map(v->String.format(Locale.ROOT,"%.8f",v)).collect(Collectors.joining(","))+"]";
            var sql=new StringBuilder("SELECT chunk_id,1.0-(embedding <=> ?::vector) AS score FROM kb_vectors WHERE user_id=?");
            List<String> params=new ArrayList<>();params.add(user);append(sql,"collection_id",scope.collectionIds(),params);append(sql,"document_id",scope.documentIds(),params);
            sql.append(" AND document_id = ANY (?::varchar[])");
            sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
            try(Connection conn=pgJdbcTemplate.getDataSource().getConnection();PreparedStatement ps=conn.prepareStatement(sql.toString())){
                int index=1;ps.setString(index++,vector);for(String param:params)ps.setString(index++,param);
                ps.setArray(index++,conn.createArrayOf("varchar",readyDocuments.toArray(String[]::new)));
                ps.setString(index++,vector);ps.setInt(index,k);
                var results=new ArrayList<Scored>();try(ResultSet rs=ps.executeQuery()){while(rs.next()){double score=rs.getDouble("score");if(score>=minVectorScore)results.add(new Scored(rs.getString("chunk_id"),score));}}return results;
            }
        }catch(Exception e){stats.degraded=true;log.warn("Vector recall unavailable: {}",e.getMessage());return List.of();}
    }
    private static void append(StringBuilder sql,String column,List<String> values,List<String> params){if(values.isEmpty())return;sql.append(" AND ").append(column).append(" IN (").append(String.join(",",Collections.nCopies(values.size(),"?"))).append(")");params.addAll(values);}
    private List<Scored> fuse(List<Scored> a,List<Scored> b){
        var scores=new HashMap<String,Double>();for(var list:List.of(a,b))for(int i=0;i<list.size();i++)scores.merge(list.get(i).id(),1d/(61+i),Double::sum);
        return scores.entrySet().stream().map(e->new Scored(e.getKey(),e.getValue())).sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::id)).toList();
    }
    private List<Scored> rerank(String query,List<Scored> candidates,int topN,String user,RetrievalScope scope,RetrievalStats stats){
        var loaded=load(candidates,candidates.size(),user,scope);if(loaded.isEmpty())return List.of();
        var texts=loaded.stream().map(c->SourceDocumentParser.retrievalText(c.getContent(),c.getSourcePage(),c.getSourceTitle())).toList();
        try{
            var body=new LinkedHashMap<String,Object>();body.put("model",rerankModel);body.put("query",query);body.put("documents",texts);body.put("top_n",topN);body.put("max_chunks_per_doc",1);
            var request=HttpRequest.newBuilder().uri(URI.create(embeddingBaseUrl.replaceFirst("/v1$","")+"/v1/rerank")).timeout(Duration.ofSeconds(20))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+embeddingApiKey).POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            stats.rerankRequests++;stats.rerankDocuments+=texts.size();stats.rerankInputChars+=texts.stream().mapToLong(String::length).sum();
            var response=httpClient.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200){stats.degraded=true;log.warn("Rerank returned {}",response.statusCode());return candidates.stream().limit(topN).toList();}
            var tree=objectMapper.readTree(response.body());if(!tree.path("results").isArray()){stats.degraded=true;return candidates.stream().limit(topN).toList();}
            var result=new ArrayList<Scored>();
            for(var row:tree.path("results")){int i=row.path("index").asInt(-1);double score=row.path("relevance_score").asDouble();if(i>=0&&i<loaded.size()&&score>=minRerankScore)result.add(new Scored(loaded.get(i).getId(),score));}
            return result;
        }catch(Exception e){stats.degraded=true;log.warn("Rerank unavailable: {}",e.getMessage());return candidates.stream().limit(topN).toList();}
    }
    private List<KbChunk> load(List<Scored> scores,int k,String user,RetrievalScope scope){
        if(scores.isEmpty()||scope.disabled())return List.of();
        var ids=scores.stream().map(Scored::id).distinct().limit(k).toList();
        var q=new LambdaQueryWrapper<KbChunk>().in(KbChunk::getId,ids).eq(KbChunk::getUserId,user).inSql(KbChunk::getDocumentId,"SELECT id FROM kb_document WHERE status='READY'");
        if(!scope.collectionIds().isEmpty())q.in(KbChunk::getCollectionId,scope.collectionIds());if(!scope.documentIds().isEmpty())q.in(KbChunk::getDocumentId,scope.documentIds());
        var rows=chunkMapper.selectList(q);if(rows==null)return List.of();
        var positions=new HashMap<String,Integer>();for(int i=0;i<ids.size();i++)positions.putIfAbsent(ids.get(i),i);
        // Defense in depth: validate owner and scope even when mapper behavior changes.
        return rows.stream().filter(c->user.equals(c.getUserId())&&scope.contains(c)).sorted(Comparator.comparingInt(c->positions.getOrDefault(c.getId(),Integer.MAX_VALUE))).limit(k).toList();
    }
    static String normalizeQuery(String query) {
        String cleaned = query.replaceFirst("^(请)?(根据|结合)(我)?(上传的|提供的)?(文档|资料|知识库)[，,：:\\s]*", "").trim();
        return cleaned.isBlank() ? query : cleaned;
    }

    static List<String> splitParallelQuestion(String query) {
        int separately = query.indexOf("分别");
        if (separately < 0) return List.of();
        int join = query.lastIndexOf("以及", separately);
        int joinLength = 2;
        if (join < 0) { join = query.lastIndexOf("和", separately); joinLength = 1; }
        if (join < 4) return List.of();
        String left = (query.substring(0, join) + query.substring(separately + 2)).trim();
        String right = (query.substring(join + joinLength, separately) + query.substring(separately + 2)).trim();
        if (left.length() < 4 || right.length() < 4) return List.of();
        return List.of(left, right);
    }

}
