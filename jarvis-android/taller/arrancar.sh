#!/data/data/com.termux/files/usr/bin/bash
# Arranca el taller de JARVIS (en primer plano: déjalo en una sesión de Termux, o con nohup … &).
set -u
DATOS="$HOME/.jarvis-taller"
[ -f "$DATOS/config" ] && . "$DATOS/config"
REPO="${REPO:-$HOME/jarvis-taller/Radar}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
command -v termux-wake-lock >/dev/null && termux-wake-lock
exec python3 "$REPO/jarvis-android/taller/taller.py" --repo "$REPO" --puerto "${PUERTO:-8090}" ${AAPT2:+--aapt2 "$AAPT2"}
