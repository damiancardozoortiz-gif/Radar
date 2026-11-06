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
        LOG.write(json.dumps({"lessons": "aprendizajes y fallos anteriores" in text, "capabilities": "## Capacidades" in body.get("system", "")}) + "\n"); LOG.flush()
        text = text.split("Mensaje:", 1)[1].split("\n", 1)[0] if "Mensaje:" in text else text  # solo la frase del usuario
        if "LENTO" in text: time.sleep(5)
        tools = [t.get("type") for t in body.get("tools", [])]
        LOG.write(json.dumps({"tools": tools, "msg": text[:40]}) + "\n"); LOG.flush()
        if "aprendé a" in text: rep = {"decir": "Preparé una herramienta para convertir segundos.", "detalle": "## Qué falta\nNada nativo.", "recordar": None, "tipo": "herramienta",
            "herramienta": {"id": "reloj", "nombre": "Segundos a reloj", "descripcion": "segundos → hh:mm:ss", "codigo": "function run(entrada){ var h=Math.floor(entrada/3600),m=Math.floor(entrada%3600/60),s=entrada%60; function p(n){return (n<10?'0':'')+n;} return p(h)+':'+p(m)+':'+p(s); }",
                            "pruebas": [{"entrada": 0, "esperado": "00:00:00"}, {"entrada": 3661, "esperado": "01:01:01"}, {"entrada": 59, "esperado": "00:00:59"}], "por_que": "división entera"}}
        elif "editá este vídeo" in text: rep = {"decir": "Todavía no sé editar vídeo, señor Rafael.", "detalle": "", "recordar": None, "capacidad_faltante": "editar vídeo"}
        elif "operación CERRADA" in text: rep = {"decir": "La salida por stop fue correcta.", "detalle": "## Factores\nEntrada contra la tendencia de H4.", "recordar": None, "aprendizaje": "No entrar contra la tendencia de H4."}
        elif has_image: rep = {"decir": "Veo un cuadrado rojo.", "detalle": "", "recordar": None}
        elif "informe" in text.lower(): rep = {"decir": "Informe listo, señor Rafael.", "detalle": "# Informe\n" + "Hallazgo con fuente https://ejemplo.org. " * 10, "recordar": None}
        elif "tarea" in text.lower(): rep = {"decir": "Tarea creada: llamar al banco.", "detalle": "", "recordar": None, "tarea": {"accion": "crear", "texto": "llamar al banco"}}
        elif "Recuerda" in text: rep = {"decir": "He guardado: la cita del dentista es el jueves.", "detalle": "", "recordar": "la cita del dentista es el jueves"}
        elif "borrador" in text: rep = {"decir": "Borrador listo.", "detalle": "**Borrador**\nHola Ricardo, ¿te va el jueves?", "recordar": None}
        else: rep = {"decir": f"Respuesta simulada número {len(body['messages'])}.", "detalle": "", "recordar": None}
        blocks = [{"type": "thinking", "thinking": ""}]
        if "web_fetch_20260209" in tools and "investig" in text.lower():  # investigación: búsqueda real simulada + un enlace inventado
            blocks += [{"type": "server_tool_use", "id": "s1", "name": "web_search", "input": {"query": "producto opiniones 2026"}},
                       {"type": "web_search_tool_result", "tool_use_id": "s1", "content": [
                           {"type": "web_search_result", "url": "https://fuente-a.org/analisis", "title": "Análisis A"},
                           {"type": "web_search_result", "url": "https://www.fuente-b.com/precios/", "title": "Precios B"}]},
                       {"type": "server_tool_use", "id": "s2", "name": "web_fetch", "input": {"url": "https://fuente-a.org/analisis"}},
                       {"type": "web_fetch_tool_result", "tool_use_id": "s2", "content": {"type": "web_fetch_result", "url": "https://fuente-a.org/analisis",
                           "content": {"type": "document", "title": "Análisis A (completo)"}}}]
            rep = {"decir": "El producto tiene buenas opiniones.", "detalle": "## Hallazgos\nPrecio 120 € [1] https://fuente-b.com/precios.\nValoración 4,5 [2] https://inventada.net/dato\n## Fuentes\n[1] https://fuente-a.org/analisis", "recordar": None, "aprender": "Comparar precios en dos tiendas."}
        out = {"type": "message", "stop_reason": "end_turn", "content": blocks + [{"type": "text", "text": json.dumps(rep, ensure_ascii=False)}]}
        data = json.dumps(out).encode(); code = 400 if problems else 200
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
