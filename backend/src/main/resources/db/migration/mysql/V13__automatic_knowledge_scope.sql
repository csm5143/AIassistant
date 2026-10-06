ALTER TABLE kb_document ADD COLUMN routing_keywords TEXT NULL;
ALTER TABLE chat_session
 ADD COLUMN knowledge_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
 ADD COLUMN knowledge_selection_json TEXT NULL,
 ADD COLUMN knowledge_route_json TEXT NULL;
