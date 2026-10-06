-- V6: Fix BAAI/bge-m3 embedding dimension from 1024 to 4096
-- The model returns 4096-dimensional embeddings, not 1024.
UPDATE ai_model_config SET embedding_dimension = 4096 WHERE model_name = 'BAAI/bge-m3';
