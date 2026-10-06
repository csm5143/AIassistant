package com.aiproject.aiassitant.module.guard;

import com.aiproject.aiassitant.module.guard.entity.GuardLog;
import com.aiproject.aiassitant.module.guard.mapper.GuardLogMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardConfigService {

    private final GuardLogMapper guardLogMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_INPUT_KEYWORDS = "guard:input:keywords";
    private static final String REDIS_KEY_INPUT_REGEX = "guard:input:regex";
    private static final String REDIS_KEY_OUTPUT_KEYWORDS = "guard:output:keywords";

    private boolean enabled = true;
    private boolean inputBlockOnHit = true;
    private String outputMask = "[已过滤]";

    @PostConstruct
    public void init() {
        seedDefaultsIfMissing();
        refresh();
    }

    @Scheduled(fixedDelay = 30_000L)
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (Exception ex) {
            log.warn("Guard config refresh failed: {}", ex.getMessage());
        }
    }

    public synchronized void refresh() {
        Object enabledObj = redisTemplate.opsForValue().get("guard:config:enabled");
        this.enabled = Boolean.parseBoolean(enabledObj != null ? enabledObj.toString() : "true");

        Object blockObj = redisTemplate.opsForValue().get("guard:config:inputBlockOnHit");
        this.inputBlockOnHit = Boolean.parseBoolean(blockObj != null ? blockObj.toString() : "true");

        Object maskObj = redisTemplate.opsForValue().get("guard:config:outputMask");
        this.outputMask = maskObj != null ? maskObj.toString() : "[已过滤]";

        log.info("Guard config refreshed: enabled={}, inputBlockOnHit={}, outputMask={}",
                enabled, inputBlockOnHit, outputMask);
    }

    public void recordHit(String userId, String direction, String stage, String rule, String matched, String action) {
        try {
            GuardLog log = new GuardLog();
            log.setUserId(userId);
            log.setDirection(direction);
            log.setStage(stage);
            log.setRule(rule);
            log.setMatchedContent(matched);
            log.setAction(action);
            guardLogMapper.insert(log);
        } catch (Exception ex) {
            log.warn("Failed to record guard hit: {}", ex.getMessage());
        }
    }

    private void seedDefaultsIfMissing() {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_KEY_INPUT_KEYWORDS))) return;
        try {
            String keywords = objectMapper.writeValueAsString(List.of("炸弹", "毒品", "色情"));
            String regex = objectMapper.writeValueAsString(List.of());
            String output = objectMapper.writeValueAsString(List.of("秘密", "密码"));
            redisTemplate.opsForValue().set(REDIS_KEY_INPUT_KEYWORDS, keywords);
            redisTemplate.opsForValue().set(REDIS_KEY_INPUT_REGEX, regex);
            redisTemplate.opsForValue().set(REDIS_KEY_OUTPUT_KEYWORDS, output);
            redisTemplate.opsForValue().set("guard:config:enabled", "true");
            redisTemplate.opsForValue().set("guard:config:inputBlockOnHit", "true");
            redisTemplate.opsForValue().set("guard:config:outputMask", "[已过滤]");
            log.info("Seeded default guard rules into Redis");
        } catch (Exception ex) {
            log.warn("Failed to seed guard rules: {}", ex.getMessage());
        }
    }
}
