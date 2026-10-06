package com.aiproject.aiassitant.ai.config;

import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding model — DB-driven.
 * Reads from ai_model_config table, selects the row where embedding_dimension > 0.
 */
@Configuration
public class EmbeddingModelConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);
    private String fingerprint;
    public String fingerprint() { return fingerprint; }

    @Bean
    public EmbeddingModel embeddingModel(AiModelConfigMapper mapper) {
        LambdaQueryWrapper<AiModelConfig> q = new LambdaQueryWrapper<>();
        q.eq(AiModelConfig::getEnabled, true)
         .eq(AiModelConfig::getType, "embedding")
         .orderByAsc(AiModelConfig::getSortOrder)
         .last("LIMIT 1");
        AiModelConfig config = mapper.selectOne(q);

        if (config == null) {
            throw new RuntimeException(
                "No embedding model configured. Add one in admin → API Config with type=embedding.");
        }

        // Non-secret startup identity; checkpoints must follow the model actually instantiated, not later DB edits.
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    (config.getId()+"|"+config.getBaseUrl()+"|"+config.getModelName()+"|"+config.getEmbeddingDimension()+"|source-v"
                            +com.aiproject.aiassitant.module.documentqa.service.DocumentUploadJobs.PARSER_VERSION+"-chunk-v1")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        log.info("Embedding model from DB: {} (provider={}, dimension={})",
                config.getModelName(), config.getProvider(), config.getEmbeddingDimension());

        // Snapshot the model metadata for checkpoint identity. Missing credentials must not
        // prevent administrators from entering the UI to configure them on a fresh install.
        var delegate = new java.util.concurrent.atomic.AtomicReference<EmbeddingModel>();
        return DeferredModel.create(EmbeddingModel.class, () -> {
            if (delegate.get() == null) {
                synchronized (delegate) {
                    if (delegate.get() == null) delegate.set(OpenAiEmbeddingModel.builder()
                            .baseUrl(config.getBaseUrl()).apiKey(resolveApiKey(config))
                            .modelName(config.getModelName()).timeout(java.time.Duration.ofSeconds(60)).build());
                }
            }
            return delegate.get();
        });
    }

    private String resolveApiKey(AiModelConfig config) {
        String val = config.getApiKeyAlias();
        if (val == null || val.isBlank()) {
            throw new RuntimeException("嵌入模型 '" + config.getName() + "' 未配置 API Key，请在后台模型配置中填写");
        }
        if (Boolean.TRUE.equals(config.getApiKeyEncrypted())) {
            return com.aiproject.aiassitant.module.admin.config.ConfigLoader.decrypt(val);
        }
        if (val.startsWith("sk-")) return val;
        String env = System.getenv(val);
        if (env != null) return env;
        throw new RuntimeException(
            "嵌入模型 '" + config.getName() + "' 的 API Key 引用了环境变量 '" + val + "'，但该变量未设置。请设置环境变量或在后台直接填入密钥（以 sk- 开头）");
    }
}
