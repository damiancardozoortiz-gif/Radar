"""El agente: JARVIS haciendo una tarea solo, de principio a fin.

Lanza Claude Code sin interfaz (`claude -p`, con TU suscripción) y le da como únicas herramientas las manos de
JARVIS (acciones.py) a través del puente. Claude no tiene terminal, ni archivos, ni ninguna otra herramienta:
solo puede pedir acciones al servidor, y el servidor decide (PAUSA, límites, freno).

Una tarea cada vez. Todo lo que pasa se publica como eventos que la pantalla pinta en directo.
"""
import json
import os
import secrets
import subprocess
import sys
import tempfile
import threading
import time
from collections import deque
from pathlib import Path

import acciones
import cerebro
import data
import memory
from freno import FRENO

ROOT = Path(__file__).resolve().parent.parent
PROMPT = Path(__file__).resolve().parent / "agente_prompt.md"
PUENTE = Path(__file__).resolve().parent / "mcp_puente.py"
MODELO = os.environ.get("JARVIS_AGENTE_MODELO", "sonnet")
ESFUERZO = os.environ.get("JARVIS_AGENTE_ESFUERZO", "medium")
TIEMPO_MAX = int(os.environ.get("JARVIS_AGENTE_MINUTOS", "20")) * 60
CLAVE_PUENTE = secrets.token_urlsafe(32)          # nueva en cada arranque; solo la conoce el puente

# ------------------------------------------------------------------ eventos para la pantalla
_eventos = deque(maxlen=1500)
_cond = threading.Condition()
_seq = 0


def emitir(tipo, **datos):
    global _seq
    with _cond:
        _seq += 1
        _eventos.append({"seq": _seq, "hora": time.strftime("%H:%M:%S"), "tipo": tipo, **datos})
        _cond.notify_all()


def eventos_desde(desde, espera=25.0):
    """Los eventos posteriores a `desde`. Si no hay, espera hasta `espera` segundos a que llegue alguno."""
    fin = time.time() + espera
    with _cond:
        while True:
            nuevos = [e for e in _eventos if e["seq"] > desde]
            if nuevos or time.time() >= fin:
                return nuevos, _seq
            _cond.wait(max(0.05, fin - time.time()))


# ------------------------------------------------------------------ tareas
ACTUAL = {"tarea": None}
HISTORIAL = deque(maxlen=12)
_candado = threading.Lock()
_n = 0


def publica(t):
    return None if not t else {k: t[k] for k in ("id", "texto", "estado", "inicio", "fin", "decir", "detalle", "pasos", "ms")}


def en_curso():
    t = ACTUAL["tarea"]
    return t if t and t["estado"] == "en curso" else None


def prompt_sistema():
    hoy = data.today()
    partes = [PROMPT.read_text(encoding="utf-8")]
    if cerebro.CLAUDE_MD.exists():
        partes.append("## Quién es la persona (CLAUDE.md)\n" + cerebro.CLAUDE_MD.read_text(encoding="utf-8"))
    partes.append("## Lo que te ha pedido recordar (memory/)\n" + memory.as_prompt())
    partes.append(f"## Fecha\nHoy es {cerebro.DIAS[hoy.weekday()]} {hoy.isoformat()}, {time.strftime('%H:%M')}."
                  + (" (Modo demo: los archivos son inventados y 'hoy' es una fecha fija.)" if data.mode() == "demo" else ""))
    if HISTORIAL:
        partes.append("## Tareas anteriores de esta sesión (por si esta continúa alguna)\n" + "\n".join(
            f"- «{h['texto'][:160]}» → {h['estado']}: {(h['decir'] or '')[:200]}" for h in HISTORIAL))
    return "\n\n".join(partes)


def lanzar(texto, conv="anon", puerto=8790):
    """Arranca una tarea. Devuelve (tarea, None) o (None, motivo por el que no se puede)."""
    global _n
    texto = " ".join(str(texto).split())[:2000]
    if not texto:
        return None, "tarea vacía"
    if cerebro.PAUSE["on"]:
        return None, "JARVIS está en pausa"
    exe = cerebro.shutil.which("claude")
    if not exe:
        return None, "no encuentro el comando claude"
    with _candado:
        if en_curso():
            return None, "ya estoy con otra tarea"
        _n += 1
        t = ACTUAL["tarea"] = {"id": f"t{_n}", "texto": texto, "conv": conv, "estado": "en curso",
                               "inicio": time.strftime("%H:%M:%S"), "t0": time.time(), "fin": None, "decir": None,
                               "detalle": None, "pasos": 0, "ms": None, "proc": None, "detenida": None}
    FRENO.nueva_tarea()
    acciones.ESTADO.update(acciones=0, tarea=t["id"])
    emitir("tarea", id=t["id"], texto=texto, estado="en curso")
    acciones.apuntar(accion="tarea", que=texto)
    threading.Thread(target=_correr, args=(t, exe, puerto), daemon=True).start()
    return t, None


def _correr(t, exe, puerto):
    cfg = tempfile.NamedTemporaryFile("w", suffix=".json", prefix="jarvis-puente-", delete=False, encoding="utf-8")
    try:
        os.chmod(cfg.name, 0o600)
        json.dump({"mcpServers": {"jarvis": {"command": sys.executable, "args": [str(PUENTE)], "env": {
            "JARVIS_PUENTE_URL": f"http://127.0.0.1:{puerto}", "JARVIS_PUENTE_CLAVE": CLAVE_PUENTE}}}}, cfg)
        cfg.close()
        permitidas = ",".join("mcp__jarvis__" + h["name"] for h in acciones.HERRAMIENTAS)
        cmd = [exe, "-p", "TAREA: " + t["texto"], "--output-format", "stream-json", "--verbose",
               "--model", MODELO, "--effort", ESFUERZO, "--system-prompt", prompt_sistema(),
               "--tools", "", "--mcp-config", cfg.name, "--strict-mcp-config", "--allowedTools", permitidas,
               "--setting-sources", "", "--disable-slash-commands"]
        proc = t["proc"] = subprocess.Popen(cmd, cwd=str(ROOT), env=cerebro._env(), stdin=subprocess.DEVNULL,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8",
                                            errors="replace")
        threading.Thread(target=_vigilar_tiempo, args=(t,), daemon=True).start()
        errores = []
        threading.Thread(target=lambda: errores.extend(proc.stderr.readlines()[-20:]), daemon=True).start()
        final = None
        for linea in proc.stdout:
            try:
                m = json.loads(linea)
            except ValueError:
                continue
            if m.get("type") == "assistant":
                for bloque in (m.get("message") or {}).get("content") or []:
                    if bloque.get("type") == "text" and bloque.get("text", "").strip() and not bloque["text"].lstrip().startswith("{"):
                        emitir("nota", texto=" ".join(bloque["text"].split())[:240])
            elif m.get("type") == "result":
                final = m
        proc.wait()
        if t["detenida"]:
            _cerrar(t, "detenida", f"Tarea detenida: {t['detenida']}.", "")
        elif final and not final.get("is_error") and final.get("subtype", "success") == "success":
            rep = cerebro.parse_reply(final.get("result", ""))
            _cerrar(t, "terminada", cerebro.clean_speech(rep["decir"]) or "Tarea terminada.", rep["detalle"].strip())
        else:
            motivo = str((final or {}).get("result") or (final or {}).get("subtype") or "".join(errores).strip()[-200:]
                         or f"claude terminó con código {proc.returncode}")[:200]
            _cerrar(t, "fallo", "No pude terminar la tarea.", f"Motivo: {motivo}")
    except Exception as e:  # que se vea en pantalla, nunca en silencio
        _cerrar(t, "fallo", "No pude terminar la tarea.", f"Error interno: {e.__class__.__name__}: {e}")
    finally:
        try:
            os.unlink(cfg.name)
        except OSError:
            pass


def _vigilar_tiempo(t):
    while t["estado"] == "en curso":
        if time.time() - t["t0"] > TIEMPO_MAX:
            detener(f"pasó el límite de {TIEMPO_MAX // 60} minutos")
            return
        time.sleep(2)


def _cerrar(t, estado, decir, detalle):
    FRENO.cancelar()
    t.update(estado=estado, decir=decir, detalle=detalle, fin=time.strftime("%H:%M:%S"),
             pasos=acciones.ESTADO["acciones"], ms=int((time.time() - t["t0"]) * 1000))
    HISTORIAL.append(publica(t))
    cerebro.anotar(t["conv"], f"Tarea autónoma «{t['texto'][:200]}» → {estado}. Resultado: {decir} {detalle[:1200]}")
    acciones.apuntar(accion="fin", que=estado, decir=decir)
    emitir("fin", id=t["id"], estado=estado, decir=decir, detalle=detalle, pasos=t["pasos"], ms=t["ms"])


def detener(motivo="lo pediste tú"):
    """Para la tarea en curso: rechaza lo que esperaba permiso y mata el proceso de Claude."""
    t = en_curso()
    if not t:
        return False
    t["detenida"] = motivo
    FRENO.cancelar()
    proc = t.get("proc")
    if proc and proc.poll() is None:
        try:
            proc.terminate()
        except OSError:
            pass
    return True


acciones.ESTADO["emitir"] = emitir
acciones.ESTADO["en_pausa"] = lambda: cerebro.PAUSE["on"]
FRENO.al_cambiar = lambda: emitir("permiso", pendientes=FRENO.lista())
cerebro.AL_PAUSAR.append(lambda: detener("pausa de emergencia"))
