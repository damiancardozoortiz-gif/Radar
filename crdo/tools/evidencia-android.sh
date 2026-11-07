#!/usr/bin/env bash
# C.R.D.O. — Captura de evidencia de la prueba local (Etapa 1A). Solo LEE y ejecuta las pruebas existentes:
# no modifica el repo ni la configuración. Escribe un informe en ~/.crdo-local/informes/evidencia-<fecha>.md
# Uso (en Termux o dentro de proot-distro, desde la carpeta crdo):  bash tools/evidencia-android.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="${CRDO_LOCAL_DIR:-$HOME/.crdo-local}/informes"; mkdir -p "$OUTDIR"
STAMP="$(date +%Y%m%d-%H%M%S)"; OUT="$OUTDIR/evidencia-$STAMP.md"
LOG="$OUTDIR/evidencia-$STAMP.log"

v() { "$@" 2>&1 | head -n1 || true; }
PGB="$(command -v postgres || ls /usr/lib/postgresql/*/bin/postgres 2>/dev/null | tail -n1)"
SHARE="$(pg_config --sharedir 2>/dev/null || echo /usr/share/postgresql/16)"

{
  echo "# Evidencia de prueba local C.R.D.O. — Etapa 1A"
  echo
  echo "| Dato | Valor |"
  echo "|---|---|"
  echo "| Fecha y hora | $(date '+%Y-%m-%d %H:%M:%S %Z') |"
  echo "| Kernel / arquitectura | $(uname -srm) |"
  echo "| Android (getprop) | $(getprop ro.build.version.release 2>/dev/null || echo 'no disponible (¿proot?)') |"
  echo "| Termux | ${TERMUX_VERSION:-no detectado en este shell} |"
  echo "| proot | $( [[ -n "${PROOT_TMP_DIR:-}${PROOT_L2S_DIR:-}" ]] || grep -qa proot /proc/1/cmdline 2>/dev/null && echo sí || echo 'no detectado') |"
  echo "| Sistema | $( . /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-?}" || echo 'Termux nativo') |"
  echo "| Python | $(v python3 --version) |"
  echo "| PostgreSQL | $( [[ -n "$PGB" ]] && v "$PGB" --version || echo 'no instalado') |"
  echo "| pgvector | $(ls "$SHARE"/extension/vector.control >/dev/null 2>&1 && echo disponible || echo 'NO disponible') |"
  echo "| restic | $(v restic version) |"
  echo "| Commit del repo | $(git -C "$ROOT" log --format='%h %s' -1 2>/dev/null || echo 'sin git') |"
  echo
} >"$OUT"

echo "== doctor" | tee -a "$LOG"
bash "$ROOT/tools/crdo-local" doctor >>"$LOG" 2>&1; RC_DOCTOR=$?
echo "== test" | tee -a "$LOG"
bash "$ROOT/tools/crdo-local" test >>"$LOG" 2>&1; RC_TEST=$?

pas="$(grep -c 'PASA' "$LOG" || true)"
fal="$(grep -cE '^ *FALLA|^ERROR' "$LOG" || true)"
omi="$(grep -cE 'OMITID' "$LOG" || true)"
{
  echo "## Resultado"
  echo
  echo "| Comando | Código de salida |"
  echo "|---|---|"
  echo "| \`bash tools/crdo-local doctor\` | $RC_DOCTOR |"
  echo "| \`bash tools/crdo-local test\` | $RC_TEST |"
  echo
  echo "| Verificaciones | Cantidad |"
  echo "|---|---|"
  echo "| Aprobadas (líneas PASA) | $pas |"
  echo "| Fallidas (FALLA / ERROR) | $fal |"
  echo "| Líneas de omisión declarada | $omi |"
  echo
  echo "## Resumen por batería"
  echo '```'
  sed -n '/RESUMEN/,$p' "$LOG" | head -n 12
  echo '```'
  echo
  echo "## Omisiones declaradas"
  echo '```'
  grep -E 'OMITID' "$LOG" | sort -u || echo "(ninguna)"
  echo '```'
  echo
  echo "## Errores completos"
  echo '```'
  grep -nE -B2 -A3 '^ *FALLA|^ERROR|HAY PRUEBAS FALLIDAS' "$LOG" || echo "(ninguno)"
  echo '```'
  echo
  echo "Log completo: \`$LOG\`"
} >>"$OUT"

echo
echo "Evidencia guardada en: $OUT"
echo "doctor=$RC_DOCTOR test=$RC_TEST aprobadas=$pas fallidas=$fal omisiones=$omi"
