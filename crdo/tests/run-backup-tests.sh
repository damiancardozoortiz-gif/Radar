#!/usr/bin/env bash
# C.R.D.O. — Prueba de cifrado, respaldo y restauración con los scripts REALES (infra/scripts/backup.sh y restore.sh)
# y restic real. El destino B2 se sustituye por un repositorio restic local (mismo formato y cifrado).
# Requiere: PostgreSQL + pgvector, restic y python3. Todo ocurre en un directorio temporal que se borra.
# Funciona como root (Ubuntu, CI, proot) o como usuario común (Termux). Ver docs/PROTOTIPO-LOCAL.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${PGBIN:-}" ]]; then
  if [[ -x /usr/lib/postgresql/16/bin/initdb ]]; then PGBIN=/usr/lib/postgresql/16/bin
  elif command -v pg_config >/dev/null && [[ -x "$(pg_config --bindir)/initdb" ]]; then PGBIN="$(pg_config --bindir)"
  else PGBIN="$(dirname "$(command -v initdb 2>/dev/null || echo /usr/bin/initdb)")"; fi
fi
for tool in restic python3 pg_dump pg_restore psql; do
  command -v "$tool" >/dev/null || { echo "Falta '$tool'. Ver docs/PROTOTIPO-LOCAL.md" >&2; exit 2; }
done
T="$(mktemp -d)"; chmod 755 "$T"
PASS=0
ok()   { echo "  PASA: $*"; PASS=$((PASS + 1)); }
fail() { echo "  FALLA: $*"; exit 1; }
if [[ $EUID -eq 0 ]] && id postgres >/dev/null 2>&1; then RUN=(su postgres -s /bin/bash -c); else RUN=(bash -c); fi
PGOPTS="-p 55440 -k $T/sock -c listen_addresses='' -c dynamic_shared_memory_type=mmap"
pg_start() { "${RUN[@]}" "$PGBIN/pg_ctl -D $T/pg -o \"$PGOPTS\" -l $T/pg/server.log -w start" >/dev/null; }
cleanup() { "${RUN[@]}" "$PGBIN/pg_ctl -D $T/pg -m fast stop" >/dev/null 2>&1 || true; rm -rf "$T"; }
trap cleanup EXIT
trap 'echo "  FALLA: salida inesperada en la línea $LINENO (comando: $BASH_COMMAND)"' ERR

echo "== Preparación: clúster temporal con el esquema real y datos de ejemplo"
mkdir -p "$T/pg" "$T/sock" "$T/secrets" "$T/work" "$T/repo" "$T/config"
[[ ${RUN[0]} == su ]] && chown postgres "$T/pg" "$T/sock"
chmod 700 "$T/pg"
"${RUN[@]}" "$PGBIN/initdb -D $T/pg -A trust -U postgres" >/dev/null
pg_start
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

RESTIC_PASSWORD_REAL="$(head -c 32 /dev/urandom | base64)"; export RESTIC_PASSWORD_REAL
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
bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null && ok "backup.sh terminó sin errores ($(( $(date +%s) - t0 )) s)" || fail "verificación de la línea 69"
[[ "$(restic snapshots -q --tag db --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')" == 1 ]] \
  && ok "1 snapshot de base de datos en el repositorio" || fail "verificación de la línea 71"
[[ -z "$(ls -A "$T/work")" ]] && ok "no quedan volcados sin cifrar en el disco del servidor" || fail "verificación de la línea 72"

echo "== Cifrado"
grep -raq "$MARK" "$T/repo" && fail "el marcador aparece en claro en el repositorio" || ok "contenido confidencial NO aparece en claro en el repositorio"
grep -raq "CREATE TABLE" "$T/repo" && fail "SQL en claro en el repositorio" || ok "ni el esquema SQL aparece en claro"
RESTIC_PASSWORD="contraseña-equivocada" restic snapshots -q >/dev/null 2>&1 && fail "abrió con contraseña incorrecta" \
  || ok "con contraseña incorrecta el repositorio no se puede abrir"
restic ls latest --tag config | grep -q 'prod.env$' && fail "un .env se respaldó" || ok "los archivos .env se excluyen del respaldo de configuración"

echo "== Integridad"
restic check --read-data -q >/dev/null 2>&1 && ok "restic check --read-data: repositorio íntegro" || fail "verificación de la línea 82"
cp -r "$T/repo" "$T/repo-dañado"; chmod -R u+w "$T/repo-dañado"   # restic deja sus archivos en solo lectura
pack="$(find "$T/repo-dañado/data" -type f | head -n1)"
printf '\xff\xff\xff\xff' | dd of="$pack" bs=1 seek=100 conv=notrunc 2>/dev/null
RESTIC_REPOSITORY="$T/repo-dañado" restic check --read-data -q >/dev/null 2>&1 \
  && fail "no detectó la corrupción" || ok "una corrupción de 4 bytes en el repositorio se detecta"

echo "== Fallo del volcado"
before="$(restic snapshots -q --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')"
PG_DUMP_CMD="false" bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null 2>&1 && fail "backup.sh no reportó el fallo" \
  || ok "si pg_dump falla, backup.sh termina con error (y avisaría a healthchecks /fail)"
after="$(restic snapshots -q --json | python3 -c 'import json,sys;print(len(json.load(sys.stdin)))')"
[[ "$before" == "$after" ]] && ok "un respaldo fallido no deja un snapshot incompleto" || fail "verificación de la línea 94"

echo "== Restauración en base temporal (restore.sh test)"
psql -X -q -d postgres -c "CREATE DATABASE crdo_restaurada"
# Cambios posteriores al respaldo: la prueba compara contra el MANIFIESTO del respaldo, no contra la base viva
psql -X -q -c "INSERT INTO audit.events (actor_type, action, outcome) VALUES ('system', 'posterior', 'success')"
t0=$(date +%s)
out="$(RESTORE_TEST_DSN="postgresql:///crdo_restaurada?host=$T/sock&port=55440&user=postgres" \
       bash "$ROOT/infra/scripts/restore.sh" test 2>&1)" || { echo "$out"; fail "restore.sh test falló"; }
secs=$(( $(date +%s) - t0 ))
echo "$out" | grep -q "Manifiesto coincide" && ok "restaurado: conteos y último hash de auditoría coinciden con el manifiesto" || fail "verificación de la línea 104"
echo "$out" | grep -q "restic check" && ok "restic check de una muestra de datos dentro de la prueba de restauración" || fail "verificación de la línea 105"
ok "restauración completa en ${secs} s (base pequeña; medir de nuevo con datos reales)"
[[ "$(psql -X -At -d crdo_restaurada -c "SELECT body FROM memory.documents WHERE id = '40000000-0000-0000-0000-000000000001'")" == "$MARK" ]] \
  && ok "el contenido confidencial vuelve intacto tras descifrar" || fail "verificación de la línea 108"
[[ "$(psql -X -At -d crdo_restaurada -c "SELECT count(*) FROM memory.chunks WHERE embedding IS NOT NULL")" == 50 ]] \
  && ok "los vectores (pgvector) se restauran" || fail "verificación de la línea 110"
[[ -z "$(psql -X -At -d crdo_restaurada -c 'SELECT audit.verify_chain()')" ]] && ok "cadena de auditoría íntegra tras restaurar" || fail "verificación de la línea 111"

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
# Dejar la base como estaba (el detalle original de esos eventos era '{}'): el resto de las pruebas parte de una cadena íntegra
psql -X -q -d crdo -c "UPDATE audit.events SET detail = '{}' WHERE id = 3"
[[ -z "$(psql -X -At -c 'SELECT audit.verify_chain()')" ]] \
  && ok "al revertir la alteración, la cadena vuelve a verificar (el hash depende solo del contenido)" || fail "no se pudo revertir"

echo "== Control presupuestario (budget-guard real)"
[[ "$(psql -X -At -c "SELECT count(*) > 0 FROM finance.meter_readings WHERE metric = 'b2_storage_gb' AND source = 'backup.sh'")" == t ]] \
  && ok "backup.sh registró los GB de B2 en el medidor del presupuesto ($(psql -X -At -c "SELECT value FROM finance.meter_readings WHERE metric='b2_storage_gb' ORDER BY id DESC LIMIT 1") GB)" || fail "verificación de la línea 128"
out="$(PSQL_CMD="psql -X -d crdo" CRDO_SECRETS_DIR="$T/secrets" bash "$ROOT/infra/scripts/budget-guard")"
echo "$out" | grep -q 'nivel=aviso proyeccion=US\$16.61 tope_operativo=US\$17.00' \
  && ok "guardián: peor escenario CX33 = US\$16,61, nivel aviso" || { echo "$out"; fail "salida inesperada"; }
psql -X -q -c "UPDATE finance.cost_items SET active = true WHERE code = 'r2_objects'" \
           -c "SELECT finance.record_meter('r2_storage_gb', 200, 'prueba')" >/dev/null
out="$(PSQL_CMD="psql -X -d crdo" CRDO_SECRETS_DIR="$T/secrets" bash "$ROOT/infra/scripts/budget-guard")"
echo "$out" | grep -q 'nivel=excedido' && echo "$out" | grep -q 'Escrituras en R2 pausadas' \
  && ok "guardián: 200 GB en R2 -> excedido, escrituras en R2 pausadas" || { echo "$out"; fail "no pausó"; }
[[ "$(psql -X -At -c "SELECT value FROM sys.settings WHERE key = 'storage_writes_enabled'")" == false ]] \
  && ok "la pausa quedó registrada en la base (storage_writes_enabled = false)" || fail "verificación de la línea 138"

echo "== Recuperación ante fallos"
# 1) Restaurar en un entorno distinto del origen está prohibido (producción -> staging)
if out="$(bash "$ROOT/infra/scripts/restore.sh" into staging </dev/null 2>&1)"; then fail "restore.sh into staging no se negó"; fi
echo "$out" | grep -q "No hay respaldos de 'staging'" \
  && ok "restaurar producción en staging: rechazado (no hay respaldos de staging y no se mezclan entornos)" \
  || { echo "$out"; fail "rechazo por otra razón"; }

# 2) Una base restaurada sigue funcionando: la auditoría continúa la cadena después de restaurar
psql -X -q -d crdo_restaurada -c "INSERT INTO audit.events (actor_type, action, outcome) VALUES ('system', 'post.restauracion', 'success')"
[[ -z "$(psql -X -At -d crdo_restaurada -c 'SELECT audit.verify_chain()')" ]] \
  && ok "tras restaurar, nuevos eventos de auditoría continúan la cadena sin romperla" || fail "verificación de la línea 150"

# 3) Caída abrupta del motor (kill -9 equivalente) con una transacción a medio hacer
psql -X -q -c "INSERT INTO memory.facts (subject, statement) VALUES ('recuperacion', 'confirmado antes de la caida')"
psql -X -q -c "SELECT 1" >/dev/null
( psql -X -q -c "BEGIN; INSERT INTO memory.facts (subject, statement) VALUES ('recuperacion', 'NO confirmado'); SELECT pg_sleep(30); COMMIT;" >/dev/null 2>&1 & )
sleep 1
"${RUN[@]}" "$PGBIN/pg_ctl -D $T/pg -m immediate stop" >/dev/null
pg_start
for _ in $(seq 1 30); do pg_isready -q && break; sleep 0.5; done
[[ "$(psql -X -At -c "SELECT count(*) FROM memory.facts WHERE statement = 'confirmado antes de la caida'")" == 1 ]] \
  && ok "tras una caída abrupta, lo confirmado sigue ahí (recuperación por WAL)" || fail "verificación de la línea 161"
[[ "$(psql -X -At -c "SELECT count(*) FROM memory.facts WHERE statement = 'NO confirmado'")" == 0 ]] \
  && ok "tras una caída abrupta, la transacción a medio hacer NO quedó aplicada" || fail "verificación de la línea 163"
[[ -z "$(psql -X -At -c 'SELECT audit.verify_chain()')" ]] && ok "tras la caída, la cadena de auditoría sigue íntegra" || fail "verificación de la línea 164"
# 4) Después de la caída, el ciclo respaldo -> restauración vuelve a funcionar
bash "$ROOT/infra/scripts/backup.sh" prod >/dev/null && ok "después de la caída, el respaldo vuelve a funcionar" || fail "verificación de la línea 166"

EXPECTED=29
[[ $PASS -eq $EXPECTED ]] || fail "se esperaban $EXPECTED verificaciones y pasaron $PASS (alguna no se ejecutó)"
echo "== OK: $PASS verificaciones pasaron, 0 fallaron"
