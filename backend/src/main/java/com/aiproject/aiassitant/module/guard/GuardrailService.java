package com.aiproject.aiassitant.module.guard;

import com.aiproject.aiassitant.module.guard.entity.GuardLog;
import com.aiproject.aiassitant.module.guard.mapper.GuardLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@org.springframework.context.annotation.DependsOn("guardConfigService")
@RequiredArgsConstructor
public class GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailService.class);
    private static final String REDIS_KEY_INPUT_KEYWORDS = "guard:input:keywords";
    private static final String REDIS_KEY_INPUT_REGEX   = "guard:input:regex";
    private static final String REDIS_KEY_OUTPUT_KEYWORDS = "guard:output:keywords";

    private final RedisTemplate<String, Object> redisTemplate;
    private final GuardLogMapper guardLogMapper;
    private final ObjectMapper objectMapper;

    @Getter
    private volatile boolean inputBlockedOnHit = true;

    @Getter
    private volatile String outputMask = "[已过滤]";

    private volatile List<String> inputKeywords = List.of();
    private volatile List<Pattern> inputRegexPatterns = List.of();
    private volatile List<String> outputKeywords = List.of();
    private volatile boolean enabled = true;

    @PostConstruct
    public void init() {
        reloadFromRedis();
        log.info("GuardrailService initialized");
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30_000L)
    public void reloadFromRedis() {
        Object enabledValue = redisTemplate.opsForValue().get("guard:config:enabled");
        enabled = enabledValue == null || Boolean.parseBoolean(enabledValue.toString());
        Object blockValue = redisTemplate.opsForValue().get("guard:config:inputBlockOnHit");
        inputBlockedOnHit = blockValue == null || Boolean.parseBoolean(blockValue.toString());
        Object maskValue = redisTemplate.opsForValue().get("guard:config:outputMask");
        outputMask = maskValue == null ? "[已过滤]" : maskValue.toString();
        inputKeywords = loadStringList(REDIS_KEY_INPUT_KEYWORDS);
        inputRegexPatterns = loadRegexPatterns(REDIS_KEY_INPUT_REGEX);
        outputKeywords = loadStringList(REDIS_KEY_OUTPUT_KEYWORDS);
        log.info("Guardrail rules reloaded: inputKeywords={}, inputRegex={}, outputKeywords={}",
                inputKeywords.size(), inputRegexPatterns.size(), outputKeywords.size());
    }

    public String checkInput(String text, String userId) {
        if (!enabled || text == null || text.isBlank()) return null;

        for (String kw : inputKeywords) {
            if (text.contains(kw)) {
                logHit("INPUT", "KEYWORD", kw, userId);
                return inputBlockedOnHit ? "BLOCK:" + kw : null;
            }
        }
        for (Pattern p : inputRegexPatterns) {
            if (p.matcher(text).find()) {
                logHit("INPUT", "REGEX", p.pattern(), userId);
                return inputBlockedOnHit ? "BLOCK:REGEX" : null;
            }
        }
        return null;
    }

    public String checkOutput(String text, String userId) {
        if (!enabled || text == null || text.isBlank()) return text;
        for (String kw : outputKeywords) {
            if (text.contains(kw)) {
                logHit("OUTPUT", "KEYWORD", kw, userId);
                text = text.replace(kw, outputMask);
            }
        }
        return text;
    }

    public StreamingKeywordMasker newOutputFilter() {
        return new StreamingKeywordMasker(enabled ? outputKeywords : List.of(), outputMask);
    }

    private List<String> loadStringList(String key) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return List.of();
            return objectMapper.readValue(raw.toString(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to load {} from Redis: {}", key, e.getMessage());
            return List.of();
        }
    }

    private List<Pattern> loadRegexPatterns(String key) {
        List<String> patterns = loadStringList(key);
        return patterns.stream()
                .map(p -> {
                    try { return Pattern.compile(p); }
                    catch (Exception e) { log.warn("Invalid regex pattern: {}", p); return null; }
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());
    }

    private void logHit(String direction, String stage, String rule, String userId) {
        try {
            GuardLog guardLog = new GuardLog();
            guardLog.setUserId(userId);
            guardLog.setDirection(direction);
            guardLog.setStage(stage);
            guardLog.setRule(rule);
            guardLog.setAction("OUTPUT".equals(direction) ? "MASK" : (inputBlockedOnHit ? "BLOCK" : "ALLOW"));
            guardLogMapper.insert(guardLog);
        } catch (Exception e) {
            log.error("Failed to log guard hit", e);
        }
    }
}
