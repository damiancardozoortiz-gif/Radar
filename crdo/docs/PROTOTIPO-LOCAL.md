# Prototipo local de C.R.D.O. — Android (Termux) y computadora

Todo corre **en tu dispositivo**: no se conecta a ningún servicio externo, no usa cuentas y no genera gastos.

> **Estado de verificación (honesto):**
> - **Probado** en Ubuntu 24.04 (x86_64), como root y como usuario sin privilegios: las 4 baterías pasan
>   (283 verificaciones) y el prototipo de punta a punta (19 verificaciones).
> - **No probado en un teléfono.** Los dos caminos de Android que siguen se diseñaron para ser compatibles
>   (detección de rutas, ejecución sin root, memoria compartida `mmap`), pero hasta que los corras vos son una hipótesis.
>   El camino A usa exactamente los mismos paquetes que el entorno donde se probó.

## Camino A (recomendado): Ubuntu 24.04 dentro de Termux con `proot-distro`

Mismos paquetes que el servidor y que las pruebas: PostgreSQL 16 + pgvector + restic.

```bash
# En Termux (instalado desde F-Droid o GitHub; la versión de Play Store está desactualizada):
pkg update && pkg install -y proot-distro git
proot-distro install ubuntu            # verificar que la versión instalada sea 24.04 (cat /etc/os-release)
proot-distro login ubuntu

# Ya dentro de Ubuntu:
apt-get update && apt-get install -y postgresql-16 postgresql-16-pgvector restic python3 git sudo
git clone https://github.com/<usuario>/crdo.git      # o copiar la carpeta crdo/ del repo Radar
cd crdo
bash tools/crdo-local doctor           # dice qué se puede ejecutar
bash tools/crdo-local test             # 4 baterías + informe en ~/.crdo-local/informes/
```

Si `proot-distro` instala otra versión de Ubuntu, los nombres de paquete cambian (por ejemplo `postgresql-18`).
En ese caso: `CRDO_EXPECT_PG_MAJOR=any bash tools/crdo-local test`, y el informe dirá qué versión se usó.

## Camino B: Termux nativo (sin proot)

```bash
pkg install -y postgresql python git
pkg install -y restic                  # si el paquete existe en tu Termux; si no, se omiten las pruebas de respaldo
CRDO_EXPECT_PG_MAJOR=any bash tools/crdo-local test
```

- Termux trae PostgreSQL 18 (según fuentes secundarias: *verificar* con `postgres --version`). El esquema usa SQL
  estándar de PostgreSQL 16 y debería funcionar en 18, pero **no se probó**.
- **pgvector no apareció como paquete de Termux.** Sin él, las 8 pruebas de memoria vectorial y la batería de
  respaldo se **omiten de forma declarada** (nunca se cuentan como aprobadas; verificado simulando su ausencia).
  Para tenerlo hay que compilarlo: `pkg install clang make` y luego `make && make install` en el código fuente de
  pgvector. Ese camino no está probado.

## Usar el prototipo con tu app TOTP real

```bash
bash tools/crdo-local start           # base local persistente con el esquema real
bash tools/crdo-local enroll          # te muestra la clave para cargar en Aegis / Google Authenticator + 10 códigos de recuperación
bash tools/crdo-local login           # email + contraseña + código de la app
bash tools/crdo-local status          # presupuesto (US$ 16,61 de US$ 17 operativo / 20), IA bloqueada, agentes apagados
bash tools/crdo-local demo            # un agente de prueba pide "publicar un video" (R3)
bash tools/crdo-local approvals       # ves el contenido EXACTO y su huella
bash tools/crdo-local approve <id>    # aprobás con el código de la app (un código sirve una sola vez)
bash tools/crdo-local audit           # últimos eventos + verificación de la cadena
bash tools/crdo-local stop            # los datos quedan en ~/.crdo-local
bash tools/crdo-local reset           # borra todo
```

La prueba automática de este flujo (`tests/run-prototype-test.sh`) calcula los códigos TOTP con una implementación
independiente en Python, igual que una app de autenticación: así se verifica que la clave que muestra `enroll` sirve
en apps reales.

## Qué demuestra y qué no

| Demuestra (probado) | No demuestra |
|---|---|
| Las reglas de seguridad, aprobaciones, presupuesto y autenticación funcionan en el motor real | El servicio web (FastAPI) y el panel PWA: **todavía no existen como código** |
| Los respaldos se cifran, verifican y restauran | Conexión a B2, healthchecks, Tailscale o Cloudflare reales |
| Tu app TOTP sirve para aprobar | El comportamiento en el servidor de Hetzner |

## Problemas conocidos en Android (a verificar)

- Android puede cerrar procesos en segundo plano: dejar Termux en primer plano mientras corren las pruebas (alrededor de 1 minuto en una PC).
- Si `initdb` falla por memoria compartida, confirmá que la versión de los scripts incluya `dynamic_shared_memory_type=mmap`.
- `proot` es más lento que una PC: es normal que las pruebas tarden varias veces más.
