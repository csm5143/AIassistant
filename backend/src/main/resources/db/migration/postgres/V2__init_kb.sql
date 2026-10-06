CREATE EXTENSION IF NOT EXISTS vector;

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
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kb_document (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    collection_id VARCHAR(32)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    mime_type     VARCHAR(128) DEFAULT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    storage_path  VARCHAR(512) DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(1024) DEFAULT NULL,
    chunk_count   INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kb_chunk (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    document_id   VARCHAR(32)  NOT NULL,
    collection_id VARCHAR(32)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    ordinal       INT          NOT NULL,
    content       TEXT         NOT NULL,
    token_count   INT          NOT NULL DEFAULT 0,
    vector_id     VARCHAR(64)  DEFAULT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS kb_vectors (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    collection_id   VARCHAR(32)  NOT NULL,
    document_id     VARCHAR(32)  NOT NULL,
    chunk_id        VARCHAR(32)  NOT NULL,
    user_id         VARCHAR(32)  NOT NULL,
    embedding       vector(1024) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kb_collection_user ON kb_collection (user_id);
CREATE INDEX IF NOT EXISTS idx_kb_document_collection ON kb_document (collection_id);
CREATE INDEX IF NOT EXISTS idx_kb_document_user ON kb_document (user_id);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_collection ON kb_chunk (collection_id);
CREATE INDEX IF NOT EXISTS idx_kb_vectors_collection ON kb_vectors (collection_id);
CREATE INDEX IF NOT EXISTS idx_kb_vectors_user ON kb_vectors (user_id);

CREATE INDEX IF NOT EXISTS idx_kb_vectors_embedding
    ON kb_vectors USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
