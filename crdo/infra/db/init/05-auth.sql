-- C.R.D.O. — Autenticación (Etapa 1, v3.1)
-- Inicio de sesión humano (contraseña + TOTP), sesiones, códigos de recuperación (teléfono perdido),
-- tokens de agentes y verificación de permisos. Todo dentro de la base:
--   * la app nunca ve hashes de contraseña, hashes de tokens ni secretos TOTP;
--   * los tokens se entregan UNA vez y se guardan solo como sha256;
--   * las funciones devuelven códigos de resultado (no excepciones) para que los contadores de fallos persistan.
-- Contraseñas: bcrypt (pgcrypto) con costo configurable (12 por defecto). La v1 preveía Argon2id en la app;
-- se movió a la base para que la regla no dependa del código de la app. pgcrypto no ofrece Argon2.

BEGIN;

ALTER TABLE auth.users ADD COLUMN must_reenroll_totp boolean NOT NULL DEFAULT false;
ALTER TABLE auth.sessions ADD COLUMN kind text NOT NULL DEFAULT 'normal' CHECK (kind IN ('normal', 'recovery'));

CREATE TABLE auth.recovery_codes (
  id         bigserial PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  code_hash  bytea NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at    timestamptz
);

INSERT INTO sys.settings (key, value) VALUES
  ('bcrypt_cost', '12'),
  ('session_hours', '12'),
  ('recovery_session_minutes', '30');

-- ------------------------------------------------------------------
-- Utilidades
-- ------------------------------------------------------------------
CREATE FUNCTION auth.base32(p bytea) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog AS $$
DECLARE
  alphabet text := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  bits bigint := 0; nbits int := 0; out text := ''; i int;
BEGIN
  FOR i IN 0 .. length(p) - 1 LOOP
    bits := (bits << 8) | get_byte(p, i);
    nbits := nbits + 8;
    WHILE nbits >= 5 LOOP
      out := out || substr(alphabet, ((bits >> (nbits - 5)) & 31)::int + 1, 1);
      nbits := nbits - 5;
    END LOOP;
    bits := bits & ((1::bigint << nbits) - 1);
  END LOOP;
  IF nbits > 0 THEN out := out || substr(alphabet, ((bits << (5 - nbits)) & 31)::int + 1, 1); END IF;
  RETURN out;
END $$;

CREATE FUNCTION auth.hash_password(p_password text) RETURNS text
LANGUAGE sql VOLATILE SET search_path = pg_catalog, public AS $$
  SELECT crypt(p_password, gen_salt('bf', (SELECT (value #>> '{}')::int FROM sys.settings WHERE key = 'bcrypt_cost')));
$$;

-- 10 códigos de recuperación de un solo uso (formato xxxx-xxxx-xxxx). Se devuelven UNA vez.
CREATE FUNCTION auth.new_recovery_codes(p_user uuid) RETURNS text[]
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE codes text[] := '{}'; c text; i int;
BEGIN
  DELETE FROM auth.recovery_codes WHERE user_id = p_user;
  FOR i IN 1 .. 10 LOOP
    c := regexp_replace(encode(gen_random_bytes(6), 'hex'), '(.{4})(.{4})(.{4})', '\1-\2-\3');
    INSERT INTO auth.recovery_codes (user_id, code_hash) VALUES (p_user, sha256(convert_to(c, 'UTF8')));
    codes := codes || c;
  END LOOP;
  RETURN codes;
END $$;

CREATE FUNCTION auth.new_session(p_user uuid, p_kind text, p_ip inet, p_ua text,
                                 OUT token text, OUT expires_at timestamptz)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
BEGIN
  token := 'crdo_se_' || encode(gen_random_bytes(32), 'hex');
  expires_at := now() + CASE p_kind
    WHEN 'recovery' THEN make_interval(mins => (SELECT (value #>> '{}')::int FROM sys.settings WHERE key = 'recovery_session_minutes'))
    ELSE make_interval(hours => (SELECT (value #>> '{}')::int FROM sys.settings WHERE key = 'session_hours')) END;
  INSERT INTO auth.sessions (user_id, token_hash, user_agent, ip, expires_at, kind)
  VALUES (p_user, sha256(convert_to(token, 'UTF8')), p_ua, p_ip, expires_at, p_kind);
END $$;

-- ------------------------------------------------------------------
-- Alta de usuarios: SOLO el rol dueño de la base (CLI por SSH o prototipo local). No se otorga a la app.
-- Devuelve el secreto TOTP (para la app de autenticación) y los códigos de recuperación, una sola vez.
-- ------------------------------------------------------------------
CREATE FUNCTION auth.create_user(p_email text, p_name text, p_role text, p_password text,
                                 OUT user_id uuid, OUT totp_secret_base32 text, OUT otpauth_uri text,
                                 OUT recovery_codes text[])
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE sec bytea := gen_random_bytes(20);
BEGIN
  IF length(coalesce(p_password, '')) < 12 THEN RAISE EXCEPTION 'La contraseña debe tener al menos 12 caracteres'; END IF;
  INSERT INTO auth.users (email, display_name, role, password_hash)
  VALUES (p_email, p_name, p_role, auth.hash_password(p_password)) RETURNING id INTO user_id;
  INSERT INTO auth.totp_secrets (user_id, secret) VALUES (user_id, sec);
  totp_secret_base32 := auth.base32(sec);
  otpauth_uri := format('otpauth://totp/CRDO:%s?secret=%s&issuer=CRDO&algorithm=SHA1&digits=6&period=30',
                        p_email, totp_secret_base32);
  recovery_codes := auth.new_recovery_codes(user_id);
  INSERT INTO audit.events (actor_type, action, resource, outcome, severity)
  VALUES ('system', 'auth.user_created', 'user:' || user_id, 'success', 'warning');
END $$;

-- ------------------------------------------------------------------
-- Inicio de sesión: contraseña + TOTP. Resultado: ok | invalid_credentials | agents_forbidden | reenroll_required
-- Mismo mensaje para email inexistente, contraseña mala, TOTP malo o cuenta bloqueada (no revela cuál falló).
-- ------------------------------------------------------------------
CREATE FUNCTION auth.login(p_email text, p_password text, p_code text, p_ip inet DEFAULT NULL, p_ua text DEFAULT NULL,
                           OUT result text, OUT token text, OUT expires_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE u auth.users%ROWTYPE; ok boolean;
BEGIN
  IF NOT auth.is_human_context() THEN result := 'agents_forbidden'; RETURN; END IF;
  SELECT * INTO u FROM auth.users WHERE email = p_email AND is_active FOR UPDATE;
  IF NOT FOUND THEN
    PERFORM auth.hash_password(coalesce(p_password, ''));          -- mismo costo de tiempo que un usuario real
    INSERT INTO audit.events (actor_type, action, outcome, severity, ip, detail)
    VALUES ('system', 'auth.login_failed', 'denied', 'warning', p_ip, jsonb_build_object('reason', 'unknown_user'));
    result := 'invalid_credentials'; RETURN;
  END IF;
  IF u.locked_until IS NOT NULL AND u.locked_until > now() THEN
    INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, ip, detail)
    VALUES ('user', u.id, 'auth.login_failed', 'denied', 'warning', p_ip, jsonb_build_object('reason', 'locked'));
    result := 'invalid_credentials'; RETURN;
  END IF;
  ok := crypt(coalesce(p_password, ''), u.password_hash) = u.password_hash;
  IF NOT ok THEN
    UPDATE auth.users
       SET failed_logins = CASE WHEN failed_logins + 1 >= 5 THEN 0 ELSE failed_logins + 1 END,
           locked_until  = CASE WHEN failed_logins + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END
     WHERE id = u.id;
    INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, ip, detail)
    VALUES ('user', u.id, 'auth.login_failed', 'denied', 'warning', p_ip, jsonb_build_object('reason', 'password'));
    result := 'invalid_credentials'; RETURN;
  END IF;
  IF u.must_reenroll_totp THEN result := 'reenroll_required'; RETURN; END IF;
  IF auth.verify_totp(u.id, p_code) IS NULL THEN
    INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, ip, detail)
    VALUES ('user', u.id, 'auth.login_failed', 'denied', 'warning', p_ip, jsonb_build_object('reason', 'totp'));
    result := 'invalid_credentials'; RETURN;
  END IF;
  UPDATE auth.users SET failed_logins = 0, locked_until = NULL WHERE id = u.id;
  SELECT s.token, s.expires_at INTO token, expires_at FROM auth.new_session(u.id, 'normal', p_ip, p_ua) s;
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, ip)
  VALUES ('user', u.id, 'auth.login', 'success', p_ip);
  result := 'ok';
END $$;

-- Devuelve el usuario de una sesión válida (o nada). Las sesiones de recuperación solo sirven para re-enrolar TOTP.
CREATE FUNCTION auth.validate_session(p_token text)
RETURNS TABLE (user_id uuid, role text, kind text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = auth, pg_catalog AS $$
  SELECT u.id, u.role, s.kind
    FROM auth.sessions s JOIN auth.users u ON u.id = s.user_id
   WHERE s.token_hash = sha256(convert_to(p_token, 'UTF8'))
     AND s.revoked_at IS NULL AND s.expires_at > now() AND u.is_active;
$$;

CREATE FUNCTION auth.logout(p_token text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog AS $$
BEGIN
  UPDATE auth.sessions SET revoked_at = now()
   WHERE token_hash = sha256(convert_to(p_token, 'UTF8')) AND revoked_at IS NULL;
  RETURN FOUND;
END $$;

-- Cerrar TODAS las sesiones de un usuario (p. ej. dispositivo comprometido). Con TOTP.
CREATE FUNCTION auth.revoke_all_sessions(p_user uuid, p_code text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE n int;
BEGIN
  IF NOT auth.is_human_context() THEN RETURN 'agents_forbidden'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE auth.sessions SET revoked_at = now() WHERE user_id = p_user AND revoked_at IS NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, detail)
  VALUES ('user', p_user, 'auth.sessions_revoked', 'success', 'warning', jsonb_build_object('count', n));
  RETURN 'revoked:' || n;
END $$;

-- ------------------------------------------------------------------
-- Teléfono perdido: contraseña + código de recuperación (un solo uso).
-- Cierra todas las sesiones, invalida el TOTP anterior y abre una sesión de recuperación de 30 min
-- que SOLO sirve para enrolar un TOTP nuevo.
-- ------------------------------------------------------------------
CREATE FUNCTION auth.login_with_recovery(p_email text, p_password text, p_recovery_code text,
                                         p_ip inet DEFAULT NULL, OUT result text, OUT token text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE u auth.users%ROWTYPE; rc bigint;
BEGIN
  IF NOT auth.is_human_context() THEN result := 'agents_forbidden'; RETURN; END IF;
  SELECT * INTO u FROM auth.users WHERE email = p_email AND is_active FOR UPDATE;
  IF NOT FOUND OR (u.locked_until IS NOT NULL AND u.locked_until > now())
     OR crypt(coalesce(p_password, ''), u.password_hash) <> u.password_hash THEN
    IF FOUND THEN
      UPDATE auth.users
         SET failed_logins = CASE WHEN failed_logins + 1 >= 5 THEN 0 ELSE failed_logins + 1 END,
             locked_until  = CASE WHEN failed_logins + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END
       WHERE id = u.id;
    END IF;
    result := 'invalid_credentials'; RETURN;
  END IF;
  UPDATE auth.recovery_codes SET used_at = now()
   WHERE id = (SELECT id FROM auth.recovery_codes
                WHERE user_id = u.id AND used_at IS NULL AND code_hash = sha256(convert_to(p_recovery_code, 'UTF8'))
                FOR UPDATE)
  RETURNING id INTO rc;
  IF rc IS NULL THEN
    INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, ip)
    VALUES ('user', u.id, 'auth.recovery_failed', 'denied', 'high', p_ip);
    result := 'invalid_credentials'; RETURN;
  END IF;
  UPDATE auth.sessions SET revoked_at = now() WHERE user_id = u.id AND revoked_at IS NULL;
  DELETE FROM auth.totp_secrets WHERE user_id = u.id;
  UPDATE auth.users SET must_reenroll_totp = true, failed_logins = 0 WHERE id = u.id;
  SELECT s.token INTO token FROM auth.new_session(u.id, 'recovery', p_ip, NULL) s;
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, ip)
  VALUES ('user', u.id, 'auth.recovery_used', 'success', 'high', p_ip);
  result := 'ok';
END $$;

-- Enrolar un TOTP nuevo con una sesión de recuperación. Devuelve el secreto UNA vez y códigos nuevos.
CREATE FUNCTION auth.reenroll_totp(p_token text, OUT result text, OUT totp_secret_base32 text,
                                   OUT recovery_codes text[])
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE s record; sec bytea := gen_random_bytes(20);
BEGIN
  SELECT * INTO s FROM auth.validate_session(p_token);
  IF s.user_id IS NULL OR s.kind <> 'recovery' THEN result := 'invalid_session'; RETURN; END IF;
  INSERT INTO auth.totp_secrets (user_id, secret) VALUES (s.user_id, sec);
  UPDATE auth.users SET must_reenroll_totp = false, totp_last_step = 0 WHERE id = s.user_id;
  UPDATE auth.sessions SET revoked_at = now() WHERE token_hash = sha256(convert_to(p_token, 'UTF8'));
  totp_secret_base32 := auth.base32(sec);
  recovery_codes := auth.new_recovery_codes(s.user_id);
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity)
  VALUES ('user', s.user_id, 'auth.totp_reenrolled', 'success', 'high');
  result := 'ok';
END $$;

-- Cambio de contraseña: contraseña actual + TOTP. Cierra las demás sesiones.
CREATE FUNCTION auth.change_password(p_user uuid, p_old text, p_new text, p_code text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE u auth.users%ROWTYPE;
BEGIN
  IF NOT auth.is_human_context() THEN RETURN 'agents_forbidden'; END IF;
  SELECT * INTO u FROM auth.users WHERE id = p_user AND is_active FOR UPDATE;
  IF NOT FOUND OR crypt(coalesce(p_old, ''), u.password_hash) <> u.password_hash THEN RETURN 'invalid_credentials'; END IF;
  IF length(coalesce(p_new, '')) < 12 THEN RETURN 'weak_password'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE auth.users SET password_hash = auth.hash_password(p_new), updated_at = now() WHERE id = p_user;
  UPDATE auth.sessions SET revoked_at = now() WHERE user_id = p_user AND revoked_at IS NULL;
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity)
  VALUES ('user', p_user, 'auth.password_changed', 'success', 'warning');
  RETURN 'changed';
END $$;

-- ------------------------------------------------------------------
-- Agentes: tokens con alcance, vencimiento y revocación. Los emite solo el dueño con TOTP.
-- ------------------------------------------------------------------
CREATE FUNCTION auth.issue_agent_token(p_user uuid, p_code text, p_agent uuid, p_name text,
                                       p_scopes text[], p_days int DEFAULT 30,
                                       OUT result text, OUT token text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
BEGIN
  IF NOT auth.is_human_context() THEN result := 'agents_forbidden'; RETURN; END IF;
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN
    result := 'forbidden'; RETURN;
  END IF;
  IF p_days NOT BETWEEN 1 AND 90 THEN result := 'invalid_ttl'; RETURN; END IF;
  IF EXISTS (SELECT 1 FROM unnest(p_scopes) sc LEFT JOIN org.permissions p ON p.code = sc
              WHERE p.code IS NULL OR p.risk = 'R4') THEN
    result := 'invalid_scope'; RETURN;
  END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN result := 'invalid_code'; RETURN; END IF;
  token := 'crdo_ag_' || encode(gen_random_bytes(32), 'hex');
  INSERT INTO auth.api_tokens (agent_id, name, token_hash, scopes, created_by, expires_at)
  VALUES (p_agent, p_name, sha256(convert_to(token, 'UTF8')), p_scopes, p_user, now() + make_interval(days => p_days));
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity, detail)
  VALUES ('user', p_user, 'auth.agent_token_issued', 'agent:' || p_agent, 'success', 'warning',
          jsonb_build_object('name', p_name, 'scopes', p_scopes, 'days', p_days));
  result := 'ok';
END $$;

CREATE FUNCTION auth.validate_agent_token(p_token text)
RETURNS TABLE (agent_id uuid, token_id uuid, scopes text[])
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = auth, pg_catalog AS $$
  SELECT t.agent_id, t.id, t.scopes
    FROM auth.api_tokens t JOIN org.agents a ON a.id = t.agent_id
   WHERE t.token_hash = sha256(convert_to(p_token, 'UTF8'))
     AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > now()) AND a.is_enabled;
$$;

-- Revocar es la dirección segura: no exige TOTP.
CREATE FUNCTION auth.revoke_agent_token(p_user uuid, p_token_id uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog AS $$
BEGIN
  UPDATE auth.api_tokens SET revoked_at = now() WHERE id = p_token_id AND revoked_at IS NULL;
  IF FOUND THEN
    INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity)
    VALUES ('user', p_user, 'auth.agent_token_revoked', 'token:' || p_token_id, 'success', 'warning');
  END IF;
  RETURN FOUND;
END $$;

-- ¿Puede este agente hacer esto ahora? (agente habilitado, permiso otorgado y vigente, nunca R4)
CREATE FUNCTION org.agent_has_permission(p_agent uuid, p_permission text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = org, pg_catalog AS $$
  SELECT EXISTS (
    SELECT 1 FROM org.agent_permissions ap
      JOIN org.agents a ON a.id = ap.agent_id
      JOIN org.permissions p ON p.code = ap.permission_code
     WHERE ap.agent_id = p_agent AND ap.permission_code = p_permission
       AND a.is_enabled AND p.risk <> 'R4'
       AND (ap.expires_at IS NULL OR ap.expires_at > now()));
$$;

-- ------------------------------------------------------------------
-- Privilegios: la app no ve hashes ni secretos; las sesiones y tokens solo por funciones
-- ------------------------------------------------------------------
REVOKE ALL ON auth.users FROM crdo_app;
GRANT SELECT (id, email, display_name, role, is_active, failed_logins, locked_until, totp_failures,
              totp_locked_until, must_reenroll_totp, created_at, updated_at) ON auth.users TO crdo_app;
GRANT UPDATE (display_name, updated_at) ON auth.users TO crdo_app;
REVOKE ALL ON auth.sessions, auth.recovery_codes FROM crdo_app;
REVOKE ALL ON auth.api_tokens FROM crdo_app;
GRANT SELECT (id, agent_id, name, scopes, created_by, created_at, expires_at, revoked_at) ON auth.api_tokens TO crdo_app;
-- crdo_backup (credencial de solo lectura) NO puede leer material que permita suplantar al dueño.
-- Los volcados completos los hace backup.sh con el rol dueño dentro del contenedor, y viajan cifrados.
REVOKE ALL ON auth.totp_secrets, auth.recovery_codes, auth.sessions FROM crdo_backup;
REVOKE SELECT ON auth.users, auth.api_tokens FROM crdo_backup;
GRANT SELECT (id, email, display_name, role, is_active, created_at, updated_at) ON auth.users TO crdo_backup;
GRANT SELECT (id, agent_id, name, scopes, created_at, expires_at, revoked_at) ON auth.api_tokens TO crdo_backup;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA auth FROM PUBLIC;
GRANT EXECUTE ON FUNCTION
  auth.login(text, text, text, inet, text), auth.validate_session(text), auth.logout(text),
  auth.revoke_all_sessions(uuid, text), auth.login_with_recovery(text, text, text, inet),
  auth.reenroll_totp(text), auth.change_password(uuid, text, text, text),
  auth.issue_agent_token(uuid, text, uuid, text, text[], int), auth.validate_agent_token(text),
  auth.revoke_agent_token(uuid, uuid), auth.verify_totp(uuid, text), auth.is_human_context(),
  org.agent_has_permission(uuid, text)
TO crdo_app;
-- auth.create_user, auth.hash_password, auth.new_session, auth.new_recovery_codes, auth.hotp: SIN permiso para la app.

COMMIT;
