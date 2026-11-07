-- C.R.D.O. — Esquema inicial (BORRADOR, Etapa 1)
-- Requiere PostgreSQL 16. La memoria vectorial está en 02-memory-vector.sql (necesita pgvector).
-- Ejecutar como el rol dueño (crdo_owner). La app usa crdo_app con permisos mínimos.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid(), digest()
CREATE EXTENSION IF NOT EXISTS citext;     -- emails sin distinguir mayúsculas

-- ------------------------------------------------------------------
-- Roles (sin login aquí; las contraseñas se asignan en la instalación)
-- ------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crdo_app')      THEN CREATE ROLE crdo_app;      END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crdo_readonly') THEN CREATE ROLE crdo_readonly; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crdo_backup')   THEN CREATE ROLE crdo_backup;   END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crdo_ops')      THEN CREATE ROLE crdo_ops;      END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS org;
CREATE SCHEMA IF NOT EXISTS ops;
CREATE SCHEMA IF NOT EXISTS memory;
CREATE SCHEMA IF NOT EXISTS content;
CREATE SCHEMA IF NOT EXISTS storage;
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE SCHEMA IF NOT EXISTS finance;
CREATE SCHEMA IF NOT EXISTS integrations;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS sys;

-- Niveles de riesgo (ver §C.4 del diseño)
CREATE TYPE ops.risk_level AS ENUM ('R0', 'R1', 'R2', 'R3', 'R4');

-- ------------------------------------------------------------------
-- auth: personas y credenciales
-- ------------------------------------------------------------------
CREATE TABLE auth.users (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email           citext NOT NULL UNIQUE,
  display_name    text NOT NULL,
  role            text NOT NULL CHECK (role IN ('owner', 'admin', 'viewer')),
  password_hash   text NOT NULL,              -- Argon2id
  totp_last_step  bigint NOT NULL DEFAULT 0,  -- último paso TOTP usado: impide reutilizar un código
  totp_failures   int NOT NULL DEFAULT 0,
  totp_locked_until timestamptz,
  is_active       boolean NOT NULL DEFAULT true,
  failed_logins   int NOT NULL DEFAULT 0,
  locked_until    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Secreto TOTP: la app NO puede leerlo. Solo lo usan funciones SECURITY DEFINER (03-ops-functions.sql).
-- Así, aunque la app o un agente fueran comprometidos, no pueden fabricar un código válido.
CREATE TABLE auth.totp_secrets (
  user_id     uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  secret      bytea NOT NULL,                 -- 20 bytes aleatorios (RFC 6238, HMAC-SHA1)
  enrolled_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE auth.sessions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  token_hash   bytea NOT NULL UNIQUE,
  user_agent   text,
  ip           inet,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  revoked_at   timestamptz
);
CREATE INDEX ON auth.sessions (user_id) WHERE revoked_at IS NULL;

-- ------------------------------------------------------------------
-- org: organigrama y permisos (sin agentes construidos en Etapa 1)
-- ------------------------------------------------------------------
CREATE TABLE org.departments (
  id          smallserial PRIMARY KEY,
  code        text NOT NULL UNIQUE,
  name        text NOT NULL,
  reports_to  text NOT NULL CHECK (reports_to IN ('owner', 'direction')),
  is_active   boolean NOT NULL DEFAULT false,  -- se activan en etapas futuras
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE org.agents (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id  smallint NOT NULL REFERENCES org.departments(id),
  code           text NOT NULL UNIQUE,
  name           text NOT NULL,
  default_route  text NOT NULL DEFAULT 'none',  -- ruta de modelo (integrations.model_routes); nunca un proveedor fijo
  instructions_ref text,                       -- ruta en el repo (agents/<depto>/<agente>.md), versionada
  is_enabled     boolean NOT NULL DEFAULT false,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE org.permissions (
  code         text PRIMARY KEY,               -- p. ej. 'memory.read', 'youtube.publish'
  description  text NOT NULL,
  risk         ops.risk_level NOT NULL
);

CREATE TABLE org.agent_permissions (
  agent_id         uuid NOT NULL REFERENCES org.agents(id) ON DELETE CASCADE,
  permission_code  text NOT NULL REFERENCES org.permissions(code),
  granted_by       uuid NOT NULL REFERENCES auth.users(id),
  granted_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz,
  PRIMARY KEY (agent_id, permission_code)
);

CREATE TABLE auth.api_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  agent_id    uuid REFERENCES org.agents(id) ON DELETE CASCADE,
  name        text NOT NULL,
  token_hash  bytea NOT NULL UNIQUE,           -- nunca se guarda el token en claro
  scopes      text[] NOT NULL DEFAULT '{}',
  created_by  uuid NOT NULL REFERENCES auth.users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz,
  revoked_at  timestamptz
);

-- ------------------------------------------------------------------
-- ops: tareas, cola de trabajos y aprobaciones humanas
-- ------------------------------------------------------------------
CREATE TABLE ops.tasks (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requested_by_agent uuid REFERENCES org.agents(id),
  requested_by_user  uuid REFERENCES auth.users(id),
  department_id  smallint REFERENCES org.departments(id),
  kind           text NOT NULL,
  title          text NOT NULL,
  payload        jsonb NOT NULL DEFAULT '{}',
  payload_hash   bytea NOT NULL,                -- sha256 del payload canónico (lo calcula un trigger)
  idempotency_key text UNIQUE,                  -- la misma solicitud repetida no crea otra tarea
  risk           ops.risk_level NOT NULL,
  status         text NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','awaiting_approval','approved','rejected',
                                   'running','done','failed','cancelled')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (requested_by_agent IS NOT NULL OR requested_by_user IS NOT NULL),
  CHECK (risk <> 'R4' OR requested_by_agent IS NULL)  -- R4 nunca puede pedirla un agente
);
CREATE INDEX ON ops.tasks (status, created_at);

-- Aprobación humana ligada al contenido EXACTO (hash), con vencimiento, TOTP de un solo uso
-- y consumo único. Solo se modifica a través de funciones (03-ops-functions.sql).
CREATE TABLE ops.approvals (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id       uuid NOT NULL UNIQUE REFERENCES ops.tasks(id) ON DELETE CASCADE,
  payload_hash  bytea NOT NULL,     -- copia del hash de la tarea al momento de pedir aprobación
  summary       text NOT NULL,      -- explicación legible para el humano
  status        text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','approved','rejected','expired','consumed')),
  requested_at  timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  decided_by    uuid REFERENCES auth.users(id),
  decided_at    timestamptz,
  decision_note text,
  totp_step     bigint,             -- paso TOTP usado para aprobar (no reutilizable)
  consumed_at   timestamptz,
  consumed_by_job bigint,
  CHECK (expires_at > requested_at AND expires_at <= requested_at + interval '72 hours'),
  CHECK (status NOT IN ('approved','consumed') OR (decided_by IS NOT NULL AND totp_step IS NOT NULL)),
  CHECK (status <> 'consumed' OR consumed_at IS NOT NULL)
);

-- Colas: dónde puede ejecutarse cada tipo de trabajo.
-- 'render' corre SOLO en workers externos: el VPS nunca renderiza video pesado.
CREATE TABLE ops.queues (
  name            text PRIMARY KEY,
  runs_on         text NOT NULL CHECK (runs_on IN ('vps','external')),
  lease_seconds   int NOT NULL DEFAULT 300 CHECK (lease_seconds BETWEEN 30 AND 21600),
  max_attempts    int NOT NULL DEFAULT 3,
  description     text NOT NULL
);

CREATE TABLE ops.jobs (
  id               bigserial PRIMARY KEY,
  task_id          uuid REFERENCES ops.tasks(id) ON DELETE CASCADE,
  queue            text NOT NULL REFERENCES ops.queues(name),
  kind             text NOT NULL,
  idempotency_key  text NOT NULL UNIQUE,     -- encolar dos veces lo mismo = un solo trabajo
  external_effect  boolean NOT NULL DEFAULT false,  -- true si toca el mundo exterior (publicar, pagar)
  status           text NOT NULL DEFAULT 'queued'
                   CHECK (status IN ('queued','running','succeeded','failed','dead','needs_review','cancelled')),
  attempts         int NOT NULL DEFAULT 0,
  max_attempts     int NOT NULL DEFAULT 3,
  run_after        timestamptz NOT NULL DEFAULT now(),
  lease_owner      text,
  lease_token      uuid,                     -- "fencing token": cambia en cada toma; un worker viejo no puede cerrar el trabajo
  lease_expires_at timestamptz,
  heartbeat_at     timestamptz,
  effect_started_at timestamptz,             -- marcado justo antes de un efecto externo
  last_error       text,
  result           jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  CHECK (status <> 'running' OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL))
);
-- La cola se consume con SELECT ... FOR UPDATE SKIP LOCKED sobre este índice
CREATE INDEX jobs_ready_idx ON ops.jobs (queue, run_after) WHERE status = 'queued';
CREATE INDEX jobs_lease_idx ON ops.jobs (lease_expires_at) WHERE status = 'running';

-- Comandos administrativos pedidos desde el panel (Android) y ejecutados por un servicio del host.
-- Los contenedores NUNCA tienen acceso al socket de Docker.
CREATE TABLE ops.admin_commands (
  id           bigserial PRIMARY KEY,
  command      text NOT NULL CHECK (command IN ('deploy','rollback','backup_now','restore_test',
                                                'restart_service','agents_stop','status_report')),
  environment  text NOT NULL CHECK (environment IN ('prod','staging')),
  args         jsonb NOT NULL DEFAULT '{}',
  requested_by uuid NOT NULL REFERENCES auth.users(id),
  totp_step    bigint NOT NULL,
  status       text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','succeeded','failed')),
  requested_at timestamptz NOT NULL DEFAULT now(),
  started_at   timestamptz,
  finished_at  timestamptz,
  output       text
);

-- ------------------------------------------------------------------
-- memory: memoria empresarial (la parte vectorial va en 02-memory-vector.sql)
-- ------------------------------------------------------------------
CREATE TABLE memory.documents (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id  smallint REFERENCES org.departments(id),
  title          text NOT NULL,
  source         text NOT NULL,                 -- 'manual', 'agent', 'import', ...
  storage_key    text,                          -- si el original está en R2
  body           text,
  tags           text[] NOT NULL DEFAULT '{}',
  sensitivity    text NOT NULL DEFAULT 'internal'
                 CHECK (sensitivity IN ('public','internal','confidential')),
  created_by_agent uuid REFERENCES org.agents(id),
  created_by_user  uuid REFERENCES auth.users(id),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memory.facts (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject      text NOT NULL,
  statement    text NOT NULL,
  confidence   numeric(3,2) NOT NULL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
  source_doc   uuid REFERENCES memory.documents(id) ON DELETE SET NULL,
  valid_from   timestamptz NOT NULL DEFAULT now(),
  valid_to     timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memory.decisions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  title        text NOT NULL,
  context      text NOT NULL,
  decision     text NOT NULL,
  rationale    text NOT NULL,
  decided_by_user  uuid REFERENCES auth.users(id),
  proposed_by_agent uuid REFERENCES org.agents(id),
  created_at   timestamptz NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------
-- storage: índice de objetos en almacenamiento S3 (R2)
-- ------------------------------------------------------------------
CREATE TABLE storage.objects (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  bucket        text NOT NULL,
  object_key    text NOT NULL,
  sha256        bytea NOT NULL,
  size_bytes    bigint NOT NULL CHECK (size_bytes >= 0),
  content_type  text,
  project_id    uuid,                              -- FK añadida abajo
  retention     text NOT NULL DEFAULT 'standard'
                CHECK (retention IN ('temp','standard','permanent')),
  delete_after  timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (bucket, object_key)
);

-- ------------------------------------------------------------------
-- content: preparado para producción (vacío en Etapa 1)
-- ------------------------------------------------------------------
CREATE TABLE content.channels (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  platform         text NOT NULL DEFAULT 'youtube',
  external_id      text UNIQUE,                 -- id del canal, se completa en Etapa 2
  name             text NOT NULL,
  connection_id    uuid,                        -- FK a integrations.connections
  created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE content.projects (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  channel_id   uuid REFERENCES content.channels(id),
  title        text NOT NULL,
  stage        text NOT NULL DEFAULT 'idea'
               CHECK (stage IN ('idea','research','script','design','production',
                                'editing','review','ready','published','archived')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE storage.objects
  ADD CONSTRAINT objects_project_fk FOREIGN KEY (project_id)
  REFERENCES content.projects(id) ON DELETE SET NULL;

CREATE TABLE content.videos (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id      uuid NOT NULL REFERENCES content.projects(id),
  external_id     text UNIQUE,                  -- id de YouTube tras publicar
  title           text NOT NULL,
  description     text,
  visibility      text NOT NULL DEFAULT 'private'
                  CHECK (visibility IN ('private','unlisted','public','scheduled')),
  scheduled_at    timestamptz,
  published_at    timestamptz,
  approval_id     uuid REFERENCES ops.approvals(id),  -- publicar exige aprobación
  created_at      timestamptz NOT NULL DEFAULT now(),
  CHECK (published_at IS NULL OR approval_id IS NOT NULL)
);

-- ------------------------------------------------------------------
-- analytics: preparado para YouTube Analytics
-- ------------------------------------------------------------------
CREATE TABLE analytics.video_metrics_daily (
  video_id        uuid NOT NULL REFERENCES content.videos(id) ON DELETE CASCADE,
  day             date NOT NULL,
  views           bigint,
  watch_minutes   numeric,
  avg_view_pct    numeric(5,2),
  likes           bigint,
  comments        bigint,
  subscribers_net int,
  impressions     bigint,
  ctr_pct         numeric(5,2),
  revenue_usd     numeric(12,4),
  fetched_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (video_id, day)
);

-- ------------------------------------------------------------------
-- finance: proveedores, presupuestos y consumo
-- ------------------------------------------------------------------
CREATE TABLE finance.providers (
  code          text PRIMARY KEY,           -- 'hetzner', 'anthropic', 'cloudflare_r2', ...
  name          text NOT NULL,
  billing_kind  text NOT NULL CHECK (billing_kind IN ('free','fixed','variable','quota')),
  monthly_fixed_usd numeric(10,2) NOT NULL DEFAULT 0,
  notes         text
);

CREATE TABLE finance.budgets (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  scope          text NOT NULL CHECK (scope IN ('global','department','provider','agent')),
  department_id  smallint REFERENCES org.departments(id),
  provider_code  text REFERENCES finance.providers(code),
  agent_id       uuid REFERENCES org.agents(id),
  period         text NOT NULL DEFAULT 'month' CHECK (period IN ('day','month')),
  limit_usd      numeric(10,2) NOT NULL CHECK (limit_usd >= 0),
  hard_stop      boolean NOT NULL DEFAULT true,
  alert_pcts     int[] NOT NULL DEFAULT '{50,80,100}',
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE finance.usage_events (
  id             bigserial PRIMARY KEY,
  occurred_at    timestamptz NOT NULL DEFAULT now(),
  provider_code  text NOT NULL REFERENCES finance.providers(code),
  agent_id       uuid REFERENCES org.agents(id),
  department_id  smallint REFERENCES org.departments(id),
  task_id        uuid REFERENCES ops.tasks(id),
  metric         text NOT NULL,              -- 'input_tokens', 'output_tokens', 'gb_month', 'quota_units'
  quantity       numeric NOT NULL,
  cost_usd_est   numeric(12,6) NOT NULL DEFAULT 0,
  meta           jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX ON finance.usage_events (provider_code, occurred_at);
CREATE INDEX ON finance.usage_events (department_id, occurred_at);

-- ------------------------------------------------------------------
-- integrations: cuentas conectadas y MCP (sin conectar nada en Etapa 1)
-- ------------------------------------------------------------------
CREATE TABLE integrations.connections (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  provider       text NOT NULL,                -- 'youtube', 'google_drive', ...
  account_label  text NOT NULL,
  secret_ref     text NOT NULL,                -- nombre de la variable/archivo, NUNCA el secreto
  scopes         text[] NOT NULL DEFAULT '{}',
  authorized_by  uuid NOT NULL REFERENCES auth.users(id),
  environment    text NOT NULL CHECK (environment IN ('prod','staging')),
  status         text NOT NULL DEFAULT 'disabled' CHECK (status IN ('active','disabled','revoked')),
  created_at     timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE content.channels
  ADD CONSTRAINT channels_connection_fk FOREIGN KEY (connection_id)
  REFERENCES integrations.connections(id);

-- Independencia de proveedor de IA: los agentes piden una RUTA ('razonamiento', 'redaccion', ...),
-- no un modelo. Cada ruta tiene un proveedor principal y alternativas. Cambiar de proveedor = editar filas.
CREATE TABLE integrations.llm_providers (
  code         text PRIMARY KEY,               -- 'anthropic', 'openai_compatible', 'local'
  api_style    text NOT NULL CHECK (api_style IN ('anthropic_messages','openai_chat','none')),
  base_url     text,
  secret_ref   text,                           -- nombre de la variable de entorno, nunca la llave
  is_enabled   boolean NOT NULL DEFAULT false
);

CREATE TABLE integrations.model_routes (
  route        text NOT NULL,                  -- 'razonamiento', 'redaccion', 'clasificacion', 'embeddings'
  priority     smallint NOT NULL,              -- 1 = principal, 2+ = alternativas
  provider     text NOT NULL REFERENCES integrations.llm_providers(code),
  model        text NOT NULL,
  max_output_tokens int,
  is_enabled   boolean NOT NULL DEFAULT true,
  PRIMARY KEY (route, priority)
);

CREATE TABLE integrations.mcp_servers (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code          text NOT NULL UNIQUE,
  transport     text NOT NULL CHECK (transport IN ('stdio','streamable_http')),
  endpoint      text,                          -- URL interna o comando
  owner         text NOT NULL CHECK (owner IN ('crdo','third_party')),
  connection_id uuid REFERENCES integrations.connections(id),
  is_enabled    boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE integrations.mcp_tool_grants (
  server_id   uuid NOT NULL REFERENCES integrations.mcp_servers(id) ON DELETE CASCADE,
  tool_name   text NOT NULL,
  agent_id    uuid NOT NULL REFERENCES org.agents(id) ON DELETE CASCADE,
  risk        ops.risk_level NOT NULL,
  granted_by  uuid NOT NULL REFERENCES auth.users(id),
  granted_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (server_id, tool_name, agent_id),
  CHECK (risk <> 'R4')                         -- herramientas R4 nunca se otorgan a agentes
);

-- ------------------------------------------------------------------
-- audit: registro append-only con hash encadenado
-- ------------------------------------------------------------------
CREATE TABLE audit.events (
  id            bigserial PRIMARY KEY,
  occurred_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  actor_type    text NOT NULL CHECK (actor_type IN ('user','agent','system')),
  actor_user    uuid REFERENCES auth.users(id),
  actor_agent   uuid REFERENCES org.agents(id),
  action        text NOT NULL,
  resource      text,
  outcome       text NOT NULL CHECK (outcome IN ('success','denied','error')),
  severity      text NOT NULL DEFAULT 'info' CHECK (severity IN ('debug','info','warning','high','critical')),
  ip            inet,
  correlation_id uuid,
  detail        jsonb NOT NULL DEFAULT '{}',
  prev_hash     bytea,
  hash          bytea
);
CREATE INDEX ON audit.events (occurred_at);
CREATE INDEX ON audit.events (action, occurred_at);

-- Encadena cada evento con el anterior: cualquier edición o borrado rompe la cadena.
CREATE FUNCTION audit.chain_hash() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = audit, public AS $$
DECLARE
  last bytea;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('audit.events'));
  SELECT hash INTO last FROM audit.events ORDER BY id DESC LIMIT 1;
  NEW.prev_hash := last;
  NEW.hash := digest(
    coalesce(encode(last, 'hex'), '') || '|' ||
    to_char(NEW.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') || '|' ||
    NEW.actor_type || '|' || coalesce(NEW.actor_user::text, '') || '|' ||
    coalesce(NEW.actor_agent::text, '') || '|' || NEW.action || '|' ||
    coalesce(NEW.resource, '') || '|' || NEW.outcome || '|' || NEW.detail::text,
    'sha256');
  RETURN NEW;
END $$;

CREATE TRIGGER events_chain BEFORE INSERT ON audit.events
  FOR EACH ROW EXECUTE FUNCTION audit.chain_hash();

-- ------------------------------------------------------------------
-- sys: operación de la plataforma
-- ------------------------------------------------------------------
CREATE TABLE sys.settings (
  key         text PRIMARY KEY,
  value       jsonb NOT NULL,
  updated_by  uuid REFERENCES auth.users(id),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE sys.deployments (
  id           bigserial PRIMARY KEY,
  environment  text NOT NULL CHECK (environment IN ('prod','staging')),
  version      text NOT NULL,
  image_digest text,
  status       text NOT NULL CHECK (status IN ('started','succeeded','failed','rolled_back')),
  started_at   timestamptz NOT NULL DEFAULT now(),
  finished_at  timestamptz,
  notes        text
);

CREATE TABLE sys.backup_runs (
  id            bigserial PRIMARY KEY,
  kind          text NOT NULL CHECK (kind IN ('backup','restore_test','restore')),
  environment   text NOT NULL CHECK (environment IN ('prod','staging')),
  started_at    timestamptz NOT NULL DEFAULT now(),
  finished_at   timestamptz,
  status        text NOT NULL CHECK (status IN ('running','succeeded','failed')),
  snapshot_id   text,
  size_bytes    bigint,
  detail        jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE sys.health_checks (
  id           bigserial PRIMARY KEY,
  checked_at   timestamptz NOT NULL DEFAULT now(),
  component    text NOT NULL,
  ok           boolean NOT NULL,
  cpu_pct      numeric(5,2),
  mem_pct      numeric(5,2),
  disk_pct     numeric(5,2),
  detail       jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX ON sys.health_checks (component, checked_at DESC);

-- ------------------------------------------------------------------
-- Datos iniciales
-- ------------------------------------------------------------------
INSERT INTO org.departments (code, name, reports_to) VALUES
  ('direction',   'Dirección',                       'owner'),
  ('research',    'Investigación',                   'direction'),
  ('strategy',    'Estrategia',                      'direction'),
  ('scripts',     'Guiones',                         'direction'),
  ('design',      'Diseño',                          'direction'),
  ('production',  'Producción audiovisual',          'direction'),
  ('editing',     'Edición',                         'direction'),
  ('publishing',  'Publicación',                     'direction'),
  ('marketing',   'Marketing',                       'direction'),
  ('analytics',   'Analítica',                       'direction'),
  ('finance',     'Finanzas',                        'direction'),
  ('audit',       'Auditoría y control de calidad',  'owner');

INSERT INTO org.permissions (code, description, risk) VALUES
  ('memory.read',          'Leer memoria empresarial',                         'R0'),
  ('analytics.read',       'Leer métricas',                                    'R0'),
  ('memory.write',         'Escribir notas y documentos internos',             'R1'),
  ('task.create',          'Crear tareas internas',                            'R1'),
  ('storage.write',        'Subir archivos de trabajo',                        'R1'),
  ('llm.invoke',           'Llamar a un modelo de IA dentro del presupuesto',  'R2'),
  ('media.generate',       'Generar imágenes/audio con proveedor externo',     'R2'),
  ('youtube.publish',      'Publicar o programar un video',                    'R3'),
  ('youtube.edit_public',  'Editar título/descripción/miniatura pública',      'R3'),
  ('youtube.comment',      'Responder comentarios públicos',                   'R3'),
  ('finance.spend',        'Gasto fuera de presupuesto',                       'R3'),
  ('integrations.connect', 'Conectar una cuenta externa',                      'R3'),
  ('youtube.delete',       'Borrar videos o canal',                            'R4'),
  ('account.modify',       'Cambiar contraseñas, pagos o dueños de cuentas',   'R4'),
  ('permissions.modify',   'Modificar permisos',                               'R4'),
  ('backup.delete',        'Borrar respaldos',                                 'R4');

-- R4 nunca puede asignarse a un agente
CREATE FUNCTION org.forbid_r4_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (SELECT risk FROM org.permissions WHERE code = NEW.permission_code) = 'R4' THEN
    RAISE EXCEPTION 'Permiso % es R4: no se puede otorgar a agentes', NEW.permission_code;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agent_permissions_no_r4 BEFORE INSERT OR UPDATE ON org.agent_permissions
  FOR EACH ROW EXECUTE FUNCTION org.forbid_r4_grant();

INSERT INTO finance.providers (code, name, billing_kind, notes) VALUES
  ('hetzner',       'Hetzner Cloud',        'fixed',    'VPS + IPv4 + backups. Verificar precio vigente'),
  ('cloudflare',    'Cloudflare',           'free',     'DNS, Tunnel, Access'),
  ('cloudflare_r2', 'Cloudflare R2',        'variable', 'Gratis hasta el cupo; luego por GB-mes'),
  ('backblaze_b2',  'Backblaze B2',         'variable', 'Respaldos restic'),
  ('anthropic',     'Anthropic Claude API', 'variable', 'Etapa 2. Por tokens; tope en consola'),
  ('youtube_api',   'YouTube Data/Analytics API', 'quota', 'Etapa 2. Cuota diaria en unidades'),
  ('domain',        'Registro de dominio',  'fixed',    'Anual');

INSERT INTO sys.settings (key, value) VALUES
  ('agents_enabled',     'false'),     -- kill switch global
  ('llm_gateway_enabled','false'),
  ('mcp_gateway_enabled','false'),
  ('approval_ttl_hours', '24');

INSERT INTO ops.queues (name, runs_on, lease_seconds, max_attempts, description) VALUES
  ('default',  'vps',      300,   3, 'Tareas internas livianas'),
  ('io',       'vps',      600,   5, 'Llamadas a APIs externas (IA, YouTube) y transferencias'),
  ('publish',  'vps',      900,   1, 'Efectos externos irreversibles: 1 intento, nunca reintento automático'),
  ('render',   'external', 7200,  2, 'Render/edición pesada: SOLO workers externos; el VPS no la toma'),
  ('ai_media', 'external', 3600,  2, 'Generación de imagen/voz/video en proveedores externos o GPU alquilada');

INSERT INTO integrations.llm_providers (code, api_style, secret_ref) VALUES
  ('anthropic',         'anthropic_messages', 'ANTHROPIC_API_KEY'),
  ('openai_compatible', 'openai_chat',        'OPENAI_COMPAT_API_KEY'),  -- cualquier proveedor/servidor con API estilo OpenAI
  ('local',             'openai_chat',        NULL);                     -- modelo propio futuro (p. ej. en GPU alquilada)

-- ------------------------------------------------------------------
-- Permisos mínimos por rol
-- ------------------------------------------------------------------
-- Nadie crea objetos en public, pero los roles sí pueden USAR las extensiones que viven ahí
-- (vector, citext, pgcrypto). La v1 hacía REVOKE ALL y la app no podía usar pgvector: lo detectó la prueba 40.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO crdo_app, crdo_readonly, crdo_backup, crdo_ops;

GRANT USAGE ON SCHEMA auth, org, ops, memory, content, storage, analytics, finance, integrations, audit, sys
  TO crdo_app, crdo_readonly, crdo_backup;
GRANT USAGE ON SCHEMA ops, sys TO crdo_ops;

-- Base: la app lee y escribe tablas de negocio...
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA
  auth, org, ops, memory, content, storage, analytics, finance, integrations, sys TO crdo_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA
  auth, org, ops, memory, content, storage, analytics, finance, integrations, audit, sys TO crdo_app;

-- ...salvo lo crítico, que solo cambia a través de funciones con TOTP (03-ops-functions.sql):
REVOKE ALL ON auth.totp_secrets FROM crdo_app;                                   -- ni siquiera lectura
-- (un REVOKE de columnas no alcanza si hay permiso sobre la tabla: se quita el de tabla y se dan columnas)
REVOKE UPDATE ON auth.users FROM crdo_app;
GRANT UPDATE (display_name, password_hash, is_active, failed_logins, locked_until, updated_at)
  ON auth.users TO crdo_app;                                                    -- no rol, no TOTP
REVOKE INSERT, UPDATE, DELETE ON org.permissions FROM crdo_app;
REVOKE INSERT, UPDATE, DELETE ON org.agent_permissions FROM crdo_app;           -- otorgar permisos = R4
REVOKE INSERT, UPDATE, DELETE ON integrations.mcp_tool_grants FROM crdo_app;
REVOKE INSERT, UPDATE, DELETE ON integrations.llm_providers, integrations.model_routes FROM crdo_app;
REVOKE INSERT, UPDATE, DELETE ON ops.approvals FROM crdo_app;                    -- solo funciones
REVOKE INSERT, UPDATE, DELETE ON ops.jobs FROM crdo_app;                         -- solo funciones (leases)
REVOKE INSERT, UPDATE, DELETE ON ops.admin_commands FROM crdo_app;               -- solo funciones
REVOKE INSERT, UPDATE, DELETE ON ops.queues FROM crdo_app;
REVOKE UPDATE, DELETE ON ops.tasks FROM crdo_app;
GRANT UPDATE (status, updated_at) ON ops.tasks TO crdo_app;                     -- contenido inmutable
REVOKE UPDATE, DELETE ON sys.settings FROM crdo_app;                             -- kill switch: función con TOTP
REVOKE UPDATE, DELETE ON sys.backup_runs, sys.deployments FROM crdo_app;
GRANT SELECT, INSERT ON audit.events TO crdo_app;

-- Servicio del host (crdo-opsd): solo toma y cierra comandos administrativos
GRANT SELECT, UPDATE (status, started_at, finished_at, output) ON ops.admin_commands TO crdo_ops;
GRANT INSERT ON sys.deployments, sys.backup_runs TO crdo_ops;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA sys TO crdo_ops;

GRANT SELECT ON ALL TABLES IN SCHEMA
  auth, org, ops, memory, content, storage, analytics, finance, integrations, audit, sys TO crdo_backup;

GRANT SELECT ON ALL TABLES IN SCHEMA
  org, ops, memory, content, storage, analytics, finance, sys TO crdo_readonly;
-- crdo_readonly no ve auth ni integrations (credenciales) ni auditoría completa

COMMIT;
