-- V3__add_sys_user_role_entity.sql
-- Provides entity mapping for existing tables that had no corresponding entity class

-- NOTE: These tables already exist from V1. This migration only seeds initial data.
-- The entity classes and mapper interfaces will be added via Java code.

-- Seed tool registrations
INSERT INTO tool_registration (id, name, description, schema_json, source, status, created_at, updated_at) VALUES
    ('tool-search-kb', 'search_knowledge_base', 'Search the user''s knowledge base for relevant information', '{"type":"object","properties":{"query":{"type":"string","description":"Search query"}},"required":["query"]}', 'BUILTIN', 1, NOW(), NOW()),
    ('tool-calculator', 'calculator', 'Perform basic arithmetic calculations', '{"type":"object","properties":{"expression":{"type":"string","description":"Mathematical expression, e.g. 2+3*5"}},"required":["expression"]}', 'BUILTIN', 1, NOW(), NOW()),
    ('tool-current-time', 'get_current_time', 'Get the current date and time', '{"type":"object","properties":{}}', 'BUILTIN', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE name=VALUES(name), updated_at=NOW();
