-- V1__init_pgvector.sql
-- Vector storage. The application uses MyBatis-Plus to manage business rows and talks
-- to PGVector directly (via the pgvector JDBC type) for the embeddings themselves.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_vectors (
    id              VARCHAR(64)  PRIMARY KEY,
    collection_id   VARCHAR(32)  NOT NULL,
    document_id     VARCHAR(32)  NOT NULL,
    chunk_id        VARCHAR(32)  NOT NULL,
    user_id         VARCHAR(32)  NOT NULL,
    embedding       vector(1024) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kb_vectors_collection ON kb_vectors (collection_id);
CREATE INDEX IF NOT EXISTS idx_kb_vectors_user       ON kb_vectors (user_id);

-- HNSW index for cosine distance ANN search. Adjust m/ef_construction as data grows.
-- Note: HNSW max 2000-dim; for 4096-dim switch to IVFFlat.
CREATE INDEX IF NOT EXISTS idx_kb_vectors_embedding
    ON kb_vectors USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
