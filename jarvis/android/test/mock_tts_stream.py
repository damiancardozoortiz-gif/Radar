#!/usr/bin/env python3
"""ElevenLabs SIMULADO (no es el servicio real): /stream envía PCM en 10 trozos con 120 ms entre cada uno;
la ruta normal envía todo junto al final (como esperar el MP3 completo). Sirve para medir la diferencia."""
import math, struct, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
PCM = b"".join(struct.pack("<h", int(8000 * math.sin(i * 2 * math.pi * 180 / 22050))) for i in range(22050))  # 1 s de tono
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.headers.get("xi-api-key") != "clave-prueba": self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
        if "/stream" in self.path:
            self.send_response(200); self.send_header("Content-Type", "audio/pcm"); self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
            step = len(PCM) // 10 + 1  # trozos de tamaño impar a propósito (parten muestras)
            for i in range(0, len(PCM), step):
                time.sleep(0.12); part = PCM[i:i + step]
                self.wfile.write(b"%x\r\n" % len(part) + part + b"\r\n"); self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n")
        else:
            time.sleep(1.2)
            self.send_response(200); self.send_header("Content-Type", "audio/pcm"); self.send_header("Content-Length", str(len(PCM))); self.end_headers(); self.wfile.write(PCM)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
