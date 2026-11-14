/* Radar YouTube · service worker.
   Página y scripts: primero la red (para que cada versión nueva llegue sola) y la copia guardada si no hay conexión.
   Nunca guarda llamadas a otras webs (YouTube, Google, Anthropic). */
const CACHE_NAME = 'radar-youtube-v2';
const ASSETS = ['./', './index.html', './cockpit6.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

self.addEventListener('install', (e) => {
  // un archivo que falte no debe impedir la instalación
  e.waitUntil(caches.open(CACHE_NAME)
    .then((cache) => Promise.all(ASSETS.map((a) => cache.add(a).catch(() => {}))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return;
  e.respondWith(
    fetch(e.request).then((res) => {
      if (res.ok) {
        const copia = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(e.request, copia));
      }
      return res;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then((r) => r || caches.match('./index.html')))
  );
});
