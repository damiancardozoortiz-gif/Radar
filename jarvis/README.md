# J.A.R.V.I.S.

Asistente personal autónomo. Le hablas, y **actúa**: maneja tu teléfono Android, navega por internet con su propio navegador y te lo enseña en directo en una pantalla con un orbe. Además conoce tus archivos, tu correo y tu agenda. Piensa con **Claude Code (`claude -p`) usando tu suscripción** y habla y escucha con **ElevenLabs**.

- **Autónomo, con freno.** Abre aplicaciones, busca, lee, rellena y prepara todo solo. Enviar, pagar, comprar, borrar, publicar o llamar se para y espera un clic tuyo. Ese freno lo aplica el servidor, no la buena voluntad del modelo.
- **Su propia computadora.** Está pensado para vivir en una máquina dedicada, siempre encendida, con el teléfono conectado. Usa un navegador con perfil propio: no toca tus sesiones.
- Servidor: solo librería estándar de Python. Interfaz: JavaScript sin frameworks. Nada que compilar.
- Solo lectura sobre tus carpetas. Lo único que se escribe es `memory/` y el diario de acciones `registro/`.

> **¿Sin computadora?** En [`jarvis-android/`](../jarvis-android/README.md) hay una versión que vive entera dentro del teléfono Android (una APK). Usa una clave de la API de Claude en lugar de la suscripción.

---

## 1. Instalación

Necesitas:

| Qué | Para qué |
|---|---|
| Python 3.9 o superior | el servidor |
| [Claude Code](https://claude.com/claude-code) con sesión iniciada (`claude` en la terminal) | el cerebro, con tu suscripción |
| Una cuenta de [ElevenLabs](https://elevenlabs.io) (el plan gratis sirve para empezar) | la voz |
| Chrome, Chromium, Edge o Brave | la interfaz, el micrófono y el navegador propio de JARVIS |
| `adb` (Android platform-tools) | manejar el teléfono. Linux: `sudo apt install adb` · Mac: `brew install android-platform-tools` · Windows: descarga [platform-tools](https://developer.android.com/tools/releases/platform-tools) y descomprímelo en `jarvis/platform-tools/` |
| Pillow (opcional: `pip install pillow`) | capturas del teléfono más ligeras. Sin él funciona igual |

```bash
cd jarvis
cp .env.example .env      # y pon tu ELEVENLABS_API_KEY (y si quieres ELEVENLABS_VOICE_ID)
```

No hace falta `ANTHROPIC_API_KEY`. Si la tienes en el entorno, JARVIS la quita antes de lanzar `claude`, para que use tu suscripción y no te cobre aparte.

## 2. Arranque (un solo comando)

```bash
./jarvis.sh
```

Genera los datos de demo si faltan y sirve en **http://localhost:8790**. Para cambiar el puerto: `JARVIS_PORT=9000 ./jarvis.sh`.

Al arrancar hace una llamada de calentamiento a Claude (unos 4 s), para que la primera pregunta no sea la lenta.

## 3. Modo autónomo: teléfono y navegador

### Verlo funcionar sin teléfono

```bash
JARVIS_TELEFONO_DEMO=1 ./jarvis.sh
```

Arranca con un Android **simulado** (WhatsApp con tres chats, Ajustes, Chrome y un «Banco Demo»). Escribe o di: *«Dile a Carlos del taller por WhatsApp que paso a las cinco»*. Verás los pasos a la izquierda, la pantalla del teléfono a la derecha y, al llegar a «Enviar», la tarjeta ámbar que espera tu clic. Nada sale de tu computadora.

### Conectar tu Android (una sola vez)

1. En el teléfono: **Ajustes → Acerca del teléfono** → pulsa 7 veces **Número de compilación**.
2. **Ajustes → Sistema → Opciones de desarrollador** → activa **Depuración USB**.
3. Conéctalo por USB a la computadora de JARVIS y acepta en el teléfono **«¿Permitir depuración USB?»** marcando *Permitir siempre*.
4. Comprueba: `adb devices` debe mostrar tu teléfono como `device`. Arranca `./jarvis.sh`: arriba pondrá `teléfono: 82 %`.

No se instala nada en el teléfono ni hace falta root. Detalles:

- **Sin cable (Wi-Fi):** con el cable puesto ejecuta `adb tcpip 5555`, quita el cable y pon en `.env` `JARVIS_TELEFONO=192.168.1.50:5555` (la IP del teléfono). Con cable es más estable y además lo carga.
- **Acentos, eñes y emojis:** Android no deja escribirlos por ADB. Instala [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard) en el teléfono y JARVIS lo usa solo. Sin él, escribe sin acentos y te lo avisa.
- **Bloqueo:** JARVIS enciende la pantalla y desliza, pero **nunca escribe tu PIN**. Para que trabaje solo, deja el teléfono desbloqueado o activa *Smart Lock / Desbloqueo ampliado → Lugares de confianza*.
- **Xiaomi/MIUI:** activa también *Depuración USB (ajustes de seguridad)*, o los toques no funcionan.
- Las aplicaciones de banco y los vídeos protegidos suelen bloquear la lectura de pantalla: JARVIS te lo dirá.

### El navegador propio

La primera vez que lo necesita, JARVIS abre un Chrome con perfil propio en `perfil_navegador/` (fuera de git). Si quieres que entre en una web con tu cuenta, inicia sesión tú una vez en esa ventana: la sesión se queda guardada. JARVIS no escribe contraseñas. En una máquina sin pantalla se abre oculto (`JARVIS_NAVEGADOR=oculto` o `visible` para forzarlo).

### Qué puede hacer solo y qué espera tu sí

| Lo hace solo | Espera tu clic en **Aprobar** | No lo hace nunca |
|---|---|---|
| Abrir aplicaciones y páginas, buscar, leer, desplazar, rellenar campos, escribir un mensaje sin enviarlo, leer notificaciones | Pulsar cualquier botón que diga enviar, pagar, comprar, confirmar, reservar, eliminar, publicar, llamar, instalar, transferir… · pulsar Intro en un chat o en un formulario que envía datos · lo que el propio agente marque como irreversible · **cualquier** acción dentro de un banco o aplicación de pagos (una vez por tarea, y cada pago aparte) | Escribir contraseñas, PIN o datos de tarjeta · abrir direcciones de tu red local o de la propia computadora · aprobarse a sí mismo |

Decir «sí» en voz alta no aprueba nada: solo vale el clic. Si no respondes en 10 minutos, cuenta como no. **«detener tarea»** para lo que esté haciendo; **PAUSA** (botón, tecla `P` o decir «pausa») lo corta todo al instante.

Cada acción queda apuntada con su hora en `registro/AAAA-MM-DD.jsonl`.

### Ajustes (en `.env`)

| Variable | Para qué | Por defecto |
|---|---|---|
| `JARVIS_TELEFONO` | serie del teléfono o `ip:puerto` si hay varios o va por Wi-Fi | el primero conectado |
| `JARVIS_TELEFONO_DEMO` | `1` = teléfono simulado | `0` |
| `JARVIS_ADB`, `JARVIS_CHROME` | ruta de `adb` o del navegador si no los encuentra | automático |
| `JARVIS_NAVEGADOR` | `visible` u `oculto` | visible si hay pantalla |
| `JARVIS_AGENTE_MODELO`, `JARVIS_AGENTE_ESFUERZO` | modelo y esfuerzo del modo autónomo | `sonnet`, `medium` |
| `JARVIS_AGENTE_MINUTOS` | tiempo máximo por tarea | `20` |

### Qué computadora ponerle

El cerebro está en la nube, así que basta una máquina modesta que esté siempre encendida:

- **Recomendado:** un mini PC (Intel N100/N150 o similar, 8–16 GB de RAM) con **Linux (Ubuntu)**. Silencioso, barato y estable para 24 horas.
- También sirven una Raspberry Pi 5 de 8 GB, un Mac mini o un portátil viejo con Windows, Mac o Linux.
- Añade un monitor o tablet para el orbe (pantalla completa con `F11`), un micrófono y altavoz USB o un altavoz de conferencias, y un cable USB al teléfono.
- Instala en ella: Python 3.9+, Chrome, `adb`, Claude Code con tu sesión iniciada y este repositorio.

## 4. Modo demo y modo real (tus archivos)

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

## 5. Exportar correo y calendario

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

## 6. Cómo se usa

| Acción | Cómo |
|---|---|
| Hablar | Pulsa el micrófono una vez. Cuando te callas unos 900 ms, termina tu turno. Al acabar de responder, vuelve a escuchar solo. |
| Interrumpir mientras habla | **Espacio** (y te escucha) o el botón del micrófono |
| Terminar la conversación por voz | **Esc** o el botón del micrófono |
| Escribir | La barra de abajo |
| Encargarle algo | Dilo o escríbelo: «pon una alarma a las siete», «busca vuelos a Lima y compara». Los pasos salen a la izquierda y lo que ve, a la derecha |
| Aprobar o rechazar una acción | Los botones de la tarjeta ámbar |
| Detener la tarea en curso | Botón **detener tarea** o decir «detén la tarea» |
| Ver la pantalla del teléfono ahora | El botón ⟳ del panel derecho |
| Mapa de tus notas | Botón **mapa** |
| Parte del día, plan, memoria | Los botones de abajo |
| Sin voz (solo texto) | Botón **silencio** |
| **Pausa de emergencia** | Botón **pausa**, tecla **P** o decir "pausa". Se quita solo con el botón. |

El silencio de fin de turno (900 ms), el umbral de volumen y el mínimo de medio segundo están como constantes al principio de `ui/app.js`.

Cada respuesta dice cuánto tardó y si respondió Claude o el modo respaldo.

El orbe cambia con lo que hace: cian en espera y al hablar, violeta pensando, verde actuando, ámbar cuando espera tu sí y rojo en pausa.

## 7. Límites, y cómo se hacen cumplir de verdad

| Límite | Cómo se garantiza |
|---|---|
| Claude no tiene terminal ni archivos | En conversación recibe `--tools ""`. En modo autónomo sus únicas herramientas son las 21 de `agent/acciones.py` (tocar, escribir, abrir, leer…), que ejecuta el servidor. No tiene Bash, ni lectura ni escritura de archivos. |
| Lo irreversible espera tu clic | `agent/freno.py` mira **qué elemento** se va a pulsar antes de pulsarlo (los toques van por elemento, no a ciegas) y bloquea la acción hasta tu clic. No depende de lo que el modelo declare. |
| No se aprueba a sí mismo | Su navegador lleva una marca y el servidor rechaza todo lo que venga con ella; además no puede abrir `localhost` ni direcciones de tu red. El puente con Claude usa una clave que cambia en cada arranque. |
| No escribe contraseñas ni tarjetas | El servidor rechaza escribir en campos de contraseña o de pago, en el teléfono y en la web. |
| Tareas acotadas | Una tarea cada vez, 150 acciones y 20 minutos como máximo. |
| No escribe en tus carpetas | `data.py` solo abre archivos dentro de tus carpetas y en modo lectura. Lo único que se escribe es `memory/`, y lo escribe `memory.py`. |
| No lee `.env` ni escribe | `.claude/settings.json`: `defaultMode: dontAsk`, `allow: WebSearch, WebFetch`, `deny: Bash, Edit, Write, Read(./.env)`. |
| No guarda en silencio | Si Claude devuelve `recordar`, el servidor guarda y obliga a que la frase hablada diga exactamente qué guardó. |
| Tu sí es un clic | Decir "sí" no aprueba: JARVIS contesta que pulses Aprobar en pantalla. Un borrador aprobado se copia para que lo envíes tú; una acción aprobada la ejecuta JARVIS. |
| No inventa | El servidor le pasa los totales calculados con el archivo de origen, y el prompt prohíbe contar o inventar. |
| Instrucciones en tus archivos, correos, webs o pantallas | Van marcadas como `<datos_no_confiables>` y con aviso si parecen una orden. Aunque una página engañara al modelo, el botón de enviar o pagar seguiría esperando tu clic. |
| Otras webs no pueden usar JARVIS | El servidor solo escucha en 127.0.0.1 y rechaza peticiones con otro `Origin` o `Host`. |
| PAUSA | Mata la tarea en curso y rechaza lo que esperaba permiso. Mientras está activa no se llama a Claude, no se toca el teléfono ni el navegador, no se escribe memoria y `/api/speak` y `/api/listen` devuelven 423. |

**Importante sobre `settings.json`.** Si nunca abriste Claude Code de forma interactiva en esta carpeta, Claude Code la considera "no de confianza" y **ignora las reglas `allow`**, aunque sigue aplicando las `deny`. Por eso JARVIS pasa además `--allowedTools WebSearch,WebFetch` en el comando de búsqueda web. Si quieres que el `allow` del archivo cuente, abre `claude` una vez dentro de `jarvis/` y acepta el aviso de confianza.

### Pruebas

```bash
bash tests/prueba_bloqueo.sh        # claude -p intenta leer .env, escribir y usar Bash: todo bloqueado
./jarvis.sh &                       # luego, con el servidor arrancado:
python3 tests/test_limites.py       # PAUSA, autorizaciones, origen, memoria, solo lectura (10 pruebas)

JARVIS_TELEFONO_DEMO=1 ./jarvis.sh &
python3 tests/test_agente.py             # freno, puente, direcciones prohibidas (sin gastar Claude)
python3 tests/test_agente.py --completo  # además 3 tareas reales sobre el teléfono simulado: rechazo, aprobación y PAUSA
```

`prueba_bloqueo.sh` no se fía del texto de la respuesta, que a veces dice que lo hizo: mira las denegaciones del JSON y si el archivo existe en disco.

## 8. Cuánto cuesta al mes

Precios consultados el 5 de noviembre de 2026. Compruébalos en [elevenlabs.io/pricing](https://elevenlabs.io/pricing) y [claude.com/pricing](https://claude.com/pricing), porque cambian.

| Pieza | Coste | Notas |
|---|---|---|
| Claude (el cerebro) | **0 € extra** sobre tu suscripción: Pro 20 $/mes, Max 100 o 200 $/mes | Cada respuesta es una llamada `claude -p` que gasta de los límites de uso de tu plan, igual que Claude Code. Con un uso intenso de JARVIS, Pro puede quedarse corto. |
| ElevenLabs (voz) | Free 0 $ (10.000 créditos), Starter 6 $ (30.000), Creator 22 $ (121.000) | La voz gasta créditos por carácter; Scribe (la transcripción) también consume. |
| Modo autónomo | 0 € extra, pero gasta más de tu plan | Una tarea son entre 5 y 30 llamadas seguidas a Claude. Con uso diario de tareas, Max va más holgado que Pro. |
| Servidor, orbe, teléfono, navegador | 0 € | Todo local. |

**Cuenta orientativa para la voz.** Una respuesta hablada tiene 25 palabras como máximo, unos 130 caracteres.
- 30 respuestas al día durante 22 días laborables son unas 660 respuestas y unos 86.000 caracteres al mes.
- Si Flash v2.5 cuesta 1 crédito por carácter, son unos 86.000 créditos: hace falta Creator. Si cuesta medio crédito por carácter (es lo que recuerdo para los modelos Flash, pero no lo he confirmado), son unos 43.000: Starter se queda corto y Creator sobra.
- A eso súmale lo que gaste Scribe en transcribir lo que dices. **No he podido confirmar su tarifa.**
- Con el plan gratis da para unas 70–150 respuestas al mes.

**Total orientativo:** tu suscripción de Claude más 0–22 $ al mes de ElevenLabs, según cuánto hables.

## 9. Lo que no está comprobado o hay que saber

- **No se ha probado con un teléfono real.** Se construyó sin teléfono: todo el circuito (leer pantalla, tocar, escribir, abrir aplicaciones, notificaciones, freno, pausa) está probado de punta a punta contra el Android simulado, que responde a los mismos comandos `adb`. Con un teléfono real puede hacer falta afinar: la lectura de notificaciones y la detección de bloqueo cambian según la marca y la versión de Android.
- **El navegador se probó con páginas locales**, no con internet abierto (el entorno de construcción no tenía salida). Funcionan clics, formularios, marcos, pestañas nuevas y listas. Las webs con captcha o que detectan automatización le pararán: te lo dirá.
- **El freno por palabras no es perfecto.** Reconoce los botones por su texto en español e inglés y por el tipo de formulario. Un botón sin texto en una aplicación rara puede escapársele si el agente tampoco lo marca; por eso los bancos y pagos piden permiso para *todo*. Empieza con tareas de poco riesgo y revisa `registro/`.
- **Tiempos del modo autónomo:** una tarea de 5 o 6 pasos en el teléfono tarda unos 20 segundos.
- **La voz real de ElevenLabs no se ha probado de punta a punta.** El entorno donde se construyó no podía salir a `api.elevenlabs.io`. Todo el circuito (grabar, transcribir, hablar, turnos, sordo mientras habla) se probó con un servidor ElevenLabs simulado que devuelve audio y texto fijos. La primera prueba real es la tuya.
- **Tiempos.** Desde que llega el texto, las respuestas tardan 4–6 s y a veces llegan a 7–9 s, sobre todo los borradores largos. Unos 2,5 s de cada respuesta son el arranque del programa `claude`. Si pasa de 6 s, el tiempo sale en naranja. La búsqueda en internet tarda 15–40 s y avisa en pantalla.
- **Modo respaldo.** Si Claude no responde en 25 s (60 en la búsqueda web), JARVIS busca en tus archivos o enseña la tarjeta de la herramienta, con un aviso rojo de que **no** ha hablado Claude.
- El mapa de la demo sale como una bola bastante uniforme; con notas reales suele agruparse mejor.

## 10. Estructura

```
jarvis/
├── jarvis.sh               arranque en un comando
├── agent/
│   ├── main.py             servidor y API (solo 127.0.0.1)
│   ├── cerebro.py          la conversación: claude -p, sesión, respaldo, PAUSA, y el paso al modo autónomo
│   ├── agente.py           el modo autónomo: lanza a Claude con las manos de JARVIS y publica cada paso
│   ├── acciones.py         las 21 herramientas (teléfono, navegador, memoria) y el diario de acciones
│   ├── freno.py            qué hace solo y qué espera tu clic
│   ├── telefono.py         Android por ADB
│   ├── navegador.py        navegador propio por CDP
│   ├── mcp_puente.py       el puente entre Claude y el servidor
│   ├── agente_prompt.md    las instrucciones del modo autónomo
│   ├── vault.py            tus carpetas convertidas en un mapa que se puede buscar
│   ├── tools.py            las 6 herramientas y la elección de herramienta
│   ├── data.py             EL ÚNICO ARCHIVO QUE TOCA TUS DATOS REALES (REAL_FOLDERS, JARVIS_DEMO)
│   ├── voice.py            ElevenLabs: texto a voz y Scribe
│   ├── memory.py           escribe en memory/ y en ningún otro sitio
│   └── prompt.md           las instrucciones del asistente
├── ui/                     index.html, app.js, orb.js (el orbe), graph.js (el mapa), styles.css
├── data/                   generate_demo.py, importar_correo.py, telefono_demo.py (Android simulado), demo/, real/
├── perfil_navegador/       el perfil del navegador de JARVIS (fuera de git)
├── registro/               diario de acciones, un archivo por día (fuera de git)
├── memory/                 un .md con fecha por cada cosa que recuerda (fuera de git)
├── tests/                  prueba_bloqueo.sh, test_limites.py, test_agente.py
├── .claude/settings.json   los permisos
├── CLAUDE.md               quién eres
├── .env                    tus claves (fuera de git, permisos 600)
└── README.md
```
