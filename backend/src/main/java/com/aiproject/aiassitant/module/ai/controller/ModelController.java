package com.aiproject.aiassitant.module.ai.controller;

import com.aiproject.aiassitant.common.R;
import com.aiproject.aiassitant.module.ai.entity.AiModelConfig;
import com.aiproject.aiassitant.module.ai.mapper.AiModelConfigMapper;
import com.aiproject.aiassitant.module.ai.service.ChatModelFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Tag(name = "Models", description = "AI model configuration endpoints")
@RestController
@RequestMapping("/models")
@RequiredArgsConstructor
public class ModelController {

    private final ChatModelFactory chatModelFactory;
    private final AiModelConfigMapper modelConfigMapper;

    // ── List / Get ──

    @Operation(summary = "List all configurations for administrators, including disabled ones")
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<AiModelConfig>> listAdminModels() {
        List<AiModelConfig> models = modelConfigMapper.selectList(
                new LambdaQueryWrapper<AiModelConfig>().orderByAsc(AiModelConfig::getSortOrder));
        models.forEach(ModelController::maskApiKey);
        return R.ok(models);
    }

    @Operation(summary = "List models. type: chat, embedding, or all")
    @GetMapping
    public R<List<AiModelConfig>> listModels(@RequestParam(defaultValue = "all") String type) {
        List<AiModelConfig> models = chatModelFactory.listModels(type);
        models.forEach(ModelController::maskApiKey);
        return R.ok(models);
    }

    @Operation(summary = "Get model configuration by ID")
    @GetMapping("/{id}")
    public R<AiModelConfig> getModel(@PathVariable String id) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model != null) maskApiKey(model);
        return R.ok(model);
    }

    // ── Create / Update / Delete ──

    @Operation(summary = "Create a new model configuration")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<AiModelConfig> createModel(@RequestBody AiModelConfig model) {
        if (model.getId() == null) {
            model.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (model.getEnabled() == null) model.setEnabled(true);
        if (model.getIsDefault() == null) model.setIsDefault(false);
        if (model.getSortOrder() == null) model.setSortOrder(99);
        encryptApiKeyIfNew(model);
        modelConfigMapper.insert(model);
        chatModelFactory.clearCache();
        maskApiKey(model);
        return R.ok(model);
    }

    @Operation(summary = "Update a model configuration")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> updateModel(@PathVariable String id, @RequestBody AiModelConfig req) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model == null) return R.fail("Model not found");
        if (req.getName() != null) model.setName(req.getName());
        if (req.getProvider() != null) model.setProvider(req.getProvider());
        if (req.getBaseUrl() != null) model.setBaseUrl(req.getBaseUrl());

        // Only update API key if a real key is provided (not masked placeholder)
        if (req.getApiKeyAlias() != null && !req.getApiKeyAlias().isBlank()
                && !req.getApiKeyAlias().contains("****")) {
            model.setApiKeyAlias(req.getApiKeyAlias());
            model.setApiKeyEncrypted(false);
            encryptApiKeyIfNew(model);
        }

        if (req.getModelName() != null) model.setModelName(req.getModelName());
        if (req.getTemperature() != null) model.setTemperature(req.getTemperature());
        if (req.getMaxTokens() != null) model.setMaxTokens(req.getMaxTokens());
        if (req.getEmbeddingDimension() != null) model.setEmbeddingDimension(req.getEmbeddingDimension());
        if (req.getTopP() != null) model.setTopP(req.getTopP());
        if (req.getPricePer1kInput() != null) model.setPricePer1kInput(req.getPricePer1kInput());
        if (req.getPricePer1kOutput() != null) model.setPricePer1kOutput(req.getPricePer1kOutput());
        if (req.getEnabled() != null) model.setEnabled(req.getEnabled());
        if (req.getIsDefault() != null) {
            if (req.getIsDefault()) {
                modelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                        .eq(AiModelConfig::getType, model.getType())
                        .set(AiModelConfig::getIsDefault, false));
            }
            model.setIsDefault(req.getIsDefault());
        }
        if (req.getSortOrder() != null) model.setSortOrder(req.getSortOrder());
        modelConfigMapper.updateById(model);
        chatModelFactory.clearCache();
        return R.ok();
    }

    @Operation(summary = "Delete a model configuration")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> deleteModel(@PathVariable String id) {
        modelConfigMapper.deleteById(id);
        chatModelFactory.clearCache();
        return R.ok();
    }

    @Operation(summary = "Clear model cache (reload from DB)")
    @PostMapping("/cache/clear")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> clearCache() {
        chatModelFactory.clearCache();
        return R.ok();
    }

    @Operation(summary = "Set model as default")
    @PostMapping("/{id}/set-default")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> setDefault(@PathVariable String id) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model == null) return R.fail("Model not found");
        modelConfigMapper.update(null, new LambdaUpdateWrapper<AiModelConfig>()
                .eq(AiModelConfig::getType, model.getType())
                .set(AiModelConfig::getIsDefault, false));
        model.setIsDefault(true);
        modelConfigMapper.updateById(model);
        chatModelFactory.clearCache();
        return R.ok();
    }

    // ── Test Connection ──

    @Operation(summary = "Test model API connection")
    @PostMapping("/{id}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> testConnection(@PathVariable String id) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model == null) return R.fail("Model not found");

        String type = model.getType() != null ? model.getType() : "chat";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model.getModelName());
        result.put("provider", model.getProvider());
        result.put("baseUrl", model.getBaseUrl());
        result.put("type", type);

        try {
            String apiKey = resolveApiKey(model);
            String base = model.getBaseUrl().replaceAll("/$", "");

            // Type-aware test path
            String testPath = switch (type) {
                case "search"    -> "/";           // search engines don't have /models
                case "ocr"       -> "/";           // OCR services vary
                case "vision"    -> "/models";     // vision models usually OpenAI-compatible
                case "embedding" -> "/models";
                default          -> "/models";     // chat LLM — standard OpenAI path
            };
            String testUrl = base + testPath;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofSeconds(10));

            // Auth header — skip for local/search services without keys
            if (apiKey != null && !apiKey.isBlank() && !"sk-placeholder".equals(apiKey)) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = reqBuilder.GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            result.put("success", success);
            result.put("httpStatus", response.statusCode());
            if (success) {
                result.put("message", "连接成功 (HTTP " + response.statusCode() + ")");
            } else {
                String body = response.body();
                if (body != null && body.length() > 200) body = body.substring(0, 200);
                result.put("message", "服务器返回 " + response.statusCode() + (body != null ? "：" + body : ""));
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败：" + e.getMessage());
        }

        return R.ok(result);
    }

    // ── Helpers ──

    private static void maskApiKey(AiModelConfig m) {
        if (m == null) return;
        m.setThinkingSupported(com.aiproject.aiassitant.module.ai.service.ApiManager.supportsFlashVision(m));
        String key = m.getApiKeyAlias();
        if (key != null && !key.isBlank()) {
            // Store original length info in a way frontend can detect
            if (key.length() > 10) {
                m.setApiKeyAlias(key.substring(0, 6) + "****" + key.substring(key.length() - 4));
            } else {
                m.setApiKeyAlias(key.substring(0, Math.min(3, key.length())) + "****");
            }
        }
        // Note: the frontend checks ApiKeyAlias != null && !blank to show "已配置"
    }

    private void encryptApiKeyIfNew(AiModelConfig model) {
        String value = model.getApiKeyAlias();
        if (value != null && !value.isBlank() && !value.matches("[A-Z][A-Z0-9_]{2,63}")) {
            model.setApiKeyAlias(
                com.aiproject.aiassitant.module.admin.config.ConfigLoader.encrypt(value));
            model.setApiKeyEncrypted(true);
        } else {
            model.setApiKeyEncrypted(false);
        }
    }

    private String resolveApiKey(AiModelConfig config) {
        String val = config.getApiKeyAlias();
        // If it's a masked value, read from DB directly
        if (val != null && val.contains("****")) {
            AiModelConfig fresh = modelConfigMapper.selectById(config.getId());
            val = fresh != null ? fresh.getApiKeyAlias() : null;
        }
        if (val == null || val.isBlank()) return "sk-placeholder";
        // Decrypt if stored encrypted
        if (Boolean.TRUE.equals(config.getApiKeyEncrypted())) {
            try {
                val = com.aiproject.aiassitant.module.admin.config.ConfigLoader.decrypt(val);
            } catch (Exception e) {
                throw new IllegalStateException("模型 API Key 解密失败，请重新配置", e);
            }
        }
        if (val.startsWith("sk-")) return val;
        String env = System.getenv(val);
        return env != null ? env : "sk-placeholder";
    }
}
