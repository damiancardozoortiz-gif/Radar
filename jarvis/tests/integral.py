#!/usr/bin/env python3
"""Prueba integral de JARVIS con claude REAL, arrancado con ./jarvis.sh como lo usarías tú.

Cada punto sale como PASA, FALLA o PENDIENTE (lo que no se puede comprobar en esta máquina).
- Voz: si api.elevenlabs.io responde y hay ELEVENLABS_API_KEY, prueba la voz REAL (texto→voz→texto).
  Si no, prueba la cadena con el ElevenLabs simulado y marca la voz real como PENDIENTE.
- Usa estado/ y memory/ temporales, un puerto libre y no envía ni publica nada.

Uso: python3 tests/integral.py        (unos 3–4 minutos; usa tu suscripción de Claude)
"""
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TMP = Path(tempfile.mkdtemp(prefix="jarvis-integral-"))
R = []


def mark(n, status, msg):
    R.append((n, status, msg))
    print(f"{status:9} {n:>2}. {msg}", flush=True)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


PORT = free_port()
URL = f"http://localhost:{PORT}"
ENV = dict(os.environ, JARVIS_PORT=str(PORT), JARVIS_STATE_DIR=str(TMP / "estado"), JARVIS_MEMORY_DIR=str(TMP / "memory"))


def http(method, path, body=None, timeout=90, ctype="application/json"):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(URL + path, data=data, method=method, headers={"Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def j(method, path, body=None, **kw):
    code, raw = http(method, path, body, **kw)
    try:
        return code, json.loads(raw)
    except ValueError:
        return code, raw


def ask(text, conv="int"):
    t = time.time()
    code, o = j("POST", "/api/ask", {"text": text, "conv": conv})
    return o, time.time() - t


def sh(*args):
    return subprocess.run(["bash", str(ROOT / "jarvis.sh"), *args], cwd=str(ROOT), env=ENV, capture_output=True, text=True, timeout=120)


def wait_brain(timeout=60):
    t = time.time()
    while time.time() - t < timeout:
        try:
            st = j("GET", "/api/status")[1]
            if st["brain"]["brain"] in ("listo", "no disponible", "en pausa"):
                return st
        except (OSError, KeyError, TypeError):
            pass
        time.sleep(0.3)
    return None


def server_pid():
    out = subprocess.run(["pgrep", "-f", f"agent/main.py"], capture_output=True, text=True).stdout.split()
    for pid in out:
        try:
            env = Path(f"/proc/{pid}/environ").read_bytes()
            if f"JARVIS_PORT={PORT}".encode() in env:
                return int(pid)
        except OSError:
            pass
    return None


def elevenlabs_reachable():
    try:
        urllib.request.urlopen("https://api.elevenlabs.io/v1/models", timeout=8)
        return True
    except urllib.error.HTTPError:
        return True  # responde (aunque sea 401): hay red
    except OSError:
        return False


def main():
    key_file = ROOT / ".env"
    real_key = ""
    if key_file.exists():
        m = re.search(r"^ELEVENLABS_API_KEY=(.*)$", key_file.read_text(), re.M)
        real_key = (m.group(1).strip() if m else "")
    voice_real = bool(real_key) and elevenlabs_reachable()
    mock = None
    if not voice_real:
        mport = free_port()
        mock = subprocess.Popen([sys.executable, str(ROOT / "tests" / "mock_elevenlabs.py"), str(mport), str(TMP / "mock.log")],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        ENV["ELEVENLABS_BASE_URL"] = f"http://127.0.0.1:{mport}"
        time.sleep(0.5)
    try:
        # 1 ---------------------------------------------------------------- inicio
        r = sh("iniciar")
        st = wait_brain()
        mark(1, "PASA" if r.returncode == 0 and st else "FALLA", f"inicio completo con ./jarvis.sh iniciar ({r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr[-200:]})")
        # 2 ---------------------------------------------------------------- claude real
        o, t = ask("Hola, ¿me escuchas?")
        mark(2, "PASA" if o.get("brain") == "claude" else "FALLA", f"conexión con Claude real ({o.get('brain')}, {t:.1f} s): «{o.get('say')}»")
        # 3/4 -------------------------------------------------------------- voz
        if voice_real:
            code, audio = http("POST", "/api/speak", {"text": "Hola, soy JARVIS. Esto es una prueba de voz real."})
            ok_tts = code == 200 and len(audio) > 2000
            mark(4, "PASA" if ok_tts else "FALLA", f"voz REAL de ElevenLabs (HTTP {code}, {len(audio)} bytes de audio)")
            if ok_tts:
                code, o = j("POST", "/api/listen", audio, ctype="audio/mpeg")
                heard = (o.get("text") or "") if isinstance(o, dict) else ""
                mark(3, "PASA" if "prueba" in heard.lower() else "FALLA", f"transcripción REAL con Scribe del audio generado: «{heard}»")
            else:
                mark(3, "FALLA", "no se pudo probar la transcripción real (falló la voz)")
        else:
            code, audio = http("POST", "/api/speak", {"text": "Hola"})
            code2, o = j("POST", "/api/listen", b"\0" * 2048, ctype="audio/webm")
            sim = code == 200 and code2 == 200 and o.get("text")
            why = "sin ELEVENLABS_API_KEY en .env" if not real_key else "api.elevenlabs.io no es accesible desde esta máquina"
            mark(3, "PENDIENTE", f"reconocimiento de voz REAL ({why}). Cadena con ElevenLabs simulado: {'funciona' if sim else 'FALLA'}")
            mark(4, "PENDIENTE", f"respuesta por voz REAL ({why}). Cadena simulada: {'funciona' if code == 200 else 'FALLA'}")
        # 5 ---------------------------------------------------------------- conversación seguida
        times, ctx_ok = [], False
        qs = ["¿Qué pólizas tiene Logística Delta?", "¿y la segunda?", "¿Qué tengo hoy?", "¿Quién me ha escrito?",
              "¿Cómo va la semana?", "Planéame el día", "¿Qué opinas de subir precios?", "¿por qué?"]
        for i, q in enumerate(qs):
            o, t = ask(q, conv="seguida")
            times.append((q, t, o.get("brain")))
            if i == 1:
                ctx_ok = bool(re.search(r"1173|vida|segunda", o.get("say", ""), re.I))
            time.sleep(3)  # como en una conversación por voz: JARVIS habla entre turno y turno
        all_claude = all(b == "claude" for _, _, b in times)
        mark(5, "PASA" if all_claude and ctx_ok else "FALLA",
             f"{len(qs)} turnos seguidos con Claude real, contexto «¿y la segunda?» {'entendido' if ctx_ok else 'NO entendido'} · "
             f"tiempos {', '.join(f'{t:.1f}' for _, t, _ in times)} s")
        # 6 ---------------------------------------------------------------- persistencia (memoria y reinicio)
        o, _ = ask("Recuerda que la revisión de flota de Logística Delta es el 15 de junio", conv="mem")
        saved = o.get("saved")
        sh("reiniciar"); wait_brain()
        o2, _ = ask("¿Cuándo es la revisión de flota de Logística Delta?", conv="mem2")
        mark(6, "PASA" if saved and "15" in o2.get("say", "") and "junio" in o2.get("say", "").lower() else "FALLA",
             f"memoria guardada ({saved and saved['file']}) y recordada tras reiniciar: «{o2.get('say')}»")
        # 7 ---------------------------------------------------------------- pausa durante llamada real
        mem_before = sorted(p.name for p in (TMP / "memory").glob("*.md"))
        res = {}
        th = threading.Thread(target=lambda: res.update(r=ask("Recuerda que esto NO debe guardarse porque llega la pausa", conv="pz")))
        th.start(); time.sleep(0.8)
        pid = server_pid()
        kids_before = subprocess.run(["pgrep", "-P", str(pid)], capture_output=True, text=True).stdout.split() if pid else []
        t0 = time.time(); code, st = j("POST", "/api/pause", {"on": True}); pause_s = time.time() - t0
        th.join(60)
        time.sleep(4)
        kids_after = subprocess.run(["pgrep", "-P", str(pid)], capture_output=True, text=True).stdout.split() if pid else []
        mem_after = sorted(p.name for p in (TMP / "memory").glob("*.md"))
        ok7 = pause_s < 3 and res["r"][0].get("paused") and mem_after == mem_before and not kids_after
        mark(7, "PASA" if ok7 else "FALLA", f"pausa durante una llamada real: respondió en {pause_s*1000:.0f} ms · procesos claude {len(kids_before)}→{len(kids_after)} · "
                                           f"respuesta en pausa={res['r'][0].get('paused')} · memoria nueva: {len(mem_after) - len(mem_before)}")
        # 8 ---------------------------------------------------------------- reanudación autorizada
        o, _ = ask("quita la pausa", conv="pz")
        still = j("GET", "/api/status")[1]["pause"]["on"]
        j("POST", "/api/pause", {"on": False}); wait_brain()
        o2, t = ask("Hola de nuevo", conv="pz")
        mark(8, "PASA" if still and o2.get("brain") == "claude" else "FALLA",
             f"en pausa, pedirlo hablando no la quita (sigue={still}); con el botón se reanuda y responde Claude en {t:.1f} s")
        # 9 ---------------------------------------------------------------- recuperación tras errores
        pid = server_pid()
        for k in subprocess.run(["pgrep", "-P", str(pid)], capture_output=True, text=True).stdout.split():
            os.kill(int(k), signal.SIGKILL)  # matamos los claude a mano
        time.sleep(0.5)
        a, _ = ask("Hola tras el fallo", conv="pz")
        if a.get("brain") != "claude":
            a, _ = ask("Hola tras el fallo", conv="pz")
        old_base = ENV.get("ELEVENLABS_BASE_URL")
        code_v, _ = http("POST", "/api/speak", {"text": "hola"}) if not voice_real else (None, None)
        os.kill(server_pid(), signal.SIGKILL)  # el servidor se cae de golpe: el supervisor lo levanta
        time.sleep(6); st = wait_brain()
        b, _ = ask("¿Sigues ahí?", conv="pz")
        mark(9, "PASA" if a.get("brain") == "claude" and b.get("brain") == "claude" and st else "FALLA",
             f"tras matar los procesos claude responde Claude ({a.get('brain')}); tras matar el servidor, el supervisor lo levanta y responde ({b.get('brain')})")
        # 10/11/14 se cubren con navegador.js, estabilidad_*.sh/py y ejecutar_todo.sh (ver README)
        # 12 --------------------------------------------------------------- reinicio con pausa y borradores
        o, _ = ask("Escribe un borrador de correo a Ricardo Fuentes para darle cita el jueves", conv="br")
        aid = (o.get("approval") or {}).get("id")
        j("POST", "/api/pause", {"on": True})
        sh("reiniciar"); st = wait_brain()
        pend = [a["id"] for a in st["approvals"]] if st else []
        mark(12, "PASA" if st and st["pause"]["on"] and aid in pend and st["brain"]["brain"] == "en pausa" else "FALLA",
             f"tras ./jarvis.sh reiniciar: pausa={st and st['pause']['on']} · borrador {aid} pendiente={aid in pend} · cerebro={st and st['brain']['brain']} (sin llamar a Claude)")
        j("POST", "/api/pause", {"on": False})
        # 13 --------------------------------------------------------------- credenciales
        problems = []
        page = b"".join(http("GET", p)[1] for p in ["/", "/app.js", "/api/status", "/api/graph", "/api/memory"])
        if real_key and real_key.encode() in page:
            problems.append("la clave llega al navegador")
        logf = TMP / "estado" / "jarvis.log"
        if real_key and logf.exists() and real_key in logf.read_text():
            problems.append("la clave está en el registro")
        if key_file.exists() and oct(key_file.stat().st_mode & 0o777) != "0o600":
            problems.append(f".env con permisos {oct(key_file.stat().st_mode & 0o777)}")
        tracked = subprocess.run(["git", "ls-files", ".env"], cwd=str(ROOT), capture_output=True, text=True).stdout.strip()
        if tracked:
            problems.append(".env está en git")
        ask("Hola", conv="cred")
        pid = server_pid()
        for k in subprocess.run(["pgrep", "-P", str(pid)], capture_output=True, text=True).stdout.split():
            env = Path(f"/proc/{k}/environ").read_bytes()
            if b"ELEVENLABS_API_KEY=" in env or b"ANTHROPIC_API_KEY=" in env:
                problems.append(f"el proceso claude {k} recibe una clave en su entorno")
        mark(13, "PASA" if not problems else "FALLA", "credenciales: " + ("clave fuera del navegador, de los registros, de git y del entorno de claude; .env 600"
                                                                       if not problems else "; ".join(problems)))
    finally:
        sh("parar")
        if mock:
            mock.kill()
        time.sleep(1)
        left = subprocess.run(["pgrep", "-f", "input-format stream-json"], capture_output=True, text=True).stdout.split()
        mark(0, "PASA" if not left else "FALLA", f"apagado limpio con ./jarvis.sh parar: procesos claude que quedan: {len(left)}")
        shutil.rmtree(TMP, ignore_errors=True)
    fails = [x for x in R if x[1] == "FALLA"]
    pend = [x for x in R if x[1] == "PENDIENTE"]
    print(f"\nRESUMEN: {sum(1 for x in R if x[1] == 'PASA')} PASA · {len(fails)} FALLA · {len(pend)} PENDIENTE")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
