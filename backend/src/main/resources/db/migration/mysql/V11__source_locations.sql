CREATE TABLE kb_document_source (
    document_id VARCHAR(32) PRIMARY KEY,
    content LONGTEXT NOT NULL,
    blocks_json LONGTEXT NOT NULL,
    CONSTRAINT fk_source_document FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE kb_chunk
    ADD COLUMN source_start INT NULL,
    ADD COLUMN source_end INT NULL,
    ADD COLUMN source_page INT NULL,
    ADD COLUMN source_title VARCHAR(512) NULL;
