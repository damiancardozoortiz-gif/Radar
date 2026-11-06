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
echo "LÍMITE: el emulador no tiene tus claves ni micrófono real: voz, Claude y ElevenLabs no se prueban aquí." | tee -a "$OUT/resumen.txt"
