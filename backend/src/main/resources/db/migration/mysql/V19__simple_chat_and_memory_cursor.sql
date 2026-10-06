-- Stable insertion cursor detects newly inserted messages even with backdated timestamps.
ALTER TABLE chat_message
    ADD COLUMN memory_seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE,
    ADD INDEX idx_chat_message_memory_seq (session_id, memory_seq);
-- NULL means cache statistics were not fully reported by the provider, not zero hits.
ALTER TABLE chat_log
    ADD COLUMN cache_hit_tokens BIGINT NULL,
    ADD COLUMN cache_miss_tokens BIGINT NULL,
    ADD COLUMN cache_usage_complete BOOLEAN NULL,
    ADD COLUMN request_kind VARCHAR(32) NULL;
