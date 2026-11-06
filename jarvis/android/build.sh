#!/usr/bin/env bash
# Compila la app Android de JARVIS sin Gradle (herramientas de Ubuntu/Debian: android-sdk-platform-23, aapt,
# dalvik-exchange, zipalign, apksigner). Reutiliza el proyecto existente: copia a la app las instrucciones
# (agent/prompt.md), tu perfil (CLAUDE.md), las notas/correo/agenda (data/demo) y la web de RADAR (raíz del repo).
#   ./build.sh          -> build/jarvis-debug.apk (firmada SOLO con la clave original; sin ella, sin firmar)
#   ./build.sh unsigned -> build/jarvis-sin-firmar.apk (para la compilación externa y las verificaciones)
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

# ---- autoconocimiento: inventario de compilación, código fuente propio y pendientes conocidos
cp pendientes.json $B/assets/pendientes.json
python3 - "$B/assets" "$HERE" <<'PY'
import json, os, re, sys, datetime
out, here = sys.argv[1], sys.argv[2]
mods, src = [], {}
for root, _, files in os.walk(os.path.join(here, "src")):
    for f in sorted(files):
        if not f.endswith(".java"): continue
        p = os.path.join(root, f); t = open(p, encoding="utf-8").read()
        rel = os.path.relpath(p, os.path.join(here, "src"))
        src[rel] = t
        mods.append({"archivo": rel, "lineas": t.count("\n"), "clases": re.findall(r"(?:public |final )*(?:class|interface) (\w+)", t)[:6]})
man = open(os.path.join(here, "AndroidManifest.xml"), encoding="utf-8").read()
ver = re.search(r'versionName="([^"]+)"', man).group(1)
perms = re.findall(r'uses-permission android:name="android\.permission\.(\w+)"', man)
tests = open(os.path.join(here, ".pruebas.txt"), encoding="utf-8").read().splitlines() if os.path.exists(os.path.join(here, ".pruebas.txt")) else ["sin resultados de pruebas guardados"]
json.dump({"version": ver, "compilado": datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M UTC"), "modulos": mods,
           "lineas_totales": sum(m["lineas"] for m in mods), "permisos": perms, "pruebas": tests}, open(os.path.join(out, "inventario.json"), "w"), ensure_ascii=False)
json.dump(src, open(os.path.join(out, "fuente.json"), "w"), ensure_ascii=False)
PY

if [ "${1:-}" = "test" ]; then
  exec > >(tee $B/salida-pruebas.txt)  # para guardar el resumen en .pruebas.txt (lo lee el inventario de JARVIS)
  mkdir -p $B/test-obj
  javac -nowarn -encoding UTF-8 -d $B/test-obj src/com/radar/jarvis/core/*.java src/com/radar/jarvis/core/fin/*.java test/CoreTest.java test/FinTest.java test/MetaApiTest.java test/DisciplineTest.java test/UiTest.java test/LabTest.java test/NodeSandbox.java test/EvolutionTest.java test/FenixTest.java
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
  echo "--- laboratorio y experiencia (aislamiento: Node vm; en el teléfono, WebView sin red):"
  rm -rf $B/test-lab; set +e; LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj LabTest $B/test-lab; rc6=$?; set -e
  [ $rc6 -ne 0 ] && rc=$rc6
  echo "--- FÉNIX 80 (motor, sin red):"
  rm -rf $B/test-evo; set +e; LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp $B/test-obj EvolutionTest $B/test-evo; rc7=$?; set -e
  [ $rc7 -ne 0 ] && rc=$rc7
  echo "--- FÉNIX 80 + VULKAN (misión completa con Claude SIMULADO y aislamiento Node):"
  rm -rf $B/test-fenix; set +e; LC_ALL=C.UTF-8 java -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp $B/test-obj FenixTest $B/test-fenix $B/assets $PORT $B/mock.log; rc8=$?; set -e
  [ $rc8 -ne 0 ] && rc=$rc8
  kill $MOCK
  sleep 0.3
  { echo "pruebas del $(date -u '+%Y-%m-%d %H:%M UTC'): $([ $rc -eq 0 ] && echo 'TODAS PASAN' || echo 'HAY FALLOS')"; grep -E "^---|PASA ·" $B/salida-pruebas.txt | grep -v "lo que vio"; } > .pruebas.txt
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
# ---- firma. La identidad de la app instalada es el certificado de abajo: SOLO esa clave produce actualizaciones.
# Nunca se genera una clave nueva: sin la original sale una APK SIN FIRMAR (sirve para verificar, no para actualizar).
EXPECTED_CERT=f8004ee25772fc3e3e5d1c7fd9737d64f4773db0f00f4ff6789f9d7dd623750b   # huella pública del certificado (no es secreta)
KS=${JARVIS_KEYSTORE:-debug.keystore}
if [ "${1:-}" = "unsigned" ] || [ ! -f "$KS" ]; then
  [ "${1:-}" = "unsigned" ] || echo "AVISO: no está la clave de firma original ($KS): genero la APK SIN FIRMAR. No sirve para actualizar la app instalada."
  mv $B/alineado.apk $B/jarvis-sin-firmar.apk
  rm -f $B/sin-firmar.apk
  zipalign -c 4 $B/jarvis-sin-firmar.apk
  ls -l $B/jarvis-sin-firmar.apk
  exit 0
fi
apksigner sign --ks "$KS" --ks-pass pass:${JARVIS_KS_PASS:-android} --key-pass pass:${JARVIS_KS_PASS:-android} --out $B/jarvis-debug.apk $B/alineado.apk
apksigner verify $B/jarvis-debug.apk
CERT=$(apksigner verify --print-certs $B/jarvis-debug.apk | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
if [ "$CERT" != "$EXPECTED_CERT" ]; then
  echo "ERROR: la APK quedó firmada con OTRO certificado ($CERT). No es una actualización de la app instalada: la borro."
  rm -f $B/jarvis-debug.apk; exit 1
fi
rm -f $B/sin-firmar.apk $B/alineado.apk
echo "firma: certificado original (actualiza la app instalada)"
ls -l $B/jarvis-debug.apk
