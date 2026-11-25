-- Revisions are global and never reused after deletion/recreation of a key.
CREATE SEQUENCE codex_memory_revision;

CREATE TABLE codex_memory (
    namespace varchar(128) NOT NULL,
    key varchar(256) NOT NULL,
    title varchar(256) NOT NULL,
    content text NOT NULL CHECK (length(content) BETWEEN 1 AND 8000),
    kind varchar(32) NOT NULL CHECK (kind IN ('preference', 'fact', 'decision', 'procedure', 'context')),
    source varchar(1024) NOT NULL,
    revision bigint NOT NULL DEFAULT nextval('tuai.codex_memory_revision'),
    embedding_profile varchar(64) NOT NULL,
    embedding public.vector(${embeddingDimensions}) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (namespace, key)
);

CREATE INDEX codex_memory_list_idx ON codex_memory(namespace, kind, updated_at DESC, key);
CREATE INDEX codex_memory_embedding_idx ON codex_memory USING hnsw (embedding public.vector_cosine_ops);
