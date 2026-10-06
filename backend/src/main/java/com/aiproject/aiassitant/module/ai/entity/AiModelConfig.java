package com.aiproject.aiassitant.module.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_model_config")
public class AiModelConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;
    private String provider;
    private String type;         // chat, embedding, ocr, vision, search, rerank
    private String baseUrl;
    private String apiKeyAlias;
    private String modelName;
    private Integer embeddingDimension;
    private BigDecimal temperature;
    private Integer maxTokens;
    private BigDecimal topP;
    @TableField("price_per_1k_input")
    private BigDecimal pricePer1kInput;
    @TableField("price_per_1k_output")
    private BigDecimal pricePer1kOutput;
    private Boolean enabled;
    private Boolean isDefault;
    private Boolean apiKeyEncrypted;
    private Integer sortOrder;
    @TableField(exist = false)
    private Boolean thinkingSupported;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isEmbeddingModel() {
        return embeddingDimension != null && embeddingDimension > 0;
    }

    public boolean getApiKeyConfigured() {
        return apiKeyAlias != null && !apiKeyAlias.isBlank();
    }
}
