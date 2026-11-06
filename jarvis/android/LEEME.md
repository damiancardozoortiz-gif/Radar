# JARVIS para Android (APK autónomo)

App Android propia: se abre desde su icono, sin Termux, sin Ubuntu y sin ordenador. El cerebro piensa con la
**API de Claude** (tu clave de API, de pago por uso) y lleva dentro las instrucciones (`agent/prompt.md`),
tu perfil (`CLAUDE.md`), las notas, el correo y la agenda de JARVIS, y RADAR.

## Instalar
- **Desde el teléfono:** descarga `jarvis-debug.apk`, ábrelo y acepta «Permitir instalar apps de esta fuente»
  (Android te lo pide la primera vez para la app desde la que lo abres). Después «Instalar».
- **Desde un ordenador con adb:** `adb install -r jarvis-debug.apk`

Al abrirla: asistente inicial (claves → permisos de uno en uno). Se repite en cada versión nueva.

## Compilar (sin Gradle)
```bash
sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
./build.sh          # build/jarvis-debug.apk
./build.sh test     # prueba del núcleo contra una API de Claude simulada (18 comprobaciones)
```
La clave de firma de depuración (`debug.keystore`) se crea sola y no va a git. Si cambia, hay que
desinstalar la versión anterior antes de instalar la nueva.

## Estructura
- `src/com/radar/jarvis/core/`: cerebro (Java puro, probado en el ordenador): `Brain` (API de Claude, PAUSA,
  memoria, borradores, registro), `Router` (herramientas y órdenes del teléfono), `Vault` (notas), `Json`.
- `src/com/radar/jarvis/`: `MainActivity` (reactor, chat, micrófono, cámara, archivos, RADAR, panel, PAUSA),
  `SetupActivity` (asistente y permisos), `Perms`, `Voice` (ElevenLabs o voz de Android), `CameraActivity`,
  `RadarActivity`, `JarvisService` (primer plano, dos palmadas), `Store` (claves cifradas con el Keystore).
