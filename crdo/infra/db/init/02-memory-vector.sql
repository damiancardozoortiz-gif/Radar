-- C.R.D.O. — Memoria vectorial (BORRADOR, Etapa 1)
-- Requiere la extensión pgvector (incluida en la imagen pgvector/pgvector:pg16).
-- La dimensión 1024 es provisional: se ajusta al modelo de embeddings que se elija en Etapa 2.

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE memory.chunks (
  id           bigserial PRIMARY KEY,
  document_id  uuid NOT NULL REFERENCES memory.documents(id) ON DELETE CASCADE,
  ordinal      int NOT NULL,
  content      text NOT NULL,
  embedding    vector(1024),
  embed_model  text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (document_id, ordinal)
);

CREATE INDEX chunks_embedding_hnsw ON memory.chunks
  USING hnsw (embedding vector_cosine_ops);

-- Búsqueda de texto completo como complemento (español)
ALTER TABLE memory.chunks
  ADD COLUMN tsv tsvector GENERATED ALWAYS AS (to_tsvector('spanish', content)) STORED;
CREATE INDEX chunks_tsv_idx ON memory.chunks USING gin (tsv);

GRANT SELECT, INSERT, UPDATE, DELETE ON memory.chunks TO crdo_app;
GRANT USAGE, SELECT ON SEQUENCE memory.chunks_id_seq TO crdo_app;
GRANT SELECT ON memory.chunks TO crdo_readonly, crdo_backup;

COMMIT;
