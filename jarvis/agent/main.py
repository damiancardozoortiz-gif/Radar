"""Servidor y API de JARVIS. Solo librería estándar.

Arranque:  ./jarvis.sh   (o: python3 agent/main.py)
Puerto:    JARVIS_PORT (por defecto 8790). Solo escucha en 127.0.0.1.
"""
import json
import mimetypes
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import data  # noqa: E402
from vault import VAULT  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "ui"
PORT = int(os.environ.get("JARVIS_PORT", "8790"))

FAVICON = (b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
           b'<circle cx="16" cy="16" r="13" fill="none" stroke="#5cc8d6" stroke-width="2"/>'
           b'<circle cx="16" cy="16" r="5" fill="#5cc8d6"/></svg>')


class Handler(BaseHTTPRequestHandler):
    server_version = "JARVIS/1"

    def log_message(self, fmt, *args):  # silencio salvo errores
        if args and str(args[1])[:1] in "45":
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

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
        return self.send_json({"error": "ruta desconocida"}, 404)


def status():
    s = VAULT.summary()
    return {"mode": data.mode(), "today": data.today().isoformat(), "notes": s["notes"],
            "edges": s["edges"], "by_type": s["by_type"], "skipped": s["skipped"],
            "mail": data.inbox() is not None, "calendar": data.calendar() is not None}


def main():
    VAULT.load()
    s = VAULT.summary()
    print(f"JARVIS · modo {data.mode()} · {s['notes']} notas · {s['edges']} enlaces · "
          f"http://localhost:{PORT}", flush=True)
    for path, why in s["skipped"]:
        print(f"  saltado: {path} ({why})", flush=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
