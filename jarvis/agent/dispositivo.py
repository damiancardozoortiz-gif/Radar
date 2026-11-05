"""JARVIS en el teléfono: catálogo de permisos, integraciones con apps, órdenes de voz y registro de acciones.

Arquitectura real: JARVIS es una aplicación web. En Android funciona en Chrome (o instalada como
aplicación web) y usa las API estándar del navegador, que Android media con sus propios diálogos de
permiso. Este módulo NO concede ni simula permisos: el estado real lo consulta el navegador
(ui/dispositivo.js) y aquí solo se describe, se registra y se decide qué orden se pidió.

Lo que una aplicación web NO puede hacer en Android (haría falta una app nativa; ver README):
leer notificaciones de otras apps, leer mensajes de WhatsApp/Instagram, controlar otras apps,
abrir directamente la pantalla de ajustes de Android, ni escuchar en segundo plano.
"""
import json
import re
import threading
import unicodedata
import time
from urllib.parse import quote

import estado
from vault import norm

# ------------------------------------------------------------------ permisos (para qué sirve cada uno)
PERMISOS = {
    "camera": {
        "nombre": "Cámara",
        "para": "Ver lo que le enseñes cuando tú digas «abre la cámara»: describir objetos, leer documentos, sacar fotos.",
        "cuando": "Solo cuando le pidas usar la cámara. Se apaga al cerrarla, al pausar o al salir de la pantalla.",
        "android": "Chrome ⋮ → Configuración → Configuración de sitios → Cámara → JARVIS. O en Android: Ajustes → Aplicaciones → Chrome → Permisos → Cámara.",
    },
    "microphone": {
        "nombre": "Micrófono",
        "para": "Escucharte cuando pulsas el micrófono. El audio se transcribe con ElevenLabs Scribe.",
        "cuando": "Solo en modo conversación y con la pantalla abierta; nunca en segundo plano. Sordo mientras JARVIS habla.",
        "android": "Chrome ⋮ → Configuración → Configuración de sitios → Micrófono → JARVIS. O Ajustes → Aplicaciones → Chrome → Permisos → Micrófono.",
    },
    "geolocation": {
        "nombre": "Ubicación",
        "para": "Saber dónde estás cuando preguntas «¿dónde estoy?» o pides una ruta desde aquí.",
        "cuando": "Una sola lectura cada vez que lo pides. No se sigue tu posición ni se envía a Claude salvo que lo pidas.",
        "android": "Chrome ⋮ → Configuración → Configuración de sitios → Ubicación. Y Ajustes → Ubicación activada.",
    },
    "notifications": {
        "nombre": "Notificaciones",
        "para": "Avisarte (por ejemplo, de un borrador pendiente) aunque tengas JARVIS en otra pestaña.",
        "cuando": "Solo si las activas desde el panel. JARVIS no puede LEER las notificaciones de otras apps.",
        "android": "Chrome ⋮ → Configuración → Notificaciones → JARVIS. O Ajustes → Notificaciones → Chrome.",
    },
    "files": {
        "nombre": "Archivos, fotos y vídeos",
        "para": "Elegir un documento o foto para que JARVIS lo lea o lo describa.",
        "cuando": "Solo el archivo que tú eliges en el selector de Android. No hay acceso general a tu almacenamiento.",
        "android": "No necesita permiso: Android abre su selector de archivos o de fotos y tú eliges.",
    },
    "bluetooth": {
        "nombre": "Bluetooth",
        "para": "Buscar un dispositivo Bluetooth cercano (solo identificarlo).",
        "cuando": "Solo si pulsas «buscar». Android muestra su propio selector y tú eliges.",
        "android": "Chrome para Android lo admite con Bluetooth y Ubicación activados. JARVIS no empareja ni controla dispositivos.",
    },
    "network": {
        "nombre": "Conectividad",
        "para": "Saber si hay conexión y de qué tipo, para avisarte si la voz o el cerebro no pueden funcionar.",
        "cuando": "Siempre (no necesita permiso).",
        "android": "No necesita permiso.",
    },
    "share": {
        "nombre": "Compartir",
        "para": "Enviar una foto o un texto a otra app mediante el menú «Compartir» de Android.",
        "cuando": "Solo cuando pulsas «compartir» y eliges tú la app. JARVIS nunca envía nada solo.",
        "android": "No necesita permiso: es el menú estándar de Android.",
    },
}

# ------------------------------------------------------------------ integraciones (mecanismos oficiales)
# estado: "disponible" = funciona hoy con un enlace o API oficial del navegador;
#         "requiere configuración" = hay API oficial pero necesita tu cuenta/clave (no conectada);
#         "no disponible" = no existe una vía oficial para eso.
INTEGRACIONES = [
    {"id": "whatsapp", "app": "WhatsApp", "estado": "disponible",
     "mecanismo": "Enlace oficial «click to chat» (wa.me) y menú Compartir de Android.",
     "puede": ["Abrir WhatsApp con un mensaje ya escrito (para un número o para que elijas el chat). Lo envías tú."],
     "no_puede": ["Leer tus mensajes o chats.", "Enviar mensajes por su cuenta.",
                  "La API oficial (WhatsApp Business Cloud API) es solo para números de empresa, con cuenta de Meta."]},
    {"id": "instagram", "app": "Instagram", "estado": "disponible",
     "mecanismo": "Enlaces a perfiles y menú Compartir de Android.",
     "puede": ["Abrir un perfil.", "Pasar una foto a Instagram con el menú Compartir; publicas tú."],
     "no_puede": ["Leer mensajes directos.", "Publicar por su cuenta. La API de publicación es solo para cuentas de empresa o creador, con revisión de Meta."]},
    {"id": "youtube", "app": "YouTube", "estado": "disponible",
     "mecanismo": "Enlaces oficiales de búsqueda y de vídeo (se abren en la app de YouTube).",
     "puede": ["Buscar y abrir vídeos."],
     "no_puede": ["Leer tu historial o tus suscripciones. La API YouTube Data v3 necesita una clave de Google Cloud: no conectada."]},
    {"id": "gmail", "app": "Gmail", "estado": "disponible",
     "mecanismo": "Enlace mailto: (abre Gmail con el borrador escrito) y lectura del correo exportado (data/real/inbox.json).",
     "puede": ["Abrir Gmail con un borrador ya escrito: destinatario, asunto y texto. Lo envías tú.",
               "Leer el correo que exportes (ver README)."],
     "no_puede": ["Leer tu bandeja en directo. Haría falta la API de Gmail con OAuth de Google: no conectada.", "Enviar correos."]},
    {"id": "maps", "app": "Google Maps", "estado": "disponible",
     "mecanismo": "Maps URLs oficiales (google.com/maps/search y /dir con api=1); se abren en la app.",
     "puede": ["Buscar un sitio.", "Abrir una ruta hasta un destino (desde tu ubicación)."],
     "no_puede": ["Leer tu historial de ubicaciones."]},
    {"id": "camara", "app": "Cámara", "estado": "disponible",
     "mecanismo": "API de cámara del navegador (getUserMedia) dentro de JARVIS, con vista previa.",
     "puede": ["Vista previa, cambiar entre frontal y trasera, sacar fotos cuando lo ordenas.",
               "Describir lo que ve con Claude (visión)."],
     "no_puede": ["Grabar vídeo o funcionar en segundo plano (JARVIS no lo hace a propósito)."]},
    {"id": "galeria", "app": "Galería / Fotos", "estado": "disponible",
     "mecanismo": "Selector de fotos y vídeos de Android (input de archivos del navegador).",
     "puede": ["Elegir una foto para que JARVIS la describa."],
     "no_puede": ["Ver tu galería entera ni borrar fotos."]},
    {"id": "navegador", "app": "Navegador", "estado": "disponible",
     "mecanismo": "Abrir una dirección web (solo http/https).",
     "puede": ["Abrir una página."], "no_puede": ["Leer tus pestañas o tu historial."]},
    {"id": "archivos", "app": "Administrador de archivos", "estado": "disponible",
     "mecanismo": "Selector de documentos de Android.",
     "puede": ["Elegir un documento de texto o una imagen para que JARVIS lo lea o lo describa."],
     "no_puede": ["Navegar por tus carpetas, mover o borrar archivos."]},
]

# ------------------------------------------------------------------ órdenes de voz del dispositivo
def _q(text):
    return quote(text.strip(), safe="")


def route(question):
    """Si es una orden del dispositivo, devuelve {"action", "say", ...}. Si no, None.
    Abrir apps o compartir NUNCA se ejecuta solo: el navegador muestra una tarjeta y tú pulsas."""
    orig = unicodedata.normalize("NFC", question).strip()
    q = norm(orig)
    if len(q) != len(orig):  # caso raro: no se puede recortar del original con seguridad
        orig = q

    def g(m, i):  # el texto tal como lo dijiste (con tildes y mayúsculas), no la versión normalizada
        return orig[m.start(i):m.end(i)] if m.group(i) is not None else None
    qs = re.sub(r"[^a-z0-9ñ ]+", " ", q)
    qs = " " + re.sub(r"\s+", " ", qs) + " "
    if re.search(r"\b(detener|detén|deten|para|parar) todo\b", qs):
        return {"action": "stop_all", "say": "Detengo todo."}
    if re.search(r"\b(abre|abrir|enciende|activa|pon) (la )?camara\b", qs) or re.search(r"\bcamara (trasera|frontal)\b", qs) and re.search(r"\b(abre|usa|pon)\b", qs):
        facing = "user" if "frontal" in qs or "selfie" in qs else "environment"
        return {"action": "camera_open", "facing": facing, "say": "Abro la cámara." + (" Frontal." if facing == "user" else "")}
    if re.search(r"\b(cierra|apaga|cerrar|apagar|desactiva) (la )?camara\b", qs):
        return {"action": "camera_close", "say": "Cierro la cámara."}
    if re.search(r"\b(cambia|gira|da la vuelta|voltea)\b.*\bcamara\b|\bcamara (frontal|trasera)\b", qs):
        return {"action": "camera_switch", "say": "Cambio de cámara."}
    if re.search(r"\b(haz|saca|toma|hazme|sacame|captura)\b.*\b(foto|fotografia|captura)\b", qs):
        return {"action": "camera_capture", "say": "Foto hecha. Dime si la guardo, la comparto o la describo."}
    if re.search(r"\b(que ves|que estas viendo|describe(me)? (lo que ves|esto|la imagen|la foto)|lee (este|el) (documento|texto|papel)|que es esto|que pone)\b", qs):
        return {"action": "camera_describe", "say": "Miro."}
    if re.search(r"\b(donde estoy|mi ubicacion|en que calle estoy)\b", qs):
        return {"action": "location", "say": "Miro dónde estás."}
    if re.search(r"\b(elige|abre|selecciona|busca)\b.*\b(archivo|documento|pdf)\b", qs):
        return {"action": "pick_file", "accept": "", "say": "Elige el archivo."}
    if re.search(r"\b(elige|abre|selecciona)\b.*\b(foto|fotos|imagen|galeria|video)\b", qs):
        return {"action": "pick_file", "accept": "image/*,video/*", "say": "Elige la foto."}
    m = re.search(r"\b(?:busca|pon|ponme|abre)\b(?: en)? youtube (.+)$|\b(?:busca|pon|ponme) (.+?) en youtube\b", q)
    if m:
        term = (g(m, 1) or g(m, 2) or "").strip(" ¿?¡!.")
        return _open("youtube", f"https://www.youtube.com/results?search_query={_q(term)}", f"Buscar «{term}» en YouTube")
    if re.search(r"\babre youtube\b", qs):
        return _open("youtube", "https://www.youtube.com/", "Abrir YouTube")
    m = re.search(r"\b(?:como llego|llevame|ruta|indicaciones)\b(?: a| hasta| hacia)? (.+)$", q)
    if m:
        dest = g(m, 1).strip(" ¿?¡!.")
        return _open("maps", f"https://www.google.com/maps/dir/?api=1&destination={_q(dest)}", f"Ruta hasta «{dest}» en Google Maps")
    m = re.search(r"\b(?:busca|buscame|encuentra)\b (.+?) en (?:google )?maps\b|\b(?:busca en|abre) (?:google )?maps (.+)$", q)
    if m:
        place = (g(m, 1) or g(m, 2)).strip(" ¿?¡!.")
        return _open("maps", f"https://www.google.com/maps/search/?api=1&query={_q(place)}", f"Buscar «{place}» en Google Maps")
    m = re.search(r"\b(?:escribe|manda|envia|mandale|enviale)\b.*\bwhatsapp\b(?:.*?\b(?:diciendo|que diga|con el texto|:)\s*(.+))?$", q)
    if m or re.search(r"\babre whatsapp\b", qs):
        text = (g(m, 1) if m and m.group(1) else "").strip()
        url = "https://wa.me/" + (f"?text={_q(text)}" if text else "")
        return _open("whatsapp", url, "Abrir WhatsApp" + (f" con el texto «{text}»" if text else "") + ". Lo envías tú.")
    m = re.search(r"\babre (?:el perfil de )?instagram(?: de)? ?@?([a-z0-9._]+)?", q)
    if m:
        user = (g(m, 1) or "").strip(".")
        return _open("instagram", f"https://www.instagram.com/{user}/" if user else "https://www.instagram.com/", "Abrir Instagram")
    m = re.search(r"\babre (?:la )?(?:web|pagina|direccion) (https?://\S+|[a-z0-9.-]+\.[a-z]{2,}\S*)", q)
    if m:
        url = m.group(1)
        url = url if url.startswith("http") else "https://" + url
        return _open("navegador", url, f"Abrir {url}")
    if re.search(r"\babre gmail\b", qs):
        return _open("gmail", "https://mail.google.com/", "Abrir Gmail")
    return None


def _open(app, url, label):
    if not re.match(r"^https://[^\s]+$", url):  # solo enlaces https oficiales
        return None
    return {"action": "open_app", "app": app, "url": url, "label": label,
            "say": f"Te dejo el botón para {label[0].lower() + label[1:]}. Púlsalo si quieres.".replace("..", ".")}


def mailto(to="", subject="", body=""):
    return f"mailto:{quote(to, safe='@,')}?subject={quote(subject)}&body={quote(body)}"


# ------------------------------------------------------------------ registro de acciones
_log_lock = threading.Lock()
TIPOS = {"permiso", "camara", "foto", "vision", "app", "archivo", "ubicacion", "compartir", "guardar",
         "notificacion", "bluetooth", "detener_todo", "pausa", "microfono", "red"}


def log(tipo, detalle):
    """Registro de auditoría en estado/acciones.jsonl. Nunca guarda imágenes, audio ni textos privados:
    solo el tipo de acción y un detalle corto. Se registra también durante la PAUSA (es auditoría)."""
    tipo = tipo if tipo in TIPOS else "otro"
    detalle = re.sub(r"\s+", " ", str(detalle))[:160]
    entry = {"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "tipo": tipo, "detalle": detalle}
    with _log_lock:
        estado.STATE_DIR.mkdir(parents=True, exist_ok=True)
        f = estado.STATE_DIR / "acciones.jsonl"
        new = not f.exists()
        with open(f, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
        if new:
            f.chmod(0o600)
        # el registro no crece sin fin: se queda con las últimas 2000 líneas
        try:
            lines = f.read_text(encoding="utf-8").splitlines()
            if len(lines) > 2500:
                f.write_text("\n".join(lines[-2000:]) + "\n", encoding="utf-8")
        except OSError:
            pass
    return entry


def last_actions(n=100):
    f = estado.STATE_DIR / "acciones.jsonl"
    if not f.exists():
        return []
    out = []
    for line in f.read_text(encoding="utf-8").splitlines()[-n:]:
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return list(reversed(out))
