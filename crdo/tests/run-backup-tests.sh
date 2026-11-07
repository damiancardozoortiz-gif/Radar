#!/usr/bin/env bash
# C.R.D.O. — Prueba de cifrado, respaldo y restauración con los scripts REALES (infra/scripts/backup.sh y restore.sh)
# y restic real. El destino B2 se sustituye por un repositorio restic local (mismo formato y cifrado).
# Requiere: binarios PostgreSQL 16 + pgvector, restic. Todo ocurre en un directorio temporal que se borra.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PGBIN="${PGBIN:-/usr/lib/postgresql/16/bin}"
T="$(mktemp -d)"; chmod 755 "$T"
PASS=0
ok()   { echo "  PASA: $*"; PASS=$((PASS + 1)); }
fail() { echo "  FALLA: $*"; exit 1; }
cleanup() { su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D $T/pg -m fast stop" >/dev/null 2>&1 || true; rm -rf "$T"; }
trap cleanup EXIT

echo "== Preparación: clúster temporal con el esquema real y datos de ejemplo"
mkdir -p "$T/pg" "$T/sock" "$T/secrets" "$T/work" "$T/repo" "$T/config"; chown postgres "$T/pg" "$T/sock"
su postgres -s /bin/bash -c "$PGBIN/initdb -D $T/pg -A trust -U postgres >/dev/null"
su postgres -s /bin/bash -c "$PGBIN/pg_ctl -D $T/pg -o \"-p 55440 -k $T/sock -c listen_addresses=''\" -l $T/pg/server.log start >/dev/null"
export PGHOST="$T/sock" PGPORT=55440 PGUSER=postgres
for _ in $(seq 1 20); do pg_isready -q && break; sleep 0.5; done
psql -X -q -d postgres -c "CREATE DATABASE crdo"
export PGDATABASE=crdo
POSTGRES_USER=postgres POSTGRES_DB=crdo CRDO_DB_APP_PASSWORD=a CRDO_DB_READONLY_PASSWORD=b \
  CRDO_DB_BACKUP_PASSWORD=c CRDO_DB_OPS_PASSWORD=d bash "$ROOT/infra/db/init/00-roles.sh" >/dev/null
for f in "$ROOT"/infra/db/init/0[1-9]*.sql; do psql -X -q -v ON_ERROR_STOP=1 -f "$f"; done
MARK="MARCADOR-CONFIDENCIAL-$RANDOM$RANDOM"
psql -X -q -v ON_ERROR_STOP=1 <<SQL
INSERT INTO memory.documents (id, title, source, body)
  VALUES ('40000000-0000-0000-0000-000000000001', 'Estrategia', 'manual', '$MARK');
INSERT INTO memory.chunks (document_id, ordinal, content, embedding)
  SELECT '40000000-0000-0000-0000-000000000001', g, 'fragmento ' || g,
         (SELECT array_agg(random()::real) FROM generate_series(1, 1024) WHERE g > 0)::vector
    FROM generate_series(1, 50) g;
INSERT INTO audit.events (actor_type, action, outcome) SELECT 'system', 'prueba.' || g, 'success' FROM generate_series(1, 25) g;
SQL

# Configuración a respaldar, con un archivo de secretos que NO debe viajar
cp -r "$ROOT/infra/." "$T/config/"
echo "ANTHROPIC_API_KEY=SECRETO-FALSO-NO-DEBE-RESPALDARSE" >"$T/config/prod.env"

export RESTIC_PASSWORD_REAL="$(head -c 32 /dev/urandom | base64)"
cat >"$T/secrets/restic.env" <<EOF
RESTIC_REPOSITORY=$T/repo
RESTIC_PASSWORD=$RESTIC_PASSWORD_REAL
EOF
chmod 600 "$T/secrets/restic.env"
RESTIC_REPOSITORY="$T/repo" RESTIC_PASSWORD="$RESTIC_PASSWORD_REAL" restic init -q >/dev/null

export CRDO_SECRETS_DIR="$T/secrets" CRDO_BACKUP_WORK="$T/work" CRDO_CONFIG_DIR="$T/config"
export PG_DUMP_CMD="pg_dump -d crdo" PSQL_CMD="psql -X -d crdo"
export RESTIC_REPOSITORY="$T/repo" RESTIC_PASSWORD="$RESTIC_PASSWORD_REAL"

echo "== Respaldo"
t0=$(date +%s)
bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null && ok "backup.sh terminó sin errores ($(( $(date +%s) - t0 )) s)"
[[ "$(restic snapshots -q --tag db --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')" == 1 ]] \
  && ok "1 snapshot de base de datos en el repositorio"
[[ -z "$(ls -A "$T/work")" ]] && ok "no quedan volcados sin cifrar en el disco del servidor"

echo "== Cifrado"
grep -raq "$MARK" "$T/repo" && fail "el marcador aparece en claro en el repositorio" || ok "contenido confidencial NO aparece en claro en el repositorio"
grep -raq "CREATE TABLE" "$T/repo" && fail "SQL en claro en el repositorio" || ok "ni el esquema SQL aparece en claro"
RESTIC_PASSWORD="contraseña-equivocada" restic snapshots -q >/dev/null 2>&1 && fail "abrió con contraseña incorrecta" \
  || ok "con contraseña incorrecta el repositorio no se puede abrir"
restic ls latest --tag config | grep -q 'prod.env$' && fail "un .env se respaldó" || ok "los archivos .env se excluyen del respaldo de configuración"

echo "== Integridad"
restic check --read-data -q >/dev/null 2>&1 && ok "restic check --read-data: repositorio íntegro"
cp -r "$T/repo" "$T/repo-dañado"
pack="$(find "$T/repo-dañado/data" -type f | head -n1)"
printf '\xff\xff\xff\xff' | dd of="$pack" bs=1 seek=100 conv=notrunc 2>/dev/null
RESTIC_REPOSITORY="$T/repo-dañado" restic check --read-data -q >/dev/null 2>&1 \
  && fail "no detectó la corrupción" || ok "una corrupción de 4 bytes en el repositorio se detecta"

echo "== Fallo del volcado"
before="$(restic snapshots -q --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')"
PG_DUMP_CMD="false" bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null 2>&1 && fail "backup.sh no reportó el fallo" \
  || ok "si pg_dump falla, backup.sh termina con error (y avisaría a healthchecks /fail)"
after="$(restic snapshots -q --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')"
[[ "$before" == "$after" ]] && ok "un respaldo fallido no deja un snapshot incompleto"

echo "== Restauración en base temporal (restore.sh test)"
psql -X -q -d postgres -c "CREATE DATABASE crdo_restaurada"
# Cambios posteriores al respaldo: la prueba compara contra el MANIFIESTO del respaldo, no contra la base viva
psql -X -q -c "INSERT INTO audit.events (actor_type, action, outcome) VALUES ('system', 'posterior', 'success')"
t0=$(date +%s)
out="$(RESTORE_TEST_DSN="postgresql:///crdo_restaurada?host=$T/sock&port=55440&user=postgres" \
       bash "$ROOT/infra/scripts/restore.sh" test 2>&1)" || { echo "$out"; fail "restore.sh test falló"; }
secs=$(( $(date +%s) - t0 ))
echo "$out" | grep -q "Manifiesto coincide" && ok "restaurado: conteos y último hash de auditoría coinciden con el manifiesto"
echo "$out" | grep -q "restic check" && ok "restic check de una muestra de datos dentro de la prueba de restauración"
ok "restauración completa en ${secs} s (base pequeña; medir de nuevo con datos reales)"
[[ "$(psql -X -At -d crdo_restaurada -c "SELECT body FROM memory.documents WHERE id = '40000000-0000-0000-0000-000000000001'")" == "$MARK" ]] \
  && ok "el contenido confidencial vuelve intacto tras descifrar"
[[ "$(psql -X -At -d crdo_restaurada -c "SELECT count(*) FROM memory.chunks WHERE embedding IS NOT NULL")" == 50 ]] \
  && ok "los vectores (pgvector) se restauran"
[[ -z "$(psql -X -At -d crdo_restaurada -c 'SELECT audit.verify_chain()')" ]] && ok "cadena de auditoría íntegra tras restaurar"

echo "== Restauración detecta un respaldo inconsistente"
psql -X -q -d postgres -c "CREATE DATABASE crdo_mal"
# Se altera un evento de auditoría ANTES del siguiente volcado: la restauración debe detectarlo
psql -X -q -d crdo -c "UPDATE audit.events SET detail = '{\"x\":1}' WHERE id = 3"
bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null
if out="$(RESTORE_TEST_DSN="postgresql:///crdo_mal?host=$T/sock&port=55440&user=postgres" \
          bash "$ROOT/infra/scripts/restore.sh" test 2>&1)"; then
  fail "no detectó la auditoría alterada"
fi
echo "$out" | grep -q "Cadena de auditoría rota en el evento 3" \
  && ok "un respaldo con auditoría alterada FALLA la prueba de restauración (detecta el evento 3)" \
  || { echo "$out"; fail "falló por otra razón"; }

echo "== OK: $PASS verificaciones pasaron, 0 fallaron"
