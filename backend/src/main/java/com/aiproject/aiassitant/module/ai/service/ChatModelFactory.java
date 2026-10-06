package com.aiproject.aiassitant.module.ai.service;

import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelFactory {

    private final AiModelConfigMapper modelConfigMapper;
    private final Map<String, StreamingChatLanguageModel> streamingModelCache = new ConcurrentHashMap<>();
    private final Map<String, ChatLanguageModel> chatModelCache = new ConcurrentHashMap<>();

    public StreamingChatLanguageModel getStreamingModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return getDefaultStreamingModel();
        }
        return streamingModelCache.computeIfAbsent(modelName, this::buildStreamingModel);
    }

    public StreamingChatLanguageModel getStreamingModel(String modelName, String effort) {
        String normalized=ThinkingEffort.normalize(effort);
        AiModelConfig config=modelName==null||modelName.isBlank()?getDefaultModelConfig():findModelConfig(modelName);
        if(config==null)config=getDefaultModelConfig();
        if(!ApiManager.supportsFlashVision(config)) {
            if(!normalized.equals("none"))throw new com.aiproject.aiassitant.common.BizException(400,"当前模型未支持可调思考强度，请选择 DeepSeek Flash");
            return getStreamingModel(modelName);
        }
        AiModelConfig selected=config;
        return streamingModelCache.computeIfAbsent(config.getId()+"|"+normalized,k->new DeepSeekFlashStreamingModel(selected.getBaseUrl(),resolveApiKey(selected),
                selected.getTemperature()==null?.3:selected.getTemperature().doubleValue(),selected.getMaxTokens()==null?4096:selected.getMaxTokens(),normalized));
    }

    public ChatLanguageModel getChatModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return getDefaultChatModel();
        }
        return chatModelCache.computeIfAbsent(modelName, this::buildChatModel);
    }

    /** Same selected model, with a bounded answer budget for independent short questions. */
    public StreamingChatLanguageModel getLightStreamingModel(String modelName) {
        AiModelConfig config=modelName==null||modelName.isBlank()?getDefaultModelConfig():findModelConfig(modelName);
        if(config==null)config=getDefaultModelConfig();
        if(!ApiManager.supportsFlashVision(config))return getStreamingModel(modelName,"none");
        AiModelConfig selected=config;
        return streamingModelCache.computeIfAbsent(config.getId()+"|light",k->new DeepSeekFlashStreamingModel(selected.getBaseUrl(),resolveApiKey(selected),
            selected.getTemperature()==null?.3:selected.getTemperature().doubleValue(),Math.min(512,selected.getMaxTokens()==null?4096:selected.getMaxTokens()),"none"));
    }

    /** Used only by an explicitly confirmed, single-message citation check. */
    public StreamingChatLanguageModel getVerificationStreamingModel(String modelName) {
        AiModelConfig config=modelName==null||modelName.isBlank()?getDefaultModelConfig():findModelConfig(modelName);
        if(config==null)config=getDefaultModelConfig();
        AiModelConfig selected=config;
        return streamingModelCache.computeIfAbsent(config.getId()+"|citation-verification",k->ApiManager.supportsFlashVision(selected)
            ?new DeepSeekFlashStreamingModel(selected.getBaseUrl(),resolveApiKey(selected),0,2048,"none")
            :OpenAiStreamingChatModel.builder().baseUrl(selected.getBaseUrl()).apiKey(resolveApiKey(selected)).modelName(requestModel(selected))
                .temperature(0.0).maxTokens(2048).timeout(java.time.Duration.ofSeconds(90)).build());
    }

    public boolean supportsNativeVision(String modelName) {
        AiModelConfig config = modelName == null || modelName.isBlank() ? getDefaultModelConfig() : findModelConfig(modelName);
        return ApiManager.supportsFlashVision(config == null ? getDefaultModelConfig() : config);
    }

    private String requestModel(AiModelConfig config) {
        return ApiManager.supportsFlashVision(config) ? "deepseek-flash" : config.getModelName();
    }

    public StreamingChatLanguageModel getDefaultStreamingModel() {
        AiModelConfig defaultModel = getDefaultModelConfig();
        return streamingModelCache.computeIfAbsent("__default__",
                k -> buildStreamingModel(defaultModel.getModelName()));
    }

    public ChatLanguageModel getDefaultChatModel() {
        AiModelConfig defaultModel = getDefaultModelConfig();
        return chatModelCache.computeIfAbsent("__default__",
                k -> buildChatModel(defaultModel.getModelName()));
    }

    private AiModelConfig getDefaultModelConfig() {
        LambdaQueryWrapper<AiModelConfig> q = new LambdaQueryWrapper<>();
        q.eq(AiModelConfig::getEnabled, true)
         .eq(AiModelConfig::getType, "chat")
         .eq(AiModelConfig::getIsDefault, true)
         .orderByAsc(AiModelConfig::getSortOrder).last("LIMIT 1");
        AiModelConfig model = modelConfigMapper.selectOne(q);
        if (model == null) {
            q = new LambdaQueryWrapper<>();
            q.eq(AiModelConfig::getEnabled, true)
             .eq(AiModelConfig::getType, "chat")
             .orderByAsc(AiModelConfig::getSortOrder).last("LIMIT 1");
            model = modelConfigMapper.selectOne(q);
        }
        if (model == null) {
            throw new RuntimeException("No chat model configured. Please add one in admin → API Config with type=chat.");
        }
        return model;
    }

    private StreamingChatLanguageModel buildStreamingModel(String modelName) {
        AiModelConfig config = findModelConfig(modelName);
        if (config == null) {
            log.warn("Model '{}' not found in DB, falling back to default", modelName);
            return getDefaultStreamingModel();
        }

        String apiKey = resolveApiKey(config);
        log.info("Building streaming model: name={}, provider={}, model={}", config.getName(), config.getProvider(), config.getModelName());

        if (ApiManager.supportsFlashVision(config)) return new DeepSeekFlashStreamingModel(config.getBaseUrl(),apiKey,
                config.getTemperature() != null ? config.getTemperature().doubleValue() : 0.3,
                config.getMaxTokens() != null ? config.getMaxTokens() : 4096);

        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(apiKey)
                .modelName(requestModel(config))
                .temperature(config.getTemperature() != null ? config.getTemperature().doubleValue() : 0.3)
                .maxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 4096)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    private ChatLanguageModel buildChatModel(String modelName) {
        AiModelConfig config = findModelConfig(modelName);
        if (config == null) {
            log.warn("Model '{}' not found in DB, falling back to default", modelName);
            return getDefaultChatModel();
        }

        String apiKey = resolveApiKey(config);

        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(apiKey)
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature().doubleValue() : 0.3)
                .maxTokens(config.getMaxTokens() != null ? config.getMaxTokens() : 4096)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    private AiModelConfig findModelConfig(String modelName) {
        LambdaQueryWrapper<AiModelConfig> q = new LambdaQueryWrapper<>();
        q.eq(AiModelConfig::getEnabled, true)
                .and(w -> w.eq(AiModelConfig::getName, modelName)
                        .or().eq(AiModelConfig::getModelName, modelName)
                .or().eq(AiModelConfig::getId, modelName))
                .orderByAsc(AiModelConfig::getSortOrder).last("LIMIT 1");
        return modelConfigMapper.selectOne(q);
    }

    private String resolveApiKey(AiModelConfig config) {
        String val = config.getApiKeyAlias();
        if (val == null || val.isBlank()) {
            throw new RuntimeException("模型 '" + config.getName() + "' 未配置 API Key，请在后台模型配置中填写");
        }
        // If stored encrypted in DB, decrypt first
        if (Boolean.TRUE.equals(config.getApiKeyEncrypted())) {
            try {
                val = com.aiproject.aiassitant.module.admin.config.ConfigLoader.decrypt(val);
            } catch (Exception e) {
                throw new IllegalStateException("模型 API Key 解密失败，请重新配置", e);
            }
        }
        if (val.startsWith("sk-") || val.startsWith("AKID") || val.length() > 20) return val;
        // Environment variable reference
        String env = System.getenv(val);
        if (env != null) return env;
        throw new RuntimeException(
            "模型 '" + config.getName() + "' 的 API Key 引用了环境变量 '" + val + "'，但该变量未设置。请设置环境变量或在后台直接填入密钥（以 sk- 开头）");
    }

    public List<AiModelConfig> listModels(String type) {
        LambdaQueryWrapper<AiModelConfig> q = new LambdaQueryWrapper<>();
        q.eq(AiModelConfig::getEnabled, true);
        if (type != null && !type.isBlank() && !"all".equals(type)) {
            q.eq(AiModelConfig::getType, type);
        }
        q.orderByAsc(AiModelConfig::getSortOrder);
        return modelConfigMapper.selectList(q);
    }

    public List<AiModelConfig> listEnabledModels() {
        return listModels("all");
    }

    public void clearCache() {
        streamingModelCache.clear();
        chatModelCache.clear();
    }
}
