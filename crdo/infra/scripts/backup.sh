#!/usr/bin/env bash
# C.R.D.O. — Respaldo (BORRADOR v2, no ejecutado en un servidor real).
# 1. pg_dump (formato custom) + manifiesto (conteos y último hash de auditoría)
# 2. Verifica que el volcado sea legible (pg_restore --list)
# 3. Sube a restic: cifrado AES-256 + autenticado del lado del cliente, ANTES de salir del VPS
# 4. Ping a healthchecks.io (éxito / fallo)
#
# Variables (con valores por defecto para el servidor; se pueden cambiar para pruebas):
#   CRDO_SECRETS_DIR  (/opt/crdo/secrets)  debe contener restic.env:
#                     RESTIC_REPOSITORY, RESTIC_PASSWORD[, B2_ACCOUNT_ID, B2_ACCOUNT_KEY, HC_PING_BACKUP]
#   CRDO_BACKUP_WORK  (/opt/crdo/backups)
#   CRDO_CONFIG_DIR   (/opt/crdo/app/infra)
#   PG_DUMP_CMD       (docker exec crdo-<env>-postgres-1 pg_dump -U crdo_owner -d crdo)
#   PSQL_CMD          (docker exec -i crdo-<env>-postgres-1 psql -U crdo_owner -d crdo)
set -euo pipefail

ENV_NAME="${1:-prod}"
SECRETS="${CRDO_SECRETS_DIR:-/opt/crdo/secrets}"
WORK="${CRDO_BACKUP_WORK:-/opt/crdo/backups}"
CONFIG_DIR="${CRDO_CONFIG_DIR:-/opt/crdo/app/infra}"
PG="crdo-$ENV_NAME-postgres-1"
PG_DUMP_CMD="${PG_DUMP_CMD:-docker exec $PG pg_dump -U crdo_owner -d crdo}"
PSQL_CMD="${PSQL_CMD:-docker exec -i $PG psql -U crdo_owner -d crdo}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="$WORK/crdo-$ENV_NAME-$STAMP"

set -a; source "$SECRETS/restic.env"; set +a

ping_hc() { [[ -n "${HC_PING_BACKUP:-}" ]] && curl -fsS -m 10 --retry 3 "$HC_PING_BACKUP$1" >/dev/null || true; }
fail() { ping_hc "/fail"; echo "RESPALDO FALLIDO: $*" >&2; rm -rf "$DIR"; exit 1; }
trap 'fail "línea $LINENO"' ERR

ping_hc "/start"
mkdir -p "$DIR"; chmod 700 "$DIR"

# 1. Volcado + manifiesto
$PG_DUMP_CMD -Fc >"$DIR/crdo.dump"
[[ -s "$DIR/crdo.dump" ]] || fail "volcado vacío"
$PSQL_CMD -At >"$DIR/manifest.txt" <<'SQL'
SELECT 'departments=' || count(*) FROM org.departments;
SELECT 'users='       || count(*) FROM auth.users;
SELECT 'tasks='       || count(*) FROM ops.tasks;
SELECT 'jobs='        || count(*) FROM ops.jobs;
SELECT 'documents='   || count(*) FROM memory.documents;
SELECT 'chunks='      || count(*) FROM memory.chunks;
SELECT 'audit_events='|| count(*) FROM audit.events;
SELECT 'audit_last_hash=' || coalesce(encode(hash, 'hex'), '') FROM audit.events ORDER BY id DESC LIMIT 1;
SQL

# 2. El volcado debe ser legible antes de subirlo
pg_restore --list "$DIR/crdo.dump" >/dev/null 2>&1 \
  || docker exec -i "$PG" pg_restore --list <"$DIR/crdo.dump" >/dev/null \
  || fail "volcado ilegible"

# 3. Subida cifrada
restic cat config >/dev/null 2>&1 || fail "repositorio restic inaccesible o contraseña incorrecta"
restic backup --quiet --tag "$ENV_NAME" --tag db --host crdo "$DIR"
if [[ -d "$CONFIG_DIR" ]]; then
  restic backup --quiet --tag "$ENV_NAME" --tag config --host crdo --exclude '*.env' "$CONFIG_DIR"
fi
# Limpieza (forget/prune) NO se hace desde el VPS: su llave de B2 no puede borrar. Ver runbook mensual.

rm -rf "$DIR"
find "$WORK" -maxdepth 1 -name 'crdo-*' -mtime +2 -exec rm -rf {} + 2>/dev/null || true

ping_hc ""
echo "Respaldo $ENV_NAME OK ($STAMP)"
