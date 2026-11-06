#!/usr/bin/env bash
# Pruebas del canal de firma (se ejecutan en GitHub Actions y en desarrollo). No usan ni necesitan la clave original.
set -uo pipefail
cd "$(dirname "$0")/.."
ok=0; bad=0
check() { if [ "$1" = 0 ]; then ok=$((ok+1)); echo "PASA   $2"; else bad=$((bad+1)); echo "FALLA  $2"; fi; }
T=$(mktemp -d)
# 1) sin la clave: APK sin firmar, aviso, y NO se crea ninguna clave
JARVIS_KEYSTORE=$T/no-existe.keystore ./build.sh > $T/a.txt 2>&1
grep -q "SIN FIRMAR" $T/a.txt && [ -f build/jarvis-sin-firmar.apk ] && [ ! -f build/jarvis-debug.apk ]; check $? "sin la clave original: sale SIN FIRMAR y lo avisa"
[ ! -f $T/no-existe.keystore ]; check $? "sin la clave original: no se genera ninguna clave nueva"
zipalign -c 4 build/jarvis-sin-firmar.apk; check $? "APK sin firmar alineada"
aapt dump badging build/jarvis-sin-firmar.apk | grep -q "package: name='com.radar.jarvis'"; check $? "paquete com.radar.jarvis (el mismo de la app instalada)"
# versión: versionCode entero y no menor que el último entregado (7); Android no permite bajar de versión
VC=$(aapt dump badging build/jarvis-sin-firmar.apk | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
[ -n "$VC" ] && [ "$VC" -ge 10 ]; check $? "versionCode $VC ≥ 10: mayor que la última entregada (9), Android lo acepta como actualización"
# 2) con otra clave: se rechaza y no queda APK firmada
keytool -genkeypair -keystore $T/otra.keystore -storepass android -keypass android -alias x -keyalg RSA -keysize 2048 -validity 1 -dname "CN=otra" >/dev/null 2>&1
JARVIS_KEYSTORE=$T/otra.keystore ./build.sh > $T/b.txt 2>&1; rc=$?
[ $rc -ne 0 ] && grep -q "OTRO certificado" $T/b.txt && [ ! -f build/jarvis-debug.apk ]; check $? "firmar con otra clave: se rechaza y se borra la APK"
rm -rf $T
echo "$ok PASA · $bad FALLA"
[ $bad -eq 0 ]
