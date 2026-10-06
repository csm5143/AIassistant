ALTER TABLE kb_collection
    ADD COLUMN folder_path VARCHAR(1024) NULL,
    ADD COLUMN folder_synced_at DATETIME NULL;
ALTER TABLE kb_document
    ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
    ADD COLUMN source_relative_path VARCHAR(1024) NULL,
    ADD COLUMN source_key VARCHAR(64) NULL,
    ADD COLUMN source_fingerprint VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_collection_folder_file (collection_id, source_key);
