"""Servidor y API de JARVIS. Solo librería estándar.

Arranque:  ./jarvis.sh   (o: python3 agent/main.py)
Puerto:    JARVIS_PORT (por defecto 8790). Solo escucha en 127.0.0.1.
"""
import base64
import errno
import hmac
import json
import mimetypes
import os
import re
import signal
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cerebro  # noqa: E402
import data  # noqa: E402
import dispositivo  # noqa: E402
import estado  # noqa: E402
import sesiones  # noqa: E402
import tools  # noqa: E402
import voice  # noqa: E402
from vault import VAULT  # noqa: E402

mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("text/javascript", ".js")

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "ui"
PORT = int(os.environ.get("JARVIS_PORT", "8790"))

# Lista EXPLÍCITA de hosts permitidos. Cualquier otra cabecera Host (p. ej. una web maliciosa que
# apunta su dominio a 127.0.0.1: "DNS rebinding") se rechaza en TODAS las rutas, también en GET,
# antes de entregar ningún dato.
ALLOWED_HOSTS = frozenset({f"localhost:{PORT}", f"127.0.0.1:{PORT}"})
ALLOWED_ORIGINS = frozenset({f"http://{h}" for h in ALLOWED_HOSTS})

# Acceso desde el teléfono (DESACTIVADO por defecto). Solo si TÚ defines las dos variables en .env:
#   JARVIS_REMOTE_HOST=mi-ordenador.mi-red.ts.net   (el nombre HTTPS de tu túnel, sin https://)
#   JARVIS_ACCESS_TOKEN=<32+ caracteres aleatorios>
# El servidor sigue escuchando solo en 127.0.0.1: el túnel (p. ej. Tailscale serve) pone el HTTPS.
# Para emparejar el teléfono se abre una vez https://HOST/?token=TOKEN, que deja una cookie segura.
REMOTE_HOST = (os.environ.get("JARVIS_REMOTE_HOST") or "").strip().lower().removeprefix("https://").rstrip("/")
ACCESS_TOKEN = (os.environ.get("JARVIS_ACCESS_TOKEN") or "").strip()
REMOTE_ON = bool(REMOTE_HOST) and len(ACCESS_TOKEN) >= 32
COOKIE = "jarvis_acceso"

FAVICON = (b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
           b'<circle cx="16" cy="16" r="13" fill="none" stroke="#5cc8d6" stroke-width="2"/>'
           b'<circle cx="16" cy="16" r="5" fill="#5cc8d6"/></svg>')


class Handler(BaseHTTPRequestHandler):
    server_version = "JARVIS/1"

    def log_message(self, fmt, *args):
        pass  # sin registro por petición: las URLs pueden llevar nombres de clientes

    def log_request(self, code="-", size="-"):
        # Solo errores, y sin consulta (?name=...) ni cabeceras: no se registran datos privados.
        if str(code)[:1] in "45":
            sys.stderr.write(f"{self.command} {urlparse(self.path).path} -> {code}\n")

    # ---------- control de acceso (todas las rutas) ----------
    def host_ok(self):
        host = (self.headers.get("Host") or "").strip().lower()
        origin = self.headers.get("Origin")
        if host in ALLOWED_HOSTS:
            return origin is None or origin.strip().lower() in ALLOWED_ORIGINS
        if REMOTE_ON and host == REMOTE_HOST:  # teléfono por el túnel HTTPS: además exige el token
            if origin is not None and origin.strip().lower() != f"https://{REMOTE_HOST}":
                return False
            return self.token_ok()
        return False

    def token_ok(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE and hmac.compare_digest(v.encode(), ACCESS_TOKEN.encode()):
                return True
        return False

    def try_pair(self):
        """https://HOST/?token=TOKEN deja una cookie segura (HttpOnly, Secure, SameSite=Strict) y redirige a /."""
        host = (self.headers.get("Host") or "").strip().lower()
        if not (REMOTE_ON and host == REMOTE_HOST):
            return False
        tok = (parse_qs(urlparse(self.path).query).get("token") or [""])[0]
        if not tok or not hmac.compare_digest(tok.encode(), ACCESS_TOKEN.encode()):
            return False
        self.send_response(303)
        self.send_header("Set-Cookie", f"{COOKIE}={ACCESS_TOKEN}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Strict")
        self.send_header("Location", "/")
        self.send_header("Content-Length", "0")
        self.end_headers()
        dispositivo.log("permiso", "teléfono emparejado por el acceso remoto")
        return True

    def reject(self):
        self.send_json({"error": "host u origen no permitido"}, 403)

    # ---------- utilidades ----------
    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def read_body(self, limit=25 * 1024 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("demasiado grande")
        return self.rfile.read(n) if n else b""

    # ---------- GET ----------
    def do_GET(self):
        if not self.host_ok():
            if self.try_pair():
                return None
            return self.reject()
        url = urlparse(self.path)
        p = url.path
        q = parse_qs(url.query)
        if p in ("/favicon.svg", "/favicon.ico"):
            return self.send_bytes(FAVICON, "image/svg+xml")
        if p == "/api/status":
            return self.send_json(status())
        if p == "/api/graph":
            return self.send_json(VAULT.graph())
        if p == "/api/note":
            nid = (q.get("id") or [""])[0]
            if not nid and q.get("name"):
                nid = VAULT.resolve(q["name"][0]) or ""
            n = VAULT.note(nid)
            return self.send_json(n) if n else self.send_json({"error": "nota no encontrada"}, 404)
        if p == "/api/route":
            tool, why = tools.route((q.get("q") or [""])[0])
            return self.send_json({"tool": tool, "why": why})
        if p == "/api/dispositivo":
            return self.send_json({"permisos": dispositivo.PERMISOS, "integraciones": dispositivo.INTEGRACIONES,
                                   "acciones": dispositivo.last_actions(60),
                                   "remoto": {"activo": REMOTE_ON, "host": REMOTE_HOST if REMOTE_ON else None}})
        if p == "/api/memory":
            return self.send_json({"card": tools.memory_card()})
        if p == "/api/top":
            return self.send_json([{"id": n["id"], "title": n["title"], "type": n["type"],
                                    "degree": n["degree"]} for n in VAULT.top(15)])
        return self.static(p)

    def static(self, p):
        if p == "/":
            p = "/index.html"
        target = (UI / p.lstrip("/")).resolve()
        if not str(target).startswith(str(UI.resolve())) or not target.is_file():
            return self.send_json({"error": "no existe"}, 404)
        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        return self.send_bytes(target.read_bytes(), ctype)

    # ---------- POST ----------
    def do_POST(self):
        if not self.host_ok():
            return self.reject()
        p = urlparse(self.path).path
        try:
            if p == "/api/pause":
                body = json.loads(self.read_body(4096) or b"{}")
                st = cerebro.set_pause(body.get("on"), by="botón")
                return self.send_json(st)
            if p == "/api/approve":
                body = json.loads(self.read_body(4096) or b"{}")
                if body.get("decision") not in ("aprobar", "descartar"):
                    return self.send_json({"error": "decisión no válida"}, 400)
                try:
                    a = cerebro.decide(str(body.get("id", "")), body["decision"])
                except estado.Paused:
                    return self.send_json({"error": "JARVIS está en pausa: quita la pausa para decidir"}, 423)
                return self.send_json(a) if a else self.send_json({"error": "no existe"}, 404)
            if p == "/api/dispositivo/evento":  # registro de auditoría (también en pausa)
                body = json.loads(self.read_body(4096) or b"{}")
                return self.send_json(dispositivo.log(str(body.get("tipo", "")), str(body.get("detalle", ""))))
            if p in ("/api/vision", "/api/archivo"):
                body = json.loads(self.read_body(12 * 1024 * 1024) or b"{}")
                if estado.paused():
                    return self.send_json({"error": "JARVIS está en pausa"}, 423)
                try:
                    if p == "/api/vision":
                        m = re.match(r"^data:(image/(?:jpeg|png|webp));base64,([A-Za-z0-9+/=]+)$", str(body.get("image", "")))
                        if not m:
                            return self.send_json({"error": "imagen no válida (jpeg, png o webp en base64)"}, 400)
                        base64.b64decode(m.group(2), validate=True)
                        o = cerebro.vision(str(body.get("question", ""))[:500], image_b64=m.group(2), media_type=m.group(1))
                        dispositivo.log("vision", f"imagen analizada ({len(m.group(2)) * 3 // 4 // 1024} KB)")
                    else:
                        text = str(body.get("text", ""))
                        name = str(body.get("name", ""))[:120]
                        o = cerebro.vision(str(body.get("question", ""))[:500], text=text, name=name)
                        dispositivo.log("archivo", f"documento analizado ({len(text)} caracteres)")
                except cerebro.BrainError as e:
                    return self.send_json({"error": f"el cerebro no pudo analizarlo: {e}"}, 503)
                return self.send_json(o)
            if p in ("/api/speak", "/api/listen") and estado.paused():
                self.read_body(10 * 1024 * 1024)
                return self.send_json({"error": "JARVIS está en pausa"}, 423)
            if p == "/api/ask":
                body = json.loads(self.read_body(64 * 1024) or b"{}")
                text = str(body.get("text", "")).strip()[:2000]
                forced = body.get("tool")
                if forced not in (None, "parte_del_dia", "planear_el_dia", "leer_correo"):
                    forced = None
                if not text:
                    text = {"parte_del_dia": "Dame el parte del día", "planear_el_dia": "Planéame el día",
                            "leer_correo": "¿Quién me ha escrito?"}.get(forced, "")
                if not text:
                    return self.send_json({"error": "texto vacío"}, 400)
                return self.send_json(cerebro.answer(str(body.get("conv", "anon"))[:64], text, forced))
            if p == "/api/speak":
                body = json.loads(self.read_body(64 * 1024) or b"{}")
                try:
                    audio, ctype = voice.speak(str(body.get("text", "")))
                except voice.VoiceError as e:
                    return self.send_json({"error": str(e)}, 503)
                if estado.paused():  # la pausa llegó mientras ElevenLabs respondía: no se entrega el audio
                    return self.send_json({"error": "JARVIS está en pausa"}, 423)
                return self.send_bytes(audio, ctype)
            if p == "/api/listen":
                audio = self.read_body(10 * 1024 * 1024)
                try:
                    text = voice.listen(audio, self.headers.get("Content-Type", "audio/webm"))
                except voice.VoiceError as e:
                    return self.send_json({"error": str(e)}, 503)
                if estado.paused():
                    return self.send_json({"error": "JARVIS está en pausa"}, 423)
                return self.send_json({"text": text})
            if p == "/api/reset":
                body = json.loads(self.read_body(4096) or b"{}")
                cerebro.reset(str(body.get("conv", ""))[:64])
                return self.send_json({"ok": True})
        except estado.Paused:
            return self.send_json({"error": "JARVIS está en pausa"}, 423)
        except Exception as e:  # que se vea
            return self.send_json({"error": f"{e.__class__.__name__}: {e}"}, 500)
        return self.send_json({"error": "ruta desconocida"}, 404)


def status():
    s = VAULT.summary()
    return {"mode": data.mode(), "today": data.today().isoformat(), "notes": s["notes"],
            "edges": s["edges"], "by_type": s["by_type"], "skipped": s["skipped"],
            "mail": data.inbox() is not None, "calendar": data.calendar() is not None,
            "brain": cerebro.STATUS, "pause": dict(estado.PAUSE), "state_warnings": list(estado.WARNINGS),
            "remote": REMOTE_ON,
            "approvals": estado.pending(),
            "voice": {"configured": voice.configured(), **voice.STATUS}}


def main():
    try:  # primero el puerto: si está ocupado, se dice claro y no se llama a Claude
        srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError as e:
        if e.errno in (errno.EADDRINUSE, getattr(errno, "WSAEADDRINUSE", -1)):
            print(f"No puedo arrancar: el puerto {PORT} ya está en uso.\n"
                  f"  ¿Tienes otro JARVIS abierto? Ciérralo, o arranca en otro puerto:\n"
                  f"  JARVIS_PORT={PORT + 1} ./jarvis.sh", file=sys.stderr, flush=True)
            sys.exit(1)
        raise
    srv.daemon_threads = True
    st = estado.load()
    VAULT.load()
    s = VAULT.summary()
    print(f"JARVIS · modo {data.mode()} · {s['notes']} notas · {s['edges']} enlaces · "
          f"http://localhost:{PORT}", flush=True)
    for path, why in s["skipped"]:
        print(f"  saltado: {path} ({why})", flush=True)
    for w in st["warnings"]:
        print(f"  AVISO: {w}", flush=True)
    if st["pause"]["on"]:
        print(f"  EN PAUSA desde {st['pause']['since']} ({st['pause']['by']}). Quítala en pantalla.", flush=True)
    if REMOTE_ON:
        print(f"  ACCESO REMOTO ACTIVO para https://{REMOTE_HOST} (con token). Desactívalo quitando JARVIS_REMOTE_HOST.", flush=True)
    elif os.environ.get("JARVIS_REMOTE_HOST"):
        print("  AVISO: JARVIS_REMOTE_HOST definido pero JARVIS_ACCESS_TOKEN falta o tiene menos de 32 caracteres: acceso remoto DESACTIVADO.", flush=True)
    if st["drafts"]:
        print(f"  {len(estado.pending())} borradores pendientes de tu sí (no se ejecuta nada).", flush=True)
    cerebro.warmup()
    sesiones.start_reaper()

    def bye(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, bye)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:  # apagado limpio: cierra los procesos claude abiertos
        sesiones.shutdown()
        print("JARVIS apagado.", flush=True)


if __name__ == "__main__":
    main()
