"""El cerebro: la llamada a `claude -p`, la sesión, el calentamiento y el respaldo.

- Usa Claude Code en modo no interactivo con TU suscripción (sin ANTHROPIC_API_KEY).
- Las herramientas ya se ejecutaron en el servidor: Claude solo lee y responde.
- Modelo y esfuerzo fijos en el comando: no depende de tus ajustes de Claude Code.
- Si Claude no responde a tiempo -> modo respaldo, avisado en pantalla.
"""
import json
import os
import re
import shutil
import signal
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

import data
import estado
import memory
import sesiones
import tools
from vault import VAULT, norm

ROOT = Path(__file__).resolve().parent.parent
PROMPT = Path(__file__).resolve().parent / "prompt.md"
CLAUDE_MD = ROOT / "CLAUDE.md"

MODEL = "sonnet"
EFFORT = "low"
TIMEOUT = 25
TIMEOUT_WEB = 60
MAX_TURNS = 10
ROTATE_TURNS = 20  # cada 20 turnos, proceso nuevo con los últimos 10 como texto: el contexto no crece sin fin
MAX_SAY_WORDS = 25
DRAFT_RE = re.compile(r"borrador", re.I)

STATUS = {"brain": "arrancando", "detail": "", "warm_ms": None, "last_ms": None, "claude": None, "calls": 0,
          "cancelled": 0, "last_wall_ms": None, "last_api_ms": None, "mode": "persistente", "sessions": 0}

# ---- PAUSA: interruptor de emergencia (estado persistente en estado.py).
# Mientras está activa no se llama a Claude, no se ejecutan herramientas, no se escribe memoria
# ni borradores y la voz queda bloqueada. Activarla mata las llamadas a Claude en curso, y una
# respuesta que llegue tarde se descarta sin producir ningún efecto.
PAUSE = estado.PAUSE
RUNNING = set()            # procesos claude en curso (para poder matarlos)
_running_lock = threading.Lock()
PAUSE_WORDS = {"pausa", "jarvis pausa", "pausa jarvis", "para", "jarvis para", "detente", "stop", "alto"}

# ---- Autorizaciones humanas: cada borrador espera tu sí, que solo se da con un clic (persistentes en estado.py).
APPROVALS = estado.APPROVALS
YES_WORDS = {"si", "sí", "vale", "adelante", "envialo", "envíalo", "mandalo", "mándalo", "apruebo", "aprobado", "ok",
             "dale", "hazlo", "si envialo", "sí, envíalo", "si mandalo", "correcto"}
_convs = {}
_convs_lock = threading.Lock()


# ------------------------------------------------------------------ prompt de sistema
DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]


def system_prompt():
    today = data.today()
    parts = [PROMPT.read_text(encoding="utf-8")]
    if CLAUDE_MD.exists():
        parts.append("## Quién es (CLAUDE.md)\n" + CLAUDE_MD.read_text(encoding="utf-8"))
    parts.append("## Lo que te ha pedido recordar (memory/)\n" + memory.as_prompt())
    parts.append(f"## Fecha\nHoy es {DIAS[today.weekday()]} {today.isoformat()}."
                 + (" (Modo demo: datos inventados; 'hoy' es una fecha fija.)" if data.mode() == "demo" else ""))
    return "\n\n".join(parts)


# ------------------------------------------------------------------ llamada a claude -p
class BrainError(Exception):
    pass


def _env():
    env = dict(os.environ)
    # Sin clave de API: usa la suscripción y no cobra aparte. La clave de voz tampoco se le pasa.
    for k in ("ANTHROPIC_API_KEY", "ELEVENLABS_API_KEY"):
        env.pop(k, None)
    return env


def claude_exe():
    # JARVIS_CLAUDE_BIN solo sirve para las pruebas automáticas (un claude simulado); por defecto, `claude`.
    return os.environ.get("JARVIS_CLAUDE_BIN") or shutil.which("claude")


def _kill(p):
    """Mata el proceso claude y sus hijos."""
    try:
        os.killpg(p.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        pass
    try:
        p.wait(timeout=1.5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            pass


def cancel_running():
    """La PAUSA llama a esto: corta todas las llamadas a Claude en curso y cierra los procesos persistentes."""
    busy = sesiones.busy_count()
    with _running_lock:
        procs = list(RUNNING)
    for p in procs:
        p.jarvis_cancelled = True
        _kill(p)
    sesiones.kill_all()
    STATUS["cancelled"] += len(procs) + busy
    return len(procs) + busy


def stream_cmd():
    """Las mismas opciones de seguridad que la llamada normal, en modo conversación persistente."""
    return [claude_exe(), "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages", "--model", MODEL, "--effort", EFFORT, "--safe-mode", "--tools", ""]


def _prewarm_quiet():
    try:
        sesiones.prewarm(stream_cmd(), system_prompt(), _env(), str(ROOT),
                         'Calentamiento. Responde solo {"decir": "listo", "detalle": "", "recordar": null}')
    except Exception:
        pass


def brain_ask(cid, c, message, web=False):
    """Pregunta a Claude. Normal: proceso persistente de la conversación. Web: llamada aparte con WebSearch/WebFetch."""
    if web:
        raw = call_claude(message, web=True, session=c["session"])
        sesiones.mark_stale(cid)  # el proceso persistente no vio este turno: se reabre retomando la sesión
        return raw
    if not claude_exe():
        raise BrainError("no encuentro el comando claude")
    if c["turns"] >= ROTATE_TURNS:  # rotación: sesión nueva; compose() ya incluye los últimos 10 turnos
        sesiones.drop(cid)
        c["session"], c["turns"] = None, 0
        message = compose_rotated(c, message)
    t0 = time.perf_counter()
    with estado.LOCK:  # pausa comprobada y mensaje enviado bajo el mismo candado que set_pause
        if estado.paused():
            raise estado.Paused()
        try:
            had_spare = sesiones.SPARE["s"] is not None
            s = sesiones.get(cid, stream_cmd(), system_prompt(), _env(), str(ROOT), resume=c["session"])
            turn = s.send(message)
            if had_spare and sesiones.SPARE["s"] is None:  # se usó el precalentado: preparar otro
                threading.Thread(target=_prewarm_quiet, daemon=True).start()
        except (sesiones.SessionError, OSError) as e:
            sesiones.drop(cid, kill=True)
            raise BrainError(str(e))
        STATUS["calls"] += 1
    try:
        raw = s.wait_reply(turn, TIMEOUT)
    except sesiones.SessionError as e:
        sesiones.drop(cid, kill=True)  # el siguiente turno abre otro proceso retomando la sesión
        raise BrainError(str(e))
    if s.cancelled or estado.paused():
        raise estado.Paused("cancelado por la pausa")
    STATUS["last_wall_ms"] = int((time.perf_counter() - t0) * 1000)
    STATUS["sessions"] = sesiones.count()
    c["turns"] += 1
    return raw


def compose_rotated(c, message):
    if not c["history"]:
        return message
    hist = "\n".join(f"- Usuario: {h['q']}\n  JARVIS: {h['a']}" for h in c["history"])
    return f"Conversación hasta ahora (últimos {len(c['history'])} turnos):\n{hist}\n\n{message}"


def call_claude(message, web=False, session=None, timeout=None):
    exe = claude_exe()
    if not exe:
        raise BrainError("no encuentro el comando claude")
    cmd = [exe, "-p", message, "--output-format", "json", "--model", MODEL, "--effort", EFFORT,
           "--safe-mode", "--system-prompt", system_prompt(),
           "--tools", "WebSearch,WebFetch" if web else ""]
    if web:
        cmd += ["--allowedTools", "WebSearch,WebFetch"]
    if session:
        cmd += ["--resume", session]
    limit = timeout or (TIMEOUT_WEB if web else TIMEOUT)
    # Comprobar la pausa y lanzar el proceso bajo el mismo candado que set_pause:
    # o el proceso existe antes de la pausa (y la pausa lo mata) o la pausa ya está y no se lanza.
    with estado.LOCK:
        if estado.paused():
            raise estado.Paused("JARVIS está en pausa")
        t0 = time.perf_counter()
        p = subprocess.Popen(cmd, cwd=str(ROOT), env=_env(), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, text=True, start_new_session=True)
        p.jarvis_cancelled = False
        with _running_lock:
            RUNNING.add(p)
        STATUS["calls"] += 1
    try:
        stdout, stderr = p.communicate(timeout=limit)
    except subprocess.TimeoutExpired:
        _kill(p)
        p.communicate()
        raise BrainError(f"no respondió en {limit} s")
    finally:
        with _running_lock:
            RUNNING.discard(p)
    if p.jarvis_cancelled or estado.paused():
        raise estado.Paused("cancelado por la pausa")
    STATUS["last_wall_ms"] = int((time.perf_counter() - t0) * 1000)
    try:
        out = json.loads(stdout)
    except json.JSONDecodeError:
        raise BrainError((stderr or stdout or f"salida vacía (código {p.returncode})").strip()[:200])
    if out.get("is_error") or out.get("subtype", "success") != "success":
        raise BrainError(str(out.get("result") or out.get("subtype") or "error")[:200])
    STATUS["last_api_ms"] = out.get("duration_api_ms")
    return out


def parse_reply(text):
    """Extrae y valida el objeto JSON {"decir","detalle","recordar"} del texto de Claude."""
    t = (text or "").strip()
    t = re.sub(r"^```(?:json)?|```$", "", t, flags=re.M).strip()
    obj = None
    i, j = t.find("{"), t.rfind("}")
    if i >= 0 and j > i:
        try:
            obj = json.loads(t[i:j + 1])
        except json.JSONDecodeError:
            obj = None
    if not isinstance(obj, dict) or not isinstance(obj.get("decir"), str):
        # Sin JSON válido: el texto entero se trata como lo que se dice
        return {"decir": t, "detalle": "", "recordar": None, "formato": False}
    det = obj.get("detalle") or ""
    if not isinstance(det, str):
        det = json.dumps(det, ensure_ascii=False)
    rec = obj.get("recordar")
    rec = rec.strip() if isinstance(rec, str) and rec.strip() else None
    return {"decir": obj["decir"], "detalle": det, "recordar": rec, "formato": True}


def clean_speech(s):
    """Lo que se lee en voz alta va limpio: sin markdown, listas ni símbolos."""
    s = re.sub(r"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]", lambda m: m.group(2) or m.group(1), s)
    s = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", s)
    s = re.sub(r"https?://\S+", "", s)
    s = re.sub(r"(?m)^\s*([-*•]|\d+\.)\s+", "", s)
    s = re.sub(r"(\d)\s*€", r"\1 euros", s).replace("€", " euros")
    s = re.sub(r"(\d)\s*%", r"\1 por ciento", s).replace("%", " por ciento")
    s = re.sub(r"\bPOL-(\d+)", r"póliza \1", s)
    s = re.sub(r"[*_#`>|~^{}\[\]<>\\]", "", s)
    s = s.replace("&", " y ").replace("/", " ")
    s = re.sub(r"\.(md|markdown|txt|pdf)\b", "", s)
    s = re.sub(r"\s+", " ", s).strip()
    words = s.split()
    if len(words) > MAX_SAY_WORDS:
        cut = " ".join(words[:MAX_SAY_WORDS]) + " "
        k = max(cut.rfind(". "), cut.rfind("? "), cut.rfind("! "))
        if k > 25:
            s = cut[:k + 1]
        else:  # si no hay fin de frase, corta en la última coma o punto y coma
            k = max(cut.rfind(", "), cut.rfind("; "), cut.rfind(": "))
            s = (cut[:k] if k > 15 else cut.strip()) + "."
    s = re.sub(r"\.{2,}", ".", s).replace(" .", ".")
    return s


# ------------------------------------------------------------------ conversación
def conv(cid):
    with _convs_lock:
        c = _convs.get(cid)
        if not c:
            c = _convs[cid] = {"session": None, "history": deque(maxlen=MAX_TURNS), "lock": threading.Lock(),
                               "turns": 0}
        return c


def compose(question, res, conv_state):
    lines = []
    if not conv_state["session"] and conv_state["history"]:
        lines.append("Conversación hasta ahora:")
        for h in conv_state["history"]:
            lines.append(f"- Usuario: {h['q']}\n  JARVIS: {h['a']}")
        lines.append("")
    lines.append(f"Mensaje: {question}")
    tool = res["tool"]
    if tool == "conversacion":
        lines.append("\nHerramienta: ninguna. Es conversación: responde como persona. \"detalle\" vacío salvo que sea un borrador o haga falta de verdad.")
    else:
        lines.append(f"\nHerramienta que ya ejecutó el servidor: {tool}")
        if res.get("context"):
            lines.append(res["context"])
        if res.get("card"):
            lines.append("\nLa herramienta YA pinta su tarjeta en pantalla: deja \"detalle\" vacío salvo que sea un borrador.")
    lines.append('\nResponde solo con el JSON {"decir": ..., "detalle": ..., "recordar": ...}.')
    return "\n".join(lines)


def set_pause(on, by="botón"):
    """Prioridad máxima: no espera a ninguna conversación. Activa la pausa (persistente) y corta lo que esté en marcha."""
    st = estado.set_pause(on, by)
    if on:
        cancel_running()
        STATUS["brain"], STATUS["detail"] = "en pausa", ""
    elif STATUS["brain"] == "en pausa":
        warmup()
    return st


def is_pause_command(text):
    return re.sub(r"[^a-zñ ]+", "", norm(text)).strip() in PAUSE_WORDS


PAUSED_SAY = "Estoy en pausa. Quita la pausa en pantalla para seguir."


def _paused_reply(cid, question, say=PAUSED_SAY):
    return _short(cid, question, say, "pausa", {"paused": True, "brain": "pausa"})


def _short(cid, question, say, tool, extra=None):
    out = {"tool": tool, "why": tool, "card": None, "brain": "servidor", "warning": None, "say": say,
           "detail": "", "saved": None, "ms": 0}
    out.update(extra or {})
    return out


def pending_for(cid):
    return estado.pending(cid)


def decide(aid, decision):
    # Aprobar NUNCA envía: JARVIS no tiene ninguna conexión para enviar. Lo envías tú.
    return estado.decide(aid, decision)


def answer(cid, question, forced_tool=None):
    try:
        return _answer(cid, question, forced_tool)
    except estado.Paused:
        # La pausa llegó en mitad: se descarta todo lo que hubiera producido la respuesta.
        return _paused_reply(cid, question)


def _answer(cid, question, forced_tool=None):
    t0 = time.perf_counter()
    if estado.paused():
        return _paused_reply(cid, question)
    if is_pause_command(question):
        set_pause(True, by="voz")
        return _short(cid, question, "En pausa. No hago nada hasta que la quites en pantalla.", "pausa",
                      {"paused": True, "brain": "pausa"})
    c = conv(cid)
    pend = pending_for(cid)
    if pend and re.sub(r"[^a-zñ ,]+", "", norm(question)).strip(" ,") in {norm(w) for w in YES_WORDS}:
        return _short(cid, question, "Para aprobar el borrador, pulsa Aprobar en pantalla. Yo no envío nada.",
                      "autorizacion", {"approval": pend[-1]})
    with c["lock"]:
        if estado.paused():
            raise estado.Paused()
        if forced_tool:
            tool, why = forced_tool, "botón"
        else:
            tool, why = tools.route(question)
        res = tools.run(tool, question)
        web = tool == "investigar_en_la_web"
        out = {"tool": tool, "why": why, "card": res.get("card"), "brain": "claude", "warning": None}
        try:
            raw = brain_ask(cid, c, compose(question, res, c), web=web)
            if estado.paused():  # respuesta tardía: la pausa llegó mientras pensaba
                raise estado.Paused()
            c["session"] = raw.get("session_id") or c["session"]
            rep = parse_reply(raw.get("result", ""))
            if not rep["formato"] and re.search(r"<\s*/?\s*(invoke|parameter|function_calls|tool_use)", rep["decir"]):
                raise BrainError("respuesta inválida (marcado de herramienta): no se lee en voz alta")
            say = clean_speech(rep["decir"])
            saved = memory.save(rep["recordar"]) if rep["recordar"] else None  # lanza Paused si hay pausa
            if saved and norm(saved["text"])[:40] not in norm(say):
                say = f"He guardado: {saved['text']}"
            det = rep["detalle"].strip()
            if det and norm(det) == norm(say):
                det = ""
            out.update(say=say, detail=det, saved=saved, api_ms=raw.get("duration_api_ms"),
                       formato=rep["formato"])
            if det and DRAFT_RE.search(det + " " + rep["decir"]):
                out["approval"] = estado.add_draft(cid, det, question)  # lanza Paused si hay pausa
            if not rep["formato"]:
                out["warning"] = "Claude no devolvió el formato pedido; muestro su texto tal cual."
        except BrainError as e:
            STATUS["brain"], STATUS["detail"] = "respaldo", str(e)
            out.update(fallback(question, tool, res))
            out["warning"] = f"Cerebro no disponible ({e}). Modo respaldo: esto NO lo ha dicho Claude."
        else:
            STATUS["brain"], STATUS["detail"] = "listo", ""
        if estado.paused():  # última comprobación: nada sale (ni se dice) si la pausa ya está
            raise estado.Paused()
        ms = int((time.perf_counter() - t0) * 1000)
        out["ms"] = ms
        STATUS["last_ms"] = ms
        c["history"].append({"q": question, "a": out["say"]})
        return out


def fallback(question, tool, res):
    """Sin Claude: decide entre conversar y buscar comparando con tus archivos. Nunca se hace pasar por Claude."""
    o = {"brain": "respaldo", "detail": "", "saved": None}
    if tool == "recordar":
        txt = re.sub(r"^\W*(oye\W+|jarvis\W+)*(recuerda(me)?|apunta|anota|guarda|no olvides|memoriza)\s+(que\s+)?",
                     "", question.strip(), flags=re.I).strip(" .?!¿¡")
        saved = memory.save(txt, source="respaldo") if txt else None  # lanza Paused si hay pausa
        o["saved"] = saved
        o["say"] = f"Sin cerebro. He guardado: {saved['text']}" if saved else "Sin cerebro. No he guardado nada."
        return o
    if tool in ("parte_del_dia", "planear_el_dia", "leer_correo"):
        o["say"] = "Sin cerebro ahora mismo. Te dejo los datos en pantalla."
        return o
    if tool == "investigar_en_la_web":
        o["say"] = "Sin cerebro no puedo buscar en internet."
        return o
    hits = VAULT.search(question, k=4)
    if hits and hits[0][0] >= 2.5:
        o["card"] = tools.buscar_en_mi_cerebro(question)["card"]
        o["tool"] = "buscar_en_mi_cerebro"
        o["say"] = f"Sin cerebro. Lo más parecido en tus archivos es {hits[0][1]['title']}."
    else:
        o["say"] = "Sin cerebro ahora mismo. Pruébalo en un momento."
    return o


def reset(cid):
    with _convs_lock:
        _convs.pop(cid, None)
    sesiones.drop(cid)


# ------------------------------------------------------------------ calentamiento
def warmup():
    if estado.paused():  # en pausa no se llama a Claude ni para calentar
        STATUS["brain"], STATUS["detail"] = "en pausa", ""
        return

    def run():
        STATUS["brain"] = "calentando"
        STATUS["claude"] = claude_exe()
        t0 = time.perf_counter()
        try:
            if not claude_exe():
                raise BrainError("no encuentro el comando claude")
            sesiones.prewarm(stream_cmd(), system_prompt(), _env(), str(ROOT),
                             'Calentamiento. Responde solo {"decir": "listo", "detalle": "", "recordar": null}')
            STATUS["sessions"] = sesiones.count()
            STATUS["brain"], STATUS["detail"] = "listo", ""
            STATUS["warm_ms"] = int((time.perf_counter() - t0) * 1000)
            print(f"  cerebro listo · calentamiento {STATUS['warm_ms']} ms", flush=True)
        except estado.Paused:
            STATUS["brain"], STATUS["detail"] = "en pausa", ""
        except (BrainError, sesiones.SessionError, OSError) as e:
            STATUS["brain"], STATUS["detail"] = "no disponible", str(e)
            print(f"  cerebro NO disponible: {e}", flush=True)
    threading.Thread(target=run, daemon=True).start()
