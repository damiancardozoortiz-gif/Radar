#!/usr/bin/env python3
"""`claude -p` SIMULADO para las pruebas automáticas. No llama a ninguna IA ni a internet.

JARVIS lo usa en lugar de `claude` cuando JARVIS_CLAUDE_BIN apunta a este archivo.
Imita la salida de `claude -p --output-format json`.

Variables (las pone la prueba):
  FAKE_CLAUDE_SLEEP   segundos que tarda en "pensar" (por defecto 0.2)
  FAKE_CLAUDE_LOG     archivo donde anota cada llamada: start/end con pid y argumentos clave
Respuesta según el mensaje:
  contiene "recuerda"  -> devuelve "recordar"
  contiene "borrador"  -> devuelve un borrador en "detalle"
  en otro caso         -> una frase corta
"""
import json
import os
import sys
import time
import unicodedata


def norm(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def log(event, extra=""):
    path = os.environ.get("FAKE_CLAUDE_LOG")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(f"{event} pid={os.getpid()} {extra}\n")


def main():
    argv = sys.argv[1:]
    msg = argv[argv.index("-p") + 1] if "-p" in argv else ""
    flags = " ".join(a for a in argv if a.startswith("--"))
    tools = argv[argv.index("--tools") + 1] if "--tools" in argv else "?"
    # Solo la frase del usuario (la línea "Mensaje: ..."), no las instrucciones del servidor.
    m = norm(msg.split("Mensaje:", 1)[1].split("\n", 1)[0]) if "Mensaje:" in msg else ""
    log("start", f"tools={tools!r} safe_mode={'--safe-mode' in argv} borrador={'borrador' in m} "
                 f"recuerda={'recuerda' in m} flags={flags}")
    time.sleep(float(os.environ.get("FAKE_CLAUDE_SLEEP", "0.2")))
    rep = {"decir": "Respuesta simulada.", "detalle": "", "recordar": None}
    if "recuerda" in m:
        rep = {"decir": "He guardado: prueba simulada de memoria.", "detalle": "",
               "recordar": "prueba simulada de memoria"}
    elif "borrador" in m:
        rep = {"decir": "Borrador listo en pantalla. Espera tu sí.", "detalle":
               "**Borrador de correo**\n\nPara: Ricardo Fuentes\n\nHola Ricardo, te propongo cita el jueves.", "recordar": None}
    out = {"type": "result", "subtype": "success", "is_error": False, "duration_ms": 200, "duration_api_ms": 150,
           "result": json.dumps(rep, ensure_ascii=False), "session_id": "fake-session-0001"}
    log("end")
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
