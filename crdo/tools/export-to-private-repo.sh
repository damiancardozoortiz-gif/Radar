#!/usr/bin/env bash
# C.R.D.O. — Exporta la carpeta crdo/ de `Radar` a un repositorio PRIVADO e independiente, conservando el historial.
#
# Este script NO crea el repositorio ni cambia nada en GitHub. Prerrequisito (lo hacés vos, desde el teléfono):
#   GitHub (app o navegador) -> New repository -> nombre "crdo" -> Private -> SIN README ni licencia -> Create.
#
# Uso (desde la raíz de un clon de Radar):
#   bash crdo/tools/export-to-private-repo.sh git@github.com:<tu-usuario>/crdo.git
# Hace: git subtree split de crdo/ -> rama local "crdo-export" -> la empuja como "main" del repo nuevo.
set -euo pipefail

REMOTE="${1:?Uso: export-to-private-repo.sh <url-del-repo-privado-vacío>}"
[[ -d crdo && -d .git ]] || { echo "Ejecutar desde la raíz del repo Radar" >&2; exit 1; }
[[ -z "$(git status --porcelain crdo)" ]] || { echo "Hay cambios sin confirmar en crdo/" >&2; exit 1; }

echo "1/3 Separando el historial de crdo/ ..."
git subtree split --prefix=crdo -b crdo-export

echo "2/3 Verificando que el repo destino esté vacío ..."
if git ls-remote --exit-code "$REMOTE" >/dev/null 2>&1; then
  if [[ -n "$(git ls-remote --heads "$REMOTE")" ]]; then
    echo "El repo destino ya tiene ramas. Por seguridad no se sobrescribe." >&2; exit 1
  fi
fi

echo "3/3 Empujando a $REMOTE (rama main) ..."
git push "$REMOTE" crdo-export:main

cat <<'EOF'
Listo. Pasos siguientes (en GitHub, desde el teléfono):
  - Settings > Branches: proteger "main" (requerir PR y que pase el CI).
  - Settings > Code security: activar secret scanning y Dependabot alerts.
  - Settings > Actions: permitir solo acciones de GitHub y verificadas.
  - Luego, en Radar, la carpeta crdo/ se puede reemplazar por un enlace al repo nuevo (decisión tuya).
EOF
