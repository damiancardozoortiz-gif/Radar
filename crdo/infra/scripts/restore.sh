#!/usr/bin/env bash
# C.R.D.O. — Restauración (BORRADOR v2).
#   restore.sh test             restaura el último respaldo en una base TEMPORAL y lo compara con su manifiesto
#   restore.sh into <entorno>   restaura sobre prod o staging (pide confirmación escrita)
#
# Para `test`:
#   - en el servidor: levanta un contenedor Postgres temporal sin red y lo borra al final;
#   - en pruebas/CI: si RESTORE_TEST_DSN está definido, restaura en esa base (que debe estar vacía).
set -euo pipefail

MODE="${1:?Uso: restore.sh test | into <prod|staging>}"
SECRETS="${CRDO_SECRETS_DIR:-/opt/crdo/secrets}"
WORKROOT="${CRDO_BACKUP_WORK:-/opt/crdo/backups}"
WORK="$(mktemp -d "$WORKROOT/restore.XXXXXX")"
CONTAINER=crdo-restore-test
cleanup() { rm -rf "$WORK"; [[ -z "${RESTORE_TEST_DSN:-}" ]] && docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

set -a; source "$SECRETS/restic.env"; set +a

restic restore latest --quiet --tag db --target "$WORK"
DUMP="$(find "$WORK" -name crdo.dump | head -n1)"
MANIFEST="$(dirname "$DUMP")/manifest.txt"
[[ -f "$DUMP" && -f "$MANIFEST" ]] || { echo "Respaldo incompleto (falta volcado o manifiesto)" >&2; exit 1; }

check_against_manifest() {  # $1 = comando psql hacia la base restaurada
  local psqlc="$1" got
  got="$($psqlc -At <<'SQL'
SELECT 'departments=' || count(*) FROM org.departments;
SELECT 'users='       || count(*) FROM auth.users;
SELECT 'tasks='       || count(*) FROM ops.tasks;
SELECT 'jobs='        || count(*) FROM ops.jobs;
SELECT 'documents='   || count(*) FROM memory.documents;
SELECT 'chunks='      || count(*) FROM memory.chunks;
SELECT 'audit_events='|| count(*) FROM audit.events;
SELECT 'audit_last_hash=' || coalesce(encode(hash, 'hex'), '') FROM audit.events ORDER BY id DESC LIMIT 1;
SQL
)"
  if ! diff <(echo "$got") "$MANIFEST" >/dev/null; then
    echo "DIFERENCIA con el manifiesto:" >&2; diff <(echo "$got") "$MANIFEST" >&2 || true; return 1
  fi
  local broken; broken="$($psqlc -At -c 'SELECT audit.verify_chain()')"
  [[ -z "$broken" ]] || { echo "Cadena de auditoría rota en el evento $broken" >&2; return 1; }
  echo "Manifiesto coincide ($(grep -c = "$MANIFEST") controles) y cadena de auditoría íntegra"
}

case "$MODE" in
  test)
    if [[ -n "${RESTORE_TEST_DSN:-}" ]]; then
      pg_restore --no-owner --exit-on-error -d "$RESTORE_TEST_DSN" "$DUMP"
      check_against_manifest "psql $RESTORE_TEST_DSN"
    else
      PGTAG="$(grep -E '^PGVECTOR_TAG=' "$SECRETS/prod.env" | cut -d= -f2)"
      docker run -d --name "$CONTAINER" --network none -e POSTGRES_PASSWORD=restoretest \
        -e POSTGRES_USER=crdo_owner -e POSTGRES_DB=crdo "pgvector/pgvector:$PGTAG" >/dev/null
      for _ in $(seq 1 30); do docker exec "$CONTAINER" pg_isready -U crdo_owner -d crdo >/dev/null 2>&1 && break; sleep 2; done
      for r in crdo_app crdo_readonly crdo_backup crdo_ops; do
        docker exec "$CONTAINER" psql -U crdo_owner -d crdo -qc "CREATE ROLE $r" >/dev/null
      done
      docker exec -i "$CONTAINER" pg_restore -U crdo_owner -d crdo --no-owner --exit-on-error <"$DUMP"
      check_against_manifest "docker exec -i $CONTAINER psql -U crdo_owner -d crdo"
    fi
    restic check --read-data-subset=5% >/dev/null && echo "restic check (5 % de los datos) OK"
    echo "Prueba de restauración OK"
    ;;
  into)
    TARGET="${2:?Indicar prod o staging}"
    read -r -p "Esto REEMPLAZA la base de datos de $TARGET. Escribí 'restaurar $TARGET' para continuar: " ok
    [[ "$ok" == "restaurar $TARGET" ]] || { echo "Cancelado"; exit 1; }
    docker compose -p "crdo-$TARGET" stop api worker scheduler
    docker exec -i "crdo-$TARGET-postgres-1" pg_restore -U crdo_owner -d crdo --clean --if-exists <"$DUMP"
    check_against_manifest "docker exec -i crdo-$TARGET-postgres-1 psql -U crdo_owner -d crdo"
    docker compose -p "crdo-$TARGET" start api worker scheduler
    echo "Restauración en $TARGET completada. Verificar /health."
    ;;
  *) echo "Modo desconocido: $MODE" >&2; exit 1 ;;
esac
