# Compilación externa de JARVIS (GitHub Actions) y FÉNIX 80

Archivo: `.github/workflows/jarvis-android.yml`. Se ejecuta al subir cambios a `android` o a ramas `fenix/**`,
en pull requests hacia `android`, o a mano (Actions → JARVIS Android → Run workflow).

## Qué hace
1. **Pruebas y APK sin firmar** (sin secretos, permisos de solo lectura):
   instala las mismas herramientas que en desarrollo (Ubuntu 24.04), ejecuta `./build.sh test` (todas las pruebas),
   `ci/prueba_firma.sh` (canal de firma), compila `./build.sh unsigned` y verifica la APK (alineación, paquete,
   `classes.dex`, inventario). Artefacto `jarvis-sin-firmar-<commit>`: la APK, los registros, el commit y su SHA-256.
2. **Android emulado** (API 29): firma la APK con una clave de USAR Y TIRAR creada en ese momento, la instala, la abre,
   comprueba que sigue viva a los 15 s sin errores fatales, la cierra y la vuelve a abrir. Guarda capturas y `logcat`
   (artefacto `jarvis-emulador-<commit>`). Esa APK NO se publica y no sirve para tu teléfono.

## Seguridad
- `permissions: contents: read`; `persist-credentials: false`; ningún trabajo usa secretos.
- Las ramas `fenix/**` (propuestas de JARVIS) solo se compilan y prueban: nada se publica, firma ni integra solo.
- `build.sh` nunca crea una clave de firma; si falta la original, sale SIN FIRMAR. Si se firma con otra clave,
  compara el certificado con la huella de la app instalada y borra la APK.

## Firma compatible con la app instalada
- Identidad de la app: paquete `com.radar.jarvis`, certificado SHA-256
  `f8004ee25772fc3e3e5d1c7fd9737d64f4773db0f00f4ff6789f9d7dd623750b` (huella pública).
- La clave privada (`debug.keystore`) NO está en el repositorio ni en GitHub. Hoy solo existe en el entorno de
  desarrollo. Mientras siga ahí, la APK de actualización se firma allí y se verifica la huella.
- Para firmar en GitHub en el futuro (pendiente de tu decisión): guardar la clave como secreto en un *environment*
  protegido con aprobación manual, en un trabajo separado que solo firme la APK ya probada (nunca código de ramas `fenix/**`).

## Flujo de FÉNIX 80 con la compilación externa
1. JARVIS diagnostica y elige UNA mejora (panel 🔥).
2. Si es nativa, el agente android deja la propuesta (laboratorio → Propuestas → Compartir).
3. La propuesta se aplica en una rama `fenix/<id>` (hoy: el desarrollador; JARVIS no tiene acceso a GitHub).
4. GitHub Actions compila y ejecuta todas las pruebas; si fallan, los registros dicen exactamente qué.
5. Se corrige y se repite hasta que pasa (VERIFICADA con evidencias: artefactos de Actions).
6. El señor Rafael decide: solo entonces se fusiona en `android` y se genera la APK firmada con la clave original.
- Nada pasa de propuesta a integrada sin pruebas superadas y aprobación humana.
- Pendiente de tu autorización: dar a JARVIS un acceso de GitHub limitado (solo ramas `fenix/**`, sin secretos) para
  que suba él mismo la rama y lea los resultados.
