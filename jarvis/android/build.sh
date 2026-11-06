#!/usr/bin/env bash
# Compila la app Android de JARVIS sin Gradle (herramientas de Ubuntu/Debian: android-sdk-platform-23, aapt,
# dalvik-exchange, zipalign, apksigner). Reutiliza el proyecto existente: copia a la app las instrucciones
# (agent/prompt.md), tu perfil (CLAUDE.md), las notas/correo/agenda (data/demo) y la web de RADAR (raíz del repo).
#   ./build.sh          -> build/jarvis-debug.apk
#   ./build.sh test     -> prueba del núcleo en el ordenador contra una API de Claude simulada
# Instalar las herramientas (una vez): sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
set -euo pipefail
unset JAVA_TOOL_OPTIONS || true
cd "$(dirname "$0")"
HERE=$(pwd); JARVIS=$(cd .. && pwd); REPO=$(cd ../.. && pwd)
AJ=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
B=build
rm -rf $B/gen $B/obj $B/assets $B/*.apk $B/classes.dex
mkdir -p $B/gen $B/obj $B/assets/radar

# ---- recursos que van dentro de la app (sin claves ni datos privados)
[ -f "$JARVIS/data/demo/meta.json" ] || python3 "$JARVIS/data/generate_demo.py" >/dev/null
cp "$JARVIS/agent/prompt.md" "$JARVIS/CLAUDE.md" $B/assets/
# las notas van en un único paquete (aapt no admite tildes en nombres de archivo)
python3 - "$JARVIS/data/demo/vault" $B/assets/vault.json <<'PY'
import json, sys, pathlib
root = pathlib.Path(sys.argv[1])
files = [{"path": "vault/" + p.relative_to(root).as_posix(), "text": p.read_text(encoding="utf-8") if p.suffix != ".pdf" else ""}
         for p in sorted(root.rglob("*")) if p.is_file()]
json.dump(files, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False)
print(f"  {len(files)} notas empaquetadas")
PY
cp "$JARVIS/data/demo/inbox.json" "$JARVIS/data/demo/calendar.json" "$JARVIS/data/demo/meta.json" $B/assets/
for f in index.html cockpit6.js manifest.webmanifest sw.js icon-192.png; do [ -f "$REPO/$f" ] && cp "$REPO/$f" $B/assets/radar/; done

if [ "${1:-}" = "test" ]; then
  mkdir -p $B/test-obj
  javac -nowarn -encoding UTF-8 -d $B/test-obj src/com/radar/jarvis/core/*.java src/com/radar/jarvis/core/fin/*.java test/CoreTest.java test/FinTest.java test/MetaApiTest.java test/DisciplineTest.java test/UiTest.java
  PORT=$(python3 -c "import socket;s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1])")
  rm -f $B/mock.log; python3 test/mock_anthropic.py $PORT $B/mock.log & MOCK=$!
  sleep 0.6
  rm -rf $B/test-data; mkdir -p $B/test-data
  set +e; LC_ALL=C.UTF-8 java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp $B/test-obj CoreTest $B/assets $B/test-data $PORT $B/mock.log; rc=$?
  echo "--- módulo financiero:"
  LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj FinTest; rc2=$?
  [ $rc2 -ne 0 ] && rc=$rc2
  echo "--- conector MetaApi (contra MetaApi SIMULADA):"
  PORT2=$(python3 -c "import socket;s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1])")
  rm -f $B/metaapi.log; touch $B/metaapi.log; python3 test/mock_metaapi.py $PORT2 $B/metaapi.log & MOCK2=$!
  sleep 0.6
  LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj MetaApiTest $PORT2 $B/metaapi.log; rc3=$?; set -e
  kill $MOCK2
  [ $rc3 -ne 0 ] && rc=$rc3
  echo "--- regla de autodisciplina:"
  rm -rf $B/test-disc; set +e; LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj DisciplineTest $B/test-disc; rc4=$?; set -e
  [ $rc4 -ne 0 ] && rc=$rc4
  echo "--- órdenes de pantalla:"
  set +e; LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj UiTest; rc5=$?; set -e
  [ $rc5 -ne 0 ] && rc=$rc5
  kill $MOCK
  echo "--- lo que vio la API simulada en cada petición:"; sort $B/mock.log | uniq -c
  exit $rc
fi

# ---- compilar
aapt package -f -m -J $B/gen -M AndroidManifest.xml -S res -I "$AJ"
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$AJ" -d $B/obj $(find src $B/gen -name '*.java')
dalvik-exchange --dex --output=$B/classes.dex $B/obj
aapt package -f -M AndroidManifest.xml -S res -A $B/assets -I "$AJ" -F $B/sin-firmar.apk
(cd $B && aapt add sin-firmar.apk classes.dex >/dev/null)
zipalign -f 4 $B/sin-firmar.apk $B/alineado.apk
# clave de depuración (no es secreta; solo para instalar en tu teléfono)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias jarvis \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=JARVIS debug" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out $B/jarvis-debug.apk $B/alineado.apk
apksigner verify $B/jarvis-debug.apk
rm -f $B/sin-firmar.apk $B/alineado.apk
ls -l $B/jarvis-debug.apk
