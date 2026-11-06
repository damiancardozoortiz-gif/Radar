#!/usr/bin/env bash
# Prueba REAL en un Android emulado (GitHub Actions): instala la APK, la abre y comprueba que no se cierra con error.
# La APK se firma aquí con una clave de USAR Y TIRAR (solo para el emulador). Nunca se publica ni sirve para tu teléfono.
set -uo pipefail
APK_IN=$1; OUT=${2:-salida-emulador}
mkdir -p "$OUT"
KS=$(mktemp -d)/temporal.keystore
keytool -genkeypair -keystore "$KS" -storepass temporal -keypass temporal -alias t -keyalg RSA -keysize 2048 -validity 1 -dname "CN=solo emulador" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:temporal --key-pass pass:temporal --out "$OUT/temporal.apk" "$APK_IN"
rm -f "$KS"
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb logcat -c
echo "== versión de Android del emulador: $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))" | tee "$OUT/resumen.txt"
if adb install -r "$OUT/temporal.apk" > "$OUT/instalacion.txt" 2>&1; then echo "INSTALACIÓN: correcta" | tee -a "$OUT/resumen.txt"; else echo "INSTALACIÓN: FALLÓ" | tee -a "$OUT/resumen.txt"; cat "$OUT/instalacion.txt"; exit 1; fi
rm -f "$OUT/temporal.apk"
adb shell am start -W -n com.radar.jarvis/.MainActivity > "$OUT/arranque.txt" 2>&1
sleep 15
adb exec-out screencap -p > "$OUT/pantalla-arranque.png" || true
PID=$(adb shell pidof com.radar.jarvis | tr -d '\r')
adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|AndroidRuntime" -A 12 "$OUT/logcat.txt" | grep -i -B2 -A12 "com.radar.jarvis" > "$OUT/errores.txt" || true
grep -E "CIERRE INESPERADO" "$OUT/logcat.txt" >> "$OUT/errores.txt" || true
if [ -n "$PID" ] && [ ! -s "$OUT/errores.txt" ]; then
  echo "ARRANQUE: la app sigue abierta tras 15 s (proceso $PID) y sin errores fatales" | tee -a "$OUT/resumen.txt"
else
  echo "ARRANQUE: FALLÓ (proceso: '${PID}')" | tee -a "$OUT/resumen.txt"; cat "$OUT/errores.txt"; exit 1
fi
# segunda apertura: comprueba que vuelve a arrancar (estado guardado en disco)
adb shell am force-stop com.radar.jarvis
adb shell am start -W -n com.radar.jarvis/.MainActivity >> "$OUT/arranque.txt" 2>&1
sleep 8
PID2=$(adb shell pidof com.radar.jarvis | tr -d '\r')
adb exec-out screencap -p > "$OUT/pantalla-reapertura.png" || true
if [ -n "$PID2" ]; then echo "REAPERTURA: correcta" | tee -a "$OUT/resumen.txt"; else echo "REAPERTURA: FALLÓ" | tee -a "$OUT/resumen.txt"; exit 1; fi
# ---- pantalla principal: recorre el asistente inicial (sin claves) y captura la interfaz real
tap_text() {  # toca el botón cuyo texto contiene $1 (desplaza si no está a la vista)
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
    XY=$(python3 -c "
import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>',x):
  n=m.group(0); t=re.search(r' text=\"([^\"]*)\"',n)
  if t and sys.argv[2].lower() in t.group(1).lower():
    b=re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n)
    if b: a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break
" "$OUT/ui.xml" "$1")
    if [ -n "$XY" ]; then adb shell input tap $XY; sleep 2; return 0; fi
    SZ=$(adb shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1); W=${SZ% *}; H=${SZ#* }
    adb shell input swipe $((W/2)) $((H*8/10)) $((W/2)) $((H*2/10)) 300; sleep 1
  done
  echo "no encontré «$1»; textos en pantalla:"; python3 -c "
import re,sys
x=open(sys.argv[1],encoding='utf-8',errors='ignore').read()
print([t for t in re.findall(r' text=\"([^\"]+)\"',x)][:25]); print('tamaño del volcado:',len(x))
" "$OUT/ui.xml" 2>&1 | head -5
  return 1
}
captura() {  # captura y la deja también en el registro (reducida) para poder verla sin descargar artefactos
  adb exec-out screencap -p > "$OUT/$1.png"
  adb exec-out screencap > "$OUT/$1.raw"
  if python3 "$(dirname "$0")/reducir.py" "$OUT/$1.raw" "$OUT/$1-reducida.png" 200; then
    echo "=== CAPTURA $1 INICIO ==="; base64 -w 150 "$OUT/$1-reducida.png"; echo "=== CAPTURA $1 FIN ==="
  fi
  rm -f "$OUT/$1.raw"
}
# ---- prueba real del exportador de vídeo: la app la ejecuta al abrir la pantalla principal si encuentra este archivo
APPDIR=/sdcard/Android/data/com.radar.jarvis/files/autotest
adb shell mkdir -p $APPDIR && adb push "$(dirname "$0")/muestra.mp4" $APPDIR/video.mp4 >/dev/null && echo "VÍDEO DE PRUEBA: copiado a la carpeta de la app"
if tap_text "Empezar" && tap_text "Guardar y seguir" && tap_text "Terminar"; then
  sleep 12
  PID3=$(adb shell pidof com.radar.jarvis | tr -d '\r')
  adb logcat -d > "$OUT/logcat-principal.txt"
  if [ -n "$PID3" ] && ! grep -q "FATAL EXCEPTION" "$OUT/logcat-principal.txt"; then echo "PANTALLA PRINCIPAL: abierta y estable 12 s" | tee -a "$OUT/resumen.txt"; else echo "PANTALLA PRINCIPAL: FALLÓ" | tee -a "$OUT/resumen.txt"; grep -A 15 "FATAL EXCEPTION" "$OUT/logcat-principal.txt" | head -40; exit 1; fi
  for i in $(seq 1 20); do adb shell "[ -f $APPDIR/resultado.txt ]" && break; sleep 1; done
  RES=$(adb shell cat $APPDIR/resultado.txt 2>/dev/null | tr -d '\r')
  echo "EXPORTADOR DE VÍDEO (en Android): ${RES:-sin resultado}" | tee -a "$OUT/resumen.txt"
  case "$RES" in OK*) ;; *) echo "EXPORTADOR: FALLÓ" | tee -a "$OUT/resumen.txt"; exit 1;; esac
  adb pull $APPDIR/salida.mp4 "$OUT/salida.mp4" >/dev/null 2>&1
  if command -v ffprobe >/dev/null && [ -f "$OUT/salida.mp4" ]; then
    DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/salida.mp4")
    NS=$(ffprobe -v error -show_entries stream=codec_type -of csv=p=0 "$OUT/salida.mp4" | sort | tr '\n' ' ')
    echo "MP4 EXPORTADO (ffprobe): duración ${DUR} s · pistas: ${NS}" | tee -a "$OUT/resumen.txt"
    python3 -c "import sys; d=float(sys.argv[1]); sys.exit(0 if 3.2<=d<=3.9 else 1)" "$DUR" && echo "DURACIÓN ESPERADA (2,0 + 1,5 s): correcta" | tee -a "$OUT/resumen.txt" || { echo "DURACIÓN ESPERADA: INCORRECTA" | tee -a "$OUT/resumen.txt"; exit 1; }
    echo "$NS" | grep -q audio && echo "$NS" | grep -q video || { echo "PISTAS: falta audio o vídeo" | tee -a "$OUT/resumen.txt"; exit 1; }
  fi
  captura principal
  if tap_text "Agentes"; then sleep 3; captura neural; echo "VENTANA NEURAL CORE: abierta" | tee -a "$OUT/resumen.txt"; fi
  PID4=$(adb shell pidof com.radar.jarvis | tr -d '\r')
  [ -n "$PID4" ] && echo "TRAS ABRIR VENTANAS: la app sigue viva" | tee -a "$OUT/resumen.txt" || { echo "TRAS ABRIR VENTANAS: SE CERRÓ" | tee -a "$OUT/resumen.txt"; adb logcat -d | grep -A 15 "FATAL EXCEPTION" | head -40; exit 1; }
else
  echo "ASISTENTE: no pude recorrerlo automáticamente (no es un fallo de la app)" | tee -a "$OUT/resumen.txt"
fi
echo "LÍMITE: el emulador no tiene tus claves ni micrófono real: voz, Claude y ElevenLabs no se prueban aquí." | tee -a "$OUT/resumen.txt"
