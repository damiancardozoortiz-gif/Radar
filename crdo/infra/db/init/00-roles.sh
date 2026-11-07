#!/usr/bin/env bash
# C.R.D.O. — Crea los roles con login y contraseña (BORRADOR v2).
# Lo ejecuta la imagen de Postgres en el primer arranque (docker-entrypoint-initdb.d).
# Las contraseñas llegan por variables de entorno desde /opt/crdo/secrets/<entorno>.env; nunca están en el repo.
set -euo pipefail

: "${CRDO_DB_APP_PASSWORD:?}" "${CRDO_DB_READONLY_PASSWORD:?}" "${CRDO_DB_BACKUP_PASSWORD:?}" "${CRDO_DB_OPS_PASSWORD:?}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app="$CRDO_DB_APP_PASSWORD" -v ro="$CRDO_DB_READONLY_PASSWORD" \
  -v bk="$CRDO_DB_BACKUP_PASSWORD" -v ops="$CRDO_DB_OPS_PASSWORD" <<'SQL'
CREATE ROLE crdo_app      LOGIN PASSWORD :'app' CONNECTION LIMIT 40;
CREATE ROLE crdo_readonly LOGIN PASSWORD :'ro'  CONNECTION LIMIT 5;
CREATE ROLE crdo_backup   LOGIN PASSWORD :'bk'  CONNECTION LIMIT 2;
CREATE ROLE crdo_ops      LOGIN PASSWORD :'ops' CONNECTION LIMIT 2;
ALTER DATABASE crdo SET timezone TO 'UTC';
SQL
