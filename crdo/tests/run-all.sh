#!/usr/bin/env bash
# C.R.D.O. — Ejecuta las cuatro baterías y resume. Código de salida 0 solo si ninguna falla.
# Las baterías que no pueden correr en este entorno (p. ej. sin restic) se informan como OMITIDAS, nunca como aprobadas.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT="${CRDO_REPORT:-}"
declare -a RES
rc=0
run() {  # $1 nombre, $2 script, $3 requisito (comando) opcional
  local name="$1" script="$2" req="${3:-}" out last
  if [[ -n "$req" ]] && ! command -v "$req" >/dev/null; then RES+=("$name|OMITIDA (falta $req)"); return; fi
  echo; echo "########## $name"
  out="$(mktemp)"
  if bash "$ROOT/tests/$script" 2>&1 | tee "$out"; then :; fi
  last="$(grep -E '^== (OK|HAY)|FALLA' "$out" | tail -n1)"
  if grep -q '^== OK' "$out" && ! grep -qE '^ *FALLA|^ERROR' "$out"; then RES+=("$name|$last")
  else RES+=("$name|FALLÓ: ${last:-sin resumen}"); rc=1; fi
  [[ -n "$REPORT" ]] && cat "$out" >>"$REPORT"
  rm -f "$out"
}
[[ -n "$REPORT" ]] && : >"$REPORT"
run "Base de datos"          run-db-tests.sh        psql
run "Respaldo y recuperación" run-backup-tests.sh   restic
run "Prototipo local"        run-prototype-test.sh  python3
run "Configuración"          run-config-tests.sh
echo; echo "================ RESUMEN ================"
for r in "${RES[@]}"; do printf '  %-26s %s\n' "${r%%|*}" "${r#*|}"; done
{ echo; echo "RESUMEN"; for r in "${RES[@]}"; do echo "  ${r%%|*}: ${r#*|}"; done; } >>"${REPORT:-/dev/null}"
exit $rc
