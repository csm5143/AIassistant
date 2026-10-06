package com.aiproject.aiassitant.ai.memory;

import com.aiproject.aiassitant.module.chat.entity.ChatMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);
    private static final String KEY_PREFIX = "chat:memory:";
    private static final long TTL_DAYS = 30;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void saveMessages(String sessionId, List<ChatMessage> messages) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key, json, TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("Failed to save chat memory for session {}", sessionId, e);
        }
    }

    public List<ChatMessage> loadMessages(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(raw.toString(), new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to load chat memory for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    public void clearMemory(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.delete(key);
    }
}
