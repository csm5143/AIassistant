package com.aiproject.aiassitant.module.chat.mapper;

import com.aiproject.aiassitant.module.chat.entity.ConversationMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ConversationMemoryMapper extends BaseMapper<ConversationMemory> {
    @Insert("INSERT IGNORE INTO conversation_memory(session_id,owner_id,state_json,version) VALUES(#{id},#{owner},'{}',0)")
    void initialize(@Param("id") String id,@Param("owner") String owner);
    @Select("SELECT * FROM conversation_memory WHERE session_id=#{id} AND owner_id=#{owner} FOR UPDATE")
    ConversationMemory lock(@Param("id") String id,@Param("owner") String owner);
}
