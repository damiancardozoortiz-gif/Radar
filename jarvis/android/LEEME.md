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

## Versión 1.2: MetaTrader 5 por MetaApi (fase 1, SOLO LECTURA)
- Panel financiero → «Conexión MT5»: ID de la cuenta en MetaApi + token (cifrado en el teléfono). Lee estado, tipo de cuenta
  (DEMO/REAL según el servidor de MT5), saldo, posiciones y velas reales M5…D1 («Velas MT5») para el análisis.
- NO envía órdenes: el conector se niega siempre (`canTrade()=false`, `execute()` falla sin tocar la red).
- Recomendado: registrar en MetaApi la cuenta DEMO con la contraseña de INVERSOR y limitar el token a esa cuenta.
- Rutas tomadas del SDK oficial `metaapi.cloud-sdk` 29.3.4. Sin confirmar contra el servicio real (inaccesible desde el
  entorno de desarrollo): `…/account-information` y `…/positions` de la API REST de cliente.
- Pruebas: `./build.sh test` incluye 16 comprobaciones contra una MetaApi SIMULADA (`test/mock_metaapi.py`).

## Versión 1.3: regla de autodisciplina financiera
- `core/fin/Discipline`: se aplica ANTES de cualquier orden y sus motivos van primero (PAUSA la primera).
  Ciclo = operaciones desde la última autorización expresa. Hasta 3 por ciclo; una 4.ª solo tras 3 ganadoras netas y con
  todos los controles; luego hace falta autorizar otro ciclo. Con 2 perdedoras en el ciclo: BLOQUEO inmediato (demo y
  real), informe de pérdidas en `files/finanzas/`, aviso por voz y en el arranque; solo el usuario reactiva, tras
  confirmar la revisión del riesgo. El volumen nunca supera el que fija el riesgo y no sube tras una pérdida.
- Registro de cada operación cerrada con los 15 datos pedidos; Claude la analiza y el aprendizaje se guarda y se da
  al estratega en los análisis siguientes. Panel con acumulados DEMO y REAL por separado.

## JARVIS 3.0 · fases 1 y 2 (en curso)
- Pantalla limpia: holograma a pantalla completa (`Hologram`): respira en espera, ondas al escuchar, giro y puntos al
  pensar, boca y anillos que siguen el volumen REAL del audio de ElevenLabs (`Voice.outLevel`, Visualizer; si el teléfono
  no lo permite, pulso suave), rojo en PAUSA y alerta roja con el bloqueo financiero.
- Ventanas flotantes (`WindowHost`): aparecen con animación, semitransparentes, se arrastran por la barra, se minimizan
  al dock, se expanden y se cierran sin perder el contenido. Chat, Resultados, Historial y Editor.
- Nada se abre solo: las respuestas largas van a «Resultados» y el icono avisa (•). Copiar: pulsación larga en el chat.
- Órdenes de pantalla resueltas en el teléfono, sin Claude (`core/UiCommands`): «abre el chat», «muéstrame la
  investigación», «abre el editor», «oculta todas las ventanas», «vuelve a la pantalla principal»…
- Investigador: búsqueda web + lectura de páginas (`web_fetch_20260209`), proceso de 10 pasos con secciones fijas;
  la app comprueba cada enlace contra lo que la búsqueda devolvió de verdad, retira los que no salieron de ella y añade
  el registro real de búsquedas y fuentes. Lee PDF. Guarda aprendizajes en `files/aprendizaje/`.
- Pendiente: Finanzas, Cámara y RADAR siguen siendo pantallas propias (no ventanas); editor de vídeo; laboratorio.
