#!/usr/bin/env bash
# C.R.D.O. — Pruebas de configuración: coherencia del repo, shellcheck, Compose y preflight con secretos de prueba.
# Las herramientas shellcheck y docker son opcionales: si faltan, esas pruebas se OMITEN y se informa.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
PASS=0; SKIP=0
ok()   { echo "  PASA: $*"; PASS=$((PASS + 1)); }
fail() { echo "  FALLA: $*"; exit 1; }
skip() { echo "  OMITIDA: $*"; SKIP=$((SKIP + 1)); }
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

echo "== Coherencia del repositorio"
bash tools/check-config.sh >"$T/cc.log" 2>&1 && ok "check-config.sh: 0 errores" || { cat "$T/cc.log"; fail "check-config.sh"; }

echo "== shellcheck"
SCRIPTS=(infra/scripts/*.sh infra/scripts/crdo infra/scripts/crdo-opsd infra/scripts/budget-guard infra/db/init/*.sh tests/*.sh tools/*.sh tools/crdo-local)
if command -v shellcheck >/dev/null; then
  shellcheck -S warning "${SCRIPTS[@]}" && ok "shellcheck (nivel warning): 0 hallazgos en ${#SCRIPTS[@]} scripts" || fail "shellcheck"
else skip "shellcheck no instalado"; fi

echo "== Docker Compose (sintaxis de las 4 variantes)"
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  sed -e 's/=cambiar/=1.0.0/' -e 's/^\(POSTGRES_OWNER_PASSWORD\|CRDO_DB_[A-Z]*_PASSWORD\)=$/\1=x/' \
      -e "s|CRDO_SECRETS_FILE=.*|CRDO_SECRETS_FILE=$T/ci.env|" -e "s|CRDO_TUNNEL_ENV_FILE=.*|CRDO_TUNNEL_ENV_FILE=$T/ci.env|" \
      infra/.env.example >"$T/ci.env"
  for extra in "" "-f infra/compose.staging.yml"; do for prof in "" "--profile edge"; do
    # shellcheck disable=SC2086
    docker compose --env-file "$T/ci.env" -f infra/compose.yml $extra $prof config -q \
      && ok "compose válido: prod${extra:+ + staging}${prof:+ + edge}" || fail "compose ${extra} ${prof}"
  done; done
  # shellcheck disable=SC2046
  nets="$(docker compose --env-file "$T/ci.env" -f infra/compose.yml -f infra/compose.staging.yml --profile edge config | grep -oE 'name: crdo-[a-z]+_[a-z]+' | sort -u)"
  echo "$nets" | grep -q 'crdo-prod' && fail "staging referencia redes de prod" || ok "staging (con túnel) solo usa redes crdo-staging_*"
else skip "docker compose no disponible (5 pruebas)"; SKIP=$((SKIP + 4)); fi

echo "== preflight.sh con secretos de prueba"
S="$T/secrets"; mkdir -p "$S"; chmod 700 "$S"
mk_env() {  # $1 archivo $2 entorno $3 puerto $4 sufijo de contraseñas
  sed -e "s/^CRDO_ENV=.*/CRDO_ENV=$2/" -e "s/^CRDO_GHCR_OWNER=.*/CRDO_GHCR_OWNER=crdo-org/" \
      -e "s/^PGVECTOR_TAG=.*/PGVECTOR_TAG=0.8.0-pg16/" -e "s/^CLOUDFLARED_VERSION=.*/CLOUDFLARED_VERSION=2026.1.0/" \
      -e "s/^CRDO_API_PORT=.*/CRDO_API_PORT=$3/" -e "s|^CRDO_DATA_DIR=.*|CRDO_DATA_DIR=/opt/crdo/data/$2|" \
      -e "s|^CRDO_SECRETS_FILE=.*|CRDO_SECRETS_FILE=/opt/crdo/secrets/$2.env|" \
      -e "s|^CRDO_ALLOWED_HOSTS=.*|CRDO_ALLOWED_HOSTS=crdo-prod.tailnet-prueba.ts.net|" \
      -e "s/^\(POSTGRES_OWNER_PASSWORD\|CRDO_DB_[A-Z]*_PASSWORD\|CRDO_SECRET_KEY\)=.*/\1=clave-larga-de-prueba-$4-\1/" \
      infra/.env.example >"$S/$1"; chmod 600 "$S/$1"
}
mk_env prod.env prod 8000 P; mk_env staging.env staging 8100 S
printf 'RESTIC_REPOSITORY=b2:crdo-backups:/restic\nRESTIC_PASSWORD=contraseña-restic-de-prueba\n' >"$S/restic.env"
printf 'HC_PING_BUDGET=https://hc-ping.com/prueba\n' >"$S/monitor.env"
chmod 600 "$S/restic.env" "$S/monitor.env"
CRDO_SECRETS_DIR="$S" bash infra/scripts/preflight.sh --secrets >"$T/pf.log" 2>&1 \
  && ok "preflight: configuración correcta aceptada" || { cat "$T/pf.log"; fail "preflight rechazó una configuración correcta"; }

expect_bad() {  # $1 descripción, $2 texto esperado en el informe
  if CRDO_SECRETS_DIR="$S" bash infra/scripts/preflight.sh --secrets >"$T/pf.log" 2>&1; then fail "$1: no lo detectó"; fi
  grep -q "$2" "$T/pf.log" && ok "preflight detecta: $1" || { cat "$T/pf.log"; fail "$1: mensaje inesperado"; }
}
chmod 644 "$S/prod.env";                      expect_bad "permisos abiertos en prod.env" "prod.env tiene permisos 644"; chmod 600 "$S/prod.env"
cp "$S/prod.env" "$T/bk"; sed -i 's/^CRDO_DB_APP_PASSWORD=.*/CRDO_DB_APP_PASSWORD=/' "$S/prod.env"
                                              expect_bad "contraseña vacía" "CRDO_DB_APP_PASSWORD vacío"; cp "$T/bk" "$S/prod.env"
sed -i 's/^PGVECTOR_TAG=.*/PGVECTOR_TAG=cambiar/' "$S/prod.env"
                                              expect_bad "valor de ejemplo sin completar" "PGVECTOR_TAG todavía tiene un valor de ejemplo"; cp "$T/bk" "$S/prod.env"
cp "$S/staging.env" "$T/bks"; sed -i "s/^CRDO_DB_APP_PASSWORD=.*/$(grep '^CRDO_DB_APP_PASSWORD=' "$S/prod.env")/" "$S/staging.env"
                                              expect_bad "misma contraseña en prod y staging" "CRDO_DB_APP_PASSWORD es IGUAL en prod y staging"; cp "$T/bks" "$S/staging.env"
sed -i 's/^CRDO_API_PORT=.*/CRDO_API_PORT=8000/' "$S/staging.env"
                                              expect_bad "mismo puerto en prod y staging" "mismo puerto de API"; cp "$T/bks" "$S/staging.env"
sed -i 's/^CRDO_SECRET_KEY=.*/CRDO_SECRET_KEY=corta/' "$S/prod.env"
                                              expect_bad "clave demasiado corta" "CRDO_SECRET_KEY tiene menos de 24"; cp "$T/bk" "$S/prod.env"
rm "$S/restic.env";                           expect_bad "falta restic.env" "falta .*restic.env"
printf 'RESTIC_REPOSITORY=b2:x:/r\nRESTIC_PASSWORD=p\n' >"$S/restic.env"; chmod 600 "$S/restic.env"
chmod 755 "$S";                               expect_bad "carpeta de secretos abierta" "la carpeta tiene permisos 755"; chmod 700 "$S"

echo "== OK: $PASS verificaciones pasaron, 0 fallaron, $SKIP omitidas"
