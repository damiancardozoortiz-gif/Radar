#!/usr/bin/env bash
# C.R.D.O. — Pruebas de base de datos.
# Modo local (por defecto): crea un clúster PostgreSQL 16 TEMPORAL (requiere binarios y pgvector) y lo borra al final.
# Modo CI: definir PGHOST/PGPORT/PGUSER/PGPASSWORD de un servidor de prueba (servicio pgvector/pgvector:pg16)
#          y USE_EXISTING_SERVER=1. Nunca apuntar a producción: el script borra y recrea la base "crdo".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INIT="$ROOT/infra/db/init"
TESTS="$ROOT/tests/db"
PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
LOG="$(mktemp)"
TMP=""

cleanup() {
  if [[ -n "$TMP" ]]; then
    "${RUN[@]}" "$PGBIN/pg_ctl -D $TMP/data -m fast stop" >/dev/null 2>&1 || true
    rm -rf "$TMP"
  fi
  rm -f "$LOG"
}
trap cleanup EXIT

if [[ "${USE_EXISTING_SERVER:-0}" != 1 ]]; then
  TMP="$(mktemp -d)"
  if [[ $EUID -eq 0 ]]; then chown postgres "$TMP"; RUN=(su postgres -s /bin/bash -c); else RUN=(bash -c); fi
  "${RUN[@]}" "$PGBIN/initdb -D $TMP/data -A trust -U postgres" >/dev/null
  "${RUN[@]}" "$PGBIN/pg_ctl -D $TMP/data -o \"-p 55439 -k $TMP -c listen_addresses=''\" -l $TMP/pg.log start" >/dev/null
  export PGHOST="$TMP" PGPORT=55439 PGUSER=postgres
  for _ in $(seq 1 20); do pg_isready -q && break; sleep 0.5; done
fi

psql -X -q -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS crdo" -c "CREATE DATABASE crdo"
for r in crdo_app crdo_readonly crdo_backup crdo_ops; do
  psql -X -q -d postgres -c "DROP ROLE IF EXISTS $r" 2>/dev/null || true
done
export PGDATABASE=crdo
PSQL=(psql -X -q -v ON_ERROR_STOP=1)

echo "== Esquema (mismos archivos que usará el servidor)"
POSTGRES_USER="$PGUSER" POSTGRES_DB=crdo CRDO_DB_APP_PASSWORD=t1 CRDO_DB_READONLY_PASSWORD=t2 \
  CRDO_DB_BACKUP_PASSWORD=t3 CRDO_DB_OPS_PASSWORD=t4 bash "$INIT/00-roles.sh"
echo "  cargado: 00-roles.sh"
for f in "$INIT"/0[1-9]*.sql; do "${PSQL[@]}" -f "$f"; echo "  cargado: $(basename "$f")"; done

echo "== Pruebas SQL"
"${PSQL[@]}" -f "$TESTS/00_helpers.sql"
status=0
for f in "$TESTS"/[1-9]*.sql; do
  echo "-- $(basename "$f")"
  if ! "${PSQL[@]}" -f "$f" >"$LOG.out" 2>&1; then status=1; fi
  sed -nE 's/^(psql:[^ ]* )?(NOTICE|ERROR): +/\2: /p' "$LOG.out" | sed 's/^NOTICE: /  /' | tee -a "$LOG"
done
rm -f "$LOG.out"
if [[ $status -ne 0 ]] || grep -qE 'FALLA|ERROR' "$LOG"; then echo "== HAY PRUEBAS FALLIDAS"; exit 1; fi

echo "== Concurrencia: 6 workers simultáneos, 300 trabajos"
"${PSQL[@]}" >/dev/null <<'SQL'
CREATE TABLE t.claims (job_id bigint, worker text);
GRANT INSERT, SELECT ON t.claims TO crdo_app;
UPDATE sys.settings SET value = 'true' WHERE key = 'agents_enabled';
SELECT count(ops.enqueue_job(NULL, 'default', 'conc', 'conc-' || g)) FROM generate_series(1, 300) g;
SQL
worker() {
  "${PSQL[@]}" >/dev/null <<SQL
SET ROLE crdo_app;
DO \$\$
DECLARE r record;
BEGIN
  LOOP
    SELECT * INTO r FROM ops.claim_job('w$1', 'vps', ARRAY['default']);
    EXIT WHEN r.job_id IS NULL;
    INSERT INTO t.claims VALUES (r.job_id, 'w$1');
    PERFORM pg_sleep(0.002);                       -- simula trabajo
    PERFORM ops.complete_job(r.job_id, r.lease_token);
    COMMIT;
  END LOOP;
END \$\$;
SQL
}
for w in 1 2 3 4 5 6; do worker "$w" & done
wait
"${PSQL[@]}" -At 2>&1 <<'SQL' | sed -E 's/^(psql:[^ ]* )?NOTICE: +/  /' | tee -a "$LOG"
SELECT t.ok((SELECT count(*) = 300 AND count(DISTINCT job_id) = 300 FROM t.claims),
            'concurrencia: 300 trabajos tomados exactamente una vez cada uno');
SELECT t.ok((SELECT count(DISTINCT worker) > 1 FROM t.claims), 'el trabajo se repartió entre varios workers');
SELECT t.ok((SELECT bool_and(status = 'succeeded' AND attempts = 1) FROM ops.jobs WHERE kind = 'conc'),
            'ningún trabajo ejecutado dos veces (attempts = 1 en los 300)');
SELECT '  reparto: ' || string_agg(worker || '=' || n, ' ' ORDER BY worker)
  FROM (SELECT worker, count(*) n FROM t.claims GROUP BY worker) x;
SQL
if grep -qE 'FALLA|ERROR' "$LOG"; then echo "== HAY PRUEBAS FALLIDAS"; exit 1; fi
echo "== OK: $(grep -c 'PASA' "$LOG") verificaciones pasaron, 0 fallaron"
