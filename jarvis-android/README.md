# JARVIS para Android

JARVIS entero dentro del teléfono, sin computadora. Le hablas o le escribes y **actúa**: abre aplicaciones, lee la pantalla, escribe mensajes, cambia ajustes, busca en internet y **edita video**: mira tus videos, decide los cortes y te entrega el montaje. Un orbe flotante (el puntito) te acompaña encima de cualquier aplicación y te enseña lo que hace.

Desde la versión 2.0 además **aprende y toma la iniciativa**: recuerda solo lo importante, se evalúa después de cada tarea, aprende habilidades y crea rutinas que ejecuta por su cuenta.

Desde la 2.1 **crea, estudia y se conecta**: dibuja sus propias imágenes y anima sus propios videos escribiendo código, construye una biblioteca de conocimiento que luego consulta y aplica, y usa servicios externos a través de puentes.

- **Autónomo, con freno.** Abrir, buscar, leer, rellenar y preparar lo hace solo. Enviar, pagar, comprar, borrar, publicar, llamar o instalar se para y espera a que toques **Aprobar**. Nunca escribe contraseñas ni datos de tarjeta.
- **Piensa con Claude** a través de la API de Anthropic, con tu propia clave.
- **Voz gratis:** usa el dictado y la voz del propio Android.
- No necesita root ni cable. Funciona en Android 8.0 o superior (la captura de pantalla, desde Android 11).

> **Importante.** Esta app no puede usar tu suscripción de Claude (Pro o Max). Necesita una **clave de API**, que se paga aparte, por uso. Ver «Cuánto cuesta».

---

## 1. Instalar

1. Descarga `app-release.apk` en el teléfono. La compila GitHub en cada cambio: pestaña **Actions** del repositorio → la última ejecución de «APK de JARVIS para Android» → **jarvis-apk**.
2. Ábrela. Android pedirá permiso para instalar aplicaciones de esa fuente (el navegador o el gestor de archivos): acéptalo.
3. Si Play Protect avisa de que no conoce la aplicación, elige **Instalar de todas formas**. Es normal en aplicaciones que no vienen de la tienda.

## 2. Configurar (una sola vez)

Abre JARVIS y toca **ajustes**:

1. **Clave de Claude.** Crea una en [platform.claude.com](https://platform.claude.com) → *API keys*, pégala y toca *Guardar clave*. Hace falta tener saldo en esa cuenta.
2. **Accesibilidad.** Toca *Abrir Accesibilidad* → **JARVIS: manejar el teléfono** → actívalo. Es lo que le deja ver y tocar la pantalla.
   - Si Android dice **«ajuste restringido»** (Android 13 o superior): *Ajustes → Aplicaciones → JARVIS → ⋮ (arriba a la derecha) → Permitir ajustes restringidos*, y repite el paso.
3. **Micrófono.** Toca *Permitir micrófono* si quieres hablarle.
4. Opcional: cuéntale **quién eres**, elige el modelo y si quieres que hable.
5. Opcional, para video: *Permitir ver mis videos* (así encuentra solo los de tu galería) y una clave de ElevenLabs (así entiende lo que se dice).

## 3. Cómo se usa

| Acción | Cómo |
|---|---|
| Hablarle | Toca el orbe (el grande o el flotante) y habla |
| Escribirle | La barra de abajo y **Mandar** |
| Aprobar o rechazar una acción | Los botones de la tarjeta ámbar, en la app o en la burbuja |
| Detener lo que hace | **detener** (arriba) |
| Pausa de emergencia | **pausa**: corta todo y no acepta nada hasta que la quites |
| Empezar de cero | **nueva** |
| Darle videos o música | El botón **+** de abajo. Elige uno o varios archivos |
| Ver el montaje | El botón **Ver el último video**, o en la galería: Películas/JARVIS |
| Mover la burbuja | Arrástrala arriba o abajo. Mantenla pulsada para abrir JARVIS |

Ejemplos: «Dile a Ana por WhatsApp que llego a las nueve», «Pon una alarma a las siete», «Mira mis notificaciones y resúmelas», «Busca en internet el mejor precio de unos auriculares con cancelación de ruido».

El orbe cambia de color: cian en espera y al hablar, violeta pensando, verde actuando, ámbar cuando espera tu sí y rojo en pausa.

## 4. Autonomía (versión 2.0)

Todo lo que aprende se ve, se corrige y se borra en **mente** (arriba, en la pantalla principal).

**Memoria que se llena sola.** Al terminar cada conversación, JARVIS decide qué vale la pena recordar y lo guarda sin preguntar, en cuatro capas: quién eres, tu mundo (personas, lugares, datos), lo que ha hecho y las lecciones que aprendió. En cada mensaje solo usa los recuerdos que vienen al caso. Nunca guarda contraseñas, PIN, códigos ni tarjetas: el filtro está en la app, no depende del modelo.

**Se evalúa.** Después de cada tarea revisa si cumplió, se pone nota y, si algo falló, apunta la lección para la próxima vez. Tú votas con **Bien** o **Mal**; tu voto pesa más que su nota. En *mente → Cómo le va* ves las tareas logradas, el coste y el tiempo.

**Habilidades.** Una habilidad es una receta guardada (cuándo usarla y los pasos), no código nuevo. Las aprende de tres formas: se la enseñas («aprende a…»), la deduce de una tarea de varios pasos que salió bien, o la importas de un archivo de texto (`# Nombre`, `cuando: …` y los pasos debajo). Una habilidad que falla tres veces queda «en revisión» y deja de usarse hasta que se la enseñes de nuevo.

**Iniciativa.** Una rutina es algo que JARVIS hace solo cuando se cumple una condición:

| Disparador | Ejemplo |
|---|---|
| Hora | «Cada día a las 8 resume mis notificaciones y mi agenda» |
| Notificación | «Cuando llegue algo del banco, dime qué dice» |
| Lugar | «Al llegar a casa pon el teléfono en silencio» |
| Calendario | «15 minutos antes de cada reunión, recuérdamela» |

- Las crea **él solo** cuando ve una costumbre (la misma petición varias veces en momentos parecidos), y te avisa de cuál creó. También se las puedes pedir. Se apagan o borran en *mente*.
- Cada rutina tiene un nivel: **avisar** (solo te cuenta; no puede tocar el teléfono), **preparar** (lo deja listo; lo que necesitaría tu sí no se hace) o **hacer** (lo hace, y enviar, pagar, comprar, borrar, publicar o llamar sigue esperando tu toque). El nivel lo hace cumplir la app.
- **Fusible:** lo que gastan en un día las tareas que lanza él solo tiene un límite (5 $ por defecto, se cambia en Ajustes). Al llegar, se detiene hasta el día siguiente y te avisa. Lo que tú le pides no cuenta.
- La **pausa** también detiene las rutinas.
- Para que salten con el teléfono dormido: *Ajustes → Aplicaciones → JARVIS → Batería → Sin restricciones*. Aun así Android puede retrasarlas unos minutos; si pasan más de 45, esa vez se salta.

**Lo que sabe de tu día.** Cuatro fuentes nuevas, todas apagadas hasta que las enciendas en Ajustes: notificaciones en vivo, agenda, contactos y ubicación.

**Copia en la nube.** La memoria, las habilidades, las rutinas y las evaluaciones entran en la copia de seguridad de Android, que las sube a tu Google Drive. Requiere tener activada *Ajustes → Google → Copia de seguridad*. Se actualiza más o menos una vez al día, con el teléfono cargando y en Wi-Fi, y se recupera al reinstalar JARVIS o al estrenar teléfono. Las claves de Claude y ElevenLabs no viajan: hay que volver a ponerlas.

## 5. Lo nuevo de la 2.1

### Imágenes y videos hechos por él

JARVIS no usa ningún generador de imágenes de pago: **dibuja escribiendo código**. Describe la escena con capas (textos, rectángulos, círculos, líneas, trazados libres como los de SVG, imágenes) y la app la pinta en el teléfono. Con movimiento, eso mismo se convierte en video.

- «Hazme una portada para un video sobre agujeros negros.»
- «Haz un video vertical de 30 segundos que explique cómo funciona una impresora, con voz y rótulos.»
- «Convierte esta foto en un video con un zoom lento y el título encima.»

Cómo trabaja: dibuja, **mira lo que dibujó** y lo corrige si el texto se corta o la composición queda coja. Para un video prueba antes un fotograma, anima todas las escenas y lo revisa. Con clave de ElevenLabs le pone voz, y los rótulos salen al ritmo de lo que dice. Las imágenes quedan en *Imágenes/JARVIS* y los videos en *Películas/JARVIS*.

Sirve para carteles, portadas, diagramas, infografías, tarjetas y videos explicativos animados. **No hace fotos realistas** ni personas: son ilustraciones con formas, texto e imágenes que ya existan. Límites: 60 escenas, 60 capas por escena y 3 minutos por video.

### Biblioteca

Pídele que estudie un tema: «estudia física cuántica y escríbete un tomo». JARVIS planea el índice, investiga cada capítulo en internet, lo escribe con sus palabras (conceptos, fórmulas, un ejemplo, errores típicos) y guarda las fuentes. Después, cuando un mensaje toca algo que estudió, recibe los pasajes que vienen al caso y los **aplica** al problema concreto en vez de recitarlos.

- Los tomos se leen y se borran en *mente → Biblioteca*.
- Estudiar un tomo entero son muchas búsquedas: tarda varios minutos y cuesta más que una tarea normal. Los temas enormes conviene dividirlos.
- Lo escribe un modelo a partir de lo que encuentra: **puede contener errores**. Las fuentes están en cada capítulo para comprobarlo. No sustituye a un libro de texto para algo crítico.
- Entra en la copia de seguridad de Android.

### Puentes hacia otros servicios

Un puente conecta a JARVIS con un servicio de fuera sin manejar la pantalla: más rápido y más fiable. Usa **MCP**, el estándar con el que muchos servicios ofrecen sus herramientas a los asistentes.

1. Consigue la dirección del servidor MCP del servicio. La forma más sencilla de cubrir mucho con un solo puente es [Zapier MCP](https://zapier.com/mcp): te da una dirección que abre Gmail, Google Drive, Calendar, Notion, Sheets y miles de aplicaciones más, y allí eliges qué acciones permites.
2. En *ajustes → Puentes*, ponle un nombre, pega la dirección (y la credencial, si la pide) y toca **Probar y añadir**. Te dirá cuántas herramientas ganó.

El freno también vale aquí, y lo aplica la app: las llamadas salen de tu teléfono, no de los servidores de Claude. **Consultar lo hace solo; lo que envía, crea, cambia o borra algo espera tu toque**, con los datos exactos a la vista. Ante la duda sobre una herramienta, pide permiso. Las direcciones de los puentes se guardan cifradas y no entran en la copia de seguridad.

### Lo nuevo de la 2.2: estudiar en serio

Sale del informe que dejó el propio JARVIS al probar la 2.1.

- **Cupo de internet llevado por la app.** Cada conversación tiene 12 búsquedas y 6 lecturas de página; al estudiar, **cada capítulo tiene las suyas** (4 búsquedas y 3 lecturas) y empiezan de cero en cada uno. JARVIS ve cuánto le queda al empezar y después de cada paso, y cuando se acaba, la búsqueda deja de ofrecérsele en vez de bloquearse a medias.
- **Búsqueda directa.** Antes las búsquedas se lanzaban desde Python en un contenedor de Anthropic: ahí el tope saltaba de golpe y la respuesta llegaba como texto que había que convertir. Ahora JARVIS pide cada búsqueda directamente y recibe el resultado tal cual.
- **Leer páginas enteras** (`web_fetch`): el texto de una dirección que haya salido en una búsqueda o que tú le des, para contrastar en la fuente original.
- **Estudio en segundo plano.** JARVIS planea el índice y el tomo se escribe solo, capítulo a capítulo. Guarda el progreso, sigue por donde iba si se cierra la app o se pierde la conexión, y avisa al terminar. Mientras tanto puedes seguir usándolo. Se ve y se detiene en *mente → Biblioteca*. Un capítulo que falla tres veces se da por imposible y el tomo continúa.
- **Datos por contrastar.** Lo que no pudo comprobar lo apunta como pendiente. Pídele «repasa los datos pendientes» y los contrasta, corrige y cierra. También se ven en *mente*.
- **Consultas limpias.** Cada pasaje dice su tomo, capítulo y sección, y las fuentes aparecen una sola vez al final.
- **Corregir sin reescribir.** Cambia un fragmento de un capítulo y deja el resto intacto.

Estudiar un tomo de 9 capítulos son 9 tareas seguidas, cada una con sus búsquedas y lecturas: es lo más caro que hace JARVIS. El coste de cada capítulo queda en el diario y en *mente → Cómo le va*.

### Lo nuevo de la 2.3: más autonomía, con comprobación

Sale del segundo informe de JARVIS, con sus diez puntos.

- **Tareas largas que se retoman.** Para un encargo de varios pasos, JARVIS abre una tarea con su objetivo, sus pasos y cómo se comprueba. Guarda el progreso después de cada paso. Si se cierra la app o se va la red, al volver retoma la tarea **desde el paso siguiente**, como mucho tres veces y solo durante las 12 horas siguientes. Si paró para preguntarte algo, espera tu respuesta en vez de seguir solo. La tarea abierta se ve, y se cierra, en *mente*.
- **Comprobar antes de dar algo por hecho.** Al cerrar una tarea, la app comprueba el resultado sin fiarse de lo que diga JARVIS: busca el texto acordado en la pantalla (el mensaje en el chat, la alarma en la lista), o revisa que el capítulo tenga secciones y fuentes. Si la comprobación falla, JARVIS reintenta una vez; si vuelve a fallar, la tarea queda como no lograda y te lo dice.
- **Te avisa solo.** Tiene una herramienta para ponerte una notificación (como mucho tres por conversación). Además, las tareas largas y los estudios te avisan solos al terminar.
- **Detecta bucles.** Si después de actuar vuelve a la misma pantalla por tercera vez, se le avisa de que cambie de plan. A la cuarta, sus manos se paran hasta la siguiente conversación y te cuenta qué pasa. Se mantiene el tope de 80 acciones.
- **Permisos fijos.** En la tarjeta de permiso aparece **«Aprobar y no volver a preguntar»** cuando esa acción se puede dejar fija: confirmar o guardar dentro de una aplicación (el calendario, el reloj) o una herramienta de un puente que no envía, ni publica, ni borra. Para mandarte mensajes a ti mismo, escribe en *Ajustes → Permisos fijos* cómo se llama tu chat contigo. Pagar, comprar, transferir, borrar, publicar, instalar, llamar y escribir a otras personas **siempre** esperan tu toque, y eso no se puede desmarcar. Los permisos se ven y se quitan en Ajustes, y cada vez que se usa uno queda en el diario.
- **Memoria ordenada.** Cada lección guarda cuántas veces se confirmó. Una vez por semana, un repaso junta lo repetido y busca lecciones que se contradicen; las enseña en *mente* para que decidas cuál vale. JARVIS puede además buscar, corregir y olvidar sus propios recuerdos.
- **Proyectos.** Una ficha por proyecto, con objetivo, siguiente paso y pendientes. Cuando sale el tema («¿cómo va lo de los videos?»), JARVIS recibe la ficha y la pone al día. Se ven en *mente*.
- **Documentos.** Lee los PDF y textos que le adjuntes con el botón + y los de una carpeta que elijas en Ajustes. Solo esos, no el resto del teléfono. Los PDF los ve página a página.
- **Órdenes por voz.** Le llega si el mensaje entró por voz y si la grabación se cortó de golpe. Si tocas el micrófono mientras hablas, usa lo que oyó hasta ahí y lo marca como cortado. Ante una orden cortada o demasiado corta, pregunta antes de actuar.
- **Registro por turno.** *mente → Registro* enseña cada herramienta que usó en cada conversación, con qué datos, qué devolvió y si falló. Lo escribe la app, no JARVIS. Se queda en el teléfono, fuera de la copia en la nube, porque guarda lo que se vio en pantalla.

Lo que se dejó fuera del punto 8: un entorno para ejecutar código o ffmpeg dentro del teléfono. Es pesado y frágil, y el montaje de video ya lo hace la app. Leer documentos sí entra.

## 6. Editar video

Dale los videos con **+** (o deja que vea tu galería) y pídeselo con tus palabras:

- «Con estos tres videos hazme uno de 30 segundos en vertical para Reels, empieza por lo más llamativo y ponle esta canción de fondo, bajita.»
- «Del video de la playa quédate solo con las partes donde salgo yo y ponle el título Verano 2026.»
- «Une los dos últimos videos de mi galería y quítale el sonido al segundo.»

Cómo trabaja:

1. **Mira.** Saca fotogramas de cada video (una hoja de contactos con el minuto y segundo de cada uno) y se acerca a los tramos dudosos hasta encontrar el punto de corte.
2. **Escucha**, si tiene clave de ElevenLabs: transcribe lo que se dice con sus tiempos. Sin ella decide solo por la imagen.
3. **Monta** con Media3, la librería de edición de Google: une los trozos en orden, pone un rótulo sobre cada trozo si se lo pides, música de fondo y el formato que quieras (original, vertical 1080×1920, horizontal 1920×1080 o cuadrado).
4. **Revisa** el resultado mirándolo otra vez, y lo guarda en la galería, en *Películas/JARVIS*.

Nuevo en la 2.0:

- **Narración**: escribe un guion y lo convierte en voz con ElevenLabs. La voz suena por encima y la música, si la hay, queda bajita. Necesita la clave de ElevenLabs y gasta créditos de esa cuenta.
- **Compartir**: abre TikTok, Instagram o la aplicación que digas con el video listo. El botón de publicar espera tu toque.

Lo que **todavía no hace**: generar imágenes o video con inteligencia artificial, dar movimiento a imágenes fijas, rótulos sincronizados con la voz, bajar la música solo cuando habla la voz, transiciones con fundido (los cortes son directos), cambiar la velocidad, filtros de color, subtítulos sincronizados palabra por palabra, ni usar fotos como trozos. Si se lo pides, te lo dirá.

Límites: 40 trozos y 10 minutos por montaje. Nunca modifica ni borra los originales. Exportar tarda: cuenta con uno o dos minutos por minuto de video, según el teléfono.

## 7. Qué hace solo y qué espera tu sí

| Lo hace solo | Espera que toques **Aprobar** | No lo hace nunca |
|---|---|---|
| Abrir aplicaciones y páginas, buscar, leer, deslizar, rellenar campos, escribir un mensaje sin enviarlo · lo que hayas dejado como permiso fijo (confirmar en una app concreta, una herramienta de un puente que no envía, mandarte mensajes a tu propio chat) | Tocar cualquier botón que diga enviar, pagar, comprar, confirmar, reservar, eliminar, publicar, llamar, instalar, transferir… · pulsar Intro en un chat · lo que el propio agente marque como irreversible · **cualquier** acción dentro de un banco o aplicación de pagos (una vez por tarea, y cada pago aparte) | Escribir contraseñas, PIN o datos de tarjeta · manejar su propia ventana (no puede pulsar su propio «Aprobar» ni cambiarse los ajustes) |

- Decir «sí» en voz alta no aprueba nada: solo vale tu toque. Si no respondes en 10 minutos, cuenta como no.
- El freno lo aplica la aplicación mirando **qué elemento** se va a tocar; no depende de lo que el modelo decida.
- Lo que lee en pantalla o en una web va marcado como datos, no como órdenes. Aunque una página intentara engañarle, el botón de enviar o pagar seguiría esperando tu toque.
- Todo lo que hace y cada permiso que pide queda en **ajustes → Diario de acciones**.
- Límites: una tarea cada vez y 80 acciones por tarea; si se queda en bucle en la misma pantalla, se paran sus manos.
- Los permisos fijos solo los das tú, con el botón de la tarjeta o en Ajustes: JARVIS no tiene ninguna forma de dárselos. En una rutina de nivel «preparar» tampoco se usan: deja el último paso para ti.

## 8. Privacidad

- La clave se guarda **cifrada** con el almacén seguro de Android y solo se envía a `api.anthropic.com`.
- Mientras hace una tarea, JARVIS envía a Claude (Anthropic) el texto de lo que hay en pantalla y, si la pide, una captura. Fuera de las tareas no lee ni envía nada.
- Al editar video envía a Claude **fotogramas** de los videos que usa (no el video entero) y los nombres de los archivos. Si pones clave de ElevenLabs, el **sonido** de los videos que transcriba se envía a ElevenLabs. El montaje se hace en el teléfono.
- Después de cada conversación, un resumen de lo dicho y hecho se envía a Claude otra vez para la revisión (qué recordar, cómo salió). Se apaga en *Ajustes → Autonomía*.
- Las fuentes que enciendas (notificaciones, agenda, contactos, ubicación) solo se leen cuando una tarea o una rutina las necesita, y entonces viajan a Claude.
- Con un puente, lo que JARVIS le pida a ese servicio viaja del teléfono al servicio, y su respuesta viaja a Claude.
- Los documentos que lea (los que adjuntes o los de tu carpeta) viajan a Claude: el texto, o las páginas de un PDF como imagen.
- No hay servidores propios, ni cuentas, ni analítica. La memoria vive en el teléfono y, si tienes activada la copia de seguridad de Android, en tu Google Drive. El diario y el registro por turno se quedan en el teléfono.

## 9. Cuánto cuesta

La app es gratis; se paga el uso de Claude con tu clave. Precios de lista consultados el 3 de noviembre de 2026, por millón de tokens (compruébalos en [claude.com/pricing](https://claude.com/pricing), porque cambian):

| Modelo | Entrada | Salida |
|---|---|---|
| Claude Opus 5.5 (por defecto, el más capaz) | 4 $ | 20 $ |
| Claude Sonnet 5.5 | 2 $ | 10 $ |
| Claude Haiku 5.5 | 0,10 $ | 0,50 $ |

Una tarea son varias llamadas seguidas (una por paso) y cada una reenvía la conversación, aunque lo ya enviado se cobra con descuento. **No he medido el coste real por tarea.** Empieza con poco saldo, mira el consumo en la consola de Anthropic y, si te parece caro, cambia a Sonnet o Haiku en Ajustes. La búsqueda en internet tiene un pequeño coste por búsqueda y se puede apagar.

**La autonomía también gasta.** La revisión de después de cada turno es una llamada extra, con el modelo más barato (Haiku 5.5). Cada rutina que salta es una tarea completa; para eso está el fusible. *mente → Cómo le va* enseña una estimación por arriba del gasto.

**Dibujar no cuesta nada aparte**: se hace en el teléfono. Lo que gasta es que JARVIS mira cada imagen que dibuja para corregirla, y eso son imágenes enviadas a Claude. **Estudiar un tomo** son muchas búsquedas web y capítulos largos: es lo más caro que puede hacer de una vez.

**Editar video gasta más que el resto**, porque cada hoja de fotogramas que mira es una imagen que se le manda a Claude, y un montaje cuidado mira muchas. Transcribir consume además créditos de tu cuenta de ElevenLabs.

## 10. Lo que no está comprobado o hay que saber

- **Probado en un teléfono real:** conversar, la voz y manejar el teléfono (1.1); la memoria automática y la pantalla *mente* (2.0); estudiar un tomo y consultar la biblioteca (2.1, con los fallos que arregla la 2.2). Lo demás se construyó en un entorno sin teléfono ni emulador: está comprobado que compila, que la APK está firmada y que pasan las 100 pruebas automáticas del núcleo.
- **Lo nuevo de la 2.2 no se ha visto funcionar con la API real.** Está probado contra una API de Claude simulada que el cupo se descuenta, que el modelo ve lo que le queda y que la búsqueda deja de ofrecerse al agotarse; y con un escritor simulado, que un tomo de 9 capítulos se completa y que un corte en el capítulo 4 se reanuda en el 5. **No se ha estudiado ningún tomo real con esta versión**: la búsqueda directa y la lectura de páginas son herramientas de Anthropic que aquí no se pudieron llamar. Si la lectura de páginas no estuviera disponible para tu cuenta o tu modelo, la API lo dirá con un error al primer mensaje; dímelo y la desactivo.
- **Lo nuevo de la 2.3 no se ha visto funcionar en un teléfono.** Los diez criterios del informe están cubiertos por pruebas automáticas, con un teléfono y una API simulados: la tarea de 15 pasos cortada en el 7 sigue en el 8, no se da por hecho un envío que no se ve en pantalla, el bucle avisa a la tercera y para a la cuarta, el permiso fijo no se pregunta y lo prohibido sí, el repaso encuentra dos lecciones que se contradicen, «¿cómo va lo de los videos?» trae el siguiente paso, y el registro coincide con lo que se hizo. Sin estrenar: leer PDF con el lector de Android, la carpeta de documentos, la voz cortada con el dictado real y retomar una tarea después de que Android cierre la app. Además, el nombre del chat contigo mismo tiene que coincidir exactamente con lo que se ve arriba del chat. Si no coincide, JARVIS seguirá preguntando, que es lo seguro.
- **El estudio en segundo plano depende de que Android deje viva la app.** Con la accesibilidad activada suele seguir; si el teléfono la cierra, se retoma al abrir JARVIS.
- **Lo nuevo de la 2.1 no se ha visto funcionar en un teléfono.**
  - **Dibujar y animar.** Está probado que el lenguaje se lee bien, que los errores se explican y que el movimiento se calcula como debe. **Pintar de verdad y codificar el video no se han ejecutado nunca**: dependen de Android y del codificador de cada teléfono. Es la parte con más riesgo de esta versión. Si falla, lo más probable es que falle el video y no la imagen.
  - **Puentes.** El cliente está probado contra un servidor MCP simulado (saludo, lista, llamada, las dos formas de respuesta, credencial rechazada y el freno). **No se ha conectado a ningún servicio real**: no había credenciales. Los servicios que exigen iniciar sesión con una ventana (OAuth) en vez de una dirección o una credencial fija no funcionarán todavía.
  - **Biblioteca.** Guardar, buscar y citar está probado. La calidad de lo que escriba al estudiar depende del modelo y de lo que encuentre; no se ha estudiado ningún tomo real.
- **Lo nuevo de la 2.0 no se ha visto funcionar en un teléfono.** Está probada la lógica (qué se recuerda y qué no, cuándo salta cada rutina, los niveles, el fusible, la revisión). Sin estrenar: la pantalla *mente*, las notificaciones en vivo, la agenda, los contactos, la ubicación, el despertador de las rutinas y los avisos. Las partes con más riesgo:
  - **Rutinas con el teléfono dormido.** Dependen de cuánto deje Android trabajar a la app en segundo plano, y eso cambia mucho según la marca.
  - **Rutinas de lugar.** Usan la última posición que conoce el teléfono, sin encender el GPS, y necesitan el permiso de ubicación «todo el tiempo». Pueden tardar en notar que llegaste, o no notarlo.
  - **Copia en Google Drive.** La hace Android cuando quiere; no se ha comprobado una restauración.
  - **Rutinas creadas por él.** Que detecte bien una costumbre depende del modelo; puede crear alguna que no quieras. Se borran en *mente*.
- **La edición de video sigue sin probarse en un teléfono**, y la narración y el compartir, tampoco.
- Exportar video depende de los codificadores de cada teléfono, y aquí no se ha podido exportar ni un solo video. Lo probado es la lógica (qué trozos, en qué orden, con qué formato); sacar fotogramas, mezclar la música, los rótulos y la exportación real están sin estrenar. En teléfonos modestos, los videos 4K o los montajes largos pueden fallar o tardar mucho. Tampoco se ha llamado a ElevenLabs desde la app.
- **Tampoco se ha llamado a la API real de Claude** desde la app: no había clave. Se verificó contra un servidor simulado que la petición lleva lo que la API exige.
- **El freno por palabras no es perfecto.** Reconoce los botones por su texto en español e inglés. Un botón sin texto en una aplicación rara puede escapársele si el agente tampoco lo marca; por eso los bancos piden permiso para todo. Empieza con tareas de poco riesgo y revisa el diario.
- Las aplicaciones de banco y las que protegen su pantalla no se dejan leer: JARVIS te lo dirá.
- **Intro** solo funciona desde Android 11; en versiones anteriores toca el botón de enviar o buscar.
- Android puede cerrar la app en segundo plano para ahorrar batería. Si la burbuja desaparece, quita a JARVIS de la optimización de batería (*Ajustes → Aplicaciones → JARVIS → Batería → Sin restricciones*).
- Algunos fabricantes (Xiaomi, Huawei, Oppo) apagan los servicios de accesibilidad al reiniciar: habrá que volver a activarlo.
- **La firma** (`firma/jarvis.keystore`) está en el repositorio a propósito, con contraseña pública, para que cada compilación se pueda instalar encima de la anterior. Sirve para uso personal. Para publicar en una tienda hay que crear una clave propia y no subirla.

## 11. Compilar

Hace falta JDK 17 o superior y el SDK de Android (plataforma 35).

```bash
cd jarvis-android
./gradlew testDebugUnitTest      # las pruebas
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

## 12. Estructura

```
jarvis-android/app/src/main/kotlin/com/radar/jarvis/
├── nucleo/                    sin nada de Android: se prueba en cualquier computadora
│   ├── Freno.kt               qué hace solo y qué espera tu sí
│   ├── Manos.kt               lo que JARVIS puede ver y hacer en un teléfono (interfaz)
│   ├── Herramientas.kt        las 51 herramientas del agente (más las de los puentes), pasando por el freno; vigila los bucles
│   ├── Permisos.kt            los permisos fijos que da la persona, y qué pueden cubrir
│   ├── Tareas.kt              tareas largas: pasos guardados, comprobación y retomar desde el paso siguiente
│   ├── Proyectos.kt           las fichas de proyecto y cuándo vienen al caso
│   ├── Bitacora.kt            el registro por turno: cada herramienta, con qué y qué devolvió
│   ├── Documentos.kt          los documentos que puede leer (interfaz)
│   ├── Estudios.kt            el estudio de un tomo como tarea larga que se reanuda
│   ├── Lienzo.kt              el lenguaje con el que dibuja y anima: escenas, capas, movimiento
│   ├── Biblioteca.kt          los tomos que estudia, y cómo los busca
│   ├── Puente.kt              cliente MCP: la conexión con servicios externos
│   ├── Mente.kt               memoria, habilidades, rutinas y evaluaciones
│   ├── Reflexion.kt           la revisión de después de cada turno: qué recordar, cómo salió, qué aprender
│   ├── Fuentes.kt             notificaciones, agenda, contactos y ubicación (interfaz)
│   ├── Estudio.kt             lo que JARVIS puede hacer con video (interfaz) y la comprobación de cada montaje
│   └── Agente.kt              la conversación con Claude: pide, ejecuta, devuelve, repite
├── android/
│   ├── ServicioAccesibilidad.kt   las manos de verdad: lee la pantalla, toca, escribe, abre
│   ├── EstudioAndroid.kt      el estudio de verdad: fotogramas, transcripción, narración y montaje con Media3
│   ├── Pintor.kt              pinta las escenas del lienzo y las codifica como video
│   ├── Burbuja.kt             el orbe flotante y la tarjeta de permiso
│   ├── Sesion.kt              lo que está haciendo ahora; lo observan la pantalla y la burbuja
│   ├── Voz.kt                 dictado y voz de Android
│   ├── FuentesAndroid.kt      las fuentes de verdad, y el servicio que oye las notificaciones
│   ├── Avisos.kt              los avisos de JARVIS y el despertador de las rutinas
│   ├── DocumentosAndroid.kt   PDF y textos adjuntos o de tu carpeta; los PDF, página a página
│   └── Almacen.kt             claves cifradas, ajustes, archivos de la mente y diario
└── ui/                        MainActivity, AjustesActivity, MenteActivity (memoria, habilidades, rutinas), OrbView
```
