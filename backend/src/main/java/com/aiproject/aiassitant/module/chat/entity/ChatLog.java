package com.aiproject.aiassitant.module.chat.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("chat_log")
public class ChatLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;
    private String sessionId;
    private String question;
    private String answer;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private Integer toolHit;
    private Integer knowledgeHit;
    private Integer guardHit;
    private String errorMessage;
    private Long cacheHitTokens;
    private Long cacheMissTokens;
    private Boolean cacheUsageComplete;
    private String requestKind;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
