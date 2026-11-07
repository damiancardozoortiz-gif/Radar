#!/usr/bin/env bash
# C.R.D.O. — Pruebas de base de datos (Etapa 1, v3.1).
#
# Modo local (por defecto): crea un clúster PostgreSQL TEMPORAL y lo borra al terminar. Funciona en:
#   - Ubuntu 24.04 (servidor futuro, CI, proot-distro dentro de Termux) con postgresql-16 + postgresql-16-pgvector
#   - Termux nativo (PostgreSQL de Termux; pgvector solo si se compiló: si falta, esas pruebas se OMITEN y se informa)
# Modo servidor existente: USE_EXISTING_SERVER=1 con PGHOST/PGPORT/PGUSER/PGPASSWORD de un servidor DE PRUEBA.
#   Nunca apuntar a producción: el script borra y recrea la base "crdo".
#
# Variables:
#   PGBIN                  carpeta de initdb/pg_ctl (se detecta sola)
#   CRDO_EXPECT_PG_MAJOR   versión esperada (16). "any" acepta otra y lo informa
#   CRDO_REPORT            archivo donde dejar el informe (opcional)
#   CRDO_INJECT_FAILURE=1  agrega una prueba que DEBE fallar: comprueba que el ejecutor detecta fallas
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INIT="$ROOT/infra/db/init"
TESTS="$ROOT/tests/db"
if [[ -z "${PGBIN:-}" ]]; then
  if [[ -x /usr/lib/postgresql/16/bin/initdb ]]; then PGBIN=/usr/lib/postgresql/16/bin
  elif command -v pg_config >/dev/null && [[ -x "$(pg_config --bindir)/initdb" ]]; then PGBIN="$(pg_config --bindir)"
  else PGBIN="$(dirname "$(command -v initdb 2>/dev/null || echo /usr/bin/initdb)")"; fi
fi
EXPECT="${CRDO_EXPECT_PG_MAJOR:-16}"
LOG="$(mktemp)"; OUT="$(mktemp)"
TMP=""
cleanup() {
  if [[ -n "$TMP" ]]; then "${RUN[@]}" "$PGBIN/pg_ctl -D $TMP/data -m fast stop" >/dev/null 2>&1 || true; rm -rf "$TMP"; fi
  rm -f "$LOG" "$OUT"
}
trap cleanup EXIT

if [[ "${USE_EXISTING_SERVER:-0}" != 1 ]]; then
  [[ -x "$PGBIN/initdb" ]] || { echo "No encuentro initdb (PGBIN=$PGBIN). Ver docs/PROTOTIPO-LOCAL.md" >&2; exit 2; }
  TMP="$(mktemp -d)"; chmod 755 "$TMP"
  if [[ $EUID -eq 0 ]] && id postgres >/dev/null 2>&1; then chown postgres "$TMP"; RUN=(su postgres -s /bin/bash -c); else RUN=(bash -c); fi
  "${RUN[@]}" "$PGBIN/initdb -D $TMP/data -A trust -U postgres" >/dev/null
  # dynamic_shared_memory_type=mmap: compatible con proot/Termux (Android no ofrece memoria compartida POSIX/SysV)
  "${RUN[@]}" "$PGBIN/pg_ctl -D $TMP/data -o \"-p 55439 -k $TMP -c listen_addresses='' -c dynamic_shared_memory_type=mmap\" -l $TMP/pg.log start" >/dev/null
  export PGHOST="$TMP" PGPORT=55439 PGUSER=postgres
  for _ in $(seq 1 30); do pg_isready -q && break; sleep 0.5; done
fi

psql -X -q -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS crdo" -c "CREATE DATABASE crdo" 2>/dev/null
for r in crdo_app crdo_readonly crdo_backup crdo_ops; do psql -X -q -d postgres -c "DROP ROLE IF EXISTS $r" 2>/dev/null || true; done
export PGDATABASE=crdo
PSQL=(psql -X -q -v ON_ERROR_STOP=1)

PGVER="$(psql -X -At -c 'SHOW server_version')"
MAJOR="$(psql -X -At -c "SELECT current_setting('server_version_num')::int / 10000")"
HAS_VECTOR="$(psql -X -At -c "SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'")"
if [[ "$EXPECT" != any && "$MAJOR" != "$EXPECT" ]]; then
  echo "PostgreSQL $MAJOR encontrado; se esperaba $EXPECT. Usar CRDO_EXPECT_PG_MAJOR=any para probar igual." >&2; exit 2
fi

echo "== Esquema (mismos archivos que usará el servidor)"
POSTGRES_USER="$PGUSER" POSTGRES_DB=crdo CRDO_DB_APP_PASSWORD=t1 CRDO_DB_READONLY_PASSWORD=t2 \
  CRDO_DB_BACKUP_PASSWORD=t3 CRDO_DB_OPS_PASSWORD=t4 bash "$INIT/00-roles.sh" >/dev/null
echo "  cargado: 00-roles.sh"
SKIPPED=()
for f in "$INIT"/0[1-9]*.sql; do
  if [[ "$(basename "$f")" == 02-memory-vector.sql && "$HAS_VECTOR" == 0 ]]; then
    SKIPPED+=("02-memory-vector.sql (pgvector no instalado)"); echo "  OMITIDO: $(basename "$f") (pgvector no instalado)"; continue
  fi
  "${PSQL[@]}" -f "$f"; echo "  cargado: $(basename "$f")"
done

echo "== Pruebas SQL"
"${PSQL[@]}" -f "$TESTS/00_helpers.sql" >/dev/null
files=("$TESTS"/[1-9]*.sql)
if [[ "${CRDO_INJECT_FAILURE:-0}" == 1 ]]; then
  echo "SELECT t.ok(false, 'FALLA INYECTADA a propósito para comprobar el ejecutor');" >"$OUT.inj.sql"
  files+=("$OUT.inj.sql")
fi
status=0
declare -A PER
for f in "${files[@]}"; do
  name="$(basename "$f")"
  if [[ "$name" == 40_pgvector.sql && "$HAS_VECTOR" == 0 ]]; then
    SKIPPED+=("40_pgvector.sql (8 pruebas: pgvector no instalado)"); echo "-- $name OMITIDO (pgvector no instalado)"; continue
  fi
  echo "-- $name"
  if ! "${PSQL[@]}" -f "$f" >"$OUT" 2>&1; then status=1; fi
  sed -nE 's/^(psql:[^ ]* )?(NOTICE|ERROR): +/\2: /p' "$OUT" | sed 's/^NOTICE: /  /' | tee -a "$LOG"
  PER[$name]="$(sed -nE 's/^(psql:[^ ]* )?NOTICE: +PASA/x/p' "$OUT" | wc -l)"
done
rm -f "$OUT.inj.sql"

conc_jobs() {
  echo "== Concurrencia de la cola: 6 workers simultáneos, 300 trabajos"
  "${PSQL[@]}" >/dev/null <<'SQL'
CREATE TABLE t.claims (job_id bigint, worker text);
GRANT INSERT, SELECT ON t.claims TO crdo_app;
SELECT set_config('crdo.x', 'x', false);
UPDATE sys.settings SET value = 'true' WHERE key = 'agents_enabled';
SELECT count(ops.enqueue_job(NULL, 'default', 'conc', 'conc-' || g)) FROM generate_series(1, 300) g;
SQL
  for w in 1 2 3 4 5 6; do
    "${PSQL[@]}" >/dev/null <<SQL &
SET ROLE crdo_app;
DO \$\$
DECLARE r record;
BEGIN
  LOOP
    SELECT * INTO r FROM ops.claim_job('w$w', 'vps', ARRAY['default']);
    EXIT WHEN r.job_id IS NULL;
    INSERT INTO t.claims VALUES (r.job_id, 'w$w');
    PERFORM pg_sleep(0.002);
    PERFORM ops.complete_job(r.job_id, r.lease_token);
    COMMIT;
  END LOOP;
END \$\$;
SQL
  done
  wait
  "${PSQL[@]}" -At 2>&1 <<'SQL' | sed -E 's/^(psql:[^ ]* )?(NOTICE|ERROR): +/  \2: /; s/  NOTICE: /  /' | grep -v '^$' | grep -v '^CONTEXT' | tee -a "$LOG" || true
SELECT t.ok((SELECT count(*) = 300 AND count(DISTINCT job_id) = 300 FROM t.claims),
            'concurrencia: 300 trabajos tomados exactamente una vez cada uno');
SELECT t.ok((SELECT count(DISTINCT worker) > 1 FROM t.claims), 'el trabajo se repartió entre varios workers');
SELECT t.ok((SELECT bool_and(status = 'succeeded' AND attempts = 1) FROM ops.jobs WHERE kind = 'conc'),
            'ningún trabajo ejecutado dos veces (attempts = 1 en los 300)');
SELECT '  reparto: ' || string_agg(worker || '=' || n, ' ' ORDER BY worker)
  FROM (SELECT worker, count(*) n FROM t.claims GROUP BY worker) x;
SQL
  true
}

conc_audit() {
  echo "== Concurrencia de la auditoría: 6 sesiones x 100 eventos simultáneos"
  before="$(psql -X -At -c 'SELECT count(*) FROM audit.events')"
  for w in 1 2 3 4 5 6; do
    "${PSQL[@]}" >/dev/null <<SQL &
SET ROLE crdo_app;
DO \$\$
BEGIN
  FOR i IN 1 .. 100 LOOP
    INSERT INTO audit.events (actor_type, action, outcome, detail) VALUES ('system', 'conc.audit', 'success', jsonb_build_object('w', $w, 'i', i));
    COMMIT;
  END LOOP;
END \$\$;
SQL
  done
  wait
  "${PSQL[@]}" -At 2>&1 <<SQL | sed -E 's/^(psql:[^ ]* )?(NOTICE|ERROR): +/  \2: /; s/  NOTICE: /  /' | grep -v '^$' | grep -v '^CONTEXT' | tee -a "$LOG" || true
SELECT t.ok((SELECT count(*) FROM audit.events) = $before + 600, 'auditoría concurrente: 600 eventos registrados, ninguno perdido');
SELECT t.ok(audit.verify_chain() IS NULL, 'auditoría concurrente: la cadena de hashes sigue íntegra');
SELECT t.ok((SELECT count(DISTINCT prev_hash) = count(*) FROM audit.events WHERE prev_hash IS NOT NULL),
            'auditoría concurrente: ningún eslabón duplicado (sin bifurcaciones)');
SQL
  true
}

if [[ $status -eq 0 ]] && ! grep -qE 'FALLA|ERROR' "$LOG"; then
  conc_jobs
  conc_audit
fi

PASSED="$(grep -c 'PASA' "$LOG" || true)"
FAILED="$(grep -cE 'FALLA|ERROR' "$LOG" || true)"
{
  echo "== Resumen"
  for k in $(printf '%s\n' "${!PER[@]}" | sort); do printf '  %-24s %3s verificaciones\n' "$k" "${PER[$k]}"; done
  echo "  concurrencia (cola+auditoría) $(grep -c 'PASA: \(concurrencia\|el trabajo se repartió\|ningún trabajo\|auditoría concurrente\)' "$LOG" || true) verificaciones"
  for s in "${SKIPPED[@]}"; do echo "  OMITIDO: $s"; done
  echo "  Entorno: PostgreSQL $PGVER · pgvector $(psql -X -At -c "SELECT coalesce((SELECT extversion FROM pg_extension WHERE extname = 'vector'), 'no instalado')") · $(uname -sm)"
  echo "  Fecha: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} | tee -a "$LOG.summary"
if [[ -n "${CRDO_REPORT:-}" ]]; then cat "$LOG" "$LOG.summary" >"$CRDO_REPORT"; fi
rm -f "$LOG.summary"
if [[ $status -ne 0 || "$FAILED" != 0 ]]; then echo "== HAY PRUEBAS FALLIDAS ($FAILED)"; exit 1; fi
echo "== OK: $PASSED verificaciones pasaron, 0 fallaron${SKIPPED:+ (con omisiones declaradas)}"
