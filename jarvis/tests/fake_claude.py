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


def reply_for(m):
    if "recuerda" in m:
        return {"decir": "He guardado: prueba simulada de memoria.", "detalle": "", "recordar": "prueba simulada de memoria"}
    if "borrador" in m:
        return {"decir": "Borrador listo en pantalla. Espera tu sí.", "detalle":
                "**Borrador de correo**\n\nPara: Ricardo Fuentes\n\nHola Ricardo, te propongo cita el jueves.", "recordar": None}
    return {"decir": "Respuesta simulada.", "detalle": "", "recordar": None}


def user_line(msg):
    # Solo la frase del usuario (la línea "Mensaje: ..."), no las instrucciones del servidor.
    return norm(msg.split("Mensaje:", 1)[1].split("\n", 1)[0]) if "Mensaje:" in msg else ""


def turn(msg, argv, n, stream):
    m = user_line(msg)
    tools = argv[argv.index("--tools") + 1] if "--tools" in argv else "?"
    flags = " ".join(a for a in argv if a.startswith("--"))
    tag = f"turn={os.getpid()}:{n}"
    log("start", f"{tag} tools={tools!r} safe_mode={'--safe-mode' in argv} borrador={'borrador' in m} "
                 f"recuerda={'recuerda' in m} flags={flags}")
    time.sleep(float(os.environ.get("FAKE_CLAUDE_SLEEP", "0.2")))
    if "bucle" in m and stream:  # imita la generación degenerada real: <invoke> falsos sin fin
        for _ in range(100000):
            print(json.dumps({"type": "stream_event", "event": {"type": "content_block_delta", "delta": {
                "type": "text_delta", "text": '<invoke name="Bash">\n<parameter name="command">true</parameter>\n</invoke>\n'}}}),
                flush=True)
            time.sleep(0.01)
    text = json.dumps(reply_for(m), ensure_ascii=False)
    sid = "fake-session-0001"
    if stream:
        print(json.dumps({"type": "assistant", "session_id": sid,
                          "message": {"content": [{"type": "text", "text": text}]}}, ensure_ascii=False), flush=True)
        time.sleep(float(os.environ.get("FAKE_CLAUDE_TAIL", "0.5")))  # imita el resumen interno final
    print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "duration_ms": 200,
                      "duration_api_ms": 150, "result": text, "session_id": sid}, ensure_ascii=False), flush=True)
    log("end", tag)


def main():
    argv = sys.argv[1:]
    if "--input-format" in argv:  # modo conversación persistente (stream-json por la entrada estándar)
        print(json.dumps({"type": "system", "subtype": "init", "session_id": "fake-session-0001"}), flush=True)
        n = 0
        for line in sys.stdin:
            if not line.strip():
                continue
            o = json.loads(line)
            msg = "".join(c.get("text", "") for c in o["message"]["content"])
            n += 1
            turn(msg, argv, n, stream=True)
        return
    msg = argv[argv.index("-p") + 1] if "-p" in argv else ""
    turn(msg, argv, 1, stream=False)


if __name__ == "__main__":
    main()
