package com.aiproject.aiassitant.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("kb_document_source")
public class KbDocumentSource {
    @TableId(type = IdType.INPUT)
    private String documentId;
    private String content;
    private String blocksJson;
}
