-- V5: Full-text search index for hybrid retrieval (BM25 via MySQL)
-- MySQL InnoDB supports FULLTEXT with ngram parser (built-in since 5.7)

ALTER TABLE kb_chunk ADD FULLTEXT INDEX ft_kb_chunk_content (content) WITH PARSER ngram;
