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
@TableName("kb_document")
public class KbDocument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String collectionId;
    private String userId;
    private String filename;
    private String mimeType;
    private Long sizeBytes;
    private String storagePath;
    private String sourceKind;
    private String sourceRelativePath;
    private String sourceKey;
    private String sourceFingerprint;
    private String routingKeywords;
    private String status;
    private String errorMessage;
    private String parseReportJson;
    private Integer chunkCount;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String progressStage;
    private Integer processedPages;
    private Integer totalPages;
    private Integer vectorizedCount;
    private Long parseDurationMs;
    private Long chunkDurationMs;
    private Long vectorDurationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
