-- V1__init_core_schema.sql
-- Core platform tables. All identifiers use snake_case. Audit columns are populated by
-- MybatisPlusConfig#metaObjectHandler.

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name  VARCHAR(64)  DEFAULT NULL,
    email         VARCHAR(128) DEFAULT NULL,
    phone         VARCHAR(32)  DEFAULT NULL,
    avatar        VARCHAR(255) DEFAULT NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active,0=disabled',
    last_login_at DATETIME     DEFAULT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform users (both user-portal and admin-portal share this table)';

CREATE TABLE IF NOT EXISTS sys_role (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Role catalogue';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id    VARCHAR(32) NOT NULL,
    role_id    VARCHAR(32) NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='User-role mapping';

CREATE TABLE IF NOT EXISTS chat_session (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(32)  NOT NULL,
    title         VARCHAR(255) NOT NULL DEFAULT 'New Chat',
    model         VARCHAR(64)  NOT NULL DEFAULT 'deepseek-chat',
    system_prompt TEXT         DEFAULT NULL,
    pinned        TINYINT      NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    KEY idx_chat_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat sessions owned by users';

CREATE TABLE IF NOT EXISTS chat_message (
    id          VARCHAR(32) NOT NULL PRIMARY KEY,
    session_id  VARCHAR(32) NOT NULL,
    role        VARCHAR(16) NOT NULL COMMENT 'user|assistant|system|tool',
    content     MEDIUMTEXT  NOT NULL,
    tool_calls  JSON        DEFAULT NULL,
    extra       JSON        DEFAULT NULL,
    token_count INT         DEFAULT 0,
    created_at  DATETIME    NOT NULL,
    KEY idx_chat_message_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Individual messages inside a chat session';

CREATE TABLE IF NOT EXISTS kb_collection (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512) DEFAULT NULL,
    embedding_model VARCHAR(64)  NOT NULL DEFAULT 'BAAI/bge-m3',
    dimension       INT          NOT NULL DEFAULT 1024,
    chunk_size      INT          NOT NULL DEFAULT 500,
    chunk_overlap   INT          NOT NULL DEFAULT 80,
    document_count  INT          NOT NULL DEFAULT 0,
    chunk_count     BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    KEY idx_kb_collection_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Knowledge base collection (per user)';

CREATE TABLE IF NOT EXISTS kb_document (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    collection_id VARCHAR(32)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(128) DEFAULT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    storage_path  VARCHAR(512) DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|PROCESSING|READY|FAILED',
    error_message VARCHAR(1024) DEFAULT NULL,
    chunk_count   INT          NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    KEY idx_kb_document_collection (collection_id),
    KEY idx_kb_document_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Uploaded document, one row per file';

CREATE TABLE IF NOT EXISTS kb_chunk (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    document_id   VARCHAR(32)  NOT NULL,
    collection_id VARCHAR(32)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    ordinal       INT          NOT NULL,
    content       MEDIUMTEXT   NOT NULL,
    token_count   INT          NOT NULL DEFAULT 0,
    vector_id     VARCHAR(64)  DEFAULT NULL COMMENT 'PGVector row id (textual UUID)',
    created_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_kb_chunk_doc_ord (document_id, ordinal),
    KEY idx_kb_chunk_collection (collection_id),
    KEY idx_kb_chunk_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Text chunks; vector lives in PGVector';

CREATE TABLE IF NOT EXISTS tool_registration (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) DEFAULT NULL,
    schema_json JSON         DEFAULT NULL,
    source      VARCHAR(16)  NOT NULL DEFAULT 'BUILTIN' COMMENT 'BUILTIN|MCP|HTTP',
    status      TINYINT      NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_tool_registration_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Catalogue of callable tools';

CREATE TABLE IF NOT EXISTS chat_log (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(32)  NOT NULL,
    session_id      VARCHAR(32)  DEFAULT NULL,
    question        MEDIUMTEXT   NOT NULL,
    answer          MEDIUMTEXT   DEFAULT NULL,
    model           VARCHAR(64)  DEFAULT NULL,
    prompt_tokens   INT          NOT NULL DEFAULT 0,
    completion_tokens INT        NOT NULL DEFAULT 0,
    latency_ms      INT          NOT NULL DEFAULT 0,
    tool_hit        TINYINT      NOT NULL DEFAULT 0,
    knowledge_hit   TINYINT      NOT NULL DEFAULT 0,
    guard_hit       TINYINT      NOT NULL DEFAULT 0,
    error_message   VARCHAR(1024) DEFAULT NULL,
    created_at      DATETIME     NOT NULL,
    KEY idx_chat_log_user_created (user_id, created_at),
    KEY idx_chat_log_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Observability: one row per chat turn';

CREATE TABLE IF NOT EXISTS guard_log (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(32)  DEFAULT NULL,
    direction     VARCHAR(8)   NOT NULL COMMENT 'INPUT|OUTPUT',
    stage         VARCHAR(32)  NOT NULL COMMENT 'KEYWORD|REGEX|LENGTH',
    rule          VARCHAR(128) DEFAULT NULL,
    matched_content VARCHAR(2048) DEFAULT NULL,
    action        VARCHAR(16)  NOT NULL COMMENT 'BLOCK|MASK|ALLOW',
    created_at    DATETIME     NOT NULL,
    KEY idx_guard_log_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Guardrail hit audit trail';

CREATE TABLE IF NOT EXISTS sys_config (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    config_key  VARCHAR(64)  NOT NULL,
    config_value MEDIUMTEXT  NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_sys_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hot-reloadable platform configuration';

-- Seed default roles
INSERT INTO sys_role (id, code, name, description, created_at, updated_at) VALUES
    ('r-admin-0001', 'ADMIN', 'Administrator', 'Full platform access', NOW(), NOW()),
    ('r-user-0001',  'USER',  'Regular User', 'Standard user-portal access', NOW(), NOW())
ON DUPLICATE KEY UPDATE name=VALUES(name), description=VALUES(description), updated_at=NOW();

-- Default admin user (username=admin, password=admin123) - bcrypt hash of 'admin123'
INSERT INTO sys_user (id, username, password_hash, display_name, status, created_at, updated_at) VALUES
    ('u-admin-0001', 'admin', '$2b$10$S/J4Fd71kSDsTjquMrA4ze.qHX1ra4HXDSZU0D7nrCFC.Gt6ynbGa', 'Platform Admin', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), updated_at=NOW();

INSERT INTO sys_user_role (user_id, role_id, created_at)
SELECT 'u-admin-0001', id, NOW() FROM sys_role WHERE code='ADMIN';

-- Seed default guard keywords
INSERT INTO sys_config (id, config_key, config_value, description, created_at, updated_at) VALUES
    ('cfg-guard-0001', 'guard.input.keywords', '["色情","赌博","毒品","诈骗","违禁"]', 'Input keyword blacklist (JSON array)', NOW(), NOW()),
    ('cfg-guard-0002', 'guard.input.regex', '["1[3-9]\\d{9}","\\d{17}[\\dXx]","[\\w.-]+@[\\w.-]+"]', 'Input regex patterns (phone/id/email)', NOW(), NOW()),
    ('cfg-guard-0003', 'guard.output.keywords', '["密码","secret","token"]', 'Output keyword blacklist', NOW(), NOW())
ON DUPLICATE KEY UPDATE config_value=VALUES(config_value), updated_at=NOW();
