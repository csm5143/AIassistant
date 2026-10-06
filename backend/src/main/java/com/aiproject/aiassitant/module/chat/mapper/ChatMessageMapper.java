package com.aiproject.aiassitant.module.chat.mapper;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @Select("SELECT * FROM chat_message WHERE session_id=#{id} ORDER BY created_at,id LIMIT #{limit}")
    List<ChatMessage> memoryInitial(@Param("id") String id,@Param("limit") int limit);
    @Select("SELECT * FROM chat_message WHERE session_id=#{id} AND memory_seq>#{cursor} ORDER BY memory_seq LIMIT #{limit}")
    List<ChatMessage> memoryDelta(@Param("id") String id,@Param("cursor") long cursor,@Param("limit") int limit);
}
