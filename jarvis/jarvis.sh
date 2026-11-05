#!/usr/bin/env bash
# JARVIS en un solo comando.
#   ./jarvis.sh             en primer plano (Ctrl+C para apagar)
#   ./jarvis.sh iniciar     en segundo plano, y se vuelve a levantar solo si se cae
#   ./jarvis.sh parar       apagado limpio (cierra también los procesos claude)
#   ./jarvis.sh reiniciar
#   ./jarvis.sh estado      ¿está vivo? ¿en pausa? ¿cerebro listo?
# Sirve en http://localhost:${JARVIS_PORT:-8790}. Genera la demo si falta.
set -uo pipefail
cd "$(dirname "$0")"

command -v python3 >/dev/null || { echo "Falta Python 3."; exit 1; }
export JARVIS_PORT="${JARVIS_PORT:-8790}"
STATE_DIR="${JARVIS_STATE_DIR:-estado}"
PIDFILE="$STATE_DIR/jarvis.pid"
LOGFILE="$STATE_DIR/jarvis.log"
mkdir -p "$STATE_DIR" && chmod 700 "$STATE_DIR" 2>/dev/null || true

prepare() {
  if [ ! -f data/demo/meta.json ]; then
    echo "Generando datos de demo…"
    python3 data/generate_demo.py
  fi
  # Carga .env (claves de ElevenLabs, etc.) sin exportarlo a git
  if [ -f .env ]; then
    chmod 600 .env 2>/dev/null || true
    set -a; . ./.env; set +a
  fi
  command -v claude >/dev/null || echo "AVISO: no encuentro el comando claude: JARVIS funcionará en modo respaldo."
}

running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }
# ¿Responde ESTE JARVIS? (no basta con que algo conteste en el puerto)
is_jarvis() { curl -s "http://localhost:$JARVIS_PORT/api/status" 2>/dev/null | python3 -c "import json,sys;d=json.load(sys.stdin);assert 'brain' in d and 'pause' in d" 2>/dev/null; }

supervise() {
  # Vuelve a levantar el servidor si se cae. Si falla 5 veces en 60 s (o el puerto está ocupado), se rinde.
  local fails=() code
  trap 'kill -TERM "$child" 2>/dev/null; wait "$child" 2>/dev/null; exit 0' TERM INT
  while true; do
    python3 agent/main.py >> "$LOGFILE" 2>&1 &
    child=$!
    wait "$child"; code=$?
    [ "$code" = 0 ] && exit 0
    [ "$code" = 1 ] && { echo "$(date '+%F %T') no arranca (código 1: puerto ocupado o configuración): me rindo" >> "$LOGFILE"; exit 1; }
    now=$(date +%s); fails+=("$now")
    recent=0; for t in "${fails[@]}"; do [ $((now - t)) -lt 60 ] && recent=$((recent + 1)); done
    echo "$(date '+%F %T') el servidor se cayó (código $code); fallos en el último minuto: $recent" >> "$LOGFILE"
    [ "$recent" -ge 5 ] && { echo "$(date '+%F %T') demasiados fallos seguidos: me rindo" >> "$LOGFILE"; exit 1; }
    sleep 2
  done
}

case "${1:-}" in
  ""|primer-plano)
    prepare
    exec python3 agent/main.py
    ;;
  iniciar|start)
    if running; then echo "JARVIS ya está en marcha (PID $(cat "$PIDFILE")) en http://localhost:$JARVIS_PORT"; exit 0; fi
    prepare
    echo "$(date '+%F %T') --- iniciar" >> "$LOGFILE"; chmod 600 "$LOGFILE" 2>/dev/null || true
    supervise </dev/null >/dev/null 2>&1 &
    echo $! > "$PIDFILE"; chmod 600 "$PIDFILE" 2>/dev/null || true
    for _ in $(seq 1 75); do
      running || break           # el supervisor se rindió (p. ej. puerto ocupado)
      is_jarvis && break
      sleep 0.2
    done
    sleep 0.5
    if running && is_jarvis; then
      echo "JARVIS en marcha: abre http://localhost:$JARVIS_PORT  (apagar: ./jarvis.sh parar)"
    else
      echo "JARVIS no arrancó. Últimas líneas del registro ($LOGFILE):"; tail -5 "$LOGFILE"; rm -f "$PIDFILE"; exit 1
    fi
    ;;
  parar|stop)
    if ! running; then echo "JARVIS no está en marcha."; rm -f "$PIDFILE"; exit 0; fi
    pid=$(cat "$PIDFILE"); kill -TERM "$pid"
    for _ in $(seq 1 50); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid"
    rm -f "$PIDFILE"; echo "JARVIS apagado."
    ;;
  reiniciar|restart)
    "$0" parar && "$0" iniciar
    ;;
  estado|status)
    if running; then
      s=$(curl -s "http://localhost:$JARVIS_PORT/api/status" 2>/dev/null)
      if [ -n "$s" ]; then
        echo "$s" | python3 -c "import json,sys;d=json.load(sys.stdin);print('JARVIS en marcha · modo', d['mode'], '· cerebro:', d['brain']['brain'], '· PAUSA:', 'SÍ' if d['pause']['on'] else 'no', '· borradores pendientes:', len(d['approvals']), '· voz configurada:', 'sí' if d['voice']['configured'] else 'NO')"
      else
        echo "El supervisor está vivo (PID $(cat "$PIDFILE")) pero el servidor no responde. Mira $LOGFILE"
      fi
    else
      echo "JARVIS no está en marcha."; exit 3
    fi
    ;;
  *)
    echo "Uso: ./jarvis.sh [iniciar|parar|reiniciar|estado]"; exit 2
    ;;
esac
