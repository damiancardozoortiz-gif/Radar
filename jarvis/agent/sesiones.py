"""Procesos `claude` persistentes: uno por conversación, abiertos entre preguntas.

Por qué: lanzar `claude -p` en cada pregunta cuesta ~1 s de arranque y, cuando la respuesta ya está
lista, `claude` tarda otros 1–3 s en un resumen interno antes de devolverla. Con un proceso abierto
(`--input-format stream-json`) y leyendo el texto en cuanto llega (sin esperar ese resumen),
una respuesta corta baja de ~4–5 s a ~1 s de modelo.

Seguridad:
- Mismas opciones que la llamada normal: --safe-mode, --tools "", modelo y esfuerzo fijos, sin ANTHROPIC_API_KEY.
- La PAUSA mata TODOS los procesos (ocupados y en reposo). Nada se envía a un proceso con la pausa activa.
- Si el servidor muere de golpe, el proceso ve cerrarse su entrada estándar y termina solo (sin huérfanos).
- Los procesos en reposo se cierran a los IDLE_SECONDS; como mucho MAX_SESSIONS a la vez.
"""
import hashlib
import json
import os
import queue
import signal
import subprocess
import threading
import time
from collections import deque

import estado

IDLE_SECONDS = 600
MAX_SESSIONS = 4
# Corte temprano de respuestas degeneradas: con herramientas denegadas, el modelo a veces escribe en bucle
# llamadas falsas "<invoke ...>" como texto (miles de caracteres, minutos). Se corta en cuanto se detecta.
MAX_REPLY_CHARS = 4000
DEGENERATE_MARKERS = ("<invoke", "<function_calls", "</parameter>", "<tool_use")

_lock = threading.Lock()
SESSIONS = {}          # conv -> Session
SPARE = {"s": None}    # proceso precalentado, listo para la próxima conversación
STATS = {"spawned": 0, "killed": 0, "reaped": 0}


class SessionError(Exception):
    pass


def sp_hash(sp):
    return hashlib.sha256(sp.encode()).hexdigest()[:16]


class Session:
    def __init__(self, cmd_base, sp, env, cwd, resume=None):
        self.hash = sp_hash(sp)
        cmd = list(cmd_base) + ["--system-prompt", sp] + (["--resume", resume] if resume else [])
        self.p = subprocess.Popen(cmd, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True, bufsize=1, start_new_session=True)
        self.cancelled = False
        self.q = queue.Queue()
        self.err = deque(maxlen=20)
        self.session_id = resume
        self.partial = {}  # turno -> texto que va llegando
        self.sent = 0      # turnos enviados
        self.done = 0      # turnos terminados (evento "result" leído por el hilo lector)
        self.last_used = time.time()
        self.stale = False
        STATS["spawned"] += 1
        threading.Thread(target=self._read_out, daemon=True).start()
        threading.Thread(target=self._read_err, daemon=True).start()

    def _read_out(self):
        try:
            for line in self.p.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    o = json.loads(line)
                except ValueError:
                    self.err.append(line[:200])
                    continue
                o["_turn"] = self.done + 1  # cada evento, etiquetado con su turno
                if o.get("type") == "result":
                    self.done += 1
                    self.last_used = time.time()
                self.q.put(o)
        except (OSError, ValueError):
            pass
        self.q.put(None)

    def _read_err(self):
        try:
            for line in self.p.stderr:
                self.err.append(line.strip()[:200])
        except (OSError, ValueError):
            pass

    def alive(self):
        return self.p.poll() is None

    def busy(self):
        return self.sent > self.done

    def send(self, text):
        """Llamar con estado.LOCK tomado y la pausa comprobada."""
        msg = {"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}}
        try:
            self.p.stdin.write(json.dumps(msg, ensure_ascii=False) + "\n")
            self.p.stdin.flush()
        except (OSError, ValueError) as e:
            raise SessionError(f"no pude escribir al proceso claude ({e.__class__.__name__})")
        self.sent += 1
        self.last_used = time.time()
        return self.sent

    def wait_reply(self, turn, timeout):
        """Devuelve en cuanto llega el texto de la respuesta (sin esperar el resumen interno de claude)."""
        deadline = time.time() + timeout
        while True:
            if self.cancelled:
                raise estado.Paused("cancelado por la pausa")
            left = deadline - time.time()
            if left <= 0:
                raise SessionError(f"no respondió en {timeout} s")
            try:
                o = self.q.get(timeout=min(left, 0.25))
            except queue.Empty:
                continue
            if o is None:
                if self.cancelled:
                    raise estado.Paused("cancelado por la pausa")
                raise SessionError("el proceso claude terminó: " + (" | ".join(list(self.err)[-2:]) or "sin detalle"))
            if o.get("session_id"):
                self.session_id = o["session_id"]
            if o["_turn"] != turn:
                continue  # final de un turno anterior (p. ej. el resumen interno que no esperamos)
            t = o.get("type")
            if t == "stream_event":
                d = (o.get("event") or {}).get("delta") or {}
                if d.get("type") == "text_delta":
                    acc = self.partial.get(turn, "") + d.get("text", "")
                    self.partial[turn] = acc
                    if len(acc) > MAX_REPLY_CHARS or any(m in acc for m in DEGENERATE_MARKERS):
                        raise SessionError("respuesta degenerada cortada a tiempo "
                                           f"({len(acc)} caracteres, marcado de herramienta: "
                                           f"{any(m in acc for m in DEGENERATE_MARKERS)})")
                continue
            if t == "assistant":
                text = "".join(c.get("text", "") for c in (o.get("message") or {}).get("content", [])
                               if isinstance(c, dict) and c.get("type") == "text")
                if text.strip():
                    self.last_used = time.time()
                    return {"result": text, "session_id": self.session_id, "early": True}
            elif t == "result":
                if o.get("is_error") or o.get("subtype", "success") != "success":
                    raise SessionError(str(o.get("result") or o.get("subtype") or "error")[:200])
                self.last_used = time.time()
                return {"result": o.get("result", ""), "session_id": self.session_id, "early": False,
                        "duration_api_ms": o.get("duration_api_ms")}

    def close(self):
        try:
            self.p.stdin.close()
        except (OSError, ValueError):
            pass
        try:
            self.p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.kill()

    def kill(self):
        self.cancelled = True
        for sig, wait in ((signal.SIGTERM, 1.0), (signal.SIGKILL, 2.0)):
            try:
                os.killpg(self.p.pid, sig)
            except (ProcessLookupError, PermissionError, OSError):
                return
            try:
                self.p.wait(timeout=wait)
                break
            except subprocess.TimeoutExpired:
                continue
        STATS["killed"] += 1


# ------------------------------------------------------------------ gestión
def get(conv, cmd_base, sp, env, cwd, resume=None):
    """El proceso de esta conversación (lo crea o lo reutiliza). Llamar con estado.LOCK y pausa comprobada."""
    with _lock:
        s = SESSIONS.get(conv)
        if s and s.alive() and not s.stale and s.hash == sp_hash(sp):
            return s
        if s:
            resume = resume or s.session_id
            SESSIONS.pop(conv, None)
            threading.Thread(target=s.close, daemon=True).start()
        spare = SPARE["s"]
        if not resume and spare and spare.alive() and spare.hash == sp_hash(sp):
            s, SPARE["s"] = spare, None
        else:
            s = Session(cmd_base, sp, env, cwd, resume=resume)
        SESSIONS[conv] = s
        while len(SESSIONS) > MAX_SESSIONS:  # cierra la menos usada
            old = min(SESSIONS, key=lambda k: SESSIONS[k].last_used)
            threading.Thread(target=SESSIONS.pop(old).close, daemon=True).start()
        return s


def drop(conv, kill=False):
    with _lock:
        s = SESSIONS.pop(conv, None)
    if s:
        (s.kill if kill else s.close)()


def mark_stale(conv):
    with _lock:
        if conv in SESSIONS:
            SESSIONS[conv].stale = True


def kill_all():
    """PAUSA: mata todos los procesos claude persistentes, ocupados o no, EN PARALELO
    (primero SIGTERM a todos, como mucho 0,5 s de espera común, luego SIGKILL a los que queden)."""
    with _lock:
        victims = list(SESSIONS.values()) + ([SPARE["s"]] if SPARE["s"] else [])
        SESSIONS.clear()
        SPARE["s"] = None
    for s in victims:
        s.cancelled = True
        try:
            os.killpg(s.p.pid, signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            pass
    deadline = time.time() + 0.5
    for s in victims:
        try:
            s.p.wait(timeout=max(0.0, deadline - time.time()))
        except subprocess.TimeoutExpired:
            pass
    for s in victims:
        if s.p.poll() is None:
            try:
                os.killpg(s.p.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass
    for s in victims:
        try:
            s.p.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
    STATS["killed"] += len(victims)
    return len(victims)


def busy_count():
    with _lock:
        return sum(1 for s in SESSIONS.values() if s.busy() and s.alive())


def count():
    with _lock:
        return len(SESSIONS) + (1 if SPARE["s"] else 0)


def prewarm(cmd_base, sp, env, cwd, warm_text, timeout=40):
    """Deja un proceso listo y caliente para la próxima conversación. No hace nada en pausa."""
    with estado.LOCK:
        if estado.paused():
            raise estado.Paused()
        with _lock:
            if SPARE["s"] and SPARE["s"].alive():
                return None
        s = Session(cmd_base, sp, env, cwd)
        turn = s.send(warm_text)
        with _lock:
            SPARE["s"] = s
    try:
        return s.wait_reply(turn, timeout)
    except SessionError:
        with _lock:
            if SPARE["s"] is s:
                SPARE["s"] = None
        s.kill()
        raise


def reap():
    """Cierra los procesos en reposo demasiado tiempo."""
    now = time.time()
    with _lock:
        old = [k for k, s in SESSIONS.items() if (not s.busy() and now - s.last_used > IDLE_SECONDS) or not s.alive()]
        victims = [SESSIONS.pop(k) for k in old]
    for s in victims:
        s.close()
        STATS["reaped"] += 1


def start_reaper():
    def loop():
        while True:
            time.sleep(30)
            try:
                reap()
            except Exception:
                pass
    threading.Thread(target=loop, daemon=True).start()


def shutdown():
    with _lock:
        victims = list(SESSIONS.values()) + ([SPARE["s"]] if SPARE["s"] else [])
        SESSIONS.clear()
        SPARE["s"] = None
    for s in victims:
        s.close()
