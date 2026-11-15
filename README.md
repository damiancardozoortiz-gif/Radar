# Radar YouTube

Aplicación web (PWA) para detectar videos de YouTube que empiezan a despegar. Es una sola página: `index.html`, más `cockpit6.js` (el Copiloto de Claude) y `sw.js`. No hay servidor ni paso de compilación; se publica tal cual en GitHub Pages.

En este repositorio viven también `jarvis/` y `jarvis-android/`, que son otro proyecto y tienen su propio README.

## Qué hace

| Pestaña | Para qué |
|---|---|
| Radar | Busca videos recientes por tema y región y los ordena por velocidad, crecimiento medido, cuánto superan a su canal o "me gusta" por vista. Guarda radares, reescanea solo y exporta CSV. También sigue canales y trae sus últimos videos por 1 unidad de cuota cada uno. |
| Favoritos | Los videos que guardaste. |
| Creator | Genera títulos, guion, descripción y etiquetas sin conexión. |
| Studio | Recorta y exporta video en el navegador, puntúa título y descripción, y sube el video a tu canal. |
| Ajustes | Clave de YouTube Data API, conexión con Google y cuota usada. |
| Copiloto | Chat con Claude sobre el ranking, con tu clave de Anthropic. |

## Qué necesitas

- **Clave de YouTube Data API v3** (Google Cloud). Se guarda solo en tu navegador. Cada búsqueda gasta unas 102 unidades de las 10.000 diarias.
- **OAuth Client ID** de tipo aplicación web, solo para Analytics y para subir videos.
- **Clave de Anthropic**, solo para el Copiloto.

## Cómo se leen las señales

- **Vistas/h estimadas**: vistas totales entre horas desde que se publicó. Es un promedio, no la velocidad de ahora.
- **Cambio medido**: diferencia de vistas entre dos consultas tuyas, por hora. Aparece desde la segunda búsqueda, con al menos 5 minutos entre ambas. El mini gráfico dibuja hasta 12 consultas.
- **N× su canal**: vistas del video entre suscriptores del canal. Un video con muchas más vistas que suscriptores salió de su público habitual.

## Pruebas

```bash
npm i playwright && npx playwright install chromium
node tests/radar.test.js
```

Recorre la app con la API de YouTube simulada: búsqueda, órdenes, crecimiento, radares guardados, canales seguidos, accesos directos y Studio, donde exporta un recorte vertical y una miniatura de un video generado en el momento.

## Lo que no está comprobado

- Las llamadas reales a YouTube, Google y Anthropic: las pruebas usan respuestas simuladas.
- Exportar en Studio con un video grabado por un teléfono, con sonido, y en navegadores que no sean Chrome. La prueba usa un video de 2 segundos sin sonido.
- Subir un video a YouTube desde Studio.
- El aviso de despegues con la app en segundo plano: depende de que el navegador dé permiso de notificaciones y no suspenda la pestaña.
