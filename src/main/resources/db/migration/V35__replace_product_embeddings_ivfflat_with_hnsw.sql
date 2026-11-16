-- V35: Replace IVFFlat index on product_embeddings with HNSW index.
-- IVFFlat created on an empty or small table (with lists = 100 and probes = 1)
-- suffers from empty centroids and returns 0 or near-0 recall until manually reindexed.
-- HNSW builds incrementally on insert/update with high recall at any table size.
DROP INDEX IF EXISTS idx_product_embeddings_ivfflat;

CREATE INDEX IF NOT EXISTS idx_product_embeddings_hnsw
    ON product_embeddings USING hnsw (embedding vector_cosine_ops);
