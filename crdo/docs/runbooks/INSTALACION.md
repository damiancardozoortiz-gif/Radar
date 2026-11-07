# Runbook — Instalación del servidor de C.R.D.O.

> **No ejecutar sin autorización explícita de gasto.** El paso 4 contrata un servicio pago.
> Probado en este documento: la lógica de base de datos, los respaldos, la restauración y el prototipo
> (ver `docs/CIERRE-ETAPA-1.md`). **No probado:** este procedimiento en un servidor real.
> 🔑 = lo hace el dueño en su cuenta. Todo lo demás son comandos que se pueden copiar y pegar.

## 0. Requisitos

| Qué | Dónde se guarda |
|---|---|
| Llave SSH ed25519 del dueño (generada en Termux: `ssh-keygen -t ed25519`) | Teléfono + copia en el gestor de contraseñas |
| Gestor de contraseñas con: contraseña de restic, contraseñas de la base (prod y staging), códigos de recuperación | Bitwarden u otro con respaldo; contraseña de restic y códigos de recuperación **también en papel** |
| Repo privado `crdo` creado y exportado (`tools/export-to-private-repo.sh`) | GitHub |
| Cuentas gratis: Tailscale, Backblaze B2 (bucket privado + llave **sin borrado** + Caps en US$ 0), healthchecks.io (chequeos: backup, restore-test, budget, heartbeat) | — |

## 1. Verificación de precio (sin gasto)

1. 🔑 En el checkout de Hetzner (CX33, Europa, Ubuntu 24.04, IPv4, **sin** backups), anotar el **precio final**.
2. Cargar el precio y el perfil impositivo verificados en una instancia local (`tools/crdo-local`) y comprobar
   que la proyección quede en `US$ 17` o menos. Si no queda, se usa CX23 (regla §B.5 del diseño).

## 2. Fase A — Servidor y núcleo de datos (instalable ya)

```bash
# 3. 🔑 Tailscale: crear una auth key de un solo uso con la etiqueta tag:crdo-server, y pegar infra/tailscale/policy.hujson
# 4. 🔑 Hetzner: crear el servidor (GASTO). Firewall de Hetzner: SSH temporal solo desde tu IP.

# 5. Desde Termux, primera conexión por IP pública (única vez):
scp crdo/infra/scripts/bootstrap-server.sh root@<IP>:/root/
ssh root@<IP> 'TS_AUTHKEY=tskey-... ADMIN_PUBKEY="$(cat ~/.ssh/authorized_keys | head -n1)" bash /root/bootstrap-server.sh'

# 6. Comprobar acceso por Tailscale ANTES de cerrar el SSH público:
ssh crdo@crdo-prod 'sudo tailscale status && sudo ufw status'
# 7. 🔑 Hetzner: quitar la regla SSH temporal del firewall.

# 8. Código (llave de despliegue de solo lectura del repo privado):
ssh crdo@crdo-prod
sudo git clone git@github.com:<usuario>/crdo.git /opt/crdo/app

# 9. Secretos (plantillas en infra/.env.example e infra/secrets.example/):
sudo install -m 600 /opt/crdo/app/infra/.env.example /opt/crdo/secrets/prod.env
sudo install -m 600 /opt/crdo/app/infra/.env.example /opt/crdo/secrets/staging.env
sudo install -m 600 /opt/crdo/app/infra/secrets.example/restic.env.example  /opt/crdo/secrets/restic.env
sudo install -m 600 /opt/crdo/app/infra/secrets.example/monitor.env.example /opt/crdo/secrets/monitor.env
openssl rand -base64 32      # una vez por cada contraseña o llave; DISTINTAS en prod y staging
sudo nano /opt/crdo/secrets/prod.env        # completar; repetir para staging.env, restic.env, monitor.env

# 10. Verificación previa: debe terminar con "0 errores"
sudo /opt/crdo/app/infra/scripts/preflight.sh

# 11. Núcleo de datos (solo PostgreSQL; el esquema se carga solo la primera vez):
cd /opt/crdo/app/infra
sudo CRDO_VERSION=0 docker compose -p crdo-prod    --env-file /opt/crdo/secrets/prod.env    -f compose.yml up -d postgres
sudo CRDO_VERSION=0 docker compose -p crdo-staging --env-file /opt/crdo/secrets/staging.env -f compose.yml -f compose.staging.yml up -d postgres

# 12. Pruebas de base de datos CONTRA EL MOTOR DEL SERVIDOR, en un contenedor de prueba descartable:
sudo docker run --rm -d --name crdo-pruebas --network none -e POSTGRES_PASSWORD=x \
  "pgvector/pgvector:$(grep ^PGVECTOR_TAG= /opt/crdo/secrets/prod.env | cut -d= -f2)"
sudo docker cp /opt/crdo/app crdo-pruebas:/crdo
sudo docker exec crdo-pruebas bash -c 'cd /crdo && PGBIN=/usr/lib/postgresql/16/bin bash tests/run-db-tests.sh'
sudo docker rm -f crdo-pruebas

# 13. Dueño del sistema (muestra UNA vez el secreto TOTP y los 10 códigos de recuperación):
sudo docker exec -it crdo-prod-postgres-1 psql -U crdo_owner -d crdo -c \
  "SELECT * FROM auth.create_user('<email>', '<nombre>', 'owner', '<contraseña de 12+>')"
#     -> cargar el secreto en la app TOTP del teléfono; los códigos de recuperación, en papel.

# 14. Respaldos, prueba de restauración, guardián presupuestario y ejecutor de comandos:
sudo bash -c 'set -a; . /opt/crdo/secrets/restic.env; restic init'
sudo cp /opt/crdo/app/infra/systemd/* /etc/systemd/system/ && sudo systemctl daemon-reload
sudo systemctl enable --now crdo-backup.timer crdo-restore-test.timer crdo-budget-guard.timer crdo-opsd.service
sudo systemctl start --wait crdo-backup.service && sudo systemctl start --wait crdo-restore-test.service
sudo journalctl -u crdo-restore-test.service -n 20    # debe decir "Prueba de restauración OK"
```

Al terminar la fase A, el servidor guarda, respalda, restaura, controla el presupuesto y aplica todas las reglas
de seguridad. La operación se hace por SSH (Tailscale) con `psql` o con `tools/crdo-local` apuntando al servidor.

## 3. Fase B — API y panel (cuando existan `backend/` y `panel/`)

```bash
# Requiere: imagen ghcr.io/<usuario>/crdo-api:<versión> publicada por el CI del repo privado.
sudo crdo deploy <versión>                  # prod (hace respaldo previo y rollback si /health falla)
sudo crdo deploy <versión> --env staging
sudo tailscale serve --bg --https=443  http://127.0.0.1:8000
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8100
# Desde el teléfono, con Tailscale encendido: https://crdo-prod.<tailnet>.ts.net -> instalar como app
```

## 4. Fase C — Cloudflare con dominio (opcional, si el control presupuestario lo acepta)

Ver `infra/cloudflared/README.md`. `sudo crdo deploy <versión> --edge`.

## 5. Cierre

Ejecutar las pruebas de aceptación de `docs/CIERRE-ETAPA-1.md` §4 y registrar la evidencia en
`docs/runbooks/acta-pruebas-etapa1.md` (fecha, comando, salida, resultado).
