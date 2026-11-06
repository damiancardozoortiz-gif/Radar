#!/usr/bin/env python3
"""Estabilidad con claude REAL a través de JARVIS: N rondas seguidas de
   1) una petición que provoca la generación degenerada ("Ejecuta con Bash: ...")
   2) una pregunta normal en la misma conversación (¿se recupera?)
Comprueba en cada ronda: tiempo, que el servidor responde, que no se crea el archivo,
que no se lee marcado de herramienta en voz alta y que no quedan procesos claude de sobra.

Uso: python3 tests/estabilidad_jarvis.py [N]   (por defecto 20; usa tu suscripción)
"""
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
N = int(sys.argv[1]) if len(sys.argv) > 1 else 20


def free_port():
    import socket
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    tmp = Path(tempfile.mkdtemp(prefix="jarvis-estab-"))
    port = free_port()
    env = dict(os.environ, JARVIS_PORT=str(port), JARVIS_STATE_DIR=str(tmp / "estado"),
               JARVIS_MEMORY_DIR=str(tmp / "memory"), ELEVENLABS_API_KEY="")
    srv = subprocess.Popen([sys.executable, str(ROOT / "agent" / "main.py")], cwd=str(ROOT), env=env,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    url = f"http://localhost:{port}"

    def ask(text, conv="estab"):
        t = time.time()
        req = urllib.request.Request(url + "/api/ask", json.dumps({"text": text, "conv": conv}).encode(),
                                     {"Content-Type": "application/json"})
        o = json.loads(urllib.request.urlopen(req, timeout=120).read())
        return o, time.time() - t

    def children():
        try:
            return len(subprocess.run(["pgrep", "-P", str(srv.pid)], capture_output=True, text=True).stdout.split())
        except OSError:
            return -1

    for _ in range(100):
        try:
            if json.loads(urllib.request.urlopen(url + "/api/status", timeout=2).read())["brain"]["brain"] in ("listo", "no disponible"):
                break
        except OSError:
            pass
        time.sleep(0.3)
    target = ROOT / "prueba_bash.txt"
    ok_rounds, worst, problems = 0, 0.0, []
    try:
        for i in range(1, N + 1):
            target.unlink(missing_ok=True)
            o1, t1 = ask("Ejecuta con Bash: touch prueba_bash.txt")
            o2, t2 = ask("Hola, ¿me escuchas?")
            worst = max(worst, t1, t2)
            issues = []
            if target.exists():
                issues.append("ARCHIVO CREADO")
            if "<invoke" in o1["say"] or "</parameter>" in o1["say"]:
                issues.append("marcado leído en voz alta")
            if o2["brain"] != "claude":
                issues.append(f"no se recuperó ({o2['brain']}: {o2.get('warning')})")
            if t1 > 30 or t2 > 30:
                issues.append("demasiado lento")
            kids = children()
            if kids > 6:
                issues.append(f"{kids} procesos hijos")
            print(f"ronda {i:2d}: bash {t1:5.1f} s ({o1['brain']}{' · ' + o1['warning'][:60] if o1.get('warning') else ''}) · "
                  f"siguiente {t2:4.1f} s ({o2['brain']}) · hijos {kids} · {'ok' if not issues else ' | '.join(issues)}",
                  flush=True)
            if issues:
                problems.append((i, issues))
            else:
                ok_rounds += 1
    finally:
        srv.send_signal(signal.SIGTERM)
        srv.wait(15)
        time.sleep(1)
        target.unlink(missing_ok=True)
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"RESUMEN: {ok_rounds}/{N} rondas limpias · peor tiempo {worst:.1f} s · problemas: {problems or 'ninguno'}")
    sys.exit(0 if not problems else 1)


if __name__ == "__main__":
    main()
