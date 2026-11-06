#!/usr/bin/env python3
"""API de Claude SIMULADA para probar el núcleo de la app (no es la API real). Valida la forma de cada petición."""
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
LOG = open(sys.argv[2], "a")
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        problems = []
        if self.path != "/v1/messages": problems.append("ruta " + self.path)
        if self.headers.get("anthropic-version") != "2026-10-21": problems.append("versión")
        if not (self.headers.get("x-api-key") or "").startswith("sk-ant-prueba"): problems.append("clave")
        if body.get("model") != "claude-opus-5-5": problems.append("modelo")
        if not isinstance(body.get("system"), str) or "JARVIS" not in body["system"]: problems.append("system")
        last = body["messages"][-1]
        content = last["content"]
        has_image = isinstance(content, list) and any(c.get("type") == "image" and c["source"]["type"] == "base64" for c in content)
        text = content if isinstance(content, str) else " ".join(c.get("text", "") for c in content if c.get("type") == "text")
        LOG.write(json.dumps({"problems": problems, "turns": len(body["messages"]), "image": has_image, "fallbacks": body.get("fallbacks"),
                              "beta": self.headers.get("anthropic-beta"), "effort": body.get("output_config", {}).get("effort")}) + "\n"); LOG.flush()
        text = text.split("Mensaje:", 1)[1].split("\n", 1)[0] if "Mensaje:" in text else text  # solo la frase del usuario
        if "LENTO" in text: time.sleep(5)
        tools = [t.get("type") for t in body.get("tools", [])]
        LOG.write(json.dumps({"tools": tools, "msg": text[:40]}) + "\n"); LOG.flush()
        if "operación CERRADA" in text: rep = {"decir": "La salida por stop fue correcta.", "detalle": "## Factores\nEntrada contra la tendencia de H4.", "recordar": None, "aprendizaje": "No entrar contra la tendencia de H4."}
        elif has_image: rep = {"decir": "Veo un cuadrado rojo.", "detalle": "", "recordar": None}
        elif "informe" in text.lower(): rep = {"decir": "Informe listo, señor Rafael.", "detalle": "# Informe\n" + "Hallazgo con fuente https://ejemplo.org. " * 10, "recordar": None}
        elif "tarea" in text.lower(): rep = {"decir": "Tarea creada: llamar al banco.", "detalle": "", "recordar": None, "tarea": {"accion": "crear", "texto": "llamar al banco"}}
        elif "Recuerda" in text: rep = {"decir": "He guardado: la cita del dentista es el jueves.", "detalle": "", "recordar": "la cita del dentista es el jueves"}
        elif "borrador" in text: rep = {"decir": "Borrador listo.", "detalle": "**Borrador**\nHola Ricardo, ¿te va el jueves?", "recordar": None}
        else: rep = {"decir": f"Respuesta simulada número {len(body['messages'])}.", "detalle": "", "recordar": None}
        out = {"type": "message", "stop_reason": "end_turn", "content": [{"type": "thinking", "thinking": ""}, {"type": "text", "text": json.dumps(rep, ensure_ascii=False)}]}
        data = json.dumps(out).encode(); code = 400 if problems else 200
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
