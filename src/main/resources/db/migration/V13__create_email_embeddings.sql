-- Story 04.03: durable home for the local sentence embeddings. Each email gets a
-- fixed-dimension vector from the in-process ONNX embedder, stored here in pgvector
-- so campaign clustering (Epic 06) and arena variant grouping can run cosine
-- similarity / nearest-neighbor queries directly in the database.
--
-- Separate from email_features on purpose: the embedding is consumed by offline
-- clustering, not by the synchronous classifier (the model-input feature schema
-- deliberately excludes it — see ml/feature_schema.py), and pgvector wants a typed
-- `vector` column with its own similarity index rather than a JSONB blob. Keeping
-- it in its own table also lets the embedder version independently of the feature
-- extractor.

-- pgvector provides the `vector` type and the `<=>` cosine-distance operator.
-- Supabase and the pgvector/pgvector test image ship it; this is idempotent.
create extension if not exists vector;

create table email_embeddings (
    -- The email this vector describes. FK to the immutable canonical record.
    email_id        uuid        not null references emails (id),

    -- The embedder that produced `embedding` (OnnxEmbeddingModel.EMBEDDING_VERSION).
    -- Part of the key so a re-embedding under a new version coexists with the old
    -- one rather than overwriting it — the same versioning discipline as features.
    model_version   text        not null,

    -- The L2-normalized embedding. Width must match OnnxEmbeddingModel.EMBEDDING_DIMENSION;
    -- because vectors are unit-length, cosine similarity is 1 - (a <=> b).
    embedding       vector(128) not null,

    created_at      timestamptz not null default now(),

    primary key (email_id, model_version)
);

-- Approximate-nearest-neighbor index for cosine distance, so "find the emails most
-- similar to this one" stays fast as the corpus grows. HNSW handles incremental
-- inserts well, which suits an embedding written per email off the event spine.
create index email_embeddings_embedding_idx
    on email_embeddings using hnsw (embedding vector_cosine_ops);
