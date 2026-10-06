-- V3: Fix vector dimension from 1024 to 4096 (BAAI/bge-m3 returns 4096-dim)
-- pgvector cannot ALTER vector column dimension, must recreate.
-- No ANN index — both HNSW and IVFFlat limited to 2000-dim. Brute-force cosine
-- distance is fine for demo-scale data (<100k vectors).
DROP TABLE IF EXISTS kb_vectors CASCADE;

CREATE TABLE kb_vectors (
    id              VARCHAR(64)  PRIMARY KEY,
    collection_id   VARCHAR(32)  NOT NULL,
    document_id     VARCHAR(32)  NOT NULL,
    chunk_id        VARCHAR(32)  NOT NULL,
    user_id         VARCHAR(32)  NOT NULL,
    embedding       vector(4096) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kb_vectors_collection ON kb_vectors (collection_id);
CREATE INDEX IF NOT EXISTS idx_kb_vectors_user       ON kb_vectors (user_id);
