-- C.R.D.O. — Funciones de control (Etapa 1, v3.1)
-- Todo cambio crítico pasa por aquí. La app (crdo_app) no tiene permiso de escritura directa sobre
-- aprobaciones, trabajos, permisos de agentes, comandos administrativos ni secretos TOTP.
-- Las funciones devuelven códigos de resultado en vez de lanzar errores en los casos esperados, para que
-- los contadores de intentos fallidos se guarden (un error desharía la transacción).

BEGIN;

-- ==================================================================
-- 0. Contexto humano: el backend marca cada petición originada por un agente con
--    SET LOCAL crdo.actor = 'agent'. Las operaciones reservadas a humanos se niegan en ese contexto.
--    Es una capa adicional: la barrera principal es el TOTP, cuyo secreto ningún agente puede leer.
-- ==================================================================
CREATE FUNCTION auth.is_human_context() RETURNS boolean
LANGUAGE sql STABLE SET search_path = pg_catalog AS $$
  SELECT coalesce(current_setting('crdo.actor', true), '') IS DISTINCT FROM 'agent';
$$;

-- ==================================================================
-- 1. TOTP (RFC 6238, HMAC-SHA1, 6 dígitos, pasos de 30 s) verificado DENTRO de la base
-- ==================================================================
CREATE FUNCTION auth.hotp(p_secret bytea, p_counter bigint, p_digits int DEFAULT 6)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT SET search_path = pg_catalog, public AS $$
DECLARE
  h   bytea := hmac(int8send(p_counter), p_secret, 'sha1');
  off int   := get_byte(h, 19) & 15;
  bin bigint;
BEGIN
  bin := ((get_byte(h, off) & 127)::bigint << 24) | (get_byte(h, off + 1)::bigint << 16)
       | (get_byte(h, off + 2)::bigint << 8) | get_byte(h, off + 3)::bigint;
  RETURN lpad((bin % (10::bigint ^ p_digits)::bigint)::text, p_digits, '0');
END $$;

-- Devuelve el paso TOTP aceptado, o NULL. Un código ya usado (paso <= último usado) se rechaza:
-- cada código sirve UNA sola vez. 5 fallos seguidos bloquean 15 minutos.
CREATE FUNCTION auth.verify_totp(p_user uuid, p_code text)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path = auth, pg_catalog, public AS $$
DECLARE
  u        auth.users%ROWTYPE;
  sec      bytea;
  now_step bigint := floor(extract(epoch FROM clock_timestamp()) / 30);
  s        bigint;
BEGIN
  SELECT * INTO u FROM auth.users WHERE id = p_user AND is_active FOR UPDATE;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF u.totp_locked_until IS NOT NULL AND u.totp_locked_until > clock_timestamp() THEN RETURN NULL; END IF;
  SELECT secret INTO sec FROM auth.totp_secrets WHERE user_id = p_user;
  IF sec IS NULL OR p_code !~ '^[0-9]{6}$' THEN
    UPDATE auth.users SET totp_failures = totp_failures + 1 WHERE id = p_user;
    RETURN NULL;
  END IF;
  FOR s IN now_step - 1 .. now_step + 1 LOOP
    IF s > u.totp_last_step AND auth.hotp(sec, s) = p_code THEN
      UPDATE auth.users SET totp_last_step = s, totp_failures = 0, totp_locked_until = NULL WHERE id = p_user;
      RETURN s;
    END IF;
  END LOOP;
  UPDATE auth.users
     SET totp_failures     = CASE WHEN totp_failures + 1 >= 5 THEN 0 ELSE totp_failures + 1 END,
         totp_locked_until = CASE WHEN totp_failures + 1 >= 5 THEN clock_timestamp() + interval '15 minutes'
                                  ELSE totp_locked_until END
   WHERE id = p_user;
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity)
  VALUES ('user', p_user, 'auth.totp_failed', 'denied', 'warning');
  RETURN NULL;
END $$;

-- ==================================================================
-- 2. Tareas: hash canónico del contenido, inmutable después de crearse
-- ==================================================================
CREATE FUNCTION ops.task_hash() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.payload IS DISTINCT FROM OLD.payload THEN
    RAISE EXCEPTION 'El contenido de una tarea es inmutable; crear una tarea nueva';
  END IF;
  -- jsonb normaliza el orden de claves y espacios: mismo contenido => mismo texto => mismo hash
  NEW.payload_hash := sha256(convert_to(NEW.payload::text, 'UTF8'));
  RETURN NEW;
END $$;
CREATE TRIGGER tasks_hash BEFORE INSERT OR UPDATE ON ops.tasks
  FOR EACH ROW EXECUTE FUNCTION ops.task_hash();

-- ==================================================================
-- 3. Aprobaciones humanas: hash exacto + vencimiento + TOTP de un solo uso + consumo único
-- ==================================================================
CREATE FUNCTION ops.request_approval(p_task uuid, p_summary text, p_ttl interval DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE
  t   ops.tasks%ROWTYPE;
  ttl interval := coalesce(p_ttl, make_interval(hours =>
                    (SELECT (value #>> '{}')::int FROM sys.settings WHERE key = 'approval_ttl_hours')));
  id  uuid;
BEGIN
  SELECT * INTO t FROM ops.tasks WHERE ops.tasks.id = p_task FOR UPDATE;
  IF NOT FOUND OR t.risk <> 'R3' OR t.status <> 'pending' THEN
    RAISE EXCEPTION 'Solo tareas R3 pendientes requieren aprobación';
  END IF;
  INSERT INTO ops.approvals (task_id, payload_hash, summary, expires_at)
  VALUES (p_task, t.payload_hash, p_summary, now() + least(ttl, interval '72 hours'))
  RETURNING ops.approvals.id INTO id;
  UPDATE ops.tasks SET status = 'awaiting_approval', updated_at = now() WHERE ops.tasks.id = p_task;
  INSERT INTO audit.events (actor_type, action, resource, outcome, detail)
  VALUES ('system', 'approval.requested', 'task:' || p_task, 'success',
          jsonb_build_object('hash', encode(t.payload_hash, 'hex')));
  RETURN id;
END $$;

-- p_presented_hash: el hash que el panel le MOSTRÓ al humano. Si el contenido cambió, no coincide.
-- Resultado: approved | not_found | not_pending | expired | forbidden | hash_mismatch | invalid_code
CREATE FUNCTION ops.approve(p_approval uuid, p_user uuid, p_code text, p_presented_hash bytea,
                            p_note text DEFAULT NULL)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE
  a    ops.approvals%ROWTYPE;
  th   bytea;
  role text;
  step bigint;
BEGIN
  IF NOT auth.is_human_context() THEN RETURN 'agents_forbidden'; END IF;
  SELECT * INTO a FROM ops.approvals WHERE id = p_approval FOR UPDATE;
  IF NOT FOUND THEN RETURN 'not_found'; END IF;
  IF a.status <> 'pending' THEN RETURN 'not_pending'; END IF;
  IF a.expires_at <= now() THEN
    UPDATE ops.approvals SET status = 'expired' WHERE id = p_approval;
    RETURN 'expired';
  END IF;
  SELECT u.role INTO role FROM auth.users u WHERE u.id = p_user AND u.is_active;
  IF role IS NULL OR role NOT IN ('owner', 'admin') THEN RETURN 'forbidden'; END IF;
  SELECT payload_hash INTO th FROM ops.tasks WHERE id = a.task_id;
  IF p_presented_hash IS DISTINCT FROM a.payload_hash OR th IS DISTINCT FROM a.payload_hash THEN
    INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity)
    VALUES ('user', p_user, 'approval.hash_mismatch', 'approval:' || p_approval, 'denied', 'high');
    RETURN 'hash_mismatch';
  END IF;
  step := auth.verify_totp(p_user, p_code);
  IF step IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE ops.approvals
     SET status = 'approved', decided_by = p_user, decided_at = now(), totp_step = step, decision_note = p_note
   WHERE id = p_approval;
  UPDATE ops.tasks SET status = 'approved', updated_at = now() WHERE id = a.task_id;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, detail)
  VALUES ('user', p_user, 'approval.approved', 'approval:' || p_approval, 'success',
          jsonb_build_object('hash', encode(a.payload_hash, 'hex'), 'totp_step', step));
  RETURN 'approved';
END $$;

-- Rechazar es la dirección segura: no exige TOTP.
CREATE FUNCTION ops.reject(p_approval uuid, p_user uuid, p_note text DEFAULT NULL)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE t uuid;
BEGIN
  UPDATE ops.approvals SET status = 'rejected', decided_by = p_user, decided_at = now(), decision_note = p_note
   WHERE id = p_approval AND status = 'pending' RETURNING task_id INTO t;
  IF t IS NULL THEN RETURN 'not_pending'; END IF;
  UPDATE ops.tasks SET status = 'rejected', updated_at = now() WHERE id = t;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome)
  VALUES ('user', p_user, 'approval.rejected', 'approval:' || p_approval, 'success');
  RETURN 'rejected';
END $$;

CREATE FUNCTION ops.expire_approvals() RETURNS int
LANGUAGE sql SECURITY DEFINER SET search_path = ops, pg_catalog AS $$
  WITH e AS (UPDATE ops.approvals SET status = 'expired'
              WHERE status IN ('pending', 'approved') AND expires_at <= now() RETURNING 1)
  SELECT count(*)::int FROM e;
$$;

-- ==================================================================
-- 4. Cola de trabajos: idempotencia, leases con fencing token, recuperación, sin duplicados
-- ==================================================================
CREATE FUNCTION ops.enqueue_job(p_task uuid, p_queue text, p_kind text, p_idempotency_key text,
                                p_external_effect boolean DEFAULT false, p_run_after timestamptz DEFAULT now())
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE
  t  ops.tasks%ROWTYPE;
  q  ops.queues%ROWTYPE;
  id bigint;
BEGIN
  SELECT * INTO q FROM ops.queues WHERE name = p_queue;
  IF NOT FOUND THEN RAISE EXCEPTION 'Cola inexistente: %', p_queue; END IF;
  IF p_task IS NOT NULL THEN
    SELECT * INTO t FROM ops.tasks WHERE ops.tasks.id = p_task;
    IF t.risk = 'R4' THEN RAISE EXCEPTION 'R4 no se ejecuta por la cola'; END IF;
    IF t.risk = 'R3' AND NOT EXISTS (SELECT 1 FROM ops.approvals a WHERE a.task_id = p_task
                                      AND a.status = 'approved' AND a.expires_at > now()) THEN
      RAISE EXCEPTION 'Tarea R3 sin aprobación vigente';
    END IF;
  END IF;
  INSERT INTO ops.jobs (task_id, queue, kind, idempotency_key, external_effect, max_attempts, run_after)
  VALUES (p_task, p_queue, p_kind, p_idempotency_key, p_external_effect,
          CASE WHEN p_external_effect THEN 1 ELSE q.max_attempts END, p_run_after)
  ON CONFLICT (idempotency_key) DO NOTHING
  RETURNING ops.jobs.id INTO id;
  IF id IS NULL THEN  -- ya existía: se devuelve el mismo trabajo, no se duplica
    SELECT j.id INTO id FROM ops.jobs j WHERE j.idempotency_key = p_idempotency_key;
  END IF;
  RETURN id;
END $$;

-- p_location: 'vps' o 'external'. El worker del VPS nunca recibe trabajos de colas 'external' (render).
CREATE FUNCTION ops.claim_job(p_worker text, p_location text, p_queues text[] DEFAULT NULL)
RETURNS TABLE (job_id bigint, lease_token uuid, queue text, kind text, task_id uuid, payload jsonb)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE
  agents_on boolean := coalesce((SELECT (value #>> '{}')::boolean FROM sys.settings WHERE key = 'agents_enabled'), false);
BEGIN
  RETURN QUERY
  WITH c AS (
    SELECT j.id
      FROM ops.jobs j
      JOIN ops.queues q ON q.name = j.queue
      LEFT JOIN ops.tasks t ON t.id = j.task_id
     WHERE j.status = 'queued' AND j.run_after <= now()
       AND q.runs_on = p_location
       AND (p_queues IS NULL OR j.queue = ANY (p_queues))
       AND (agents_on OR t.requested_by_agent IS NULL)       -- kill switch
     ORDER BY j.run_after, j.id
     FOR UPDATE OF j SKIP LOCKED                              -- dos workers nunca toman el mismo trabajo
     LIMIT 1)
  UPDATE ops.jobs j
     SET status = 'running', attempts = j.attempts + 1, lease_owner = p_worker,
         lease_token = gen_random_uuid(), heartbeat_at = now(),
         lease_expires_at = now() + make_interval(secs => q.lease_seconds)
    FROM c, ops.queues q
   WHERE j.id = c.id AND q.name = j.queue
  RETURNING j.id, j.lease_token, j.queue, j.kind, j.task_id,
            (SELECT t.payload FROM ops.tasks t WHERE t.id = j.task_id);
END $$;

-- Renueva el lease. Si devuelve false, el worker PERDIÓ el trabajo y debe abortar sin efectos.
CREATE FUNCTION ops.heartbeat(p_job bigint, p_token uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog AS $$
BEGIN
  UPDATE ops.jobs j SET heartbeat_at = now(),
         lease_expires_at = now() + make_interval(secs => (SELECT lease_seconds FROM ops.queues q WHERE q.name = j.queue))
   WHERE j.id = p_job AND j.lease_token = p_token AND j.status = 'running' AND j.lease_expires_at > now();
  RETURN FOUND;
END $$;

-- Llamar JUSTO ANTES de un efecto externo (publicar, pagar). Consume la aprobación (una sola vez)
-- y deja constancia de que el efecto pudo haber ocurrido: si el worker muere después, el trabajo
-- NO se reintenta solo; queda en 'needs_review' para decisión humana.
-- Resultado: ok | lease_lost | already_started | not_approved | expired | hash_mismatch
CREATE FUNCTION ops.begin_external_effect(p_job bigint, p_token uuid) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE
  j ops.jobs%ROWTYPE;
  t ops.tasks%ROWTYPE;
  a ops.approvals%ROWTYPE;
BEGIN
  SELECT * INTO j FROM ops.jobs WHERE id = p_job FOR UPDATE;
  IF NOT FOUND OR j.lease_token IS DISTINCT FROM p_token OR j.status <> 'running'
     OR j.lease_expires_at <= now() THEN
    RETURN 'lease_lost';
  END IF;
  IF j.effect_started_at IS NOT NULL THEN RETURN 'already_started'; END IF;
  SELECT * INTO t FROM ops.tasks WHERE id = j.task_id;
  IF t.risk = 'R3' THEN
    SELECT * INTO a FROM ops.approvals WHERE task_id = t.id FOR UPDATE;
    IF NOT FOUND OR a.status <> 'approved' THEN RETURN 'not_approved'; END IF;
    IF a.expires_at <= now() THEN
      UPDATE ops.approvals SET status = 'expired' WHERE id = a.id;
      RETURN 'expired';
    END IF;
    IF a.payload_hash IS DISTINCT FROM t.payload_hash THEN RETURN 'hash_mismatch'; END IF;
    UPDATE ops.approvals SET status = 'consumed', consumed_at = now(), consumed_by_job = p_job WHERE id = a.id;
  END IF;
  UPDATE ops.jobs SET effect_started_at = now() WHERE id = p_job;
  INSERT INTO audit.events (actor_type, action, resource, outcome, detail)
  VALUES ('system', 'job.effect_started', 'job:' || p_job, 'success',
          jsonb_build_object('task', t.id, 'hash', encode(t.payload_hash, 'hex')));
  RETURN 'ok';
END $$;

CREATE FUNCTION ops.complete_job(p_job bigint, p_token uuid, p_result jsonb DEFAULT '{}') RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog AS $$
BEGIN
  UPDATE ops.jobs SET status = 'succeeded', result = p_result, finished_at = now(),
         lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
   WHERE id = p_job AND lease_token = p_token AND status = 'running';
  RETURN FOUND;  -- false: el lease ya no era de este worker (no se pisa el trabajo de otro)
END $$;

-- Resultado: requeued | dead | needs_review | lease_lost
CREATE FUNCTION ops.fail_job(p_job bigint, p_token uuid, p_error text, p_retryable boolean DEFAULT true)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog AS $$
DECLARE j ops.jobs%ROWTYPE; r text;
BEGIN
  SELECT * INTO j FROM ops.jobs WHERE id = p_job AND lease_token = p_token AND status = 'running' FOR UPDATE;
  IF NOT FOUND THEN RETURN 'lease_lost'; END IF;
  r := CASE WHEN j.effect_started_at IS NOT NULL THEN 'needs_review'
            WHEN NOT p_retryable OR j.attempts >= j.max_attempts THEN 'dead'
            ELSE 'requeued' END;
  UPDATE ops.jobs
     SET status = CASE r WHEN 'requeued' THEN 'queued' ELSE r END,
         run_after = CASE r WHEN 'requeued'
                       THEN now() + make_interval(secs => least(3600, 30 * 2 ^ (j.attempts - 1)))
                       ELSE run_after END,
         last_error = p_error, lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
         finished_at = CASE WHEN r = 'requeued' THEN NULL ELSE now() END
   WHERE id = p_job;
  RETURN r;
END $$;

-- Recupera trabajos de workers caídos (lease vencido). Lo ejecuta el scheduler cada minuto.
CREATE FUNCTION ops.reap_expired()
RETURNS TABLE (requeued int, dead int, needs_review int)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE j ops.jobs%ROWTYPE; rq int := 0; dd int := 0; nr int := 0;
BEGIN
  FOR j IN SELECT * FROM ops.jobs WHERE status = 'running' AND lease_expires_at < now()
           ORDER BY id FOR UPDATE SKIP LOCKED LOOP
    IF j.effect_started_at IS NOT NULL THEN
      UPDATE ops.jobs SET status = 'needs_review', last_error = 'lease vencido después de iniciar efecto externo',
             lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, finished_at = now() WHERE id = j.id;
      INSERT INTO audit.events (actor_type, action, resource, outcome, severity)
      VALUES ('system', 'job.needs_review', 'job:' || j.id, 'error', 'high');
      nr := nr + 1;
    ELSIF j.attempts >= j.max_attempts THEN
      UPDATE ops.jobs SET status = 'dead', last_error = 'lease vencido; sin intentos restantes',
             lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, finished_at = now() WHERE id = j.id;
      dd := dd + 1;
    ELSE
      UPDATE ops.jobs SET status = 'queued', last_error = 'lease vencido; reencolado',
             lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL WHERE id = j.id;
      rq := rq + 1;
    END IF;
  END LOOP;
  RETURN QUERY SELECT rq, dd, nr;
END $$;

-- ==================================================================
-- 5. Operaciones del dueño desde el panel (Android), todas con TOTP de un solo uso
-- ==================================================================
CREATE FUNCTION ops.request_admin_command(p_user uuid, p_code text, p_command text, p_env text,
                                          p_args jsonb DEFAULT '{}', OUT result text, OUT command_id bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = ops, pg_catalog, public AS $$
DECLARE step bigint;
BEGIN
  IF NOT auth.is_human_context() THEN result := 'agents_forbidden'; RETURN; END IF;
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN
    result := 'forbidden'; RETURN;
  END IF;
  step := auth.verify_totp(p_user, p_code);
  IF step IS NULL THEN result := 'invalid_code'; RETURN; END IF;
  INSERT INTO ops.admin_commands (command, environment, args, requested_by, totp_step)
  VALUES (p_command, p_env, p_args, p_user, step) RETURNING id INTO command_id;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, detail)
  VALUES ('user', p_user, 'admin.command_requested', 'admin_command:' || command_id, 'success',
          jsonb_build_object('command', p_command, 'env', p_env, 'args', p_args));
  result := 'queued';
END $$;

-- Lo usa crdo-opsd (servicio del host) para tomar el siguiente comando.
CREATE FUNCTION ops.claim_admin_command()
RETURNS TABLE (id bigint, command text, environment text, args jsonb)
LANGUAGE sql SECURITY DEFINER SET search_path = ops, pg_catalog AS $$
  UPDATE ops.admin_commands c SET status = 'running', started_at = now()
   WHERE c.id = (SELECT x.id FROM ops.admin_commands x WHERE x.status = 'queued'
                  ORDER BY x.id FOR UPDATE SKIP LOCKED LIMIT 1)
  RETURNING c.id, c.command, c.environment, c.args;
$$;

-- Kill switch: APAGAR no exige código (dirección segura); ENCENDER sí.
CREATE FUNCTION sys.set_agents_enabled(p_user uuid, p_enabled boolean, p_code text DEFAULT NULL)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = sys, pg_catalog, public AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role IN ('owner','admin') AND is_active) THEN
    RETURN 'forbidden';
  END IF;
  IF p_enabled AND NOT auth.is_human_context() THEN RETURN 'agents_forbidden'; END IF;
  IF p_enabled AND auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  UPDATE sys.settings SET value = to_jsonb(p_enabled), updated_by = p_user, updated_at = now()
   WHERE key = 'agents_enabled';
  INSERT INTO audit.events (actor_type, actor_user, action, outcome, severity, detail)
  VALUES ('user', p_user, 'agents.' || CASE WHEN p_enabled THEN 'enabled' ELSE 'stopped' END,
          'success', 'warning', '{}');
  RETURN CASE WHEN p_enabled THEN 'enabled' ELSE 'stopped' END;
END $$;

-- Otorgar un permiso a un agente: solo el dueño, con TOTP. Los permisos R4 siguen prohibidos (trigger).
CREATE FUNCTION org.grant_permission(p_user uuid, p_code text, p_agent uuid, p_permission text,
                                     p_expires timestamptz DEFAULT NULL)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = org, pg_catalog, public AS $$
BEGIN
  IF NOT auth.is_human_context() THEN RETURN 'agents_forbidden'; END IF;
  IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_user AND role = 'owner' AND is_active) THEN
    RETURN 'forbidden';
  END IF;
  IF (SELECT risk FROM org.permissions WHERE code = p_permission) = 'R4' THEN RETURN 'forbidden_r4'; END IF;
  IF auth.verify_totp(p_user, p_code) IS NULL THEN RETURN 'invalid_code'; END IF;
  INSERT INTO org.agent_permissions (agent_id, permission_code, granted_by, expires_at)
  VALUES (p_agent, p_permission, p_user, p_expires)
  ON CONFLICT (agent_id, permission_code) DO UPDATE SET expires_at = EXCLUDED.expires_at;
  INSERT INTO audit.events (actor_type, actor_user, action, resource, outcome, severity)
  VALUES ('user', p_user, 'permission.granted', 'agent:' || p_agent || ':' || p_permission, 'success', 'warning');
  RETURN 'granted';
END $$;

-- ==================================================================
-- 6. Auditoría: hash independiente de la zona horaria + verificación de la cadena
-- ==================================================================
CREATE OR REPLACE FUNCTION audit.compute_hash(p_prev bytea, e audit.events) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, public AS $$
  SELECT digest(
    coalesce(encode(p_prev, 'hex'), '') || '|' ||
    to_char(e.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') || '|' ||
    e.actor_type || '|' || coalesce(e.actor_user::text, '') || '|' ||
    coalesce(e.actor_agent::text, '') || '|' || e.action || '|' ||
    coalesce(e.resource, '') || '|' || e.outcome || '|' || e.detail::text,
    'sha256');
$$;

CREATE OR REPLACE FUNCTION audit.chain_hash() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, pg_catalog, public AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('audit.events'));
  -- El id se asigna DENTRO del candado (el DEFAULT lo asigna antes, fuera de orden con escrituras concurrentes:
  -- la prueba de concurrencia de la v3.1 detectó la cadena rota por eso). Así, orden de id = orden de encadenado.
  NEW.id := nextval(pg_get_serial_sequence('audit.events', 'id'));
  NEW.occurred_at := clock_timestamp();
  SELECT hash INTO NEW.prev_hash FROM audit.events ORDER BY id DESC LIMIT 1;
  NEW.hash := audit.compute_hash(NEW.prev_hash, NEW);
  RETURN NEW;
END $$;

-- Sin DEFAULT: el id lo asigna únicamente el trigger, dentro del candado (numeración sin huecos y en orden de cadena)
ALTER TABLE audit.events ALTER COLUMN id DROP DEFAULT;

-- Devuelve el id del primer evento alterado, o NULL si la cadena está íntegra.
CREATE FUNCTION audit.verify_chain() RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = audit, pg_catalog, public AS $$
DECLARE e audit.events%ROWTYPE; prev bytea := NULL;
BEGIN
  FOR e IN SELECT * FROM audit.events ORDER BY id LOOP
    IF e.prev_hash IS DISTINCT FROM prev OR e.hash IS DISTINCT FROM audit.compute_hash(prev, e) THEN
      RETURN e.id;
    END IF;
    prev := e.hash;
  END LOOP;
  RETURN NULL;
END $$;

-- ==================================================================
-- 7. Quién puede ejecutar qué
-- ==================================================================
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA auth, org, ops, audit, sys FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
  auth.verify_totp(uuid, text),
  ops.request_approval(uuid, text, interval), ops.approve(uuid, uuid, text, bytea, text),
  ops.reject(uuid, uuid, text), ops.expire_approvals(),
  ops.enqueue_job(uuid, text, text, text, boolean, timestamptz),
  ops.claim_job(text, text, text[]), ops.heartbeat(bigint, uuid),
  ops.begin_external_effect(bigint, uuid), ops.complete_job(bigint, uuid, jsonb),
  ops.fail_job(bigint, uuid, text, boolean), ops.reap_expired(),
  ops.request_admin_command(uuid, text, text, text, jsonb),
  sys.set_agents_enabled(uuid, boolean, text),
  org.grant_permission(uuid, text, uuid, text, timestamptz),
  audit.verify_chain()
TO crdo_app;

GRANT EXECUTE ON FUNCTION ops.claim_admin_command(), audit.verify_chain() TO crdo_ops;
GRANT EXECUTE ON FUNCTION auth.is_human_context() TO crdo_app, crdo_ops;
GRANT EXECUTE ON FUNCTION audit.verify_chain() TO crdo_backup;

-- Los triggers corren con permisos de su dueño o del que inserta; se habilita lo necesario
GRANT EXECUTE ON FUNCTION ops.task_hash(), audit.chain_hash(), audit.compute_hash(bytea, audit.events),
  org.forbid_r4_grant() TO crdo_app;

COMMIT;
