#!/usr/bin/env bash
# C.R.D.O. — Prueba de punta a punta del prototipo local (tools/crdo-local).
# El código TOTP lo calcula una implementación INDEPENDIENTE en Python (RFC 6238, como una app de autenticación),
# a partir del secreto base32 que entrega `crdo-local enroll`: verifica la compatibilidad con apps TOTP reales.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CL="$ROOT/tools/crdo-local"
export CRDO_LOCAL_DIR; CRDO_LOCAL_DIR="$(mktemp -d)/inst"
export CRDO_LOCAL_PORT=55451 CRDO_LOCAL_CONFIRM=borrar
PASS=0
ok()   { echo "  PASA: $*"; PASS=$((PASS + 1)); }
fail() { echo "  FALLA: $*"; exit 1; }
cleanup() { bash "$CL" reset >/dev/null 2>&1 || true; rm -rf "$(dirname "$CRDO_LOCAL_DIR")"; }
trap cleanup EXIT

totp() {  # $1 secreto base32, $2 desfase de pasos
  python3 - "$1" "${2:-0}" <<'PY'
import base64, hmac, hashlib, struct, sys, time
key = base64.b32decode(sys.argv[1] + '=' * (-len(sys.argv[1]) % 8))
step = int(time.time()) // 30 + int(sys.argv[2])
h = hmac.new(key, struct.pack('>Q', step), hashlib.sha1).digest()
o = h[19] & 15
print('%06d' % ((struct.unpack('>I', h[o:o + 4])[0] & 0x7fffffff) % 1000000))
PY
}

echo "== Prototipo local (crdo-local)"
bash "$CL" doctor >/dev/null && ok "doctor: entorno completo" || ok "doctor: entorno parcial (informado)"
bash "$CL" start >/dev/null && ok "start: base local creada con el esquema real" || fail "start"
CRDO_LOCAL_EMAIL=duenio@crdo.local CRDO_LOCAL_NAME=Dueño CRDO_LOCAL_PASSWORD='una-contraseña-larga' \
  CRDO_LOCAL_SHOW_SECRET_FILE=1 bash "$CL" enroll >"$CRDO_LOCAL_DIR/enroll.txt"
grep -q 'otpauth://totp/CRDO:duenio@crdo.local' "$CRDO_LOCAL_DIR/enroll.txt" && ok "enroll: URI otpauth para la app" || fail "enroll URI"
[[ "$(grep -cE '^     [0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}$' "$CRDO_LOCAL_DIR/enroll.txt")" == 10 ]] && ok "enroll: 10 códigos de recuperación" || fail "enroll códigos"
SEC="$(cat "$CRDO_LOCAL_DIR/.secret-for-tests")"
if bash "$CL" enroll </dev/null >/dev/null 2>&1; then fail "permitió un segundo dueño"; else ok "no se puede enrolar un segundo dueño por encima del primero"; fi

if CRDO_LOCAL_EMAIL=duenio@crdo.local CRDO_LOCAL_PASSWORD='una-contraseña-larga' CRDO_LOCAL_CODE=000000 bash "$CL" login >/dev/null 2>&1; then
  fail "login con código inválido"; else ok "login con código inválido: rechazado"; fi
if bash "$CL" status >/dev/null 2>&1; then fail "status sin sesión"; else ok "sin sesión no se ve el estado"; fi
CRDO_LOCAL_EMAIL=duenio@crdo.local CRDO_LOCAL_PASSWORD='una-contraseña-larga' CRDO_LOCAL_CODE="$(totp "$SEC" 0)" \
  bash "$CL" login >/dev/null && ok "login con el código calculado por una implementación TOTP independiente (Python)" || fail "login"
[[ "$(stat -c '%a' "$CRDO_LOCAL_DIR/session")" == 600 ]] && ok "el token de sesión se guarda con permisos 600" || fail "permisos sesión"
out="$(bash "$CL" status)"
echo "$out" | grep -q 'nivel: aviso · proyección US\$16.61 · tope operativo US\$17.00 · tope US\$20.00' && ok "status: presupuesto oficial (US\$ 16,61 / 17 / 20)" || { echo "$out"; fail "status presupuesto"; }
echo "$out" | grep -q 'agentes: apagados' && ok "status: agentes apagados por defecto" || fail "status agentes"

bash "$CL" demo >/dev/null && ok "demo: un agente pidió publicar (R3)" || fail "demo"
AID="$(bash "$CL" approvals | sed -nE 's/^── ([0-9a-f-]{36})$/\1/p' | head -n1)"
[[ -n "$AID" ]] && ok "approvals: la solicitud aparece con contenido exacto y huella" || fail "approvals"
CODE2="$(totp "$SEC" 1)"
CRDO_LOCAL_CODE="$CODE2" bash "$CL" approve "$AID" >/dev/null && ok "approve: aprobada con el siguiente código TOTP" || fail "approve"
bash "$CL" demo "Otro video" >/dev/null
AID2="$(bash "$CL" approvals | sed -nE 's/^── ([0-9a-f-]{36})$/\1/p' | head -n1)"
if CRDO_LOCAL_CODE="$CODE2" bash "$CL" approve "$AID2" >/dev/null 2>&1; then fail "reutilizó un código"; else ok "el mismo código no aprueba una segunda solicitud"; fi
bash "$CL" audit | grep -q 'Cadena de auditoría: ÍNTEGRA' && ok "audit: cadena íntegra después de toda la sesión" || fail "audit"
bash "$CL" logout >/dev/null
if bash "$CL" approvals >/dev/null 2>&1; then fail "approvals tras logout"; else ok "tras cerrar sesión ya no se puede operar"; fi
bash "$CL" stop >/dev/null && bash "$CL" start >/dev/null && ok "stop/start: la instancia persiste entre reinicios" || fail "persistencia"
SOCKDIR="$(find "$CRDO_LOCAL_DIR" /var/lib/crdo-local -maxdepth 2 -type d -name sock 2>/dev/null | head -n1)"
[[ "$(psql -X -At -h "$SOCKDIR" -p "$CRDO_LOCAL_PORT" -U postgres -d crdo -c "SELECT count(*) FROM ops.approvals WHERE status = 'approved'")" == 1 ]] \
  && ok "los datos sobreviven al reinicio (1 aprobación registrada)" || fail "datos tras reinicio"

EXPECTED=19
[[ $PASS -eq $EXPECTED ]] || fail "se esperaban $EXPECTED verificaciones y pasaron $PASS"
echo "== OK: $PASS verificaciones pasaron, 0 fallaron"
