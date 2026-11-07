# Plantillas de secretos

Estos archivos van en `/opt/crdo/secrets/` en el servidor (carpeta `root:root 700`, archivos `600`). **Nunca** van al
repositorio: `.gitignore` bloquea `*.env`.

| Archivo en el servidor | Plantilla | Lo usa |
|---|---|---|
| `prod.env`, `staging.env` | `../.env.example` | Docker Compose (un archivo **distinto** por entorno) |
| `restic.env` | `restic.env.example` | `backup.sh`, `restore.sh` |
| `monitor.env` | `monitor.env.example` | `budget-guard`, scheduler |
| `tunnel-prod.env`, `tunnel-staging.env` | `tunnel.env.example` | `cloudflared` (solo con dominio) |

`infra/scripts/preflight.sh` comprueba que existan, tengan permisos correctos y no tengan campos vacíos.
