package com.aiproject.aiassitant.module.knowledge.mapper;

import com.aiproject.aiassitant.module.knowledge.entity.KbChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {
    @Select("<script>SELECT id FROM kb_document WHERE status='READY' AND user_id=#{userId} " +
            "<if test='collections != null and !collections.isEmpty()'>AND collection_id IN <foreach collection='collections' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "<if test='documents != null and !documents.isEmpty()'>AND id IN <foreach collection='documents' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "</script>")
    List<String> readyDocumentIds(@Param("userId") String userId,
                                  @Param("collections") List<String> collections,
                                  @Param("documents") List<String> documents);

    @Insert("""
            <script>
            INSERT INTO kb_chunk
                (id, document_id, collection_id, user_id, ordinal, content, token_count,
                 created_at, source_start, source_end, source_page, source_title)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.id}, #{row.documentId}, #{row.collectionId}, #{row.userId},
                 #{row.ordinal}, #{row.content}, #{row.tokenCount}, #{row.createdAt},
                 #{row.sourceStart,jdbcType=INTEGER}, #{row.sourceEnd,jdbcType=INTEGER},
                 #{row.sourcePage,jdbcType=INTEGER}, #{row.sourceTitle,jdbcType=VARCHAR})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<KbChunk> rows);

    @Select("<script>SELECT id AS chunk_id, MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) AS score " +
            "FROM kb_chunk WHERE user_id=#{userId} AND document_id IN (SELECT id FROM kb_document WHERE status='READY') " +
            "<if test='collections != null and !collections.isEmpty()'>AND collection_id IN <foreach collection='collections' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "<if test='documents != null and !documents.isEmpty()'>AND document_id IN <foreach collection='documents' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "AND MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) &gt; 0 ORDER BY score DESC LIMIT #{limit}</script>")
    List<Map<String,Object>> bm25SearchScoped(@Param("query")String query,@Param("userId")String userId,
            @Param("collections")List<String> collections,@Param("documents")List<String> documents,@Param("limit")int limit);

    @Select("<script>SELECT id AS chunk_id, MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) AS score " +
            "FROM kb_chunk WHERE user_id=#{userId} AND document_id IN (SELECT id FROM kb_document WHERE status='READY') " +
            "<if test='collections != null and !collections.isEmpty()'>AND collection_id IN <foreach collection='collections' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "<if test='documents != null and !documents.isEmpty()'>AND document_id IN <foreach collection='documents' item='id' open='(' separator=',' close=')'>#{id}</foreach> </if>" +
            "AND REGEXP_LIKE(content,#{captionPattern},'im') ORDER BY score DESC,ordinal ASC LIMIT #{limit}</script>")
    List<Map<String,Object>> tableSearchScoped(@Param("captionPattern")String captionPattern,
            @Param("query")String query,@Param("userId")String userId,
            @Param("collections")List<String> collections,@Param("documents")List<String> documents,@Param("limit")int limit);


    @Select("SELECT id AS chunk_id, MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) AS score " +
            "FROM kb_chunk WHERE user_id = #{userId} " +
            "AND document_id IN (SELECT id FROM kb_document WHERE status = 'READY') " +
            "AND MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) > 0 " +
            "ORDER BY score DESC LIMIT #{limit}")
    List<Map<String, Object>> bm25Search(@Param("query") String query,
                                         @Param("userId") String userId,
                                         @Param("limit") int limit);

    @Select("SELECT id AS chunk_id, MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) AS score " +
            "FROM kb_chunk WHERE user_id = #{userId} AND collection_id = #{collectionId} " +
            "AND document_id IN (SELECT id FROM kb_document WHERE status = 'READY') " +
            "AND MATCH(content) AGAINST(#{query} IN BOOLEAN MODE) > 0 " +
            "ORDER BY score DESC LIMIT #{limit}")
    List<Map<String, Object>> bm25SearchInCollection(@Param("query") String query,
                                                      @Param("userId") String userId,
                                                      @Param("collectionId") String collectionId,
                                                      @Param("limit") int limit);
}
