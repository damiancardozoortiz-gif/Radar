// Service worker mínimo: permite instalar JARVIS como aplicación y mostrar notificaciones en Android.
// NO guarda nada en caché (tus datos no se quedan en el teléfono) y NO funciona sin conexión a propósito.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", e => e.waitUntil(self.clients.claim()));
self.addEventListener("notificationclick", e => { e.notification.close(); e.waitUntil(self.clients.openWindow("/")); });
