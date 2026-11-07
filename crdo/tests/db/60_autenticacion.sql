-- Autenticación: alta, inicio de sesión (contraseña + TOTP), sesiones, bloqueo, recuperación por teléfono perdido,
-- cambio de contraseña, tokens de agentes y verificación de permisos.
-- Los códigos TOTP se calculan justo antes de usarse (desfase 0 y +1) para no depender del borde de 30 s.
\set ON_ERROR_STOP 1

SELECT t.ok(auth.base32(convert_to('12345678901234567890', 'UTF8')) = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
            'base32 del secreto RFC 6238 correcto (compatible con apps TOTP)');

-- Alta (solo rol dueño de la base)
SELECT user_id AS ua, otpauth_uri AS uri, array_length(recovery_codes, 1) AS nrc
  FROM auth.create_user('ana@crdo.test', 'Ana', 'owner', 'contraseña-larga-1') \gset
SELECT t.ok(:'uri' LIKE 'otpauth://totp/CRDO:ana@crdo.test?secret=%&issuer=CRDO%', 'alta devuelve URI otpauth para la app TOTP');
SELECT t.ok(:nrc = 10, 'alta devuelve 10 códigos de recuperación');
SELECT t.ok((SELECT password_hash LIKE '$2a$12$%' FROM auth.users WHERE id = :'ua'), 'contraseña guardada con bcrypt costo 12');
SELECT t.ok((SELECT count(*) = 10 AND bool_and(octet_length(code_hash) = 32) FROM auth.recovery_codes WHERE user_id = :'ua'),
            'los códigos de recuperación se guardan solo como sha256');
SELECT t.must_fail($$SELECT auth.create_user('x@crdo.test', 'X', 'owner', 'corta')$$, 'contraseña de menos de 12 caracteres rechazada');
SELECT user_id AS ub FROM auth.create_user('beto@crdo.test', 'Beto', 'owner', 'contraseña-larga-2') \gset
SELECT user_id AS uc, recovery_codes[1] AS rc1, recovery_codes[2] AS rc2
  FROM auth.create_user('caro@crdo.test', 'Caro', 'owner', 'contraseña-larga-3') \gset
SELECT user_id AS ud FROM auth.create_user('dani@crdo.test', 'Dani', 'owner', 'contraseña-larga-4') \gset
SELECT user_id AS ue FROM auth.create_user('eva@crdo.test', 'Eva', 'owner', 'contraseña-larga-5') \gset

-- La app no ve material secreto
SET ROLE crdo_app;
SELECT t.must_fail($$SELECT password_hash FROM auth.users$$, 'app no puede leer hashes de contraseña');
SELECT t.must_fail($$SELECT * FROM auth.sessions$$,         'app no puede leer sesiones');
SELECT t.must_fail($$SELECT token_hash FROM auth.api_tokens$$, 'app no puede leer hashes de tokens de agentes');
SELECT t.must_fail($$SELECT * FROM auth.recovery_codes$$,   'app no puede leer códigos de recuperación');
SELECT t.must_fail($$SELECT auth.create_user('y@crdo.test', 'Y', 'owner', 'contraseña-larga-9')$$, 'app no puede crear usuarios');
SELECT t.must_fail($$UPDATE auth.users SET password_hash = 'x'$$, 'app no puede escribir hashes de contraseña');
SELECT t.ok((SELECT count(*) > 0 FROM auth.users WHERE email = 'ana@crdo.test'), 'app sí puede leer datos no secretos del usuario');

-- Inicio de sesión
SELECT t.ok((auth.login('ana@crdo.test', 'mala', '000000')).result = 'invalid_credentials', 'contraseña incorrecta: rechazado');
SELECT t.ok((auth.login('nadie@crdo.test', 'contraseña-larga-1', '000000')).result = 'invalid_credentials',
            'email inexistente: mismo mensaje (no revela qué usuarios existen)');
SELECT t.ok((auth.login('ana@crdo.test', 'contraseña-larga-1', '000000')).result = 'invalid_credentials',
            'contraseña correcta + TOTP incorrecto: rechazado');
RESET ROLE; SELECT t.code(:'ua') AS ca0 \gset
SET ROLE crdo_app;
SELECT (l).result AS lr, (l).token AS tok FROM (SELECT auth.login('ana@crdo.test', 'contraseña-larga-1', :'ca0', '10.0.0.5', 'prueba') l) x \gset
SELECT t.ok(:'lr' = 'ok' AND :'tok' LIKE 'crdo_se_%', 'contraseña + TOTP correctos: sesión creada');
SELECT t.ok((SELECT user_id = :'ua' AND role = 'owner' AND kind = 'normal' FROM auth.validate_session(:'tok')), 'la sesión es válida');
SELECT t.ok((auth.login('ana@crdo.test', 'contraseña-larga-1', :'ca0')).result = 'invalid_credentials',
            'el mismo código TOTP no sirve para un segundo inicio de sesión');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_session('crdo_se_falso')), 'token falso: sin sesión');
RESET ROLE;
SELECT t.ok((SELECT token_hash = sha256(convert_to(:'tok', 'UTF8')) FROM auth.sessions WHERE user_id = :'ua' ORDER BY created_at DESC LIMIT 1),
            'la base guarda solo el sha256 del token de sesión');
SELECT t.ok((SELECT expires_at <= now() + interval '12 hours' FROM auth.sessions WHERE user_id = :'ua' ORDER BY created_at DESC LIMIT 1),
            'la sesión vence a las 12 h');
SET ROLE crdo_app;
SELECT t.ok(auth.logout(:'tok'), 'cierre de sesión');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_session(:'tok')), 'sesión cerrada: ya no es válida');

SET crdo.actor = 'agent';
SELECT t.ok((auth.login('ana@crdo.test', 'contraseña-larga-1', '000000')).result = 'agents_forbidden',
            'un agente no puede iniciar sesión como humano');
RESET crdo.actor;

-- Bloqueo por fuerza bruta de contraseña
SELECT (auth.login('beto@crdo.test', 'mala-' || g, '000000')).result FROM generate_series(1, 5) g;
RESET ROLE; SELECT t.code(:'ub') AS cb0 \gset
SET ROLE crdo_app;
SELECT t.ok((auth.login('beto@crdo.test', 'contraseña-larga-2', :'cb0')).result = 'invalid_credentials',
            '5 contraseñas malas bloquean 15 min: ni las credenciales correctas entran');
RESET ROLE;
SELECT t.ok((SELECT locked_until > now() FROM auth.users WHERE id = :'ub'), 'bloqueo registrado');
SELECT t.ok((SELECT count(*) >= 5 FROM audit.events WHERE action = 'auth.login_failed' AND actor_user = :'ub'),
            'cada intento fallido queda en la auditoría');
-- Sesión vencida
SELECT (auth.new_session(:'ub', 'normal', NULL, NULL)).token AS tokv \gset
UPDATE auth.sessions SET expires_at = now() - interval '1 second' WHERE token_hash = sha256(convert_to(:'tokv', 'UTF8'));
SET ROLE crdo_app;
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_session(:'tokv')), 'sesión vencida: no es válida');

-- Cambio de contraseña (usuario Ana; el código siguiente)
RESET ROLE; SELECT t.code(:'ua', 1) AS ca1 \gset
SET ROLE crdo_app;
SELECT t.ok(auth.change_password(:'ua', 'mala', 'nueva-contraseña-larga', :'ca1') = 'invalid_credentials', 'cambio con contraseña actual mala: rechazado');
SELECT t.ok(auth.change_password(:'ua', 'contraseña-larga-1', 'corta', :'ca1') = 'weak_password', 'contraseña nueva débil: rechazada');
SELECT t.ok(auth.change_password(:'ua', 'contraseña-larga-1', 'nueva-contraseña-larga', :'ca1') = 'changed', 'cambio de contraseña con TOTP');
RESET ROLE;
SELECT t.ok((SELECT crypt('nueva-contraseña-larga', password_hash) = password_hash FROM auth.users WHERE id = :'ua'), 'la contraseña nueva funciona');

-- Teléfono perdido: código de recuperación
SELECT (s).token AS tokc FROM (SELECT auth.new_session(:'uc', 'normal', NULL, NULL) s) x \gset
SET ROLE crdo_app;
SELECT t.ok((auth.login_with_recovery('caro@crdo.test', 'contraseña-larga-3', 'aaaa-bbbb-cccc')).result = 'invalid_credentials',
            'código de recuperación falso: rechazado');
SELECT (r).result AS rr, (r).token AS tokr FROM (SELECT auth.login_with_recovery('caro@crdo.test', 'contraseña-larga-3', :'rc1') r) x \gset
SELECT t.ok(:'rr' = 'ok', 'contraseña + código de recuperación: acceso de recuperación');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_session(:'tokc')), 'todas las sesiones anteriores quedaron cerradas');
SELECT t.ok((SELECT kind = 'recovery' FROM auth.validate_session(:'tokr')), 'la sesión es de tipo recuperación (solo re-enrolar)');
SELECT t.ok((auth.login_with_recovery('caro@crdo.test', 'contraseña-larga-3', :'rc1')).result = 'invalid_credentials',
            'cada código de recuperación sirve una sola vez');
SELECT t.ok((auth.login('caro@crdo.test', 'contraseña-larga-3', '000000')).result = 'reenroll_required',
            'el TOTP del teléfono perdido ya no sirve: exige re-enrolar');
SELECT t.ok((auth.reenroll_totp(:'tok')).result = 'invalid_session', 'no se puede re-enrolar con una sesión cualquiera');
SELECT (e).result AS er, (e).totp_secret_base32 AS newsec, array_length((e).recovery_codes, 1) AS nrc2
  FROM (SELECT auth.reenroll_totp(:'tokr') e) x \gset
SELECT t.ok(:'er' = 'ok' AND length(:'newsec') = 32 AND :nrc2 = 10, 'TOTP nuevo enrolado y códigos de recuperación renovados');
RESET ROLE; SELECT t.code(:'uc') AS cc0 \gset
SET ROLE crdo_app;
SELECT t.ok((auth.login('caro@crdo.test', 'contraseña-larga-3', :'cc0')).result = 'ok', 'con el TOTP nuevo se vuelve a entrar');
SELECT t.ok((auth.login_with_recovery('caro@crdo.test', 'contraseña-larga-3', :'rc2')).result = 'invalid_credentials',
            'los códigos de recuperación viejos quedaron invalidados');
RESET ROLE;
SELECT t.ok((SELECT count(*) >= 2 FROM audit.events WHERE actor_user = :'uc' AND action IN ('auth.recovery_used', 'auth.totp_reenrolled')
               AND severity = 'high'), 'recuperación y re-enrolamiento auditados con severidad alta');

-- Cerrar todas las sesiones (dispositivo comprometido)
RESET ROLE; SELECT t.code(:'ue') AS ce0, t.code(:'ue', 1) AS ce1 \gset
SET ROLE crdo_app;
SELECT (auth.login('eva@crdo.test', 'contraseña-larga-5', :'ce0')).token AS toke \gset
SELECT t.ok(auth.revoke_all_sessions(:'ue', :'ce1') LIKE 'revoked:%', 'cerrar todas las sesiones con TOTP');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_session(:'toke')), 'la sesión quedó cerrada');

-- Tokens de agentes
RESET ROLE;
UPDATE org.agents SET is_enabled = true WHERE id = '00000000-0000-0000-0000-0000000000a1';
SELECT t.code(:'ud') AS cd0 \gset
SET ROLE crdo_app;
SELECT t.ok((auth.issue_agent_token(:'ud', :'cd0', '00000000-0000-0000-0000-0000000000a1', 'inv', ARRAY['youtube.delete'])).result = 'invalid_scope',
            'token de agente con alcance R4: rechazado');
SELECT t.ok((auth.issue_agent_token(:'ud', :'cd0', '00000000-0000-0000-0000-0000000000a1', 'inv', ARRAY['memory.read'], 365)).result = 'invalid_ttl',
            'token de agente de más de 90 días: rechazado');
SELECT (i).result AS ir, (i).token AS atok
  FROM (SELECT auth.issue_agent_token(:'ud', :'cd0', '00000000-0000-0000-0000-0000000000a1', 'inv', ARRAY['memory.read','task.create'], 30) i) x \gset
SELECT t.ok(:'ir' = 'ok' AND :'atok' LIKE 'crdo_ag_%', 'dueño emite token de agente con TOTP');
SELECT t.ok((SELECT agent_id = '00000000-0000-0000-0000-0000000000a1' AND scopes = ARRAY['memory.read','task.create']
               FROM auth.validate_agent_token(:'atok')), 'token de agente válido con sus alcances');
SET crdo.actor = 'agent';
SELECT t.ok((auth.issue_agent_token(:'ud', '000000', '00000000-0000-0000-0000-0000000000a1', 'x', ARRAY['memory.read'])).result = 'agents_forbidden',
            'un agente no puede emitirse tokens');
RESET crdo.actor;
RESET ROLE;
UPDATE org.agents SET is_enabled = false WHERE id = '00000000-0000-0000-0000-0000000000a1';
SET ROLE crdo_app;
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_agent_token(:'atok')), 'agente deshabilitado: su token deja de valer');
RESET ROLE;
UPDATE org.agents SET is_enabled = true WHERE id = '00000000-0000-0000-0000-0000000000a1';
SET ROLE crdo_app;
SELECT token_id AS atid FROM auth.validate_agent_token(:'atok') \gset
SELECT t.ok(auth.revoke_agent_token(:'ud', :'atid'), 'revocar token (sin TOTP: dirección segura)');
SELECT t.ok(NOT EXISTS (SELECT 1 FROM auth.validate_agent_token(:'atok')), 'token revocado: no vale');

-- Permisos de agentes
RESET ROLE;
INSERT INTO org.agent_permissions (agent_id, permission_code, granted_by, expires_at) VALUES
  ('00000000-0000-0000-0000-0000000000a1', 'memory.read', :'ud', NULL),
  ('00000000-0000-0000-0000-0000000000a1', 'analytics.read', :'ud', now() - interval '1 minute');
SET ROLE crdo_app;
SELECT t.ok(org.agent_has_permission('00000000-0000-0000-0000-0000000000a1', 'memory.read'), 'permiso vigente: sí');
SELECT t.ok(NOT org.agent_has_permission('00000000-0000-0000-0000-0000000000a1', 'analytics.read'), 'permiso vencido: no');
SELECT t.ok(NOT org.agent_has_permission('00000000-0000-0000-0000-0000000000a1', 'youtube.publish'), 'permiso no otorgado: no');
RESET ROLE;
UPDATE org.agents SET is_enabled = false WHERE id = '00000000-0000-0000-0000-0000000000a1';
SET ROLE crdo_app;
SELECT t.ok(NOT org.agent_has_permission('00000000-0000-0000-0000-0000000000a1', 'memory.read'), 'agente deshabilitado: ningún permiso');
RESET ROLE;
UPDATE org.agents SET is_enabled = true WHERE id = '00000000-0000-0000-0000-0000000000a1';
