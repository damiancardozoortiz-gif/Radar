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
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

import data
import memory
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
MAX_SAY_WORDS = 25
DRAFT_RE = re.compile(r"borrador", re.I)

STATUS = {"brain": "arrancando", "detail": "", "warm_ms": None, "last_ms": None, "claude": None}
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


def call_claude(message, web=False, session=None, timeout=None):
    exe = shutil.which("claude")
    if not exe:
        raise BrainError("no encuentro el comando claude")
    cmd = [exe, "-p", message, "--output-format", "json", "--model", MODEL, "--effort", EFFORT,
           "--safe-mode", "--system-prompt", system_prompt(),
           "--tools", "WebSearch,WebFetch" if web else ""]
    if web:
        cmd += ["--allowedTools", "WebSearch,WebFetch"]
    if session:
        cmd += ["--resume", session]
    try:
        p = subprocess.run(cmd, cwd=str(ROOT), env=_env(), stdin=subprocess.DEVNULL, capture_output=True,
                           text=True, timeout=timeout or (TIMEOUT_WEB if web else TIMEOUT))
    except subprocess.TimeoutExpired:
        raise BrainError(f"no respondió en {timeout or (TIMEOUT_WEB if web else TIMEOUT)} s")
    try:
        out = json.loads(p.stdout)
    except json.JSONDecodeError:
        raise BrainError((p.stderr or p.stdout or f"salida vacía (código {p.returncode})").strip()[:200])
    if out.get("is_error") or out.get("subtype", "success") != "success":
        raise BrainError(str(out.get("result") or out.get("subtype") or "error")[:200])
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
            c = _convs[cid] = {"session": None, "history": deque(maxlen=MAX_TURNS), "lock": threading.Lock()}
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


def answer(cid, question, forced_tool=None):
    t0 = time.perf_counter()
    c = conv(cid)
    with c["lock"]:
        if forced_tool:
            tool, why = forced_tool, "botón"
        else:
            tool, why = tools.route(question)
        res = tools.run(tool, question)
        web = tool == "investigar_en_la_web"
        out = {"tool": tool, "why": why, "card": res.get("card"), "brain": "claude", "warning": None}
        try:
            raw = call_claude(compose(question, res, c), web=web, session=c["session"])
            c["session"] = raw.get("session_id") or c["session"]
            rep = parse_reply(raw.get("result", ""))
            say = clean_speech(rep["decir"])
            saved = memory.save(rep["recordar"]) if rep["recordar"] else None
            if saved and norm(saved["text"])[:40] not in norm(say):
                say = f"He guardado: {saved['text']}"
            det = rep["detalle"].strip()
            if det and norm(det) == norm(say):
                det = ""
            out.update(say=say, detail=det, saved=saved, api_ms=raw.get("duration_api_ms"),
                       formato=rep["formato"])
            if not rep["formato"]:
                out["warning"] = "Claude no devolvió el formato pedido; muestro su texto tal cual."
        except BrainError as e:
            STATUS["brain"], STATUS["detail"] = "respaldo", str(e)
            out.update(fallback(question, tool, res))
            out["warning"] = f"Cerebro no disponible ({e}). Modo respaldo: esto NO lo ha dicho Claude."
        else:
            STATUS["brain"], STATUS["detail"] = "listo", ""
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
        saved = memory.save(txt, source="respaldo") if txt else None
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


# ------------------------------------------------------------------ calentamiento
def warmup():
    def run():
        STATUS["brain"] = "calentando"
        STATUS["claude"] = shutil.which("claude")
        t0 = time.perf_counter()
        try:
            raw = call_claude('Calentamiento. Responde solo {"decir": "listo", "detalle": "", "recordar": null}', timeout=40)
            STATUS["brain"], STATUS["detail"] = "listo", ""
            STATUS["warm_ms"] = int((time.perf_counter() - t0) * 1000)
            print(f"  cerebro listo · calentamiento {STATUS['warm_ms']} ms", flush=True)
        except BrainError as e:
            STATUS["brain"], STATUS["detail"] = "no disponible", str(e)
            print(f"  cerebro NO disponible: {e}", flush=True)
    threading.Thread(target=run, daemon=True).start()
