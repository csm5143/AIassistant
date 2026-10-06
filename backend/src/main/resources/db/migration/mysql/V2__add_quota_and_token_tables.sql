-- V2__add_quota_and_token_tables.sql
-- User quota, token usage logging, AI model config, and subscription management

-- Token usage log: one row per (user, date) aggregated from chat_log
CREATE TABLE IF NOT EXISTS token_usage_log (
    id              VARCHAR(32) NOT NULL PRIMARY KEY,
    user_id         VARCHAR(32) NOT NULL,
    date_key        DATE        NOT NULL,
    model           VARCHAR(64) NOT NULL DEFAULT 'deepseek-chat',
    prompt_tokens   BIGINT      NOT NULL DEFAULT 0,
    completion_tokens BIGINT     NOT NULL DEFAULT 0,
    total_tokens   BIGINT      NOT NULL DEFAULT 0,
    request_count   INT         NOT NULL DEFAULT 0,
    cost_usd        DECIMAL(10,4) DEFAULT 0,
    created_at      DATETIME    NOT NULL,
    updated_at      DATETIME    NOT NULL,
    UNIQUE KEY uk_token_usage_user_date (user_id, date_key),
    KEY idx_token_usage_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily token consumption per user';

-- User quota / subscription tier
CREATE TABLE IF NOT EXISTS user_quota (
    id                   VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id              VARCHAR(32)  NOT NULL UNIQUE,
    tier                 VARCHAR(32)  NOT NULL DEFAULT 'free',
    daily_token_limit    BIGINT       NOT NULL DEFAULT 100000,
    monthly_token_limit  BIGINT       NOT NULL DEFAULT 500000,
    daily_token_used     BIGINT       NOT NULL DEFAULT 0,
    monthly_token_used   BIGINT       NOT NULL DEFAULT 0,
    daily_request_limit  INT          NOT NULL DEFAULT 100,
    monthly_request_limit INT         NOT NULL DEFAULT 3000,
    daily_request_used   INT          NOT NULL DEFAULT 0,
    monthly_request_used INT          NOT NULL DEFAULT 0,
    quota_reset_at       DATETIME     NOT NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    KEY idx_user_quota_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User quota limits and current usage';

-- AI model configuration (runtime model registry)
CREATE TABLE IF NOT EXISTS ai_model_config (
    id                   VARCHAR(32)  NOT NULL PRIMARY KEY,
    name                 VARCHAR(64)  NOT NULL,
    provider             VARCHAR(32)  NOT NULL,
    base_url             VARCHAR(256) NOT NULL,
    api_key_alias        VARCHAR(64)  DEFAULT NULL COMMENT 'Reference to secrets manager key name',
    model_name           VARCHAR(64)  NOT NULL,
    embedding_dimension  INT          DEFAULT NULL COMMENT 'Set for embedding models only',
    temperature          DECIMAL(3,2) DEFAULT 0.7,
    max_tokens           INT          DEFAULT 4096,
    top_p                DECIMAL(3,2) DEFAULT 1.0,
    price_per_1k_input   DECIMAL(10,4) DEFAULT 0,
    price_per_1k_output  DECIMAL(10,4) DEFAULT 0,
    enabled              TINYINT      NOT NULL DEFAULT 1,
    is_default           TINYINT      NOT NULL DEFAULT 0,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    UNIQUE KEY uk_ai_model_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI model registry for multi-model support';

-- Subscription tier definitions
CREATE TABLE IF NOT EXISTS subscription_tier (
    id                    VARCHAR(32)  NOT NULL PRIMARY KEY,
    name                  VARCHAR(64)  NOT NULL,
    description           VARCHAR(255) DEFAULT NULL,
    daily_token_limit     BIGINT       NOT NULL DEFAULT 100000,
    monthly_token_limit   BIGINT       NOT NULL DEFAULT 500000,
    daily_request_limit   INT          NOT NULL DEFAULT 100,
    monthly_request_limit INT          NOT NULL DEFAULT 3000,
    price_monthly_usd     DECIMAL(10,2) DEFAULT 0,
    features              JSON         DEFAULT NULL COMMENT 'Feature flags as JSON array',
    sort_order            INT          NOT NULL DEFAULT 0,
    enabled               TINYINT      NOT NULL DEFAULT 1,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Subscription tier catalogue';

-- User subscription mapping
CREATE TABLE IF NOT EXISTS user_subscription (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(32)  NOT NULL,
    tier_id     VARCHAR(32)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active|cancelled|expired|trial',
    started_at  DATETIME     NOT NULL,
    expires_at  DATETIME     DEFAULT NULL,
    cancelled_at DATETIME     DEFAULT NULL,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_user_subscription (user_id),
    KEY idx_user_sub_tier (tier_id),
    KEY idx_user_sub_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User subscription records';

-- Seed free tier
INSERT INTO subscription_tier (id, name, description, daily_token_limit, monthly_token_limit,
    daily_request_limit, monthly_request_limit, price_monthly_usd, features, sort_order, enabled,
    created_at, updated_at) VALUES
    ('tier-free', 'Free', 'Free tier with basic limits', 100000, 500000, 100, 3000, 0,
     '["basic_chat","knowledge_base"]', 0, 1, NOW(), NOW()),
    ('tier-pro', 'Pro', 'Pro tier with higher limits', 1000000, 10000000, 1000, 50000, 19.9,
     '["basic_chat","knowledge_base","function_calling","priority_support","advanced_models"]', 1, 1, NOW(), NOW()),
    ('tier-enterprise', 'Enterprise', 'Enterprise unlimited', -1, -1, -1, -1, 99.9,
     '["all_features","sla","dedicated_support","custom_models","api_access"]', 2, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE name=VALUES(name), updated_at=NOW();

-- Seed default AI models
INSERT INTO ai_model_config (id, name, provider, base_url, model_name, temperature, max_tokens,
    price_per_1k_input, price_per_1k_output, enabled, is_default, sort_order, created_at, updated_at) VALUES
    ('model-deepseek-chat', 'DeepSeek Chat', 'deepseek', 'https://api.deepseek.com/v1',
     'deepseek-chat', 0.3, 4096, 0.00014, 0.00028, 1, 1, 0, NOW(), NOW()),
    ('model-deepseek-reasoner', 'DeepSeek Reasoner', 'deepseek', 'https://api.deepseek.com/v1',
     'deepseek-reasoner', 0.3, 8192, 0.00027, 0.0011, 1, 0, 1, NOW(), NOW()),
    ('model-gpt-4o-mini', 'GPT-4o Mini', 'openai', 'https://api.openai.com/v1',
     'gpt-4o-mini', 0.3, 16384, 0.00015, 0.0006, 1, 0, 2, NOW(), NOW()),
    ('model-gpt-4o', 'GPT-4o', 'openai', 'https://api.openai.com/v1',
     'gpt-4o', 0.3, 16384, 0.0025, 0.01, 1, 0, 3, NOW(), NOW()),
    ('model-claude-sonnet', 'Claude Sonnet 4', 'anthropic', 'https://api.anthropic.com/v1',
     'claude-sonnet-4-20250514', 0.3, 8192, 0.003, 0.015, 1, 0, 4, NOW(), NOW()),
    ('model-bge-m3', 'BGE-M3 Embedding', 'siliconflow', 'https://api.siliconflow.cn/v1',
     'BAAI/bge-m3', 0.0, 8192, 0.0001, 0, 1, 0, 10, NOW(), NOW())
ON DUPLICATE KEY UPDATE name=VALUES(name), updated_at=NOW();

-- Initialize quota for existing admin user
INSERT INTO user_quota (id, user_id, tier, daily_token_limit, monthly_token_limit,
    daily_request_limit, monthly_request_limit, quota_reset_at, created_at, updated_at) VALUES
    ('quota-admin-0001', 'u-admin-0001', 'enterprise', -1, -1, -1, -1, DATE_ADD(NOW(), INTERVAL 1 MONTH), NOW(), NOW())
ON DUPLICATE KEY UPDATE tier=VALUES(tier), updated_at=NOW();
