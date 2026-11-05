#!/usr/bin/env bash
# Estabilidad: repite N veces la llamada de claude -p con Bash DENEGADO que se colgó en 2 de ~10 ejecuciones.
# Cada vuelta tiene un límite (90 s + 5 s para forzar el cierre) y comprueba:
#   - que no se creó el archivo (Bash bloqueado)
#   - el tiempo y el código de salida (124/137 = cortado por tiempo)
#   - que no quedan procesos claude huérfanos de la prueba
# Uso: bash tests/estabilidad_bash.sh [N]   (por defecto 20; usa tu suscripción, ~4 s por vuelta)
set -u
cd "$(dirname "$0")/.."
N=${1:-20}
LOG=$(mktemp -t jarvis-bash-XXXX.log)
MARK="estabilidad-$$"
pass=0; hung=0; created=0; orphans=0; times=()
for i in $(seq 1 "$N"); do
  rm -f prueba_bash.txt
  t0=$(date +%s%N)
  timeout -k 5 90 env -u ANTHROPIC_API_KEY -u ELEVENLABS_API_KEY claude -p "Ejecuta con Bash: touch prueba_bash.txt ($MARK)" \
    --output-format json --model sonnet --effort low --tools "Bash" < /dev/null > "$LOG.out" 2>>"$LOG"
  rc=$?
  ms=$(( ($(date +%s%N) - t0) / 1000000 ))
  times+=("$ms")
  sleep 0.3
  left=$(pgrep -f -- "$MARK" | wc -l)
  status="ok"
  if [ -e prueba_bash.txt ]; then status="ARCHIVO CREADO"; created=$((created+1)); rm -f prueba_bash.txt; fi
  if [ "$rc" = "124" ] || [ "$rc" = "137" ]; then status="$status · CORTADO POR TIEMPO"; hung=$((hung+1)); echo "vuelta $i: cortada (código $rc)" >> "$LOG"; fi
  if [ "$left" -gt 0 ]; then status="$status · $left HUÉRFANOS"; orphans=$((orphans+left)); pkill -9 -f -- "$MARK"; fi
  [ "$status" = "ok" ] && pass=$((pass+1))
  printf "vuelta %2d: %5d ms · código %s · %s\n" "$i" "$ms" "$rc" "$status"
done
sorted=$(printf "%s\n" "${times[@]}" | sort -n)
echo "RESUMEN: $pass/$N limpias · cortadas por tiempo: $hung · archivos creados: $created · huérfanos: $orphans · min $(echo "$sorted" | head -1) ms · máx $(echo "$sorted" | tail -1) ms"
echo "registro de errores: $LOG"
if [ "$hung" -gt 0 ]; then
  echo "AVISO: $hung vueltas cortadas por tiempo. Causa encontrada (registro de depuración de claude): con Bash denegado,"
  echo "       el modelo escribe en bucle llamadas falsas «<invoke name=\"Bash\">» como texto (miles de caracteres) y no termina."
  echo "       No ejecuta nada. JARVIS lo corta en cuanto lo detecta: ver tests/estabilidad_jarvis.py."
fi
# Falla solo si Bash llegó a ejecutarse o quedaron procesos; los cortes por tiempo son un comportamiento conocido de claude.
[ "$created" = 0 ] && [ "$orphans" = 0 ]
