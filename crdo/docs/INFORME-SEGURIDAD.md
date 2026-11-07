# C.R.D.O. — Informe de seguridad, Etapa 1 (v3.1)

Fecha: 7 de noviembre de 2026. Alcance: todo `crdo/` (esquema y funciones de base de datos, scripts de operación,
Compose, systemd, política de Tailscale, CI, prototipo). **No hay servidor ni cuentas externas**: lo que depende de
ellos figura como no verificado.

## 1. Método

| Técnica | Resultado |
|---|---|
| Revisión línea por línea de `infra/`, `tools/`, `tests/` y del diseño v3 | 10 defectos encontrados y corregidos (§3) |
| Pruebas automatizadas nuevas (autenticación, permisos por rol, auditoría concurrente, recuperación, límites, integridad) | 4 defectos más encontrados **por las pruebas** y corregidos (§3) |
| `shellcheck` (nivel warning) sobre 15 scripts | 0 hallazgos |
| Verificador de coherencia (`tools/check-config.sh`) | 0 errores |
| Meta-prueba: falla inyectada a propósito | El ejecutor la detecta y termina con error (comprobado) |

## 2. Controles y evidencia

| Control | Implementación | Evidencia |
|---|---|---|
| Autenticación humana | Contraseña bcrypt (costo 12) + TOTP RFC 6238, verificados **dentro** de PostgreSQL | `60_autenticacion.sql` (58), vectores oficiales RFC 6238 en `10_seguridad.sql` |
| Códigos de un solo uso | `totp_last_step`: un código no se acepta dos veces (login, aprobaciones, límites, comandos) | `20`, `50`, `60` y el prototipo con una implementación TOTP independiente |
| Fuerza bruta | 5 fallos de contraseña o de TOTP bloquean 15 minutos; mismo mensaje para usuario inexistente | `10`, `60` |
| Sesiones | Token aleatorio de 256 bits, solo se guarda su sha256, vence a las 12 h, revocable una por una o todas | `60` |
| Teléfono perdido | 10 códigos de recuperación de un solo uso (sha256): cierran todas las sesiones, anulan el TOTP y obligan a re-enrolar | `60` |
| Secretos fuera del alcance de la app | La app no puede leer hashes de contraseñas, secretos TOTP, códigos de recuperación ni hashes de tokens | `60`, `70` |
| Permisos mínimos por rol | `crdo_app` (sin escritura directa en aprobaciones, trabajos, permisos, límites, medidores ni consumo), `crdo_readonly`, `crdo_backup` (sin secretos), `crdo_ops` (solo comandos y estado del presupuesto) | `10`, `70` (33) |
| Agentes nunca hacen operaciones humanas | TOTP que ningún agente posee + `auth.is_human_context()` (contexto `crdo.actor = 'agent'`) en login, aprobaciones, límites, permisos, encendido de agentes y comandos | `50`, `60` |
| Aprobaciones ligadas al contenido | Hash sha256 del contenido, vencimiento de 72 h como máximo, TOTP, consumo único, nueva verificación del hash al ejecutar | `20` (23) |
| R4 imposible | CHECK + trigger + funciones: ningún agente recibe ni pide permisos R4 | `10` |
| Límites de gasto | US$ 20 (reserva 15 %). Solo el dueño humano + TOTP + justificación; trigger contra cambios por fuera; historial inmutable; auditado con severidad alta al subirlos | `50` (42) |
| Control de gasto automático | Proyección bruta con impuestos, rechazo de opcionales que invaden la reserva, guardián que pausa opcionales; consumo de IA solo agregable y nunca negativo | `50`, `run-backup-tests.sh` |
| Auditoría inviolable | Solo agregar; cadena sha256 encadenada, numerada dentro de un candado; verificable al restaurar | `10`, `70`, concurrencia (6 × 100 eventos) |
| Cola sin duplicados | Leases, token de exclusión, idempotencia, `needs_review` para efectos externos | `30`, concurrencia (6 workers × 300 trabajos) |
| Respaldo cifrado | restic (cifrado autenticado del lado del cliente), contraseña incorrecta rechazada, corrupción detectada | `run-backup-tests.sh` |
| Recuperación | Restauración comparada con el manifiesto; caída abrupta (WAL); auditoría continúa tras restaurar; prohibido restaurar producción en staging | `run-backup-tests.sh` (29) |
| Secretos en el servidor | `/opt/crdo/secrets` 700, archivos 600, contraseñas distintas por entorno, longitud mínima, sin valores de ejemplo | `preflight.sh` + `run-config-tests.sh` (8 casos malos detectados) |
| Exposición de red | Sin puertos entrantes (UFW), API en 127.0.0.1, SSH solo por Tailscale, staging sin redes compartidas | Compose verificado; **UFW, Tailscale y SSH: no verificados (sin servidor)** |

## 3. Defectos encontrados en esta etapa (todos corregidos)

| # | Gravedad | Defecto | Cómo se encontró | Corrección | Prueba que lo cubre ahora |
|---|---|---|---|---|---|
| 1 | **Alta** | Con escrituras concurrentes, la **cadena de auditoría se rompía**: el id se asignaba fuera del candado. Habría generado falsas alarmas de "auditoría alterada" con la API, el worker y el scheduler escribiendo a la vez | Prueba nueva de concurrencia (6 × 100) | El id se asigna dentro del candado y la columna no tiene valor por defecto | `run-db-tests.sh` (concurrencia), `70` |
| 2 | **Alta** | `crdo_backup` podía **leer los secretos TOTP** y los códigos de recuperación: con esa credencial se podían generar códigos válidos | Prueba nueva `70_integridad.sql` | El rol de respaldo no ve secretos; los volcados los hace el dueño de la base dentro del contenedor | `70` |
| 3 | **Alta** | La app podía **leer y escribir `password_hash`**: un backend comprometido podía cambiar la contraseña del dueño | Revisión | Privilegios por columna; la contraseña se cambia solo con `auth.change_password` (contraseña actual + TOTP) | `60` |
| 4 | Media | `restore.sh into staging` restauraba **datos de producción en staging** | Revisión | Solo restaura respaldos del mismo entorno | `run-backup-tests.sh` |
| 5 | Media | El tope fijo (CHECK) impedía que **el propio dueño** cambiara los límites, y no había historial | Pedido del dueño | Procedimiento autenticado y auditado + historial inmutable + trigger | `50` |
| 6 | Media | `crdo status` ejecutaba el guardián (que **pausa servicios y envía alertas**): una consulta tenía efectos | Revisión | `status` solo consulta `finance.budget_status` | `check-config`, revisión |
| 7 | Media | `crdo rollback` con una sola versión sana "volvía" a la misma versión | Revisión | Exige 2 versiones sanas | revisión (requiere servidor para probarlo de punta a punta) |
| 8 | Media | En la prueba de respaldo, las verificaciones `condición && ok` **no fallaban** si la condición era falsa: un fallo podía pasar inadvertido | Un conteo que no cerraba (27 en lugar de 28) | Toda verificación falla de forma explícita + conteo esperado obligatorio + aviso de salida inesperada | autocontenido |
| 9 | Baja | `restore.sh` combinaba etiquetas como "o" en lugar de "y" (error que introduje en esta etapa) | Prueba de restauración | `--tag db,prod` | `run-backup-tests.sh` |
| 10 | Baja | `preflight.sh` informaba un nombre de archivo equivocado | `run-config-tests.sh` | Corregido | `run-config-tests.sh` |
| 11 | Baja | Faltaban plantillas de `restic.env`, `monitor.env` y `tunnel-*.env` | Revisión de coherencia | `infra/secrets.example/` | `check-config.sh` |
| 12 | Baja | `restore.sh into` usaba `docker compose -p` sin los archivos de Compose | Revisión | Detiene e inicia los contenedores por nombre | requiere servidor |
| 13 | Baja | Las pruebas de respaldo no corrían sin root (restic deja sus archivos en solo lectura) | Corrida sin privilegios | Copia con permiso de escritura | corrida sin privilegios |
| 14 | Info | La carga del esquema imprimía filas de `set_config` | Corrida sin privilegios | `DO … PERFORM` | — |

> Sobre la v3 (base oficial): sus 112 + 22 pruebas **siguen pasando** (reproducido 3 veces), pero no cubrían los
> defectos 1 a 3. Por eso no alcanzaba con que "pasaran las pruebas": hacía falta ampliarlas.

## 4. Riesgos residuales (aceptados o pendientes)

| Riesgo | Por qué queda | Mitigación |
|---|---|---|
| Un **superusuario** de PostgreSQL puede desactivar triggers | Es el equivalente a ser root del servidor | Solo se usa por SSH (Tailscale + llave); la auditoría detecta borrados o ediciones de eventos; los respaldos se comparan con el manifiesto |
| Los secretos TOTP viajan dentro de los respaldos (cifrados) | Hacen falta para restaurar | La contraseña de restic se custodia como la llave maestra (gestor + papel) |
| `crdo.actor = 'agent'` depende de que el backend lo marque | El backend todavía no existe | Es una capa adicional: la barrera real es el TOTP, que ningún agente puede leer ni calcular |
| bcrypt en lugar de Argon2id | pgcrypto no ofrece Argon2 | bcrypt con costo 12 es adecuado para 1 a 5 usuarios humanos |
| No hay capa HTTP (FastAPI ni panel) | No construida | Al construirla: CSRF, límite de peticiones, cabeceras de seguridad, validación del JWT de Cloudflare Access y pruebas propias |
| Android admite una sola VPN a la vez | Plataforma | Cloudflare Access cuando exista el dominio |
| Integraciones externas no verificadas | No existen cuentas | Pruebas de aceptación T03–T07, T12–T14, T28–T29 |

## 5. No verificado (requiere servidor o cuentas)

UFW y SSH endurecido · Tailscale y su política · `tailscale serve` · Cloudflare Tunnel/Access · Docker en ejecución
(incluido `00-roles.sh` dentro del *entrypoint* de la imagen) · `crdo deploy/rollback` · `crdo-opsd` · timers de systemd ·
llave de B2 sin borrado y Caps & Alerts · avisos reales de healthchecks · CI en GitHub · comportamiento en un teléfono.
