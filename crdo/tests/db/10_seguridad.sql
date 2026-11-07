-- Permisos mínimos, auditoría append-only, R4, TOTP (vectores oficiales RFC 6238)
\set ON_ERROR_STOP 1

-- RFC 6238, apéndice B (SHA1, secreto ASCII "12345678901234567890", 8 dígitos)
SELECT t.ok(auth.hotp(convert_to('12345678901234567890','UTF8'), 1, 8)        = '94287082', 'RFC 6238 T=59');
SELECT t.ok(auth.hotp(convert_to('12345678901234567890','UTF8'), 37037036, 8) = '07081804', 'RFC 6238 T=1111111109');
SELECT t.ok(auth.hotp(convert_to('12345678901234567890','UTF8'), 41152263, 8) = '89005924', 'RFC 6238 T=1234567890');
SELECT t.ok(auth.hotp(convert_to('12345678901234567890','UTF8'), 66666666, 8) = '69279037', 'RFC 6238 T=2000000000');

SET ROLE crdo_app;
INSERT INTO audit.events (actor_type, action, outcome) VALUES ('system', 'prueba.a', 'success');
INSERT INTO audit.events (actor_type, action, outcome) VALUES ('system', 'prueba.b', 'success');
SELECT t.must_fail($$UPDATE audit.events SET action = 'x'$$, 'app no puede editar auditoría');
SELECT t.must_fail($$DELETE FROM audit.events$$,            'app no puede borrar auditoría');
SELECT t.must_fail($$SELECT * FROM auth.totp_secrets$$,      'app no puede leer secretos TOTP');
SELECT t.must_fail($$SELECT auth.hotp('\x00'::bytea, 1)$$,   'app no puede calcular códigos TOTP');
SELECT t.must_fail($$UPDATE auth.users SET totp_last_step = 0$$, 'app no puede reiniciar anti-repetición TOTP');
SELECT t.must_fail($$UPDATE auth.users SET role = 'owner'$$, 'app no puede cambiar roles');
SELECT t.must_fail($$UPDATE org.permissions SET risk = 'R0'$$, 'app no puede rebajar el riesgo de un permiso');
SELECT t.must_fail($$INSERT INTO org.agent_permissions VALUES ('00000000-0000-0000-0000-0000000000a1','memory.read','00000000-0000-0000-0000-000000000001')$$,
                   'app no puede otorgar permisos directamente');
SELECT t.must_fail($$UPDATE sys.settings SET value = 'true' WHERE key = 'agents_enabled'$$,
                   'app no puede encender agentes sin TOTP');
SELECT t.must_fail($$INSERT INTO ops.approvals (task_id, payload_hash, summary, expires_at) VALUES (gen_random_uuid(), '\x00', 's', now() + interval '1 hour')$$,
                   'app no puede fabricar aprobaciones');
SELECT t.must_fail($$INSERT INTO ops.jobs (queue, kind, idempotency_key) VALUES ('default','x','x')$$,
                   'app no puede insertar trabajos sin pasar por enqueue_job');
SELECT t.must_fail($$INSERT INTO ops.tasks (requested_by_agent, kind, title, risk) VALUES ('00000000-0000-0000-0000-0000000000a1','x','borrar canal','R4')$$,
                   'agente no puede pedir tarea R4');
SELECT t.ok(org.grant_permission('00000000-0000-0000-0000-000000000001', '000000', '00000000-0000-0000-0000-0000000000a1', 'youtube.delete') = 'forbidden_r4',
            'permiso R4 no otorgable ni con función');
SELECT t.ok(org.grant_permission('00000000-0000-0000-0000-000000000005', '000000', '00000000-0000-0000-0000-0000000000a1', 'memory.read') = 'forbidden',
            'un usuario viewer no puede otorgar permisos');
RESET ROLE;

SELECT t.must_fail($$INSERT INTO org.agent_permissions VALUES ('00000000-0000-0000-0000-0000000000a1','youtube.delete','00000000-0000-0000-0000-000000000001')$$,
                   'R4 no otorgable ni siquiera por el superusuario (trigger)');

-- Cadena de auditoría
SELECT t.ok(audit.verify_chain() IS NULL, 'cadena de auditoría íntegra');
SET TimeZone = 'America/Argentina/Buenos_Aires';
SELECT t.ok(audit.verify_chain() IS NULL, 'cadena íntegra con otra zona horaria (hash independiente de TZ)');
RESET TimeZone;
BEGIN;
UPDATE audit.events SET detail = '{"alterado": true}' WHERE action = 'prueba.a';
SELECT t.ok(audit.verify_chain() = (SELECT id FROM audit.events WHERE action = 'prueba.a'),
            'una alteración directa (incluso de superusuario) se detecta');
ROLLBACK;

-- Bloqueo por fuerza bruta: 5 códigos malos bloquean 15 min, incluso al código correcto
SET ROLE crdo_app;
SELECT auth.verify_totp('00000000-0000-0000-0000-000000000003', '000001') FROM generate_series(1, 5);
RESET ROLE;
SELECT t.ok((SELECT totp_locked_until > now() FROM auth.users WHERE id = '00000000-0000-0000-0000-000000000003'),
            '5 fallos TOTP bloquean al usuario');
SELECT t.code('00000000-0000-0000-0000-000000000003') AS c3 \gset
SET ROLE crdo_app;
SELECT t.ok(auth.verify_totp('00000000-0000-0000-0000-000000000003', :'c3') IS NULL,
            'usuario bloqueado: ni el código correcto pasa');
RESET ROLE;
