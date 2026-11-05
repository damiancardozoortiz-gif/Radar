# J.A.R.V.I.S.

Asistente de voz local que conoce tus archivos, tu correo y tu agenda. Piensa con **Claude Code (`claude -p`) usando tu suscripción**, habla y escucha con **ElevenLabs**, y enseña tus notas como un mapa.

- Servidor: solo librería estándar de Python. Navegador: JavaScript sin frameworks. Nada que instalar ni compilar.
- Solo lectura sobre tus carpetas. JARVIS solo escribe en dos carpetas suyas: `memory/` (lo que le pides recordar) y `estado/` (la PAUSA y los borradores pendientes).
- Nunca envía nada: hace borradores y espera tu sí, que se da con un clic.

---

## 1. Instalación

Necesitas:

| Qué | Para qué |
|---|---|
| Python 3.9 o superior | el servidor |
| [Claude Code](https://claude.com/claude-code) con sesión iniciada (`claude` en la terminal) | el cerebro, con tu suscripción |
| Una cuenta de [ElevenLabs](https://elevenlabs.io) (el plan gratis sirve para empezar) | la voz |
| Chrome, Edge, Firefox o Safari recientes | la interfaz y el micrófono |

```bash
cd jarvis
cp .env.example .env      # y pon tu ELEVENLABS_API_KEY (y si quieres ELEVENLABS_VOICE_ID)
```

No hace falta `ANTHROPIC_API_KEY`. Si la tienes en el entorno, JARVIS la quita antes de lanzar `claude`, para que use tu suscripción y no te cobre aparte.

## 2. Arranque y apagado

| Comando | Qué hace |
|---|---|
| `./jarvis.sh` | Arranca en primer plano. Se apaga con Ctrl+C. |
| `./jarvis.sh iniciar` | Arranca en segundo plano y **se vuelve a levantar solo si se cae**. Si falla 5 veces en un minuto, o el puerto está ocupado, se rinde y lo dice. |
| `./jarvis.sh estado` | Dice si está en marcha, el estado del cerebro, si hay PAUSA, cuántos borradores esperan tu sí y si la voz está configurada. |
| `./jarvis.sh parar` | Apagado limpio: cierra también los procesos `claude` abiertos. |
| `./jarvis.sh reiniciar` | Parar e iniciar. La PAUSA y los borradores se conservan. |

Después abre **http://localhost:8790** en Chrome, Edge, Firefox o Safari. La primera vez, genera los datos de demo si faltan. Para cambiar el puerto: `JARVIS_PORT=9000 ./jarvis.sh iniciar`. El registro de errores está en `estado/jarvis.log` y no guarda datos privados.

Si el puerto ya está ocupado (por ejemplo, porque ya tienes otro JARVIS abierto), no arranca y lo dice así:
```
No puedo arrancar: el puerto 8790 ya está en uso.
  ¿Tienes otro JARVIS abierto? Ciérralo, o arranca en otro puerto:
  JARVIS_PORT=8791 ./jarvis.sh
```

Al arrancar deja un proceso `claude` precalentado (unos 1,4 s), para que la primera pregunta no sea la lenta.

## 3. Modo demo y modo real

Lo controla una sola variable, `JARVIS_DEMO`, que solo se lee en `agent/data.py`. **Por defecto es demo**: usar tu vida real es una decisión a propósito.

| | `JARVIS_DEMO=1` (por defecto) | `JARVIS_DEMO=0` |
|---|---|---|
| Archivos | `data/demo/vault/`: una corredora inventada, 420 notas | las carpetas de `REAL_FOLDERS` en `agent/data.py` |
| Correo y agenda | `data/demo/inbox.json` y `calendar.json` | `data/real/` (ver abajo) |
| "Hoy" | fijo: martes 10 de marzo de 2026 | la fecha real |

```bash
JARVIS_DEMO=0 ./jarvis.sh
```

Antes de usarlo en modo real:

1. Pon tus carpetas al principio de `agent/data.py`:
   ```python
   REAL_FOLDERS = [
       "/Users/tu-usuario/Documents/Clientes",
       "/Users/tu-usuario/Notas",
   ]
   ```
2. Edita `CLAUDE.md` con quién eres. Ahora tiene un perfil **genérico de demo**.

Lee markdown y texto, y los PDF que tengan texto. Los PDF escaneados no se pueden leer con la librería estándar: aparecen como "saltados" en pantalla y en la consola. Salta `node_modules`, `.git` y los archivos de más de 2 MB. Los `[[enlaces]]` entre notas son las líneas del mapa.

Los datos de demo se generan con semilla fija (`python3 data/generate_demo.py`), así que el mapa sale igual cada vez.

## 4. Exportar correo y calendario

En esta versión no hay conexiones con Google ni con Outlook. JARVIS lee archivos exportados que dejas en `data/real/`. Si no están, la pantalla dice "no conectado" y el resto funciona.

**Correo (Gmail):**
1. Entra en [Google Takeout](https://takeout.google.com), deselecciona todo y marca solo **Correo**. Puedes elegir solo la etiqueta *Recibidos*.
2. Descarga y descomprime. Te queda un archivo `.mbox`.
3. Conviértelo (guarda los 200 más recientes):
   ```bash
   python3 data/importar_correo.py ~/Downloads/Takeout/Correo/Recibidos.mbox --max 200
   ```
   Esto escribe `data/real/inbox.json`. Repítelo cuando quieras refrescar el correo.

En Outlook o Thunderbird también puedes exportar a `.mbox` (en Thunderbird, con el complemento *ImportExportTools NG*) y usar el mismo script.

**Calendario (Google Calendar):**
1. En Google Calendar ve a **Configuración → Importar y exportar → Exportar**.
2. Descomprime el `.zip` y copia el `.ics` de tu calendario en `data/real/`.

También vale un `calendar.json` con la forma `[{"title", "start": "2026-03-10T09:00", "minutes", "with"}]`.

## 5. Cómo se usa

| Acción | Cómo |
|---|---|
| Hablar | Pulsa el micrófono una vez. Cuando te callas unos 900 ms, termina tu turno. Al acabar de responder, vuelve a escuchar solo. |
| Interrumpir mientras habla | **Espacio** (y te escucha) o el botón del micrófono |
| Terminar la conversación por voz | **Esc** o el botón del micrófono |
| Escribir | La barra de abajo |
| Parte del día, plan, memoria | Los botones de abajo |
| Sin voz (solo texto) | Botón **silencio** |
| **Pausa de emergencia** | Botón **pausa**, tecla **P** o decir "pausa". Se quita solo con el botón. |

El silencio de fin de turno (900 ms), el umbral de volumen y el mínimo de medio segundo están como constantes al principio de `ui/app.js`.

Cada respuesta dice cuánto tardó y si respondió Claude o el modo respaldo.

## 6. Límites, y cómo se hacen cumplir de verdad

| Límite | Cómo se garantiza |
|---|---|
| No ejecuta nada | Las herramientas las ejecuta el servidor en Python. Claude recibe `--tools ""` (sin herramientas) salvo en la búsqueda en internet, donde solo tiene `WebSearch` y `WebFetch`. |
| No escribe en tus carpetas | `data.py` solo abre archivos dentro de tus carpetas y en modo lectura. Solo se escribe en `memory/` (lo escribe `memory.py`) y en `estado/` (lo escribe `estado.py`), las dos dentro de `jarvis/`. |
| No lee `.env` ni escribe | `.claude/settings.json`: `defaultMode: dontAsk`, `allow: WebSearch, WebFetch`, `deny: Bash, Edit, Write, Read(./.env)`. |
| No guarda en silencio | Si Claude devuelve `recordar`, el servidor guarda y obliga a que la frase hablada diga exactamente qué guardó. |
| No envía nada | No existe ninguna conexión para enviar. Los borradores quedan **pendientes de tu sí**. |
| Tu sí es un clic | Decir "sí" no aprueba: JARVIS contesta "pulsa Aprobar en pantalla". Aprobar no envía nada: copia el texto para que lo envíes tú. |
| No inventa | El servidor le pasa los totales calculados con el archivo de origen, y el prompt prohíbe contar o inventar. |
| Instrucciones en tus archivos o correos | Van marcadas como `<datos_no_confiables>` y la tarjeta avisa: "contiene instrucciones; no se obedecen". |
| Otras webs no pueden usar ni leer JARVIS | El servidor solo escucha en 127.0.0.1. **Todas** las rutas, también las de lectura (GET) y los archivos de la página, comprueban la cabecera `Host` contra una lista explícita (`localhost:PUERTO` y `127.0.0.1:PUERTO`) antes de entregar nada; si llega `Origin`, también tiene que ser uno de esos. Así se frena el ataque de "DNS rebinding", en el que una web maliciosa apunta su dominio a 127.0.0.1 para leer tus notas. Lo demás recibe 403. |
| PAUSA | Ver abajo. |
| Registros sin datos privados | El servidor no registra cada petición; de los errores solo apunta el método y la ruta, sin la consulta (`?name=…`) ni cabeceras. Las pruebas comprueban que la clave de ElevenLabs no aparece en los registros. |

### PAUSA: qué hace exactamente

- **Bloquea lo nuevo.** Mientras está activa: no se llama a Claude (ni siquiera para calentar al arrancar), no se ejecutan herramientas, no se escribe memoria, no se crean ni se deciden borradores, y `/api/speak` y `/api/listen` devuelven 423.
- **Corta lo que está en marcha.** Al activarla se matan las llamadas a `claude` en curso.
- **Descarta lo que llegue tarde.** Si una respuesta ya estaba llegando y no se pudo cortar, se descarta sin efectos: ni memoria, ni borrador, ni voz.
- **Tiene prioridad sobre todo.** Ninguna escritura empieza con la pausa activa, y activarla solo espera a la escritura que esté en curso en ese instante, como mucho unos milisegundos. Cuando el botón de pausa responde, ya no se escribirá nada más.
- **Sobrevive a un reinicio**, también si el proceso muere de golpe (`kill -9`). Se guarda en `estado/pausa.json` y al arrancar JARVIS sigue en pausa hasta que la quites con el botón.
- **Falla cerrado.** Si `estado/pausa.json` o `estado/borradores.json` están dañados, JARVIS arranca **en pausa**, aparta el archivo como `*.ilegible-FECHA` (no lo borra) y lo avisa en pantalla.

### Borradores pendientes de tu sí

- Se guardan en `estado/borradores.json` (carpeta 700, archivo 600, fuera de git). La escritura es atómica: un corte a mitad no deja un archivo roto.
- **Sobreviven a un reinicio** y vuelven a aparecer en pantalla con sus botones Aprobar y Descartar.
- **Recuperarlos no ejecuta nada**: no se vuelve a llamar a Claude, no se habla y no se envía nada.
- Cada borrador tiene un identificador único, así que no se duplican. Una decisión tomada no cambia.
- Se guarda el historial de los últimos 200 borradores ya decididos.

**Importante sobre `settings.json`.** Si nunca abriste Claude Code de forma interactiva en esta carpeta, Claude Code la considera "no de confianza" y **ignora las reglas `allow`**, aunque sigue aplicando las `deny`. Por eso JARVIS pasa además `--allowedTools WebSearch,WebFetch` en el comando de búsqueda web. Si quieres que el `allow` del archivo cuente, abre `claude` una vez dentro de `jarvis/` y acepta el aviso de confianza.

### Pruebas

Todas de una vez, desde `jarvis/`:
```bash
bash tests/ejecutar_todo.sh
```

O una a una:

| Comando | Qué prueba | Necesita |
|---|---|---|
| `python3 tests/test_criticos.py` | Host/DNS rebinding en todas las rutas; PAUSA inmediata (llamada en curso, concurrencia, respuesta tardía, voz tardía, carrera de escrituras); PAUSA y borradores tras reinicio y `kill -9`; estado dañado; puerto ocupado; flags de `claude` (20 pruebas) | Solo Python. Sin internet ni suscripción: usa `tests/fake_claude.py` y `tests/mock_elevenlabs.py` |
| `node tests/navegador.js` | Mapa (y 2.500 notas a ≥30 fps), voz con micrófono falso, Esc y Espacio, PAUSA (también tras reiniciar), borradores tras reiniciar y aprobación con clic (38 comprobaciones) | Node 18+ y Playwright: `npm install --no-save playwright && npx playwright install chromium` |
| `python3 tests/test_limites.py` | Lo mismo con `claude -p` **real**: PAUSA, autorizaciones, origen, memoria, solo lectura (10 pruebas) | `claude` con sesión iniciada y un JARVIS arrancado (`ejecutar_todo.sh` lo arranca solo, con estado y memoria temporales) |
| `bash tests/prueba_bloqueo.sh` | `claude -p` real intenta leer `.env`, escribir y usar Bash: todo bloqueado | `claude` con sesión iniciada |
| `python3 tests/integral.py` | Prueba integral con `claude` real arrancando con `./jarvis.sh`: inicio, conversación seguida con contexto, memoria tras reiniciar, PAUSA durante una llamada real, reanudación solo con el botón, recuperación tras matar `claude` y el servidor, reinicio con PAUSA y borradores, credenciales, apagado limpio. La voz la prueba REAL si hay red y clave; si no, la marca **PENDIENTE** | `claude` con sesión iniciada |
| `python3 tests/prueba_voz_real.py` | **Voz REAL**: texto a voz con tu clave, guarda el audio para que lo escuches y lo vuelve a pasar a texto con Scribe. Gasta unos 100 créditos | `ELEVENLABS_API_KEY` en `.env` y conexión a `api.elevenlabs.io` |
| `python3 tests/estabilidad_jarvis.py 20` | 20 rondas con `claude` real: petición que provoca la generación degenerada y pregunta normal (¿se recupera?) | `claude` con sesión iniciada |
| `bash tests/estabilidad_bash.sh 20` | 20 llamadas directas a `claude -p` con Bash denegado: nunca se ejecuta y no quedan huérfanos. Los cortes por tiempo salen como AVISO | `claude` con sesión iniciada |

Todo junto: `bash tests/ejecutar_todo.sh --completo` (unos 15 minutos).

**Playwright** (solo para `navegador.js`, JARVIS no lo necesita): `cd tests && npm run instalar`. Instala la versión fijada en `tests/package.json` y Chromium.

Todas las pruebas usan `estado/` y `memory/` temporales, así que no tocan los tuyos, y no envían ni publican nada. `prueba_bloqueo.sh` no se fía del texto de la respuesta, que a veces dice que lo hizo: mira las denegaciones del JSON y si el archivo existe en disco.

Variables solo para las pruebas: `JARVIS_STATE_DIR` y `JARVIS_MEMORY_DIR` (carpetas temporales) y `JARVIS_CLAUDE_BIN` (el `claude` simulado). En uso normal no hace falta ponerlas.

## 7. Cuánto cuesta al mes

Precios consultados el 5 de noviembre de 2026. Compruébalos en [elevenlabs.io/pricing](https://elevenlabs.io/pricing) y [claude.com/pricing](https://claude.com/pricing), porque cambian.

| Pieza | Coste | Notas |
|---|---|---|
| Claude (el cerebro) | **0 € extra** sobre tu suscripción: Pro 20 $/mes, Max 100 o 200 $/mes | Cada respuesta es una llamada `claude -p` que gasta de los límites de uso de tu plan, igual que Claude Code. Con un uso intenso de JARVIS, Pro puede quedarse corto. |
| ElevenLabs (voz) | Free 0 $ (10.000 créditos), Starter 6 $ (30.000), Creator 22 $ (121.000) | La voz gasta créditos por carácter; Scribe (la transcripción) también consume. |
| Servidor, mapa, herramientas | 0 € | Todo local. |

**Cuenta orientativa para la voz.** Una respuesta hablada tiene 25 palabras como máximo, unos 130 caracteres.
- 30 respuestas al día durante 22 días laborables son unas 660 respuestas y unos 86.000 caracteres al mes.
- Si Flash v2.5 cuesta 1 crédito por carácter, son unos 86.000 créditos: hace falta Creator. Si cuesta medio crédito por carácter (es lo que recuerdo para los modelos Flash, pero no lo he confirmado), son unos 43.000: Starter se queda corto y Creator sobra.
- A eso súmale lo que gaste Scribe en transcribir lo que dices. **No he podido confirmar su tarifa.**
- Con el plan gratis da para unas 70–150 respuestas al mes.

**Total orientativo:** tu suscripción de Claude más 0–22 $ al mes de ElevenLabs, según cuánto hables.

## 8. Lo que no está comprobado o hay que saber

- **La voz real de ElevenLabs no se ha probado de punta a punta (PENDIENTE).** El entorno donde se construyó no podía conectarse a `api.elevenlabs.io`. Todo el circuito (grabar, transcribir, hablar, turnos, sordo mientras habla, 5 turnos seguidos) se probó con un ElevenLabs simulado. La primera prueba real es la tuya: `python3 tests/prueba_voz_real.py` y después el micrófono en el navegador.

### Velocidad: cómo funciona y tiempos medidos (Claude real)

Cada conversación tiene **un proceso `claude` abierto entre preguntas** (`--input-format stream-json`), con las mismas opciones de seguridad (`--safe-mode`, `--tools ""`, modelo y esfuerzo fijos). JARVIS **devuelve la respuesta en cuanto llega el texto**, sin esperar al resumen interno que `claude` hace después (de 1 a 3 s). Al arrancar deja un proceso precalentado para la primera conversación.

| | Antes (un `claude -p` por pregunta) | Ahora (con 3–4 s entre preguntas, como al hablar) |
|---|---|---|
| Respuesta corta | 4,3–6,6 s (media 4,7–5,5) | 0,8–1,6 s (media 1,5) |
| Borrador | 6,7–7,3 s | 2,3–4,3 s (borradores de unas 90 palabras) |
| Primera pregunta de una conversación | ~4,5 s | 0,8 s |

Cuándo es más lento:
- **Si preguntas en los 2–3 s siguientes a una respuesta**: `claude` todavía termina su resumen interno de la anterior. Con preguntas seguidas sin pausa, 2,5–6,5 s.
- **La primera respuesta después de quitar la PAUSA, o después de guardar algo en memoria**: hay que reabrir el proceso y recuperar la conversación. Unos 5–8 s.
- **Cada 20 turnos** JARVIS abre un proceso nuevo y le pasa los últimos 10 turnos, para que el contexto no crezca sin fin.
- **La búsqueda en internet** sigue siendo una llamada aparte: 15–40 s, con aviso en pantalla.

Si una respuesta pasa de 6 s, el tiempo sale en naranja.

### Cuelgues de `claude -p` con Bash: causa encontrada

En 20 llamadas directas a `claude -p` con Bash denegado, 2 no terminaron (se cortaron a los 90 s). El registro de depuración de `claude` muestra la causa: el modelo no tiene la herramienta y escribe **en bucle llamadas falsas `<invoke name="Bash">` como texto**. En el caso registrado fueron 120 llamadas y casi 15.000 caracteres, sin terminar. No ejecuta nada. `CLAUDE_CODE_MAX_OUTPUT_TOKENS` no lo limita en esta versión (comprobado).

Cómo se protege JARVIS:
- Lee la respuesta mientras llega y **la corta en cuanto ve marcado de herramienta o más de 4.000 caracteres**. El proceso se cierra, el siguiente turno abre otro y lo inválido nunca se lee en voz alta.
- Además está el límite de 25 s.

Con `claude` real, 20 de 20 rondas pidiendo Bash a JARVIS se recuperaron. Ningún cuelgue bloqueó a JARVIS y no quedaron procesos huérfanos.
- **Modo respaldo.** Si Claude no responde en 25 s (60 en la búsqueda web), JARVIS busca en tus archivos o enseña la tarjeta de la herramienta, con un aviso rojo de que **no** ha hablado Claude.
- **Recuperación.** Si un proceso `claude` muere, el siguiente turno abre otro y retoma la conversación. Si el servidor se cae, `./jarvis.sh iniciar` lo vuelve a levantar. Si el servidor muere de golpe, los procesos `claude` terminan solos, sin quedar huérfanos.

### Usarlo desde el teléfono (Android): PENDIENTE de tu decisión

JARVIS está hecho para usarse **en el mismo ordenador** donde corre, en `http://localhost:8790`. Desde el teléfono hoy **no funciona**, por diseño de seguridad:
- El servidor solo escucha en `127.0.0.1` y rechaza cualquier otro `Host`. Es lo que frena el ataque de "DNS rebinding".
- Los navegadores solo dan acceso al micrófono en `https://` o en `localhost`. Desde el teléfono, `http://IP-del-ordenador` no tendría micrófono.

Para usarlo desde Android hace falta elegir una de estas opciones. Todas abren JARVIS fuera del ordenador y necesitan tu autorización expresa:
1. **Un túnel con HTTPS y contraseña** hasta tu ordenador (por ejemplo, una VPN privada como Tailscale con HTTPS), añadiendo ese nombre a la lista de hosts permitidos y una autenticación.
2. **Ejecutarlo dentro del teléfono** (Termux con Python y Claude Code). Sin probar.

Hasta que elijas, no se ha abierto nada fuera de tu ordenador.
- El mapa de la demo sale como una bola bastante uniforme; con notas reales suele agruparse mejor.

## 9. Estructura

```
jarvis/
├── jarvis.sh               arranque en un comando
├── agent/
│   ├── main.py             servidor y API (solo 127.0.0.1)
│   ├── cerebro.py          llamada al cerebro, respaldo, PAUSA, autorizaciones
│   ├── sesiones.py         procesos claude abiertos por conversación (rápidos, cancelables, sin huérfanos)
│   ├── estado.py           PAUSA y borradores persistentes; candado de escritura
│   ├── vault.py            tus carpetas convertidas en un mapa que se puede buscar
│   ├── tools.py            las 6 herramientas y la elección de herramienta
│   ├── data.py             EL ÚNICO ARCHIVO QUE TOCA TUS DATOS REALES (REAL_FOLDERS, JARVIS_DEMO)
│   ├── voice.py            ElevenLabs: texto a voz y Scribe
│   ├── memory.py           escribe en memory/ y en ningún otro sitio
│   └── prompt.md           las instrucciones del asistente
├── ui/                     index.html, app.js, graph.js, styles.css
├── data/                   generate_demo.py, importar_correo.py, demo/ (generada), real/ (tus exportaciones)
├── memory/                 un .md con fecha por cada cosa que recuerda (fuera de git)
├── estado/                 pausa.json y borradores.json (fuera de git, se crea al usarse)
├── tests/                  ejecutar_todo.sh, test_criticos.py, navegador.js (+ package.json), test_limites.py,
│                           prueba_bloqueo.sh, integral.py, prueba_voz_real.py, estabilidad_jarvis.py,
│                           estabilidad_bash.sh, fake_claude.py (claude simulado), mock_elevenlabs.py
├── .claude/settings.json   los permisos
├── CLAUDE.md               quién eres
├── .env                    tus claves (fuera de git, permisos 600)
└── README.md
```
