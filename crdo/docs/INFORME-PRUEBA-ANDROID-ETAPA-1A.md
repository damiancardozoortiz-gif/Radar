# Informe — Prueba local en Android, Etapa 1A

**Estado: BLOQUEADA para el asistente. Pendiente de ejecución en el teléfono del dueño.**

**La Etapa 1A NO está cerrada:** las pruebas todavía no se ejecutaron en un teléfono real y no existe evidencia de eso.

## 1. Por qué está bloqueada

El asistente trabaja en una máquina virtual en la nube, sin acceso al teléfono. Comprobado el 7/11/2026:

| Comprobación | Resultado |
|---|---|
| `uname -srm` | `Linux 6.18.44-fc-v310 x86_64` (no es Android/ARM) |
| `$TERMUX_VERSION` | vacío |
| `/data/data/com.termux` | no existe |

No hay forma de ejecutar comandos en el teléfono desde esta sesión. Cualquier resultado "de Android" que el asistente
escribiera sería inventado.

## 2. Verificación previa (hecha)

| Requisito | Estado |
|---|---|
| Carpeta del repositorio C.R.D.O. | ✅ `crdo/`, árbol de trabajo limpio antes de esta tarea (commit `2759782`) |
| `tools/crdo-local` | ✅ existe |
| `docs/PROTOTIPO-LOCAL.md` | ✅ existe |
| `docs/CIERRE-ETAPA-1.md` | ✅ existe |
| Archivos modificados | **Ninguno.** Solo se agregaron 3 archivos nuevos (§6) |

## 3. Qué ejecutar en el teléfono

Camino A de `docs/PROTOTIPO-LOCAL.md`: Termux + Ubuntu 24.04 en `proot-distro`. Cada paso trae su comprobación:
no hay que asumir que una instalación funcionó.

```bash
# En Termux (instalado desde F-Droid o GitHub)
pkg update && pkg install -y proot-distro git
proot-distro install ubuntu
proot-distro login ubuntu

# Dentro de Ubuntu: comprobar la versión ANTES de instalar
cat /etc/os-release | grep VERSION_ID           # se espera "24.04"
apt-get update && apt-get install -y postgresql-16 postgresql-16-pgvector restic python3 git
ls /usr/lib/postgresql/16/bin/initdb && python3 --version && restic version   # las tres deben responder

# Traer el repo (rama ccr-1bbb5162-0008g2 de Radar, carpeta crdo/) y entrar a crdo/
cd crdo
bash tools/evidencia-android.sh                # corre doctor + test y guarda la evidencia
```

Si `VERSION_ID` no es 24.04, los paquetes se llaman distinto (por ejemplo `postgresql-18`). En ese caso no hay que
forzar nada: anotar la versión y usar `CRDO_EXPECT_PG_MAJOR=any`. El informe registrará qué se usó.

`tools/evidencia-android.sh` escribe `~/.crdo-local/informes/evidencia-<fecha>.md`. Registra:
- fecha y hora;
- Android, Termux, proot y sistema;
- versiones de Python, PostgreSQL y restic, y si pgvector está disponible;
- el código de salida de `doctor` y de `test`;
- aprobadas, fallidas y omisiones declaradas;
- los errores completos y el log entero.

## 4. Verificación funcional (manual, con tu app TOTP real)

Las publicaciones son de ejemplo, locales. No hay ninguna conexión a YouTube.

| # | Paso | Comando | Resultado esperado | Resultado real |
|---|---|---|---|---|
| F1 | Preparar la base | `bash tools/crdo-local start` | "Esquema de C.R.D.O. cargado" | _pendiente_ |
| F2 | Alta del autenticador | `bash tools/crdo-local enroll` y cargar la clave en Aegis o Google Authenticator | URI otpauth + 10 códigos | _pendiente_ |
| F3 | Inicio de sesión | `bash tools/crdo-local login` con el código de la app | "Sesión iniciada" | _pendiente_ |
| F4 | Presupuesto | `bash tools/crdo-local status` | nivel aviso · US$ 16,61 · operativo US$ 17 · tope US$ 20 · IA tope 0 | _pendiente_ |
| F5 | Aprobación de ejemplo | `bash tools/crdo-local demo` → `approvals` → `approve <id>` | "Resultado: approved" | _pendiente_ |
| F6 | Código repetido | `demo` otra vez y aprobar con el **mismo** código | rechazado | _pendiente_ |
| F7 | Auditoría | `bash tools/crdo-local audit` | "Cadena de auditoría: ÍNTEGRA" | _pendiente_ |
| F8 | Sin conexiones externas | `psql -h ~/.crdo-local/sock -p 55450 -U postgres -d crdo -Atc "show listen_addresses"`. Como root en proot, el socket queda en `/var/lib/crdo-local/inst-55450/sock` | vacío: solo socket local, sin puerto de red | _pendiente_ |

## 5. Resultados

| Categoría | Android (teléfono) | Referencia: VM en la nube, no es Android |
|---|---|---|
| Ejecutadas y aprobadas | **sin ejecutar** | 283 (`tools/evidencia-android.sh`, doctor=0, test=0) |
| Fallidas | **sin ejecutar** | 0 |
| Omitidas | **sin ejecutar** | 0 |
| Funciones verificadas a mano (F1–F8) | **ninguna** | — (el flujo automático equivalente pasa 19/19 en la VM) |

La columna de referencia **no cuenta** para el cierre de la Etapa 1A. Solo sirve para saber que el código actual
pasa en Ubuntu 24.04.

## 6. Archivos

Nuevos (no se modificó ningún archivo existente):
- `tools/evidencia-android.sh`: captura de evidencia. `shellcheck` sin advertencias; probado en la VM.
- `docs/INFORME-PRUEBA-ANDROID-ETAPA-1A.md`: este informe.
- `PROGRESO.md`: estado del proyecto.

## 7. Para cerrar la Etapa 1A

1. Ejecutar §3 y §4 en el teléfono.
2. Pegar al asistente el archivo `evidencia-<fecha>.md` y los resultados de F1–F8. Basta con texto o capturas.
3. Con esa evidencia se completa este informe y el estado pasa a:
   - **COMPLETADA**, si todo pasa, aceptando solo omisiones declaradas;
   - **PARCIAL**, si quedan pendientes;
   - **BLOQUEADA**, si hay un error. Se diagnostica y se propone la corrección mínima, sin cambiar nada sin tu autorización.
