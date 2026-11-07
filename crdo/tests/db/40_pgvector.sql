-- pgvector sobre PostgreSQL 16: extensión, inserción, índice HNSW, calidad de búsqueda, texto completo en español
\set ON_ERROR_STOP 1

SELECT t.ok(current_setting('server_version_num')::int / 10000 = 16, 'PostgreSQL 16');
SELECT extversion AS vver FROM pg_extension WHERE extname = 'vector' \gset
SELECT t.ok(:'vver' IS NOT NULL, 'extensión vector instalada (versión ' || :'vver' || ')');

-- 2.000 fragmentos con embeddings de 1024 dimensiones (aleatorios con semilla fija)
SELECT setseed(0.42);
INSERT INTO memory.documents (id, title, source, body)
SELECT ('30000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'Doc ' || g, 'prueba', NULL
  FROM generate_series(1, 200) g;

SET ROLE crdo_app;
INSERT INTO memory.chunks (document_id, ordinal, content, embedding, embed_model)
SELECT ('30000000-0000-0000-0000-' || lpad((1 + (g - 1) / 10)::text, 12, '0'))::uuid,
       (g - 1) % 10,
       CASE WHEN g = 7 THEN 'Los videos sobre astronomía tienen mayor retención en la audiencia'
            ELSE 'Fragmento de prueba número ' || g END,
       (SELECT array_agg(random()::real) FROM generate_series(1, 1024) WHERE g > 0)::vector,
       'prueba-1024'
  FROM generate_series(1, 2000) g;
RESET ROLE;
ANALYZE memory.chunks;

SELECT t.ok((SELECT count(*) FROM memory.chunks) = 2000, '2.000 fragmentos insertados por la app');
SELECT t.must_fail($$INSERT INTO memory.chunks (document_id, ordinal, content, embedding) VALUES ('30000000-0000-0000-0000-000000000001', 99, 'x', '[1,2,3]')$$,
                   'dimensión incorrecta rechazada');

-- El planificador usa el índice HNSW
SET enable_seqscan = off;
SELECT t.ok(t.explain_text($$SELECT id FROM memory.chunks ORDER BY embedding <=> (SELECT embedding FROM memory.chunks WHERE id = 1) LIMIT 10$$)
            LIKE '%chunks_embedding_hnsw%', 'la búsqueda usa el índice HNSW');
RESET enable_seqscan;

-- Calidad (recall@10) del índice frente a búsqueda exacta, sobre 20 consultas
SET hnsw.ef_search = 100;
CREATE TEMP TABLE q AS SELECT id, embedding FROM memory.chunks WHERE id % 100 = 3;
CREATE TEMP TABLE aprox AS
  SELECT q.id AS qid, c.id FROM q, LATERAL (SELECT id FROM memory.chunks ORDER BY embedding <=> q.embedding LIMIT 10) c;
SET enable_indexscan = off;
CREATE TEMP TABLE exacto AS
  SELECT q.id AS qid, c.id FROM q, LATERAL (SELECT id FROM memory.chunks ORDER BY embedding <=> q.embedding LIMIT 10) c;
RESET enable_indexscan;
SELECT round(count(*)::numeric / (SELECT count(*) FROM exacto), 3) AS recall
  FROM aprox JOIN exacto USING (qid, id) \gset
SELECT t.ok(:recall >= 0.90, 'recall@10 del índice HNSW = ' || :recall || ' (mínimo 0,90)');
SELECT t.ok((SELECT bool_and(c.id = q.id) FROM q, LATERAL (SELECT id FROM memory.chunks ORDER BY embedding <=> q.embedding LIMIT 1) c),
            'cada vector se encuentra a sí mismo como vecino más cercano');

-- Búsqueda de texto completo en español (complemento de la vectorial)
SELECT t.ok((SELECT count(*) FROM memory.chunks WHERE tsv @@ plainto_tsquery('spanish', 'retención astronomía')) = 1,
            'texto completo en español (raíces: retención/astronomía)');
