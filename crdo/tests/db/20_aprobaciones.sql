-- Aprobación humana ligada al contenido exacto: hash + vencimiento + TOTP de un solo uso + consumo único
\set ON_ERROR_STOP 1
\set u1 '''00000000-0000-0000-0000-000000000001'''
\set u2 '''00000000-0000-0000-0000-000000000002'''

-- Encender agentes (requiere TOTP del dueño u2) para que el worker tome tareas de agentes
SELECT t.code(:u2) AS c2 \gset
SET ROLE crdo_app;
SELECT t.ok(sys.set_agents_enabled(:u2, true, '123') = 'invalid_code', 'encender agentes con código inválido: rechazado');
SELECT t.ok(sys.set_agents_enabled(:u2, true, :'c2') = 'enabled', 'encender agentes con TOTP: aceptado');

-- Tarea R3 creada por un agente
INSERT INTO ops.tasks (id, requested_by_agent, kind, title, payload, risk) VALUES
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'youtube.publish',
   'Publicar video A', '{"video_id": "A", "title": "Hola mundo", "visibility": "public"}', 'R3'),
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1', 'youtube.publish',
   'Publicar video B', '{"video_id": "B", "title": "Segundo", "visibility": "public"}', 'R3'),
  ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000a1', 'youtube.publish',
   'Publicar video C', '{"video_id": "C"}', 'R3');

SELECT t.ok((SELECT payload_hash = sha256(convert_to('{"title": "Hola mundo", "video_id": "A", "visibility": "public"}', 'UTF8'))
               FROM ops.tasks WHERE id = '10000000-0000-0000-0000-000000000001'),
            'hash = sha256 del JSON canónico (claves ordenadas por jsonb)');
SELECT t.must_fail($$UPDATE ops.tasks SET payload = '{"video_id": "A", "title": "OTRO"}' WHERE id = '10000000-0000-0000-0000-000000000001'$$,
                   'app no puede modificar el contenido de una tarea');
SELECT t.must_fail($$SELECT ops.enqueue_job('10000000-0000-0000-0000-000000000001', 'publish', 'youtube.publish', 'pub-A', true)$$,
                   'tarea R3 sin aprobación no puede encolarse');

SELECT ops.request_approval('10000000-0000-0000-0000-000000000001', 'Publicar "Hola mundo" como público') AS ap_a \gset
SELECT ops.request_approval('10000000-0000-0000-0000-000000000002', 'Publicar "Segundo" como público') AS ap_b \gset
SELECT ops.request_approval('10000000-0000-0000-0000-000000000003', 'Publicar C', interval '1 second') AS ap_c \gset
SELECT t.ok((SELECT expires_at <= requested_at + interval '24 hours' FROM ops.approvals WHERE id = :'ap_a'),
            'vencimiento por defecto: 24 h');
SELECT encode(payload_hash, 'hex') AS hash_a FROM ops.approvals WHERE id = :'ap_a' \gset
SELECT encode(payload_hash, 'hex') AS hash_b FROM ops.approvals WHERE id = :'ap_b' \gset
RESET ROLE;
SELECT t.code(:u1) AS c1 \gset
SET ROLE crdo_app;

SELECT t.ok(ops.approve(:'ap_a', :u1, :'c1', decode(:'hash_b', 'hex')) = 'hash_mismatch',
            'aprobar mostrando OTRO contenido (hash distinto): rechazado');
SELECT t.ok(ops.approve(:'ap_a', '00000000-0000-0000-0000-000000000005', :'c1', decode(:'hash_a', 'hex')) = 'forbidden',
            'usuario viewer no puede aprobar');
SELECT t.ok(ops.approve(:'ap_a', :u1, '999999', decode(:'hash_a', 'hex')) IN ('invalid_code'),
            'código TOTP incorrecto: rechazado');
SELECT t.ok(ops.approve(:'ap_a', :u1, :'c1', decode(:'hash_a', 'hex')) = 'approved',
            'hash correcto + TOTP correcto: aprobado');
SELECT t.ok(ops.approve(:'ap_b', :u1, :'c1', decode(:'hash_b', 'hex')) = 'invalid_code',
            'el MISMO código no sirve para una segunda aprobación (un solo uso)');
SELECT t.ok(ops.approve(:'ap_a', :u1, :'c1', decode(:'hash_a', 'hex')) = 'not_pending',
            'una aprobación no se puede aprobar dos veces');
SELECT pg_sleep(1.2);
SELECT t.ok(ops.approve(:'ap_c', :u1, '000000', (SELECT payload_hash FROM ops.approvals WHERE id = :'ap_c')) = 'expired',
            'aprobación vencida: rechazada');

-- Ejecución: encolado idempotente, consumo único de la aprobación
SELECT ops.enqueue_job('10000000-0000-0000-0000-000000000001', 'publish', 'youtube.publish', 'pub-A', true) AS job_a \gset
SELECT t.ok(ops.enqueue_job('10000000-0000-0000-0000-000000000001', 'publish', 'youtube.publish', 'pub-A', true) = :job_a,
            'encolar dos veces con la misma clave devuelve el mismo trabajo');
SELECT t.ok((SELECT max_attempts = 1 FROM ops.jobs WHERE id = :job_a), 'efecto externo: 1 solo intento');

SELECT job_id AS cj, lease_token AS tok FROM ops.claim_job('w1', 'vps', ARRAY['publish']) \gset
SELECT t.ok(:cj = :job_a, 'el worker toma el trabajo de publicación');
SELECT t.ok(ops.begin_external_effect(:job_a, gen_random_uuid()) = 'lease_lost', 'token falso: no puede iniciar efecto');
SELECT t.ok(ops.begin_external_effect(:job_a, :'tok') = 'ok', 'token válido + aprobación vigente: efecto autorizado');
SELECT t.ok((SELECT status = 'consumed' FROM ops.approvals WHERE id = :'ap_a'), 'la aprobación queda consumida');
SELECT t.ok(ops.begin_external_effect(:job_a, :'tok') = 'already_started', 'el efecto no puede iniciarse dos veces');
SELECT t.ok(ops.complete_job(:job_a, :'tok', '{"youtube_id": "simulado"}'), 'trabajo completado');
RESET ROLE;

-- Defensa en profundidad: si alguien con acceso total alterara el contenido tras aprobar, se detecta al ejecutar
SELECT t.code(:u1, 1) AS c1b \gset
SET ROLE crdo_app;
SELECT t.ok(ops.approve(:'ap_b', :u1, :'c1b', decode(:'hash_b', 'hex')) = 'approved', 'aprobación B (código siguiente)');
SELECT ops.enqueue_job('10000000-0000-0000-0000-000000000002', 'publish', 'youtube.publish', 'pub-B', true) AS job_b \gset
RESET ROLE;
ALTER TABLE ops.tasks DISABLE TRIGGER tasks_hash;
UPDATE ops.tasks SET payload = '{"video_id": "B", "title": "ALTERADO"}',
                     payload_hash = sha256('ALTERADO'::bytea) WHERE id = '10000000-0000-0000-0000-000000000002';
ALTER TABLE ops.tasks ENABLE TRIGGER tasks_hash;
SET ROLE crdo_app;
SELECT job_id AS cjb, lease_token AS tokb FROM ops.claim_job('w1', 'vps', ARRAY['publish']) \gset
SELECT t.ok(ops.begin_external_effect(:job_b, :'tokb') = 'hash_mismatch',
            'contenido alterado después de aprobar: la ejecución se bloquea');
RESET ROLE;
