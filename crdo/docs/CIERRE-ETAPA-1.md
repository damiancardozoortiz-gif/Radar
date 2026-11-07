# C.R.D.O. — Cierre técnico de la Etapa 1 (sin costos)

Fecha: 7 de noviembre de 2026. Base oficial: diseño v3, sin cambios de arquitectura. Esta entrega es la **v3.1**.

> **Qué está cerrado y qué no.** El criterio original de la Etapa 1 es "infraestructura instalada, accesible,
> protegida, respaldada y probada". Sin contratar un servidor, ese criterio **no se puede cumplir**. Esta entrega
> divide la etapa en dos partes con criterios objetivos:
>
> - **Etapa 1A — cierre técnico local (sin costos):** todo lo verificable sin servidor ni cuentas. **Cumplida**,
>   salvo el criterio A9, que requiere que vos corras el prototipo en tu teléfono.
> - **Etapa 1B — instalación en la nube:** requiere tu autorización de gasto, más la API y el panel (todavía sin
>   construir). **Pendiente.**
>
> No se contrató, instaló ni conectó nada. No se modificó ningún límite económico (US$ 20, reserva 15 %, IA en 0).

## 1. Archivos definitivos de la Etapa 1

| Archivo | Función |
|---|---|
| `infra/db/init/00-roles.sh` | Roles de la base con contraseña por entorno |
| `infra/db/init/01-core.sql` | Esquema: organización, tareas, aprobaciones, cola, memoria, contenido, finanzas, integraciones, auditoría |
| `infra/db/init/02-memory-vector.sql` | Memoria vectorial (pgvector, HNSW) + texto completo en español |
| `infra/db/init/03-ops-functions.sql` | TOTP, aprobaciones, cola, comandos admin, kill switch, permisos, auditoría encadenada |
| `infra/db/init/04-budget.sql` | Presupuesto: proyección bruta, reserva, guardián, IA separada, **procedimiento de cambio de límites** |
| `infra/db/init/05-auth.sql` | **Nuevo:** login, sesiones, bloqueo, recuperación, tokens de agentes, permisos |
| `infra/compose.yml`, `compose.staging.yml` | Servicios para CX33; staging aislado; Cloudflare como perfil `edge` |
| `infra/.env.example`, `infra/secrets.example/*` | Plantillas de configuración y de **todos** los archivos de secretos |
| `infra/scripts/bootstrap-server.sh` | Endurecimiento del VPS |
| `infra/scripts/preflight.sh` | **Nuevo:** verificación previa (secretos, permisos, entorno del servidor) |
| `infra/scripts/backup.sh`, `restore.sh` | Respaldo cifrado + manifiesto · restauración verificada (solo del mismo entorno) |
| `infra/scripts/crdo`, `crdo-opsd`, `budget-guard` | Despliegue y rollback · comandos desde el panel · guardián de gasto |
| `infra/systemd/*` | Timers de respaldo, prueba de restauración y presupuesto; servicio `crdo-opsd` |
| `infra/tailscale/policy.hujson`, `infra/cloudflared/README.md` | Acceso privado · activación de Cloudflare con dominio |
| `tests/run-all.sh` + `run-db-tests.sh`, `run-backup-tests.sh`, `run-prototype-test.sh`, `run-config-tests.sh`, `tests/db/*.sql` | Las cuatro baterías (283 verificaciones) |
| `tools/crdo-local` | **Prototipo** ejecutable (Termux, proot, Ubuntu) |
| `tools/check-config.sh`, `tools/export-to-private-repo.sh` | Coherencia del repo · exportación al repo privado |
| `.github/workflows/ci.yml` | CI efímero (corre `run-all.sh`) |
| `docs/ETAPA-1-DISENO.md` | Diseño v3.1 |
| `docs/CIERRE-ETAPA-1.md` | Este documento |
| `docs/INFORME-SEGURIDAD.md` | Informe de seguridad |
| `docs/PROTOTIPO-LOCAL.md` | Instrucciones del prototipo y de Android |
| `docs/runbooks/INSTALACION.md`, `RESTAURACION.md` | Procedimientos reproducibles para el servidor futuro |

## 2. Inventario de pruebas automatizadas (aprobadas)

Entorno: Ubuntu 24.04 x86_64 · PostgreSQL 16.15 · pgvector 0.6.0 · restic 0.16.4 · shellcheck 0.11.0 · Docker Compose
(solo validación de sintaxis). Comando: `bash tests/run-all.sh`.

| Batería | Archivo | Verif. | Área |
|---|---|---|---|
| Base de datos | `10_seguridad.sql` | 24 | permisos mínimos, auditoría solo agregable, R4, TOTP (vectores RFC 6238), bloqueo |
| | `20_aprobaciones.sql` | 23 | hash exacto, vencimiento, TOTP de un solo uso, consumo único, alteración detectada |
| | `30_cola.sql` | 25 | render solo externo, kill switch, leases, token de exclusión, `needs_review`, reintentos, comandos admin |
| | `40_pgvector.sql` | 8 | pgvector en PostgreSQL 16: HNSW, recall@10 = 1,000, texto completo en español |
| | `50_presupuesto.sql` | 42 | **límites de gasto**: procedimiento autenticado (15 nuevas), proyección con impuestos, reserva, guardián, IA separada |
| | `60_autenticacion.sql` | 58 | **nueva**: alta, login, sesiones, fuerza bruta, recuperación, contraseña, tokens de agentes, permisos |
| | `70_integridad.sql` | 33 | **nueva**: integridad de registros, auditoría, permisos de cada rol |
| | concurrencia | 6 | 6 workers × 300 trabajos sin duplicados · **6 × 100 eventos de auditoría con la cadena íntegra (nueva)** |
| | **Subtotal** | **219** | |
| Respaldo y recuperación | `run-backup-tests.sh` | 29 | cifrado, integridad, restauración, manipulación, presupuesto, **recuperación (7 nuevas)**: caída abrupta, auditoría después de restaurar, producción→staging prohibido |
| Prototipo | `run-prototype-test.sh` | 19 | **nueva**: flujo completo con TOTP calculado por una implementación independiente |
| Configuración | `run-config-tests.sh` | 16 | **nueva**: coherencia, shellcheck, 4 variantes de Compose, `preflight.sh` (8 configuraciones malas detectadas) |
| **Total** | | **283** | **0 fallas** |

### 2.1 Reproducibilidad (corridas reales)

| Corrida | Resultado |
|---|---|
| Baterías **originales de la v3** (commit `28b83d5`, sin tocar), 3 veces | 112/112 y 22/22 las 3 veces |
| `run-all.sh` como root, 3 veces seguidas | 283/283 las 3 veces (58–62 s cada una) |
| `run-all.sh` como **usuario sin privilegios** (como en Termux) | 283/283 (58 s), después de corregir el defecto 13 del informe de seguridad |
| **Sin pgvector** (simulación de Termux nativo) | 211 aprobadas + 8 **omitidas de forma declarada**; nunca se cuentan como aprobadas |
| Versión de PostgreSQL distinta de la esperada | Se detiene con explicación (código 2), no prueba en silencio |
| **Falla inyectada** a propósito (`CRDO_INJECT_FAILURE=1`) | Detectada: código 1, "HAY PRUEBAS FALLIDAS" |
| Repo privado exportado (contra un repo git local, no GitHub) | 283/283 dentro del repo exportado (58 s); ver §7 |
| Búsqueda de secretos en el repo | 0 coincidencias |

### 2.2 Exportación al repo privado

Resultado en §7.

## 3. Pruebas pendientes (no ejecutables sin servidor o cuentas)

No se declaran aprobadas. Requieren la Etapa 1B.

| Área | Qué falta probar |
|---|---|
| Red y acceso | UFW, SSH por Tailscale, política de Tailscale, `tailscale serve`, Cloudflare Tunnel/Access |
| Contenedores | Docker en ejecución, `00-roles.sh` dentro del *entrypoint*, límites de memoria/CPU, aislamiento real de redes |
| Operación | `crdo deploy/rollback`, `crdo-opsd`, timers de systemd, `preflight.sh` completo (modo servidor) |
| Proveedores | B2 (llave sin borrado, Caps & Alerts), healthchecks reales, CI en GitHub, factura real frente a la proyección |
| Android | El prototipo en tu teléfono (criterio A9) y, más adelante, el panel |
| Componentes sin construir | **API FastAPI y panel PWA** |

## 4. Pruebas de aceptación de la Etapa 1B (T01–T30)

| ID | Prueba | Criterio | Estado |
|---|---|---|---|
| T01 | Teléfono apagado 24 h | Heartbeat sin cortes | Pendiente (servidor) |
| T02 | Panel desde Android con datos móviles | Carga < 3 s | Pendiente (panel por construir) |
| T03 | `nmap` a la IP pública | 0 puertos abiertos | Pendiente (servidor) |
| T04 | Panel desde fuera de la red Tailscale | Inaccesible | Pendiente (servidor) |
| T05 | Login: sin TOTP, TOTP repetido, 5 fallos | Rechazo + bloqueo + auditoría | **Lógica probada** (`60`); falta por HTTP |
| T06 | SSH por IP / por Tailscale con llave / con contraseña | No / sí / no | Pendiente (servidor) |
| T07 | Secretos en el repo y permisos en el servidor | 0 secretos; 600 | **Repo probado**; falta `preflight.sh` en el servidor |
| T08 | Todas las pruebas de base de datos contra el motor del servidor | 0 fallas | Pendiente (runbook, paso 12) |
| T09 | Aprobación R3 desde el teléfono | Hash visible, TOTP, consumo único | **Probado en el prototipo**; falta el panel |
| T10 | Solicitud R4 de un agente | Rechazo | **Lógica probada** |
| T11 | Presupuesto superado | Corte + alerta | **Lógica y guardián probados**; falta la alerta real |
| T12 | Respaldo manual desde el panel | Snapshot en B2 + manifiesto + aviso | **Scripts probados con un repositorio local**; falta B2 |
| T13 | Desastre: servidor nuevo + restauración | RTO < 2 h, RPO ≤ 24 h | **Restauración probada**; falta el ensayo completo |
| T14 | Borrar respaldos desde el VPS | Rechazado | Pendiente (B2) |
| T15 | Staging intenta llegar a la base de producción | Imposible | **Configuración verificada**; falta en ejecución |
| T16 | Deploy y rollback | Respaldo previo + rollback | Pendiente (imagen de la API) |
| T17 | Error 500 provocado | Visible con id de correlación | Pendiente (API) |
| T18 | Carga leve + alertas de disco | Sin errores | Pendiente (servidor) |
| T19 | (Opcional) restaurar en otro proveedor | Funciona igual | Pendiente |
| T20 | Gateways de IA con llaves vacías | "Deshabilitado" | **IA bloqueada probada** (`ai_authorize`); falta el gateway |
| T21 | Matar el worker a mitad de un trabajo | Lo retoma otro, sin duplicar | **Lógica probada** (`30`) |
| T22 | Trabajo de render en el VPS | No lo toma | **Probado** (`30`) |
| T23 | Matriz de administración desde Android | Todo sin PC | Pendiente (panel) |
| T24 | CI en el repo privado | Verde | Pendiente (crear el repo) |
| T25 | Primera factura frente a la proyección | Diferencia < 5 % | Pendiente (factura) |
| T26 | Medidor de R2 sobre el tope en staging | Pausa + alerta al teléfono | **Pausa probada**; falta la alerta real |
| T27 | Activar un opcional que invade la reserva | Rechazado | **Probado** (`50`) |
| T28 | B2 Caps & Alerts en US$ 0 | Falla en vez de cobrar | Pendiente (B2) |
| T29 | (Con dominio) panel por Cloudflare | Access + túneles separados | Pendiente |
| T30 | Staging saturado | Producción responde < 3 s | Pendiente (servidor) |

## 5. Criterios objetivos para declarar terminada la Etapa 1

### Etapa 1A — cierre técnico local (sin costos)

| # | Criterio verificable | Cómo se verifica | Estado |
|---|---|---|---|
| A1 | `tests/run-all.sh` termina con código 0: ≥ 283 verificaciones y 0 fallas en Ubuntu 24.04 limpio | Ejecutarlo | ✅ |
| A2 | Mismo resultado en 3 corridas seguidas y como usuario sin privilegios | §2.1 | ✅ |
| A3 | La falla inyectada se detecta | `CRDO_INJECT_FAILURE=1 bash tests/run-db-tests.sh` → código 1 | ✅ |
| A4 | Coherencia: `check-config.sh` 0 errores, `shellcheck` 0 advertencias | `bash tests/run-config-tests.sh` | ✅ |
| A5 | 0 secretos en el repo | Búsqueda del CI | ✅ |
| A6 | Límites: solo el dueño humano los cambia (TOTP + justificación, auditado, con historial); agentes rechazados | `50_presupuesto.sql` | ✅ |
| A7 | El repo exportado es autosuficiente: sus pruebas pasan fuera de `Radar` | §7 | ✅ (ver §7) |
| A8 | Runbooks de instalación y restauración, informe de seguridad e inventario publicados | Este documento y `docs/` | ✅ |
| A9 | **El prototipo corre en tu teléfono** | `bash tools/crdo-local test`: resumen sin FALLAS (las omisiones declaradas se aceptan) + el informe guardado | ⏳ **Requiere que lo corras vos** |

### Etapa 1B — instalación en la nube (requiere tu autorización)

| # | Criterio |
|---|---|
| B1 | Autorización de gasto por escrito; precio final verificado con proyección ≤ US$ 17 |
| B2 | Repo privado creado, exportado, con `main` protegida y CI en verde (T24) |
| B3 | Fase A del runbook completa; `preflight.sh` con 0 errores en el servidor |
| B4 | API FastAPI y panel PWA construidos, con sus propias pruebas (CSRF, límites, cabeceras) |
| B5 | T01–T30 aprobadas con evidencia en `docs/runbooks/acta-pruebas-etapa1.md` (T19 y T29 opcionales) |
| B6 | Primera factura dentro del 5 % de la proyección (T25) |

**La Etapa 1 queda terminada cuando A1–A9 y B1–B6 se cumplen.** Hasta entonces, la etapa siguiente no comienza.

## 6. Comandos

```bash
# Todo, con informe:
CRDO_REPORT=informe.txt bash tests/run-all.sh
# Por batería:
bash tests/run-db-tests.sh              # 219 (como root o usuario común)
bash tests/run-backup-tests.sh          # 29 (requiere restic, python3, pgvector)
bash tests/run-prototype-test.sh        # 19
bash tests/run-config-tests.sh          # 16 (shellcheck y docker son opcionales: se informan como omitidas)
# Meta-prueba:
CRDO_INJECT_FAILURE=1 bash tests/run-db-tests.sh   # debe FALLAR
# Prototipo: docs/PROTOTIPO-LOCAL.md
bash tools/crdo-local doctor && bash tools/crdo-local test
```

## 7. Resultado de la exportación al repo privado (versión definitiva)

`tools/export-to-private-repo.sh` contra un repositorio git **local** vacío. No se usó GitHub ni se creó ningún
repositorio remoto. Corrida sobre el commit `f920c1f`:

| Verificación | Resultado |
|---|---|
| Exportación | Código 0. Historial de C.R.D.O. conservado (6 commits), `crdo/` pasa a ser la raíz |
| Archivos `.env` versionados | 0 |
| `bash tests/run-all.sh` **dentro del repo exportado** | Código 0: 219 + 29 + 19 + 16 = **283 aprobadas, 0 fallas** (58 s) |
| Volver a exportar sobre un repo con contenido | Rechazado: "El repo destino ya tiene ramas. Por seguridad no se sobrescribe." |

Cuando crees el repo privado en GitHub, el mismo comando lo publica. Esa acción afecta una cuenta externa:
**requiere tu autorización**.
