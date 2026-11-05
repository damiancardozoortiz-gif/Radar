#!/usr/bin/env bash
# JARVIS en un solo comando. Genera la demo si falta y sirve en http://localhost:${JARVIS_PORT:-8790}
set -euo pipefail
cd "$(dirname "$0")"

command -v python3 >/dev/null || { echo "Falta Python 3."; exit 1; }

if [ ! -f data/demo/meta.json ]; then
  echo "Generando datos de demo…"
  python3 data/generate_demo.py
fi

# Carga .env (claves de ElevenLabs, etc.) sin exportarlo a git
if [ -f .env ]; then
  chmod 600 .env 2>/dev/null || true
  set -a; . ./.env; set +a
fi

export JARVIS_PORT="${JARVIS_PORT:-8790}"
exec python3 agent/main.py
