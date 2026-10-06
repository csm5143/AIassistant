CREATE TABLE conversation_memory (
    session_id VARCHAR(32) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(32) NOT NULL,
    state_json MEDIUMTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    KEY idx_memory_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_chat_message_session_time ON chat_message(session_id, created_at, id);
-- New corrections in the same second need a stable chronological order.
ALTER TABLE chat_message MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
