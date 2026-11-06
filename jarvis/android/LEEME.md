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

## Versión 1.1: identidad, voz y JARVIS FINANCIAL
- Arranque: comprueba de verdad Claude (consulta ligera a la API), ElevenLabs (lee sus voces), memoria y micrófono, y saluda
  UNA vez por arranque: «Buen día / Buenas tardes / Buenas noches, señor Rafael. JARVIS está a su servicio…». Si algo
  falla, lo nombra en vez de decir «todos los sistemas operativos».
- Personalidad y nombre en `agent/prompt.md` y `CLAUDE.md` (compartidos con la versión web).
- Voz: una voz fija elegida entre las de su cuenta (masculina, grave, mejor en español; se puede cambiar en el panel),
  calidad natural (`eleven_multilingual_v2`) o rápida (`eleven_flash_v2_5`), volumen, botón de voz, interrupción
  al hablar (con cancelación de eco).
- Órdenes con voseo: «investigá…», «analizá el mercado», «prepará un informe», «pausá todas las operaciones».
  Investigación e informes con la búsqueda web de la API de Claude (cuesta por búsqueda). Tareas e informes guardados.
- JARVIS FINANCIAL INTELLIGENCE (botón «📈 Finanzas»): análisis multitemporal M1…D1 (velas, acción del precio,
  soportes/resistencias, tendencia, estructura, liquidez, rupturas falsas, volumen, RSI, MACD, medias, ATR, Fibonacci)
  sobre velas reales importadas en CSV desde MT5; estratega (Claude, solo con los números calculados); gestor de riesgo
  con reglas fijas; ticket de orden con autorización; auditoría.
- MetaTrader 5: NO CONECTADO. No hay API oficial de MetaQuotes para apps; la vía sin ordenador es MetaApi (terceros),
  pendiente de verificar su documentación (inaccesible desde el entorno de desarrollo). Operaciones reales deshabilitadas.
