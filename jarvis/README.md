# J.A.R.V.I.S.

Asistente de voz local que conoce tus archivos, tu correo y tu agenda. Piensa con **Claude Code (`claude -p`) usando tu suscripción**, habla y escucha con **ElevenLabs**, y enseña tus notas como un mapa.

- Servidor: solo librería estándar de Python. Navegador: JavaScript sin frameworks. Nada que instalar ni compilar.
- Solo lectura sobre tus carpetas. Lo único que se escribe es `memory/`.
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

## 2. Arranque (un solo comando)

```bash
./jarvis.sh
```

Genera los datos de demo si faltan y sirve en **http://localhost:8790**. Para cambiar el puerto: `JARVIS_PORT=9000 ./jarvis.sh`.

Al arrancar hace una llamada de calentamiento a Claude (unos 4 s), para que la primera pregunta no sea la lenta.

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
| No escribe en tus carpetas | `data.py` solo abre archivos dentro de tus carpetas y en modo lectura. Lo único que se escribe es `memory/`, y lo escribe `memory.py`. |
| No lee `.env` ni escribe | `.claude/settings.json`: `defaultMode: dontAsk`, `allow: WebSearch, WebFetch`, `deny: Bash, Edit, Write, Read(./.env)`. |
| No guarda en silencio | Si Claude devuelve `recordar`, el servidor guarda y obliga a que la frase hablada diga exactamente qué guardó. |
| No envía nada | No existe ninguna conexión para enviar. Los borradores quedan **pendientes de tu sí**. |
| Tu sí es un clic | Decir "sí" no aprueba: JARVIS contesta "pulsa Aprobar en pantalla". Aprobar no envía nada: copia el texto para que lo envíes tú. |
| No inventa | El servidor le pasa los totales calculados con el archivo de origen, y el prompt prohíbe contar o inventar. |
| Instrucciones en tus archivos o correos | Van marcadas como `<datos_no_confiables>` y la tarjeta avisa: "contiene instrucciones; no se obedecen". |
| Otras webs no pueden usar JARVIS | El servidor solo escucha en 127.0.0.1 y rechaza peticiones con otro `Origin` o `Host`. |
| PAUSA | Mientras está activa no se llama a Claude, no se ejecutan herramientas, no se escribe memoria y `/api/speak` y `/api/listen` devuelven 423. |

**Importante sobre `settings.json`.** Si nunca abriste Claude Code de forma interactiva en esta carpeta, Claude Code la considera "no de confianza" y **ignora las reglas `allow`**, aunque sigue aplicando las `deny`. Por eso JARVIS pasa además `--allowedTools WebSearch,WebFetch` en el comando de búsqueda web. Si quieres que el `allow` del archivo cuente, abre `claude` una vez dentro de `jarvis/` y acepta el aviso de confianza.

### Pruebas

```bash
bash tests/prueba_bloqueo.sh        # claude -p intenta leer .env, escribir y usar Bash: todo bloqueado
./jarvis.sh &                       # luego, con el servidor arrancado:
python3 tests/test_limites.py       # PAUSA, autorizaciones, origen, memoria, solo lectura (10 pruebas)
```

`prueba_bloqueo.sh` no se fía del texto de la respuesta, que a veces dice que lo hizo: mira las denegaciones del JSON y si el archivo existe en disco.

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

- **La voz real de ElevenLabs no se ha probado de punta a punta.** El entorno donde se construyó no podía salir a `api.elevenlabs.io`. Todo el circuito (grabar, transcribir, hablar, turnos, sordo mientras habla) se probó con un servidor ElevenLabs simulado que devuelve audio y texto fijos. La primera prueba real es la tuya.
- **Tiempos.** Desde que llega el texto, las respuestas tardan 4–6 s y a veces llegan a 7–9 s, sobre todo los borradores largos. Unos 2,5 s de cada respuesta son el arranque del programa `claude`. Si pasa de 6 s, el tiempo sale en naranja. La búsqueda en internet tarda 15–40 s y avisa en pantalla.
- **Modo respaldo.** Si Claude no responde en 25 s (60 en la búsqueda web), JARVIS busca en tus archivos o enseña la tarjeta de la herramienta, con un aviso rojo de que **no** ha hablado Claude.
- El mapa de la demo sale como una bola bastante uniforme; con notas reales suele agruparse mejor.

## 9. Estructura

```
jarvis/
├── jarvis.sh               arranque en un comando
├── agent/
│   ├── main.py             servidor y API (solo 127.0.0.1)
│   ├── cerebro.py          claude -p, sesión, calentamiento, respaldo, PAUSA, autorizaciones
│   ├── vault.py            tus carpetas convertidas en un mapa que se puede buscar
│   ├── tools.py            las 6 herramientas y la elección de herramienta
│   ├── data.py             EL ÚNICO ARCHIVO QUE TOCA TUS DATOS REALES (REAL_FOLDERS, JARVIS_DEMO)
│   ├── voice.py            ElevenLabs: texto a voz y Scribe
│   ├── memory.py           escribe en memory/ y en ningún otro sitio
│   └── prompt.md           las instrucciones del asistente
├── ui/                     index.html, app.js, graph.js, styles.css
├── data/                   generate_demo.py, importar_correo.py, demo/ (generada), real/ (tus exportaciones)
├── memory/                 un .md con fecha por cada cosa que recuerda (fuera de git)
├── tests/                  prueba_bloqueo.sh, test_limites.py
├── .claude/settings.json   los permisos
├── CLAUDE.md               quién eres
├── .env                    tus claves (fuera de git, permisos 600)
└── README.md
```
