"""El puente entre Claude y las manos de JARVIS. Solo librería estándar.

Claude Code arranca este archivo como servidor MCP (por la entrada y la salida estándar). El puente no hace nada
por sí mismo: reenvía cada petición de herramienta al servidor de JARVIS (127.0.0.1), que es quien tiene el
teléfono, el navegador, la PAUSA y el freno. La contraseña del puente cambia en cada arranque de JARVIS.
"""
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get("JARVIS_PUENTE_URL", "")
CLAVE = os.environ.get("JARVIS_PUENTE_CLAVE", "")
ABRIDOR = urllib.request.build_opener(urllib.request.ProxyHandler({}))   # siempre directo, sin proxy


def servidor(ruta, cuerpo=None, timeout=900):
    req = urllib.request.Request(URL + ruta, data=json.dumps(cuerpo).encode() if cuerpo is not None else None,
                                 headers={"X-Jarvis-Puente": CLAVE, "Content-Type": "application/json"},
                                 method="POST" if cuerpo is not None else "GET")
    with ABRIDOR.open(req, timeout=timeout) as r:
        return json.loads(r.read())


def responder(mid, resultado=None, error=None):
    m = {"jsonrpc": "2.0", "id": mid}
    if error:
        m["error"] = error
    else:
        m["result"] = resultado
    sys.stdout.write(json.dumps(m, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    for linea in sys.stdin:
        linea = linea.strip()
        if not linea:
            continue
        try:
            m = json.loads(linea)
        except ValueError:
            continue
        mid, metodo, params = m.get("id"), m.get("method"), m.get("params") or {}
        if mid is None:          # avisos: no llevan respuesta
            continue
        try:
            if metodo == "initialize":
                responder(mid, {"protocolVersion": params.get("protocolVersion", "2024-11-05"),
                                "capabilities": {"tools": {}}, "serverInfo": {"name": "jarvis", "version": "2"}})
            elif metodo == "ping":
                responder(mid, {})
            elif metodo == "tools/list":
                responder(mid, {"tools": servidor("/interno/herramientas", timeout=15)})
            elif metodo == "tools/call":
                responder(mid, servidor("/interno/herramienta", {"nombre": params.get("name"), "args": params.get("arguments") or {}}))
            else:
                responder(mid, error={"code": -32601, "message": f"método desconocido: {metodo}"})
        except (urllib.error.URLError, OSError, ValueError) as e:
            if metodo == "tools/call":
                responder(mid, {"content": [{"type": "text", "text": f"El servidor de JARVIS no responde ({e}). Termina y avisa a la persona."}], "isError": True})
            else:
                responder(mid, error={"code": -32000, "message": f"sin conexión con JARVIS: {e}"})


if __name__ == "__main__":
    main()
