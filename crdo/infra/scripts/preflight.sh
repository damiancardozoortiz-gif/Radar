#!/usr/bin/env bash
# C.R.D.O. — Verificación previa al despliegue (Etapa 1, v3.1). Solo LEE: no cambia nada.
#   preflight.sh            todo (en el servidor)
#   preflight.sh --secrets  solo los archivos de secretos (sirve también para probarlo localmente)
# Variables: CRDO_SECRETS_DIR (/opt/crdo/secrets)
set -uo pipefail

SECRETS="${CRDO_SECRETS_DIR:-/opt/crdo/secrets}"
MODE="${1:-all}"
ERR=0; WARN=0
bad()  { echo "  ERROR: $*"; ERR=$((ERR + 1)); }
warn() { echo "  AVISO: $*"; WARN=$((WARN + 1)); }
good() { echo "  ok: $*"; }

val() { grep -E "^$2=" "$1" 2>/dev/null | head -n1 | cut -d= -f2- | sed 's/[[:space:]]*#.*$//; s/^[[:space:]]*//; s/[[:space:]]*$//'; }

check_file() {  # $1 archivo, $2.. variables obligatorias
  local name="$1" f="$SECRETS/$1"; shift
  if [[ ! -f "$f" ]]; then bad "falta $f"; return; fi
  local perm; perm="$(stat -c '%a' "$f")"
  if [[ "$perm" == 600 || "$perm" == 400 ]]; then good "$name con permisos $perm"; else bad "$name tiene permisos $perm (deben ser 600)"; fi
  for v in "$@"; do
    local x; x="$(val "$f" "$v")"
    if [[ -z "$x" ]]; then bad "$name: $v vacío"
    elif [[ "$x" == cambiar || "$x" == *"<"*">"* || "$x" == *ejemplo* ]]; then bad "$name: $v todavía tiene un valor de ejemplo ('$x')"
    fi
  done
}

echo "== Secretos ($SECRETS)"
if [[ -d "$SECRETS" ]]; then
  dperm="$(stat -c '%a' "$SECRETS")"; [[ "$dperm" == 700 ]] && good "carpeta con permisos 700" || bad "la carpeta tiene permisos $dperm (debe ser 700)"
fi
COMMON=(CRDO_ENV CRDO_VERSION CRDO_GHCR_OWNER CRDO_SECRETS_FILE CRDO_DATA_DIR CRDO_API_PORT PGVECTOR_TAG
        POSTGRES_OWNER_PASSWORD CRDO_DB_APP_PASSWORD CRDO_DB_READONLY_PASSWORD CRDO_DB_BACKUP_PASSWORD
        CRDO_DB_OPS_PASSWORD CRDO_SECRET_KEY CRDO_ALLOWED_HOSTS)
check_file prod.env "${COMMON[@]}"
check_file staging.env "${COMMON[@]}"
check_file restic.env RESTIC_REPOSITORY RESTIC_PASSWORD
check_file monitor.env HC_PING_BUDGET

if [[ -f "$SECRETS/prod.env" && -f "$SECRETS/staging.env" ]]; then
  [[ "$(val "$SECRETS/prod.env" CRDO_ENV)" == prod ]] || bad "prod.env: CRDO_ENV debe ser prod"
  [[ "$(val "$SECRETS/staging.env" CRDO_ENV)" == staging ]] || bad "staging.env: CRDO_ENV debe ser staging"
  for v in POSTGRES_OWNER_PASSWORD CRDO_DB_APP_PASSWORD CRDO_DB_READONLY_PASSWORD CRDO_DB_BACKUP_PASSWORD \
           CRDO_DB_OPS_PASSWORD CRDO_SECRET_KEY S3_SECRET_ACCESS_KEY ANTHROPIC_API_KEY; do
    p="$(val "$SECRETS/prod.env" "$v")"; s="$(val "$SECRETS/staging.env" "$v")"
    [[ -n "$p" && "$p" == "$s" ]] && bad "$v es IGUAL en prod y staging (deben ser distintos)"
  done
  [[ "$(val "$SECRETS/prod.env" CRDO_API_PORT)" != "$(val "$SECRETS/staging.env" CRDO_API_PORT)" ]] \
    && good "puertos de API distintos por entorno" || bad "prod y staging usan el mismo puerto de API"
  [[ "$(val "$SECRETS/prod.env" CRDO_DATA_DIR)" != "$(val "$SECRETS/staging.env" CRDO_DATA_DIR)" ]] \
    && good "datos en carpetas distintas por entorno" || bad "prod y staging comparten carpeta de datos"
  for f in prod.env staging.env; do
    for v in POSTGRES_OWNER_PASSWORD CRDO_DB_APP_PASSWORD CRDO_SECRET_KEY; do
      x="$(val "$SECRETS/$f" "$v")"; [[ -n "$x" && ${#x} -lt 24 ]] && bad "$f: $v tiene menos de 24 caracteres"
    done
  done
fi
[[ -f "$SECRETS/restic.env" && "$(val "$SECRETS/restic.env" RESTIC_REPOSITORY)" == b2:* ]] \
  || warn "restic.env: el repositorio no es B2 (aceptable solo en pruebas)"

if [[ "$MODE" != --secrets ]]; then
  echo "== Servidor"
  grep -q 'VERSION_ID="24.04"' /etc/os-release 2>/dev/null && good "Ubuntu 24.04" || bad "no es Ubuntu 24.04"
  for t in docker restic jq python3 pg_restore curl tailscale ufw; do
    command -v "$t" >/dev/null && good "$t instalado" || bad "falta $t"
  done
  docker compose version >/dev/null 2>&1 && good "docker compose disponible" || bad "falta docker compose"
  ufw status 2>/dev/null | grep -q 'Status: active' && good "UFW activo" || bad "UFW inactivo"
  ufw status 2>/dev/null | grep -qE '^22/tcp +ALLOW +Anywhere *$' && bad "SSH abierto a Internet en UFW"
  sshd -T 2>/dev/null | grep -qi '^passwordauthentication no' && good "SSH sin contraseñas" || bad "SSH acepta contraseñas"
  sshd -T 2>/dev/null | grep -qi '^permitrootlogin no' && good "SSH sin login de root" || bad "SSH permite root"
  tailscale status >/dev/null 2>&1 && good "Tailscale conectado" || bad "Tailscale no conectado"
  for u in crdo-backup.timer crdo-restore-test.timer crdo-budget-guard.timer crdo-opsd.service; do
    systemctl is-enabled "$u" >/dev/null 2>&1 && good "$u habilitado" || warn "$u no habilitado todavía"
  done
  free_gb="$(df -BG --output=avail / | tail -n1 | tr -dc 0-9)"
  (( free_gb >= 10 )) && good "disco libre: ${free_gb} GB" || bad "disco libre insuficiente: ${free_gb} GB"
fi

echo "== Resultado: $ERR errores, $WARN avisos"
(( ERR == 0 ))
