# Runbook — Restauración, desastre, teléfono perdido y rotación de llaves

> Los pasos de restauración de base de datos están **probados** con los scripts reales (`tests/run-backup-tests.sh`:
> restauración, comparación con el manifiesto, cadena de auditoría, caída abrupta). El procedimiento completo en un
> servidor real **no** se ensayó todavía: es la prueba de aceptación T13.

## A. Prueba de restauración (semanal, automática)

`crdo-restore-test.timer` ejecuta `restore.sh test` todos los domingos. Para lanzarla a mano:

```bash
sudo systemctl start --wait crdo-restore-test.service && sudo journalctl -u crdo-restore-test.service -n 20
```

Qué hace: descifra el último respaldo de producción, lo restaura en un PostgreSQL temporal **sin red**, compara
conteos y el último hash de auditoría con el manifiesto guardado al respaldar, verifica la cadena completa y
revisa con `restic check` una muestra del 5 % de los datos. Si algo no coincide, falla y avisa por healthchecks.

## B. Restaurar la base de un entorno (error humano, corrupción)

```bash
sudo /opt/crdo/app/infra/scripts/restore.sh into prod      # pide escribir "restaurar prod"
```

- Solo restaura respaldos **del mismo entorno**. Restaurar producción en staging está prohibido (probado).
- Detiene api, worker y scheduler; restaura; compara con el manifiesto; vuelve a arrancar.

## C. Desastre: el servidor ya no existe (objetivo: < 2 h, pérdida ≤ 24 h)

Necesitás: el gestor de contraseñas (contraseña de restic, llave de B2, secretos de `prod.env`) y Termux.

```bash
# 1. Servidor nuevo + bootstrap + clon del repo: INSTALACION.md pasos 3 a 10 (con los MISMOS secretos de antes).
# 2. Base vacía con el esquema:
cd /opt/crdo/app/infra
sudo CRDO_VERSION=0 docker compose -p crdo-prod --env-file /opt/crdo/secrets/prod.env -f compose.yml up -d postgres
# 3. Antes de restaurar, ver qué respaldos hay:
sudo bash -c 'set -a; . /opt/crdo/secrets/restic.env; restic snapshots --tag db,prod'
# 4. Restaurar y verificar contra el manifiesto:
sudo /opt/crdo/app/infra/scripts/restore.sh into prod
# 5. Timers y servicios (INSTALACION.md paso 14); luego API y panel si existen (fase B).
# 6. Registrar el incidente: fecha, causa, último respaldo usado y datos perdidos.
```

## D. Teléfono perdido o robado

1. Desde cualquier otro dispositivo: consola de Tailscale → **eliminar el teléfono** (corta el acceso al servidor).
2. Entrar con **contraseña + un código de recuperación** (papel): `auth.login_with_recovery`. Esto:
   - cierra todas las sesiones abiertas;
   - invalida el TOTP del teléfono perdido;
   - abre una sesión de recuperación de 30 minutos que solo sirve para enrolar un TOTP nuevo.
3. Enrolar el TOTP en el teléfono nuevo con `auth.reenroll_totp`. Esto entrega un secreto nuevo y **10 códigos de
   recuperación nuevos**; los anteriores quedan invalidados.
4. Cambiar la contraseña (`auth.change_password`) y las contraseñas de las cuentas externas que el teléfono tenía abiertas.
5. Revisar la auditoría: `SELECT * FROM audit.events WHERE severity IN ('high','critical') ORDER BY id DESC LIMIT 50;`

Todo el flujo está probado en `tests/db/60_autenticacion.sql`.

## E. Rotación de llaves y contraseñas (cada 6 meses o ante sospecha)

| Qué | Cómo |
|---|---|
| Contraseñas de la base | `ALTER ROLE crdo_app PASSWORD '…'` (como `crdo_owner`) + actualizar `prod.env` + `sudo crdo deploy <versión actual>` |
| `CRDO_SECRET_KEY` | Cambiar en `prod.env` y redesplegar: cierra todas las sesiones web |
| Tokens de agentes | `auth.revoke_agent_token` + `auth.issue_agent_token` (90 días como máximo, por diseño) |
| Llave de B2 | Crear una llave nueva sin borrado → `restic.env` → `restore.sh test` → borrar la vieja |
| Contraseña de restic | `restic key add` → probar → `restic key remove <vieja>`. Actualizar gestor y papel |
| Auth key de Tailscale | Las de un solo uso vencen solas; revisar dispositivos en la consola |

## F. Limpieza de respaldos (mensual, solo el dueño)

El servidor **no puede** borrar respaldos (su llave de B2 no tiene ese permiso). Una vez al mes, desde un
dispositivo del dueño, con una llave de B2 que sí puede borrar y que **no** se guarda en el servidor:

```bash
restic forget --tag db,prod --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune
```
