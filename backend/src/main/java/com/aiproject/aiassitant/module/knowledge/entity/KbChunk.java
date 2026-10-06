package com.aiproject.aiassitant.module.knowledge.entity;

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
@TableName("kb_chunk")
public class KbChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String documentId;
    private String collectionId;
    private String userId;
    private Integer ordinal;
    private String content;
    private Integer tokenCount;
    private String vectorId;
    private Integer sourceStart;
    private Integer sourceEnd;
    private Integer sourcePage;
    private String sourceTitle;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
