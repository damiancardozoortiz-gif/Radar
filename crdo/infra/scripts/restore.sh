#!/usr/bin/env bash
# C.R.D.O. — Restauración (BORRADOR, no ejecutado).
# Modos:
#   restore.sh test            -> restaura el último volcado en un Postgres temporal y compara conteos (no toca prod)
#   restore.sh into <entorno>  -> restaura sobre el entorno indicado (pide confirmación escrita)
set -euo pipefail

MODE="${1:?Uso: restore.sh test | into <prod|staging>}"
SECRETS=/opt/crdo/secrets
WORK="$(mktemp -d /opt/crdo/backups/restore.XXXXXX)"
trap 'rm -rf "$WORK"; docker rm -f crdo-restore-test >/dev/null 2>&1 || true' EXIT

set -a; source "$SECRETS/restic.env"; set +a

restic restore latest --tag db --target "$WORK"
DUMP="$(find "$WORK" -name '*.dump' | sort | tail -n1)"
[[ -n "$DUMP" ]] || { echo "No se encontró volcado" >&2; exit 1; }

case "$MODE" in
  test)
    PGTAG="$(grep -E '^PGVECTOR_TAG=' "$SECRETS/prod.env" | cut -d= -f2)"
    docker run -d --name crdo-restore-test --network none \
      -e POSTGRES_PASSWORD=restoretest -e POSTGRES_USER=crdo_owner -e POSTGRES_DB=crdo \
      -v "$DUMP":/restore.dump:ro "pgvector/pgvector:$PGTAG" >/dev/null
    for _ in $(seq 1 30); do docker exec crdo-restore-test pg_isready -U crdo_owner -d crdo >/dev/null 2>&1 && break; sleep 2; done
    docker exec crdo-restore-test sh -c 'for r in crdo_app crdo_readonly crdo_backup; do psql -U crdo_owner -d crdo -qc "CREATE ROLE $r" 2>/dev/null; done; true'
    docker exec crdo-restore-test pg_restore -U crdo_owner -d crdo --clean --if-exists /restore.dump
    docker exec crdo-restore-test psql -U crdo_owner -d crdo -Atc \
      "SELECT 'departments='||count(*) FROM org.departments UNION ALL
       SELECT 'audit_events='||count(*) FROM audit.events UNION ALL
       SELECT 'users='||count(*) FROM auth.users;"
    echo "Prueba de restauración OK"
    ;;
  into)
    TARGET="${2:?Indicar prod o staging}"
    read -r -p "Esto REEMPLAZA la base de datos de $TARGET. Escribí 'restaurar $TARGET' para continuar: " ok
    [[ "$ok" == "restaurar $TARGET" ]] || { echo "Cancelado"; exit 1; }
    docker compose -p "crdo-$TARGET" stop api worker scheduler
    docker cp "$DUMP" "crdo-$TARGET-postgres-1:/tmp/restore.dump"
    docker exec "crdo-$TARGET-postgres-1" pg_restore -U crdo_owner -d crdo --clean --if-exists /tmp/restore.dump
    docker exec "crdo-$TARGET-postgres-1" rm -f /tmp/restore.dump
    docker compose -p "crdo-$TARGET" start api worker scheduler
    echo "Restauración en $TARGET completada. Verificar /health y registrar en sys.backup_runs."
    ;;
  *) echo "Modo desconocido: $MODE" >&2; exit 1 ;;
esac
