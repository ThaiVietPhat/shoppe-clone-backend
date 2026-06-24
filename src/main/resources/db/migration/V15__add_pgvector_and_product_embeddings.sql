-- V15: Enable pgvector extension and create product_embeddings table
-- text-embedding-004 (Google Gemini) produces 768-dimensional vectors
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_embeddings
(
    product_id     UUID         NOT NULL PRIMARY KEY
        REFERENCES products (id) ON DELETE CASCADE,
    embedding      vector(768)  NOT NULL,
    model_version  VARCHAR(50)  NOT NULL,
    indexed_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- IVFFlat index for approximate nearest-neighbour cosine similarity search.
-- lists=100 is suitable for up to ~1M rows; rebuild if catalog grows significantly.
CREATE INDEX idx_product_embeddings_ivfflat
    ON product_embeddings USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
