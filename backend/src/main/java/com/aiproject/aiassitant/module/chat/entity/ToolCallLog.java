package com.aiproject.aiassitant.module.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("tool_call_log")
public class ToolCallLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String sessionId;
    private String userId;
    private String toolName;
    private String arguments;
    private String result;
    private String status;       // success / failed / timeout
    private String errorMessage;
    private Long elapsedMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
