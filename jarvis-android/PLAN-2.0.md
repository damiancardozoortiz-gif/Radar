# JARVIS Android 2.0 · plan acordado

Decidido con el dueño del proyecto el 14 de noviembre de 2026. Estado: **construido en la versión 2.0** (ver README).

Pendiente para la 2.1, pedido por el dueño a partir de la lista que propuso el propio JARVIS: rótulos sincronizados con la voz,
movimiento en imágenes fijas, fundidos y cambio de velocidad, volumen por partes, y generar imágenes y video con ElevenLabs
(requiere plan Pro o superior en esa cuenta). Ya entraron en la 2.0: narración a partir de un guion y compartir hacia TikTok.

## Punto de partida

La versión 1.1 ya se probó en un teléfono real: conversar, la voz y manejar el teléfono funcionan.
La edición de video sigue sin probarse en un teléfono.

## Qué se construye

1. **Memoria que se llena sola**
   - Tres capas: perfil (quién es, sus gustos), hechos (personas, lugares, fechas) y episodios (qué hizo, cuándo, cómo salió).
   - Al terminar cada conversación extrae lo que vale la pena y lo guarda sin preguntar.
   - Antes de cada tarea busca solo los recuerdos relevantes, en vez de cargar toda la memoria.
   - Pantalla para ver, corregir y borrar cada recuerdo, con fecha y origen.
   - Nunca guarda contraseñas, datos de tarjeta ni códigos.
   - **Copia en Google Drive** con la copia de seguridad de Android (sin configuración; se recupera al reinstalar o cambiar
     de teléfono). Las claves de Claude y ElevenLabs no viajan en la copia.

2. **Autoevaluación**
   - Después de cada tarea comprueba si cumplió lo pedido, cuántos pasos dio, qué falló y cuánto costó.
   - Si falló, guarda la lección y la consulta la próxima vez.
   - Pulgar arriba o abajo en cada respuesta: el voto de la persona pesa más que su propia nota.
   - Panel con tareas logradas, fallidas, tiempo y coste.

3. **Habilidades**
   - Una habilidad es una receta guardada: nombre, cuándo usarla y pasos. No es código nuevo.
   - Se crean explicándoselas, deduciéndolas de una tarea que salió bien o importándolas de un archivo.
   - Antes de cada tarea busca si alguna encaja. Las que fallan seguido se marcan para corregir o retirar.

4. **Información nueva** (todo apagado hasta que la persona lo encienda)
   - Notificaciones en vivo.
   - Calendario y contactos.
   - Ubicación.

5. **Iniciativa**
   - JARVIS **crea rutinas él solo** cuando detecta un patrón; también se le pueden dictar.
   - Disparadores: hora del día, notificación que llega, lugar, evento del calendario.
   - El freno no cambia: enviar, pagar, comprar, borrar, publicar o llamar espera siempre el toque de la persona, también dentro de una rutina.
   - **Fusible de gasto alto**: no molesta en el uso normal; si un día gasta mucho más de lo habitual, se detiene y avisa.

## Orden

1. Memoria automática y copia en Drive.
2. Autoevaluación.
3. Habilidades.
4. Información nueva.
5. Iniciativa y rutinas, con el fusible.

## Cómo se entrega

- En la rama `ccr-a2f47d67-l3jw2z` (pull request #3), con pruebas del núcleo para cada pieza.
- APK firmada 2.0, compilada también por GitHub Actions.
- Lo que no se pueda comprobar sin un teléfono se dice claramente en el README.
