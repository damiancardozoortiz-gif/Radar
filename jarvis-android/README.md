# JARVIS para Android

JARVIS entero dentro del teléfono, sin computadora. Le hablas o le escribes y **actúa**: abre aplicaciones, lee la pantalla, escribe mensajes, cambia ajustes, busca en internet y **edita video**: mira tus videos, decide los cortes y te entrega el montaje. Un orbe flotante (el puntito) te acompaña encima de cualquier aplicación y te enseña lo que hace.

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

## 4. Editar video

Dale los videos con **+** (o deja que vea tu galería) y pídeselo con tus palabras:

- «Con estos tres videos hazme uno de 30 segundos en vertical para Reels, empieza por lo más llamativo y ponle esta canción de fondo, bajita.»
- «Del video de la playa quédate solo con las partes donde salgo yo y ponle el título Verano 2026.»
- «Une los dos últimos videos de mi galería y quítale el sonido al segundo.»

Cómo trabaja:

1. **Mira.** Saca fotogramas de cada video (una hoja de contactos con el minuto y segundo de cada uno) y se acerca a los tramos dudosos hasta encontrar el punto de corte.
2. **Escucha**, si tiene clave de ElevenLabs: transcribe lo que se dice con sus tiempos. Sin ella decide solo por la imagen.
3. **Monta** con Media3, la librería de edición de Google: une los trozos en orden, pone un rótulo sobre cada trozo si se lo pides, música de fondo y el formato que quieras (original, vertical 1080×1920, horizontal 1920×1080 o cuadrado).
4. **Revisa** el resultado mirándolo otra vez, y lo guarda en la galería, en *Películas/JARVIS*.

Lo que **todavía no hace**: transiciones con fundido (los cortes son directos), cambiar la velocidad, filtros de color, subtítulos sincronizados palabra por palabra, ni usar fotos como trozos. Si se lo pides, te lo dirá.

Límites: 40 trozos y 10 minutos por montaje. Nunca modifica ni borra los originales. Exportar tarda: cuenta con uno o dos minutos por minuto de video, según el teléfono.

## 5. Qué hace solo y qué espera tu sí

| Lo hace solo | Espera que toques **Aprobar** | No lo hace nunca |
|---|---|---|
| Abrir aplicaciones y páginas, buscar, leer, deslizar, rellenar campos, escribir un mensaje sin enviarlo | Tocar cualquier botón que diga enviar, pagar, comprar, confirmar, reservar, eliminar, publicar, llamar, instalar, transferir… · pulsar Intro en un chat · lo que el propio agente marque como irreversible · **cualquier** acción dentro de un banco o aplicación de pagos (una vez por tarea, y cada pago aparte) | Escribir contraseñas, PIN o datos de tarjeta · manejar su propia ventana (no puede pulsar su propio «Aprobar» ni cambiarse los ajustes) |

- Decir «sí» en voz alta no aprueba nada: solo vale tu toque. Si no respondes en 10 minutos, cuenta como no.
- El freno lo aplica la aplicación mirando **qué elemento** se va a tocar; no depende de lo que el modelo decida.
- Lo que lee en pantalla o en una web va marcado como datos, no como órdenes. Aunque una página intentara engañarle, el botón de enviar o pagar seguiría esperando tu toque.
- Todo lo que hace y cada permiso que pide queda en **ajustes → Diario de acciones**.
- Límites: una tarea cada vez y 80 acciones por tarea.

## 6. Privacidad

- La clave se guarda **cifrada** con el almacén seguro de Android y solo se envía a `api.anthropic.com`.
- Mientras hace una tarea, JARVIS envía a Claude (Anthropic) el texto de lo que hay en pantalla y, si la pide, una captura. Fuera de las tareas no lee ni envía nada.
- Al editar video envía a Claude **fotogramas** de los videos que usa (no el video entero) y los nombres de los archivos. Si pones clave de ElevenLabs, el **sonido** de los videos que transcriba se envía a ElevenLabs. El montaje se hace en el teléfono.
- No hay servidores propios, ni cuentas, ni analítica. La memoria y el diario se quedan en el teléfono.

## 7. Cuánto cuesta

La app es gratis; se paga el uso de Claude con tu clave. Precios de lista consultados el 3 de noviembre de 2026, por millón de tokens (compruébalos en [claude.com/pricing](https://claude.com/pricing), porque cambian):

| Modelo | Entrada | Salida |
|---|---|---|
| Claude Opus 5.5 (por defecto, el más capaz) | 4 $ | 20 $ |
| Claude Sonnet 5.5 | 2 $ | 10 $ |
| Claude Haiku 5.5 | 0,10 $ | 0,50 $ |

Una tarea son varias llamadas seguidas (una por paso) y cada una reenvía la conversación, aunque lo ya enviado se cobra con descuento. **No he medido el coste real por tarea.** Empieza con poco saldo, mira el consumo en la consola de Anthropic y, si te parece caro, cambia a Sonnet o Haiku en Ajustes. La búsqueda en internet tiene un pequeño coste por búsqueda y se puede apagar.

**Editar video gasta más que el resto**, porque cada hoja de fotogramas que mira es una imagen que se le manda a Claude, y un montaje cuidado mira muchas. Transcribir consume además créditos de tu cuenta de ElevenLabs.

## 8. Lo que no está comprobado o hay que saber

- **No se ha ejecutado en un teléfono.** Se construyó en un entorno sin teléfono ni emulador. Está comprobado que compila, que la APK está firmada y que pasan las 30 pruebas automáticas del núcleo (el freno, las herramientas, la comprobación de los montajes y la conversación con una API de Claude simulada). **La pantalla, la burbuja, la voz, el servicio que toca la pantalla y la edición de video no se han visto funcionar**: la primera prueba real es la tuya, y puede haber fallos que corregir.
- **La edición de video es la parte con más riesgo.** Exportar depende de los codificadores de cada teléfono, y aquí no se ha podido exportar ni un solo video. Lo probado es la lógica (qué trozos, en qué orden, con qué formato); sacar fotogramas, mezclar la música, los rótulos y la exportación real están sin estrenar. En teléfonos modestos, los videos 4K o los montajes largos pueden fallar o tardar mucho. Tampoco se ha llamado a ElevenLabs desde la app.
- **Tampoco se ha llamado a la API real de Claude** desde la app: no había clave. Se verificó contra un servidor simulado que la petición lleva lo que la API exige.
- **El freno por palabras no es perfecto.** Reconoce los botones por su texto en español e inglés. Un botón sin texto en una aplicación rara puede escapársele si el agente tampoco lo marca; por eso los bancos piden permiso para todo. Empieza con tareas de poco riesgo y revisa el diario.
- Las aplicaciones de banco y las que protegen su pantalla no se dejan leer: JARVIS te lo dirá.
- **Intro** solo funciona desde Android 11; en versiones anteriores toca el botón de enviar o buscar.
- Android puede cerrar la app en segundo plano para ahorrar batería. Si la burbuja desaparece, quita a JARVIS de la optimización de batería (*Ajustes → Aplicaciones → JARVIS → Batería → Sin restricciones*).
- Algunos fabricantes (Xiaomi, Huawei, Oppo) apagan los servicios de accesibilidad al reiniciar: habrá que volver a activarlo.
- **La firma** (`firma/jarvis.keystore`) está en el repositorio a propósito, con contraseña pública, para que cada compilación se pueda instalar encima de la anterior. Sirve para uso personal. Para publicar en una tienda hay que crear una clave propia y no subirla.

## 9. Compilar

Hace falta JDK 17 o superior y el SDK de Android (plataforma 35).

```bash
cd jarvis-android
./gradlew testDebugUnitTest      # las pruebas
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

## 10. Estructura

```
jarvis-android/app/src/main/kotlin/com/radar/jarvis/
├── nucleo/                    sin nada de Android: se prueba en cualquier computadora
│   ├── Freno.kt               qué hace solo y qué espera tu sí
│   ├── Manos.kt               lo que JARVIS puede ver y hacer en un teléfono (interfaz)
│   ├── Herramientas.kt        las 14 herramientas del agente, pasando por el freno
│   ├── Estudio.kt             lo que JARVIS puede hacer con video (interfaz) y la comprobación de cada montaje
│   └── Agente.kt              la conversación con Claude: pide, ejecuta, devuelve, repite
├── android/
│   ├── ServicioAccesibilidad.kt   las manos de verdad: lee la pantalla, toca, escribe, abre
│   ├── EstudioAndroid.kt      el estudio de verdad: fotogramas, transcripción y montaje con Media3
│   ├── Burbuja.kt             el orbe flotante y la tarjeta de permiso
│   ├── Sesion.kt              lo que está haciendo ahora; lo observan la pantalla y la burbuja
│   ├── Voz.kt                 dictado y voz de Android
│   └── Almacen.kt             clave cifrada, ajustes, memoria y diario
└── ui/                        MainActivity, AjustesActivity, OrbView (el orbe)
```
