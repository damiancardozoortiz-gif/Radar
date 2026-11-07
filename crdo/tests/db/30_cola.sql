-- Cola: render solo externo, kill switch, recuperación de trabajos interrumpidos, fencing, reintentos
\set ON_ERROR_STOP 1
SET ROLE crdo_app;

-- El VPS nunca toma trabajos de render
SELECT ops.enqueue_job(NULL, 'render', 'video.render', 'render-1') AS rj \gset
SELECT t.ok(NOT EXISTS (SELECT 1 FROM ops.claim_job('vps-1', 'vps')) , 'worker del VPS no recibe render');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM ops.claim_job('vps-1', 'vps', ARRAY['render'])), 'ni pidiéndolo explícitamente');
SELECT t.ok((SELECT job_id FROM ops.claim_job('ext-gpu-1', 'external')) = :rj, 'worker externo sí recibe render');

-- Kill switch: con agentes apagados, sus trabajos no se toman (los del sistema sí)
INSERT INTO ops.tasks (id, requested_by_agent, kind, title, risk) VALUES
  ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-0000000000a1', 'research.note', 'Nota', 'R1');
SELECT t.ok(sys.set_agents_enabled('00000000-0000-0000-0000-000000000002', false) = 'stopped', 'apagar agentes no exige código');
SELECT ops.enqueue_job('20000000-0000-0000-0000-000000000001', 'default', 'research.note', 'nota-1') AS nj \gset
SELECT t.ok(NOT EXISTS (SELECT 1 FROM ops.claim_job('vps-1', 'vps')), 'kill switch: trabajo de agente no se ejecuta');
RESET ROLE;
UPDATE sys.settings SET value = 'true' WHERE key = 'agents_enabled';
SET ROLE crdo_app;

-- Recuperación: worker muere (lease vence) -> reencolado; el worker viejo ya no puede cerrarlo
SELECT job_id AS j1, lease_token AS t1 FROM ops.claim_job('vps-viejo', 'vps') \gset
SELECT t.ok(:j1 = :nj, 'worker toma el trabajo');
SELECT t.ok(ops.heartbeat(:j1, :'t1'), 'heartbeat renueva el lease');
RESET ROLE;
UPDATE ops.jobs SET lease_expires_at = now() - interval '1 second' WHERE id = :j1;  -- simula caída
SET ROLE crdo_app;
SELECT t.ok(NOT ops.heartbeat(:j1, :'t1'), 'heartbeat con lease vencido: el worker sabe que perdió el trabajo');
SELECT t.ok((SELECT requeued FROM ops.reap_expired()) = 1, 'el recolector reencola el trabajo huérfano');
SELECT job_id AS j2, lease_token AS t2 FROM ops.claim_job('vps-nuevo', 'vps') \gset
SELECT t.ok(:j2 = :j1 AND :'t2' <> :'t1', 'otro worker lo toma con un token nuevo');
SELECT t.ok(NOT ops.complete_job(:j1, :'t1'), 'el worker viejo (token viejo) NO puede cerrarlo: sin duplicado');
SELECT t.ok(ops.complete_job(:j2, :'t2'), 'el worker nuevo lo cierra');
SELECT t.ok((SELECT attempts = 2 AND status = 'succeeded' FROM ops.jobs WHERE id = :j1), 'estado final coherente');

-- Efecto externo interrumpido: NO se reintenta solo
INSERT INTO ops.tasks (id, requested_by_user, kind, title, risk) VALUES
  ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'drive.upload', 'Subir', 'R1');
SELECT ops.enqueue_job('20000000-0000-0000-0000-000000000002', 'io', 'drive.upload', 'up-1', true) AS ej \gset
SELECT job_id AS je, lease_token AS te FROM ops.claim_job('vps-1', 'vps', ARRAY['io']) \gset
SELECT t.ok(ops.begin_external_effect(:je, :'te') = 'ok', 'efecto externo iniciado');
RESET ROLE;
UPDATE ops.jobs SET lease_expires_at = now() - interval '1 second' WHERE id = :je;
SET ROLE crdo_app;
SELECT t.ok((SELECT needs_review FROM ops.reap_expired()) = 1, 'caída tras iniciar efecto -> needs_review');
SELECT t.ok((SELECT status = 'needs_review' FROM ops.jobs WHERE id = :je), 'queda para decisión humana, no se repite');

-- Reintentos con espera exponencial y agotamiento
SELECT ops.enqueue_job(NULL, 'default', 'flaky', 'flaky-1') AS fj \gset
SELECT job_id AS f1, lease_token AS ft1 FROM ops.claim_job('vps-1', 'vps') \gset
SELECT t.ok(ops.fail_job(:f1, :'ft1', 'error temporal') = 'requeued', 'falla temporal -> reencolado');
SELECT t.ok((SELECT run_after > now() + interval '20 seconds' FROM ops.jobs WHERE id = :fj), 'con espera antes del reintento');
SELECT t.ok(ops.fail_job(:f1, :'ft1', 'otra vez') = 'lease_lost', 'no se puede fallar dos veces con el mismo token');
RESET ROLE;
UPDATE ops.jobs SET run_after = now(), attempts = max_attempts - 1 WHERE id = :fj;
SET ROLE crdo_app;
SELECT job_id AS f2, lease_token AS ft2 FROM ops.claim_job('vps-1', 'vps') \gset
SELECT t.ok(ops.fail_job(:f2, :'ft2', 'error final') = 'dead', 'sin intentos restantes -> dead');

-- Comandos administrativos desde el panel
RESET ROLE;
SELECT t.code('00000000-0000-0000-0000-000000000004') AS c4 \gset
SET ROLE crdo_app;
SELECT t.ok((ops.request_admin_command('00000000-0000-0000-0000-000000000004', '111111', 'backup_now', 'prod')).result = 'invalid_code',
            'comando admin con código malo: rechazado');
SELECT t.ok((ops.request_admin_command('00000000-0000-0000-0000-000000000004', :'c4', 'backup_now', 'prod')).result = 'queued',
            'comando admin con TOTP: encolado');
SELECT t.must_fail($$INSERT INTO ops.admin_commands (command, environment, requested_by, totp_step) VALUES ('deploy','prod','00000000-0000-0000-0000-000000000004', 1)$$,
                   'app no puede insertar comandos sin TOTP');
RESET ROLE;
SET ROLE crdo_ops;
SELECT t.ok((SELECT command FROM ops.claim_admin_command()) = 'backup_now', 'crdo-opsd toma el comando');
SELECT t.must_fail($$SELECT * FROM auth.users$$, 'crdo-opsd no ve usuarios');
RESET ROLE;
