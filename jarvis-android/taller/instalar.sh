#!/data/data/com.termux/files/usr/bin/bash
# Instala el taller de JARVIS en Termux.
#  - No borra nada. Si ya existe la copia del proyecto, solo la actualiza (git fetch).
#  - Pregunta antes de instalar paquetes o descargar el SDK de Android.
#  - Las licencias del SDK de Android las aceptas TÚ cuando sdkmanager te las enseñe.
# Uso:  bash instalar.sh            (taller: git, python y la copia del proyecto)
#       bash instalar.sh --compilar (además: Java, SDK de Android y aapt2 para compilar en el teléfono)
set -u
REPO_URL="${REPO_URL:-https://github.com/damiancardozoortiz-gif/Radar.git}"
RAMA="${RAMA:-ccr-a2f47d67-l3jw2z}"
BASE="$HOME/jarvis-taller"
DESTINO="$BASE/Radar"
DATOS="$HOME/.jarvis-taller"
SDK="$HOME/android-sdk"
HERRAMIENTAS_SDK="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

paso() { printf '\n== %s\n' "$*"; }
preguntar() { read -r -p "$1 [s/N] " r; [ "$r" = "s" ] || [ "$r" = "S" ]; }

if [ -z "${PREFIX:-}" ] || [ ! -d "/data/data/com.termux" ]; then
  echo "Esto es para Termux en Android."; exit 1
fi
mkdir -p "$BASE" "$DATOS"

paso "1. Paquetes básicos: git y python"
if ! command -v git >/dev/null || ! command -v python3 >/dev/null; then
  preguntar "Instalar git y python con pkg?" && pkg install -y git python || { echo "Sin git y python no hay taller."; exit 1; }
fi
git --version; python3 --version

paso "2. Copia del proyecto (la app instalada no se toca)"
if [ -d "$DESTINO/.git" ]; then
  echo "Ya existe $DESTINO: solo traigo lo nuevo (no cambio tu rama ni tus archivos)."
  git -C "$DESTINO" fetch origin "$RAMA" || echo "AVISO: no se pudo actualizar (¿sin red o sin acceso?)."
else
  echo "Clonando $REPO_URL (rama $RAMA) en $DESTINO."
  echo "Si el repositorio es privado, git te pedirá usuario y un token de GitHub (no tu contraseña)."
  git clone --branch "$RAMA" "$REPO_URL" "$DESTINO" || { echo "No se pudo clonar."; exit 1; }
fi
git -C "$DESTINO" log -1 --format='Copia en el commit %h: %s'

COMPILAR=0; [ "${1:-}" = "--compilar" ] && COMPILAR=1
AAPT2=""
if [ "$COMPILAR" = 1 ]; then
  paso "3. Java 17 (para Gradle)"
  command -v java >/dev/null || { preguntar "Instalar openjdk-17 con pkg?" && pkg install -y openjdk-17; }
  java -version 2>&1 | head -1

  paso "4. SDK de Android en $SDK"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    if preguntar "Descargar las herramientas del SDK de Android (unos 150 MB, de dl.google.com)?"; then
      command -v unzip >/dev/null || pkg install -y unzip
      command -v curl >/dev/null || pkg install -y curl
      tmp="$(mktemp -d)"
      curl -fL -o "$tmp/sdk.zip" "$HERRAMIENTAS_SDK" && unzip -q "$tmp/sdk.zip" -d "$tmp" && mkdir -p "$SDK/cmdline-tools" && mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
      rm -rf "$tmp"
    fi
  fi
  if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Ahora sdkmanager te enseña las licencias de Android: léelas y acepta solo si estás de acuerdo."
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-35" "build-tools;35.0.0"
    printf 'sdk.dir=%s\n' "$SDK" > "$DESTINO/jarvis-android/local.properties"
  fi

  paso "5. aapt2 para ARM (el que descarga Gradle no funciona en el teléfono)"
  if ! command -v aapt2 >/dev/null; then
    preguntar "Instalar aapt2 con pkg?" && pkg install -y aapt2
  fi
  if command -v aapt2 >/dev/null; then
    AAPT2="$(command -v aapt2)"; "$AAPT2" version 2>&1 | head -1
  else
    echo "AVISO: no hay aapt2. El taller podrá editar y hacer commits, pero no compilar en el teléfono (queda GitHub)."
  fi
fi

paso "Diagnóstico"
grep -E "MemTotal|MemAvailable" /proc/meminfo
df -h "$HOME" | tail -1
printf 'REPO=%s\nAAPT2=%s\nANDROID_HOME=%s\n' "$DESTINO" "$AAPT2" "$SDK" > "$DATOS/config"
echo "Configuración guardada en $DATOS/config"

paso "Listo"
echo "Arranca el taller con:  bash $DESTINO/jarvis-android/taller/arrancar.sh"
echo "La primera vez enseña una credencial: cópiala en JARVIS → Ajustes → Puentes (nombre «taller», dirección http://127.0.0.1:8090/mcp)."
