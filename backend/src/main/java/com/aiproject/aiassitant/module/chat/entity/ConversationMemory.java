package com.aiproject.aiassitant.module.chat.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("conversation_memory")
public class ConversationMemory {
    @TableId private String sessionId;
    private String ownerId;
    private String stateJson;
    private Long version;
}
