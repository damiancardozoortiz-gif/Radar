-- Integridad de registros y permisos por rol. Corre al FINAL: verifica también que toda la actividad de las
-- pruebas anteriores dejó una cadena de auditoría íntegra.
\set ON_ERROR_STOP 1

-- Auditoría
SELECT t.ok((SELECT count(*) > 50 FROM audit.events), 'la actividad de las pruebas anteriores quedó auditada');
SELECT t.ok((SELECT bool_and(hash IS NOT NULL) FROM audit.events), 'todos los eventos tienen hash');
SELECT t.ok((SELECT max(id) - min(id) + 1 = count(*) FROM audit.events), 'numeración de auditoría sin huecos');
SELECT t.ok(audit.verify_chain() IS NULL, 'cadena de auditoría íntegra tras toda la batería de pruebas');
SET ROLE crdo_app;
INSERT INTO audit.events (actor_type, action, outcome, prev_hash, hash)
VALUES ('system', 'prueba.hash_forjado', 'success', '\x00', '\x00');
RESET ROLE;
SELECT t.ok((SELECT hash <> '\x00' AND prev_hash <> '\x00' FROM audit.events WHERE action = 'prueba.hash_forjado'),
            'un hash forjado por la app se ignora: lo recalcula el trigger');
SELECT t.ok(audit.verify_chain() IS NULL, 'la cadena sigue íntegra después del intento de forjar');
BEGIN;
DELETE FROM audit.events WHERE id = (SELECT id FROM audit.events ORDER BY id OFFSET 5 LIMIT 1);
SELECT t.ok(audit.verify_chain() IS NOT NULL, 'borrar un evento intermedio (superusuario) rompe la cadena y se detecta');
ROLLBACK;

-- Registros de solo agregar (append-only) para la app
SET ROLE crdo_app;
SELECT t.must_fail($$UPDATE finance.usage_events SET cost_usd_est = 0$$, 'consumo: no se puede editar');
SELECT t.must_fail($$INSERT INTO finance.meter_readings (metric, value, source) VALUES ('r2_storage_gb', 0, 'x')$$,
                   'medidores: solo por función, no por SQL directo');
SELECT t.must_fail($$DELETE FROM finance.meter_readings$$, 'medidores: no se borran');
SELECT t.must_fail($$UPDATE ops.jobs SET status = 'succeeded'$$, 'trabajos: el estado no se edita a mano');
SELECT t.must_fail($$DELETE FROM ops.tasks$$, 'tareas: no se borran');
SELECT t.must_fail($$UPDATE ops.approvals SET status = 'approved'$$, 'aprobaciones: no se editan');
SELECT t.must_fail($$UPDATE ops.admin_commands SET status = 'succeeded'$$, 'comandos admin: la app no puede marcar resultados');
SELECT t.must_fail($$UPDATE sys.backup_runs SET status = 'succeeded'$$, 'registro de respaldos: no se edita');
SELECT t.must_fail($$UPDATE sys.deployments SET status = 'succeeded'$$, 'registro de despliegues: no se edita');
RESET ROLE;

-- Rol de solo lectura (reportes, futuro MCP de lectura)
SET ROLE crdo_readonly;
SELECT t.must_fail($$SELECT * FROM auth.users$$,          'readonly no ve usuarios');
SELECT t.must_fail($$SELECT * FROM audit.events$$,        'readonly no ve la auditoría completa');
SELECT t.must_fail($$SELECT * FROM integrations.connections$$, 'readonly no ve conexiones');
SELECT t.must_fail($$INSERT INTO memory.facts (subject, statement) VALUES ('x', 'y')$$, 'readonly no escribe');
SELECT t.ok((SELECT count(*) >= 0 FROM finance.cost_items), 'readonly sí lee el presupuesto');
RESET ROLE;

-- Rol de respaldo: lee todo, no escribe nada
SET ROLE crdo_backup;
SELECT t.ok((SELECT count(*) > 0 FROM audit.events), 'backup lee la auditoría (para el volcado)');
SELECT t.must_fail($$INSERT INTO org.departments (code, name, reports_to) VALUES ('x', 'x', 'owner')$$, 'backup no escribe');
SELECT t.must_fail($$SELECT * FROM auth.totp_secrets$$, 'backup no lee secretos TOTP');
SELECT t.must_fail($$SELECT * FROM auth.recovery_codes$$, 'backup no lee códigos de recuperación');
SELECT t.must_fail($$SELECT password_hash FROM auth.users$$, 'backup no lee hashes de contraseña');
RESET ROLE;

-- Rol del servicio del host
SET ROLE crdo_ops;
SELECT t.must_fail($$SELECT * FROM finance.cost_items$$, 'crdo-opsd no lee el detalle de costos (solo el estado)');
SELECT t.must_fail($$SELECT * FROM ops.tasks$$,          'crdo-opsd no ve tareas de negocio');
SELECT t.must_fail($$SELECT finance.set_budget_policy('00000000-0000-0000-0000-000000000001', '000000', 'infra', 99, 15, 'intento desde ops')$$,
                   'crdo-opsd no puede cambiar límites');
SELECT t.ok((SELECT level IS NOT NULL FROM finance.budget_status('infra')), 'crdo-opsd sí consulta el estado del presupuesto');
RESET ROLE;

-- Integridad referencial de negocio
INSERT INTO content.projects (id, title) VALUES ('50000000-0000-0000-0000-000000000001', 'Proyecto de prueba');
SELECT t.must_fail($$INSERT INTO content.videos (project_id, title, published_at) VALUES ('50000000-0000-0000-0000-000000000001', 'x', now())$$,
                   'un video publicado exige una aprobación asociada');
INSERT INTO ops.tasks (id, requested_by_user, kind, title, risk) VALUES
  ('50000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'x', 'x', 'R3');
SELECT t.must_fail($$INSERT INTO ops.approvals (task_id, payload_hash, summary, requested_at, expires_at) VALUES ('50000000-0000-0000-0000-000000000002', '\x00', 's', now(), now() + interval '100 hours')$$,
                   'una aprobación no puede durar más de 72 h (ni insertada por superusuario)');
SELECT t.ok(audit.verify_chain() IS NULL, 'cierre: cadena de auditoría íntegra');
