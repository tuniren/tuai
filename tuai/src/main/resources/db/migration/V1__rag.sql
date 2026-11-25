CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE rag_document (
    id uuid PRIMARY KEY,
    namespace varchar(128) NOT NULL,
    source varchar(1024) NOT NULL,
    title varchar(512) NOT NULL,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    embedding_model varchar(256) NOT NULL,
    embedding_profile varchar(64) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(namespace, source)
);

CREATE TABLE rag_chunk (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    ordinal integer NOT NULL,
    content text NOT NULL,
    start_line integer NOT NULL,
    end_line integer NOT NULL,
    embedding public.vector(${embeddingDimensions}) NOT NULL,
    UNIQUE(document_id, ordinal)
);

CREATE INDEX rag_document_namespace_idx ON rag_document(namespace);
CREATE INDEX rag_chunk_embedding_idx ON rag_chunk USING hnsw (embedding public.vector_cosine_ops);
