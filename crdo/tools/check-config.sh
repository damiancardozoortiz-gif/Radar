#!/usr/bin/env bash
# C.R.D.O. — Coherencia de la configuración del repositorio (Etapa 1, v3.1). Solo lee. Corre en cualquier lado.
# Verifica que los archivos se refieran entre sí correctamente: variables de Compose declaradas, scripts que
# existen y son ejecutables, unidades systemd que apuntan a archivos reales, documentos que enlazan archivos
# existentes, política de Tailscale válida y orden de los scripts de base de datos.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
ERR=0
bad()  { echo "  ERROR: $*"; ERR=$((ERR + 1)); }
good() { echo "  ok: $*"; }

echo "== Variables de Compose declaradas en .env.example"
vars="$(grep -ohE '\$\{[A-Z0-9_]+(:[-?][^}]*)?\}' infra/compose.yml infra/compose.staging.yml | sed -E 's/\$\{([A-Z0-9_]+).*/\1/' | sort -u)"
for v in $vars; do
  if grep -qE "^$v=" infra/.env.example; then :
  elif grep -qE "\\\$\{$v:-" infra/compose.yml infra/compose.staging.yml; then :     # tiene valor por defecto
  else bad "la variable $v se usa en Compose pero no está en .env.example ni tiene valor por defecto"; fi
done
good "$(echo "$vars" | wc -w) variables revisadas"

echo "== Scripts y unidades systemd"
for f in infra/scripts/* infra/db/init/*.sh tests/*.sh tools/*.sh tools/crdo-local; do
  [[ -f "$f" ]] || continue
  [[ -x "$f" ]] || bad "$f no es ejecutable"
  head -n1 "$f" | grep -q '^#!/usr/bin/env bash' || bad "$f sin shebang de bash"
  bash -n "$f" 2>/dev/null || bad "$f tiene errores de sintaxis"
done
good "scripts con shebang, permiso de ejecución y sintaxis válida"
for u in infra/systemd/*.service; do
  exe="$(sed -nE 's|^ExecStart=/opt/crdo/app/([^ ]+).*|\1|p' "$u")"
  [[ -n "$exe" && -f "$exe" ]] || bad "$u apunta a /opt/crdo/app/$exe, que no existe en el repo"
done
for t in infra/systemd/*.timer; do
  [[ -f "${t%.timer}.service" ]] || bad "$t no tiene su .service"
  grep -q "$(basename "$t")" infra/scripts/bootstrap-server.sh || bad "bootstrap-server.sh no habilita $(basename "$t")"
done
grep -q 'crdo-opsd.service' infra/scripts/bootstrap-server.sh || bad "bootstrap-server.sh no habilita crdo-opsd.service"
good "unidades systemd apuntan a scripts existentes"

echo "== Rutas de servidor coherentes con el repo independiente"
if grep -rn --exclude=check-config.sh '/opt/crdo/app/crdo/' infra tools 2>/dev/null; then bad "rutas viejas /opt/crdo/app/crdo/ (antes del repo independiente)"; else good "sin rutas viejas"; fi

echo "== Base de datos"
prev=""
for f in infra/db/init/0*; do
  n="$(basename "$f" | cut -c1-2)"; [[ "$n" > "$prev" ]] || bad "orden de init incorrecto en $f"; prev="$n"
done
good "scripts de init en orden: $(ls infra/db/init | tr '\n' ' ')"
grep -q 'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA' infra/db/init/03-ops-functions.sql || bad "03 no revoca funciones a PUBLIC"

echo "== Política de Tailscale"
python3 - <<'PY' || bad "infra/tailscale/policy.hujson no es HuJSON válido"
import json, re
t = open('infra/tailscale/policy.hujson').read()
t = re.sub(r'//[^\n]*', '', t)
t = re.sub(r',(\s*[}\]])', r'\1', t)
json.loads(t)
PY
good "política de Tailscale válida"

echo "== Enlaces en la documentación"
for d in docs/*.md docs/runbooks/*.md README.md infra/secrets.example/README.md infra/cloudflared/README.md; do
  [[ -f "$d" ]] || continue
  base="$(dirname "$d")"
  while read -r link; do
    [[ "$link" =~ ^(https?:|#|mailto:) ]] && continue
    target="${link%%#*}"; [[ -z "$target" ]] && continue
    [[ -e "$base/$target" || -e "$target" ]] || bad "$d enlaza '$link', que no existe"
  done < <(grep -oE '\]\([^)]+\)' "$d" | sed -E 's/^\]\((.*)\)$/\1/')
done
good "enlaces revisados"

echo "== Pendientes declarados (valores 'cambiar' que se completan al instalar)"
grep -nE '=cambiar' infra/.env.example | sed 's/^/  pendiente: /'

echo "== Resultado: $ERR errores"
(( ERR == 0 ))
