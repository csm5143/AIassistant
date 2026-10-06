-- V8: Fix V2→V4 migration gap and seed all model categories.

-- 1. Add missing columns from V4 (safe to re-run — checks existence first)
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'type');
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE ai_model_config ADD COLUMN type VARCHAR(16) DEFAULT ''chat'' AFTER provider, '
    'ADD COLUMN api_key_encrypted TINYINT DEFAULT 0 AFTER is_default',
    'SELECT ''columns already exist, skipping''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Widen existing columns
ALTER TABLE ai_model_config
    MODIFY COLUMN temperature      DECIMAL(4,2)  DEFAULT 0.30,
    MODIFY COLUMN price_per_1k_input  DECIMAL(10,6) DEFAULT 0.000000,
    MODIFY COLUMN price_per_1k_output DECIMAL(10,6) DEFAULT 0.000000,
    MODIFY COLUMN sort_order        INT           DEFAULT 99;

-- 3. Fix existing DeepSeek row
UPDATE ai_model_config SET type = 'chat', api_key_alias = 'DEEPSEEK_KEY',
    base_url = 'https://api.deepseek.com/v1', model_name = 'deepseek-chat', is_default = 1, sort_order = 1
WHERE name = 'DeepSeek Chat' OR model_name = 'deepseek-chat';

-- 4. Clean up duplicate embedding model
DELETE t1 FROM ai_model_config t1
INNER JOIN ai_model_config t2
WHERE t1.id > t2.id AND t1.model_name = t2.model_name AND t1.model_name = 'BAAI/bge-m3';

-- 5. Ensure embedding dimension is 4096
UPDATE ai_model_config SET embedding_dimension = 4096
WHERE model_name = 'BAAI/bge-m3' AND (embedding_dimension IS NULL OR embedding_dimension != 4096);

-- 6. Seed all model categories
INSERT IGNORE INTO ai_model_config (id, name, provider, type, base_url, api_key_alias, model_name,
    embedding_dimension, temperature, max_tokens, enabled, is_default, sort_order, created_at, updated_at)
VALUES
    ('m-chat-001',  'DeepSeek Flash', 'deepseek',    'chat',      'https://api.deepseek.com/v1',      'DEEPSEEK_KEY', 'deepseek-chat',  NULL, 0.30, 4096, 1, 1, 1,  NOW(), NOW()),
    ('m-embed-001', 'BGE-M3',         'siliconflow', 'embedding', 'https://api.siliconflow.cn/v1',    'EMB_KEY',      'BAAI/bge-m3',    4096, NULL, NULL, 1, 0, 10, NOW(), NOW()),
    ('m-search-001','SiliconFlow Srch','siliconflow', 'search',    'https://api.siliconflow.cn/v1',    'EMB_KEY',      'web-search',      NULL, NULL, NULL, 1, 0, 20, NOW(), NOW()),
    ('m-vision-001','Mimo-v2.5 Vis',  'siliconflow', 'vision',    'https://api.siliconflow.cn/v1',    'EMB_KEY',      'mimo-v2.5',       NULL, 0.30, 500,  1, 0, 30, NOW(), NOW()),
    ('m-ocr-001',   'Baidu OCR',      'baidu',       'ocr',       'https://aip.baidubce.com/rest/2.0/ocr/v1/accurate_basic', 'BAIDU_OCR_KEY', 'baidu-ocr', NULL, NULL, NULL, 1, 0, 40, NOW(), NOW()),
    ('m-clean-001', 'Mimo-v2.5 Clean','siliconflow', 'cleaning',  'https://api.siliconflow.cn/v1',    'EMB_KEY',      'mimo-v2.5',       NULL, 0.30, 4096, 1, 0, 50, NOW(), NOW());
