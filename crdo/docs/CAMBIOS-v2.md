# C.R.D.O. — Etapa 1: informe de cambios v1 → v2

Fecha: 7 de noviembre de 2026. Corresponde a la revisión técnica pedida tras aprobar la arquitectura general.

**Qué NO se hizo:** no se instaló nada en ningún servidor, no se contrató ningún servicio, no se creó ninguna
cuenta, no se creó el repositorio privado y no se tocó ninguna cuenta tuya. Las pruebas corrieron en el contenedor
temporal donde trabajo. Ahí, y solo ahí, se instalaron `postgresql-16-pgvector` y `restic` para poder probar.
Ese contenedor se destruye al terminar la sesión.

---

## 1. Resumen por pedido

| # | Pedido | Resultado | Evidencia |
|---|---|---|---|
| 1 | Optimizar costos, separar indispensable de diferible | Costo fijo mínimo **de US$ 9–14 a ≈ US$ 5–6/mes**. Tres niveles con disparadores explícitos | Diseño §B.4 |
| 2 | Confirmar que el VPS no renderiza | **Confirmado y forzado** en base de datos, contenedor y red | §C.6; prueba `30_cola.sql` (3 verificaciones) |
| 3 | Recuperar tareas interrumpidas, evitar duplicados | Leases, token de exclusión, idempotencia, recolector, `needs_review` para efectos externos | §C.7; `30_cola.sql` + prueba de concurrencia |
| 4 | Aprobaciones con hash, vencimiento y código de un solo uso | Implementado en la base: la app no puede saltearlo | §C.4; `20_aprobaciones.sql` (23 verificaciones) |
| 5 | Verificar cifrado, respaldo y restauración | **Verificado con los scripts reales** y restic real | §C.10; `run-backup-tests.sh` (18 verificaciones) |
| 6 | Probar pgvector en PostgreSQL 16 | **Probado**: HNSW, recall@10 = 1,000, texto completo en español | `40_pgvector.sql` (8 verificaciones) |
| 7 | Entorno de pruebas aislado | CI efímero + staging sin redes compartidas + staging por horas opcional | §C.5; `docker compose config` |
| 8 | Repositorio privado independiente | Script de exportación **probado contra un repo local**: historial conservado, pruebas pasan en el repo exportado | §D.1, §E.1 |
| 9 | Administración completa desde Android | Matriz de 14 tareas, ninguna requiere PC. Comandos de servidor desde el panel con TOTP | §C.8 |
| 10 | Claude API, MCP y agentes sin depender de Claude | Rutas de modelo con proveedores intercambiables; contratos de agentes y MCP | §C.9; `agents/README.md`, `mcp/README.md` |

---

## 2. Errores de la v1 encontrados y corregidos

Los encontraron las pruebas nuevas o la revisión línea por línea. Son errores reales del diseño anterior.

| Gravedad | Error en la v1 | Cómo se encontró | Corrección |
|---|---|---|---|
| **Alta** | La app tenía `UPDATE` sobre `ops.approvals`: un agente o un panel comprometido podía **autoaprobarse** | Revisión | Sin escritura directa; solo funciones con TOTP verificado en la base |
| **Alta** | La app podía otorgar permisos a agentes (`INSERT` en `org.agent_permissions`) y encender agentes (`UPDATE` en `sys.settings`) | Revisión | Solo por funciones con TOTP del dueño |
| **Alta** | El secreto TOTP estaba en `auth.users`, legible por la app | Revisión | Tabla `auth.totp_secrets` sin acceso de la app |
| **Alta** | Staging y producción **compartían la red** `crdo_app` (por el Caddy común) | Revisión | Sin redes compartidas; verificado con `docker compose config` |
| **Alta** | `REVOKE ALL ON SCHEMA public` dejaba a la app **sin acceso a pgvector** ni a `citext`: la memoria vectorial no habría funcionado | Prueba `40_pgvector.sql` | Se quita solo `CREATE`; se da `USAGE` |
| Media | Un `REVOKE` de columnas no tiene efecto si existe permiso sobre la tabla completa: el "contenido inmutable" no lo era | Revisión | Se quita el permiso de tabla y se dan solo las columnas permitidas |
| Media | Los roles de la base no tenían login ni contraseña: la app no habría podido conectarse | Revisión | `00-roles.sh` con contraseñas por entorno |
| Media | El hash de auditoría usaba `occurred_at::text`, que depende de la zona horaria de la sesión: la verificación podía dar falsos positivos | Revisión; prueba con otra zona horaria | Formato UTC fijo |
| Media | La cola no tenía leases ni token de exclusión: con un worker caído, un trabajo quedaba colgado o podía ejecutarse dos veces | Revisión | §C.7 |
| Media | `crdo rollback` podía volver a una versión que había **fallado** | Revisión | Solo versiones registradas como sanas |
| Media | El respaldo no verificaba el volcado y la prueba de restauración no comparaba nada | Revisión | `pg_restore --list` + manifiesto + verificación de la cadena de auditoría |
| Baja | El despliegue no hacía un respaldo previo | Revisión | Respaldo obligatorio antes de desplegar en producción |
| Baja | Rutas de archivos pensadas para el repo `Radar` | Revisión | Rutas del repo independiente (`/opt/crdo/app/infra`) |

---

## 3. Cambios de arquitectura

| Área | v1 | v2 | Motivo |
|---|---|---|---|
| Servidor | CX33 recomendado | **CX23** (sube a CX33 a demanda) | Sin render en el VPS y con staging a pedido alcanza; ahorra ≈ €2,50/mes |
| Acceso al panel | Cloudflare Tunnel + Access + dominio | **Tailscale + `tailscale serve`** | Sin dominio ni Cloudflare: menos piezas, menos costo, cero puertos. Cloudflare queda en `infra/optional/edge` |
| Proxy | Caddy en el núcleo | Sin Caddy; la API escucha en 127.0.0.1 | Una pieza menos; las cabeceras de seguridad las pone la app |
| Almacenamiento de objetos | R2 desde el inicio | **Diferido** | En la Etapa 1 no hay archivos |
| Autoridad | La app aplica las reglas | **La base aplica las reglas** (funciones `SECURITY DEFINER`) | Un error o compromiso de la app no alcanza para saltear aprobaciones |
| Operaciones de servidor | Solo por SSH | Panel → comando con TOTP → `crdo-opsd` en el host | Administración desde Android sin dar Docker al panel |
| Entornos | Staging en el mismo VPS | CI efímero + staging aislado + staging por horas | Aislamiento real a costo cero |
| IA | `llm_gateway` hacia Claude | Rutas de modelo + proveedores intercambiables + contratos | Independencia de Claude |

---

## 4. Archivos

| Archivo | Estado |
|---|---|
| `docs/ETAPA-1-DISENO.md` | Reescrito (v2) |
| `docs/CAMBIOS-v2.md` | Nuevo (este informe) |
| `infra/db/init/00-roles.sh` | Nuevo |
| `infra/db/init/01-core.sql` | Cambiado: TOTP, aprobaciones, colas, trabajos, comandos admin, rutas de IA, permisos |
| `infra/db/init/03-ops-functions.sql` | Nuevo: TOTP RFC 6238, aprobaciones, cola, comandos admin, kill switch, permisos, auditoría |
| `infra/compose.yml`, `compose.staging.yml` | Reescritos (sin Caddy/Cloudflare, sin redes compartidas, topes de CPU/RAM) |
| `infra/.env.example` | Reescrito |
| `infra/scripts/backup.sh`, `restore.sh` | Reescritos (manifiesto, verificación, se pueden probar fuera de Docker) |
| `infra/scripts/crdo` | Corregido (rollback, respaldo previo) |
| `infra/scripts/crdo-opsd`, `infra/systemd/crdo-opsd.service` | Nuevos |
| `infra/scripts/bootstrap-server.sh` | Actualizado (Tailscale serve, etiquetas, herramientas) |
| `infra/tailscale/policy.hujson` | Nuevo |
| `infra/optional/edge/*` | Movido desde `infra/caddy` e `infra/cloudflared` (diferido) |
| `tests/run-db-tests.sh`, `tests/db/*.sql` | Nuevos: 83 verificaciones |
| `tests/run-backup-tests.sh` | Nuevo: 18 verificaciones |
| `.github/workflows/ci.yml` | Nuevo (se activa en el repo propio) |
| `tools/export-to-private-repo.sh` | Nuevo |
| `.gitignore`, `agents/README.md`, `mcp/README.md` | Nuevos |

---

## 5. Resultados de las pruebas (ejecutadas, no estimadas)

Entorno: contenedor temporal Ubuntu 24.04, PostgreSQL 16, pgvector 0.6.0, restic 0.16.4.

```
tests/run-db-tests.sh
  10_seguridad.sql      24 PASA
  20_aprobaciones.sql   23 PASA
  30_cola.sql           25 PASA
  40_pgvector.sql        8 PASA   (recall@10 HNSW = 1.000 sobre 2.000 vectores de 1024 dim.)
  concurrencia           3 PASA   (6 workers, 300 trabajos, reparto 48/48/47/47/55/55, attempts=1 en todos)
  == OK: 83 verificaciones pasaron, 0 fallaron

tests/run-backup-tests.sh
  == OK: 18 verificaciones pasaron, 0 fallaron
  (cifrado sin texto en claro, contraseña incorrecta rechazada, corrupción de 4 bytes detectada,
   fallo de pg_dump sin snapshot parcial, restauración = manifiesto, vectores restaurados,
   auditoría alterada detectada al restaurar)

tools/export-to-private-repo.sh  (contra un repo git local vacío, no GitHub)
  historial conservado (2 commits), CI en la raíz, 0 archivos .env,
  83 + 18 pruebas pasan dentro del repo exportado, se niega a sobrescribir un repo con contenido

docker compose config (prod y staging): válido; staging solo con redes crdo-staging_*
bash -n: todos los scripts válidos
```

**Sin verificar (requiere servidor o cuentas):** contenedores en ejecución, `tailscale serve`, `crdo-opsd`,
`crdo deploy/rollback`, `bootstrap-server.sh`, timers de systemd, la llave de B2 sin borrado, los avisos a
healthchecks y el CI corriendo en GitHub. Quedan cubiertos por las pruebas de aceptación T01–T24.

---

## 6. Qué necesito de vos para avanzar

| Paso | ¿Genera gasto? | Qué hace falta |
|---|---|---|
| Crear el repo privado vacío `crdo` en GitHub y autorizarme a exportar | No | Que lo crees vos y me pases la URL |
| Abrir las cuentas gratuitas (Tailscale, Backblaze, healthchecks) | No | Hacerlo vos, con 2FA |
| Contratar el VPS (≈ US$ 5–6/mes + impuestos) e instalar | **Sí** | **Autorización explícita de gasto e instalación** |
| Pregunta nueva Q9: ¿usás otra VPN en el teléfono? | — | Respuesta |
