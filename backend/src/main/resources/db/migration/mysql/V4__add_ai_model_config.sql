-- V4: AI model configuration table (DB-driven model management)

CREATE TABLE IF NOT EXISTS ai_model_config (
    id                 VARCHAR(32)   NOT NULL PRIMARY KEY,
    name               VARCHAR(64)   NOT NULL,
    provider           VARCHAR(32)   NOT NULL DEFAULT 'deepseek',
    base_url           VARCHAR(256)  NOT NULL,
    api_key_alias      VARCHAR(128)  DEFAULT NULL COMMENT 'Direct key (sk-...) or env var name',
    model_name         VARCHAR(64)   NOT NULL,
    embedding_dimension INT           DEFAULT NULL COMMENT '>0 means embedding model',
    temperature        DECIMAL(4,2)  DEFAULT 0.30,
    max_tokens         INT           DEFAULT 4096,
    top_p              DECIMAL(4,2)  DEFAULT NULL,
    price_per_1k_input  DECIMAL(10,6) DEFAULT 0.000000,
    price_per_1k_output DECIMAL(10,6) DEFAULT 0.000000,
    enabled            TINYINT       NOT NULL DEFAULT 1,
    is_default         TINYINT       NOT NULL DEFAULT 0,
    sort_order         INT           NOT NULL DEFAULT 99,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_model_config_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI model configurations (chat + embedding)';

-- Seed: DeepSeek Chat (default)
INSERT INTO ai_model_config (id, name, provider, base_url, api_key_alias, model_name, temperature, max_tokens, enabled, is_default, sort_order, created_at, updated_at)
VALUES ('m-deepseek-001', 'DeepSeek Chat', 'deepseek', 'https://api.deepseek.com/v1', 'DEEPSEEK_KEY', 'deepseek-chat', 0.30, 4096, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE base_url=VALUES(base_url), model_name=VALUES(model_name), updated_at=NOW();

-- Seed: SiliconFlow BGE-M3 embedding
INSERT INTO ai_model_config (id, name, provider, base_url, api_key_alias, model_name, embedding_dimension, enabled, is_default, sort_order, created_at, updated_at)
VALUES ('m-embed-001', 'BGE-M3 (SiliconFlow)', 'siliconflow', 'https://api.siliconflow.cn/v1', 'EMB_KEY', 'BAAI/bge-m3', 1024, 1, 0, 10, NOW(), NOW())
ON DUPLICATE KEY UPDATE base_url=VALUES(base_url), model_name=VALUES(model_name), embedding_dimension=VALUES(embedding_dimension), updated_at=NOW();
