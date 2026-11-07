#!/usr/bin/env bash
# C.R.D.O. — Respaldo diario (BORRADOR, no ejecutado).
# pg_dump (formato custom) -> restic cifrado en Backblaze B2 -> ping a healthchecks.io.
# Lee: /opt/crdo/secrets/restic.env  (RESTIC_REPOSITORY, RESTIC_PASSWORD, B2_ACCOUNT_ID, B2_ACCOUNT_KEY, HC_PING_BACKUP)
set -euo pipefail

ENV_NAME="${1:-prod}"
SECRETS=/opt/crdo/secrets
WORK=/opt/crdo/backups
STAMP="$(date +%Y%m%dT%H%M%S)"
DUMP="$WORK/crdo-$ENV_NAME-$STAMP.dump"
PG_CONTAINER="crdo-$ENV_NAME-postgres-1"

set -a; source "$SECRETS/restic.env"; set +a

ping_hc() { [[ -n "${HC_PING_BACKUP:-}" ]] && curl -fsS -m 10 --retry 3 "$HC_PING_BACKUP$1" >/dev/null || true; }
fail() { ping_hc "/fail"; echo "RESPALDO FALLIDO: $*" >&2; exit 1; }
trap 'fail "línea $LINENO"' ERR

ping_hc "/start"

docker exec "$PG_CONTAINER" pg_dump -U crdo_owner -d crdo -Fc >"$DUMP"
[[ -s "$DUMP" ]] || fail "volcado vacío"

restic snapshots >/dev/null 2>&1 || fail "repositorio restic inaccesible"
restic backup --tag "$ENV_NAME" --tag db "$DUMP"
restic backup --tag "$ENV_NAME" --tag config /opt/crdo/app/crdo/infra

# La limpieza (forget/prune) NO se ejecuta desde el VPS: la llave de B2 no tiene permiso de borrado.
# Se hace mensualmente con una llave separada que solo tiene el dueño (ver runbook).

rm -f "$DUMP"
find "$WORK" -name 'crdo-*.dump' -mtime +2 -delete

ping_hc ""
echo "Respaldo $ENV_NAME OK ($STAMP)"
