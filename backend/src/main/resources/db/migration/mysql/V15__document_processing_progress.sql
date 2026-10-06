-- kb_document is also the durable document processing queue. Existing rows stay intact.
ALTER TABLE kb_document
    ADD COLUMN progress_stage VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN processed_pages INT NOT NULL DEFAULT 0,
    ADD COLUMN total_pages INT NOT NULL DEFAULT 0,
    ADD COLUMN vectorized_count INT NOT NULL DEFAULT 0,
    ADD COLUMN parse_duration_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN chunk_duration_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN vector_duration_ms BIGINT NOT NULL DEFAULT 0,
    ADD KEY idx_kb_document_status_created (status, created_at);

UPDATE kb_document SET progress_stage = CASE
    WHEN status = 'READY' THEN 'COMPLETE'
    WHEN status IN ('FAILED', 'VECTOR_FAILED') THEN 'FAILED'
    WHEN status = 'PROCESSING' THEN 'PARSING'
    WHEN status = 'VECTORIZING' THEN 'EMBEDDING'
    ELSE 'QUEUED'
END;
