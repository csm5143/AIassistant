-- Align the fresh installation with the current Java entities and BGE-M3.
ALTER TABLE kb_document
    ADD COLUMN chunk_size INT NOT NULL DEFAULT 500,
    ADD COLUMN chunk_overlap INT NOT NULL DEFAULT 80;

CREATE TABLE IF NOT EXISTS tool_call_log (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    session_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments MEDIUMTEXT,
    result MEDIUMTEXT,
    status VARCHAR(16),
    error_message TEXT,
    elapsed_ms BIGINT,
    created_at DATETIME NOT NULL,
    KEY idx_tool_call_session (session_id),
    KEY idx_tool_call_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE ai_model_config SET type = 'embedding', embedding_dimension = 1024,
    api_key_alias = 'EMB_KEY', is_default = 0, enabled = 1
WHERE model_name = 'BAAI/bge-m3';
UPDATE kb_collection SET dimension = 1024 WHERE embedding_model = 'BAAI/bge-m3';

-- The historical seeds contain two identical chat entries.
UPDATE ai_model_config SET enabled = 0, is_default = 0 WHERE id = 'm-chat-001';
UPDATE ai_model_config SET is_default = 0 WHERE model_name <> 'deepseek-chat';
UPDATE ai_model_config SET type = 'chat', api_key_alias = 'DEEPSEEK_KEY', enabled = 1,
    is_default = 1 WHERE id = 'model-deepseek-chat';
UPDATE ai_model_config SET api_key_alias = 'DEEPSEEK_KEY'
WHERE model_name = 'deepseek-reasoner';
UPDATE ai_model_config SET enabled = 0
WHERE provider IN ('openai', 'anthropic') AND (api_key_alias IS NULL OR api_key_alias = '');
