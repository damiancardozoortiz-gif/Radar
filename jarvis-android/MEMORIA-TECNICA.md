# Memoria técnica de JARVIS

La escribe JARVIS mientras trabaja en su propio código (con `taller_nota`), y la lee antes de cambiar nada.
Esta primera entrada describe el punto de partida.

## Punto de partida · versión 2.5

- **Proyecto:** `jarvis-android/` (Gradle 8.14.3, AGP 8.11.1, Kotlin 2.1.21, compileSdk 35, minSdk 26). Package `com.radar.jarvis`.
- **Capas:**
  - `nucleo/`: sin Android, se prueba en la JVM. Contiene el freno, las herramientas, el agente, la mente, la biblioteca, las tareas, el registro, los proyectos, el cerebro local y el enrutador.
  - `android/`: servicios y almacenamiento.
  - `ui/`: pantallas.
- **Cerebros:**
  - Claude: `nucleo/Agente.kt`, el único que importa el SDK de Anthropic.
  - Local: `LocalAIClient.kt` y `CerebroLocal.kt` hablan con llama-server en Termux.
  - `Enrutador.kt` decide quién atiende cada mensaje. Las tareas de código van a Claude si está autorizado.
- **El freno lo aplica la app** (`Freno.kt`, `Herramientas.frenar`, `Permisos.kt`), no el modelo. Las herramientas de los puentes que cambian algo piden permiso; subir y deshacer nunca se dejan como permiso fijo.
- **Taller** (`taller/taller.py`): servidor MCP en Termux sobre una copia del proyecto.
  - Escribe solo en ramas `jarvis/…` y no toca la firma, `.git`, `.github`, `gradlew` ni `local.properties`.
  - Antes de cada cambio deja un punto de respaldo (etiquetas `respaldo/…`) y guarda copias de los archivos en `~/.jarvis-taller/copias`.
  - Compila solo con objetivos fijos (pruebas, apk, lint).
- **Pruebas:** `./gradlew testDebugUnitTest` ejecuta 121 pruebas.
  - `LlamaRealTest` corre con `LLAMA_SERVER` y `LLAMA_MODELO`.
  - Las pruebas reales de `TallerTest` corren con `TALLER_URL`, `TALLER_TOKEN` y `TALLER_REPO`.
  - Las del taller en Python: `python3 -m unittest test_taller` dentro de `taller/`.
- **Reglas al cambiar código:**
  - Cambios pequeños, con su prueba.
  - Nunca dar por buena una compilación sin ver `taller_resultado`.
  - Nunca instalar ni subir sin que lo pida la persona.
