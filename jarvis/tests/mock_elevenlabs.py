#!/usr/bin/env python3
"""ElevenLabs SIMULADO para las pruebas. NO es el servicio real y no sale a internet.

Uso: python3 tests/mock_elevenlabs.py PUERTO ARCHIVO_LOG
  MOCK_TTS_SECONDS  duración del audio devuelto (por defecto 3)
  MOCK_DELAY        segundos que tarda en responder (por defecto 0)
Anota cada petición (modelo, idioma, si llegó la clave) en ARCHIVO_LOG, nunca la clave en sí.
"""
import io
import json
import math
import os
import struct
import sys
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
LOG = sys.argv[2] if len(sys.argv) > 2 else os.devnull


def tone(sec):
    b = io.BytesIO()
    w = wave.open(b, "wb")
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
    w.writeframes(b"".join(struct.pack("<h", int(3000 * math.sin(i * 2 * math.pi * 440 / 16000)))
                           for i in range(int(16000 * sec))))
    w.close()
    return b.getvalue()


def note(obj):
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(obj, ensure_ascii=False) + "\n")


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def out(self, code, body, ct):
        self.send_response(code)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        note({"GET": self.path, "key": bool(self.headers.get("xi-api-key"))})
        self.out(200, json.dumps({"voices": [{"voice_id": "mockvoice", "name": "Mock"}]}).encode(), "application/json")

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        time.sleep(float(os.environ.get("MOCK_DELAY", "0")))
        if self.path.startswith("/v1/text-to-speech/"):
            j = json.loads(body)
            note({"TTS": self.path.split("?")[0], "key": bool(self.headers.get("xi-api-key")),
                  "model_id": j.get("model_id"), "language_code": j.get("language_code")})
            return self.out(200, tone(float(os.environ.get("MOCK_TTS_SECONDS", "3"))), "audio/wav")
        if self.path == "/v1/speech-to-text":
            fields = {}
            for part in body.split(b"--jarvis")[1:-1]:
                if b'name="' in part:
                    name = part.split(b'name="')[1].split(b'"')[0].decode()
                    fields[name] = part.split(b"\r\n\r\n", 1)[1][:40].strip()
            note({"STT": len(body), "model_id": fields.get("model_id", b"").decode(),
                  "language_code": fields.get("language_code", b"").decode(), "key": bool(self.headers.get("xi-api-key"))})
            return self.out(200, json.dumps({"text": "¿Qué tengo hoy?"}).encode(), "application/json")
        self.out(404, b"{}", "application/json")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
