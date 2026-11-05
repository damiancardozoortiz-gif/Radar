#!/usr/bin/env bash
# Ejecuta las pruebas de JARVIS y resume el resultado.
#   bash tests/ejecutar_todo.sh              las 4 baterías (unos 4 minutos)
#   bash tests/ejecutar_todo.sh --completo   además: integral, estabilidad x20 y voz real (unos 15 minutos)
#
# 1. test_criticos.py   sin internet ni suscripción (claude y ElevenLabs simulados)      ~1 min
# 2. navegador.js       Playwright + Chromium (opcional; si falta se marca OMITIDA)       ~40 s
# 3. test_limites.py    con claude -p REAL (usa tu suscripción, unas pocas llamadas)       ~30 s
# 4. prueba_bloqueo.sh  con claude -p REAL: leer .env / Write / Bash bloqueados           ~15 s
# Las pruebas usan estado/ y memory/ temporales: no tocan los tuyos. Nada se envía ni se publica.
set -u
cd "$(dirname "$0")/.."
declare -a NAMES RESULTS
FAIL=0
record() { NAMES+=("$1"); RESULTS+=("$2"); [ "$2" = "FALLA" ] && FAIL=1; }

echo "=== 1/4 test_criticos.py"
if python3 tests/test_criticos.py; then record "test_criticos.py" "PASA"; else record "test_criticos.py" "FALLA"; fi

echo; echo "=== 2/4 navegador.js"
if command -v node >/dev/null && node -e "require('playwright')" 2>/dev/null; then
  if node tests/navegador.js; then record "navegador.js" "PASA"; else record "navegador.js" "FALLA"; fi
else
  echo "OMITIDA: falta Node o Playwright (npm install --no-save playwright && npx playwright install chromium)"
  record "navegador.js" "OMITIDA"
fi

echo; echo "=== 3/4 test_limites.py (claude real)"
if command -v claude >/dev/null; then
  TMPD=$(mktemp -d)
  PORT=$(python3 -c "import socket;s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1])")
  [ -f data/demo/meta.json ] || python3 data/generate_demo.py >/dev/null
  JARVIS_PORT=$PORT JARVIS_STATE_DIR=$TMPD/estado JARVIS_MEMORY_DIR=$TMPD/memory ELEVENLABS_API_KEY= \
    python3 agent/main.py >"$TMPD/server.log" 2>&1 &
  SRV=$!
  for _ in $(seq 1 100); do curl -s -o /dev/null "http://localhost:$PORT/api/status" && break; sleep 0.1; done
  if JARVIS_PORT=$PORT JARVIS_MEMORY_DIR=$TMPD/memory python3 tests/test_limites.py; then record "test_limites.py" "PASA"; else record "test_limites.py" "FALLA"; fi
  kill $SRV 2>/dev/null; wait $SRV 2>/dev/null; rm -rf "$TMPD"
else
  echo "OMITIDA: no encuentro el comando claude"; record "test_limites.py" "OMITIDA"
fi

echo; echo "=== 4/4 prueba_bloqueo.sh (claude real)"
if command -v claude >/dev/null; then
  if bash tests/prueba_bloqueo.sh; then record "prueba_bloqueo.sh" "PASA"; else record "prueba_bloqueo.sh" "FALLA"; fi
else
  echo "OMITIDA: no encuentro el comando claude"; record "prueba_bloqueo.sh" "OMITIDA"
fi

if [ "${1:-}" = "--completo" ]; then
  for t in "integral.py" "estabilidad_jarvis.py 20" "estabilidad_bash.sh 20" "prueba_voz_real.py"; do
    name=${t%% *}; args=${t#"$name"}
    echo; echo "=== extra: $t"
    if ! command -v claude >/dev/null && [ "$name" != "prueba_voz_real.py" ]; then echo "OMITIDA: no encuentro claude"; record "$t" "OMITIDA"; continue; fi
    case "$name" in *.sh) cmd=(bash "tests/$name");; *) cmd=(python3 "tests/$name");; esac
    # shellcheck disable=SC2086
    "${cmd[@]}" $args; rc=$?
    if [ $rc = 0 ]; then record "$t" "PASA"; elif [ $rc = 2 ]; then record "$t" "PENDIENTE"; else record "$t" "FALLA"; fi
  done
fi

echo; echo "=== RESUMEN"
for i in "${!NAMES[@]}"; do printf "  %-8s %s\n" "${RESULTS[$i]}" "${NAMES[$i]}"; done
exit $FAIL
