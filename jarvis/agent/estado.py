"""Estado persistente de JARVIS: la PAUSA y los borradores pendientes de tu sí.

- Se guarda en jarvis/estado/ (fuera de git, carpeta 700 y archivos 600). Nunca en tus carpetas.
- Escritura atómica (archivo temporal + os.replace): un corte a mitad no deja un archivo roto.
- Si un archivo de estado existe pero no se puede leer, JARVIS arranca EN PAUSA (falla cerrado) y lo avisa.
- Candado de escritura: ninguna escritura (memoria, borradores) empieza si la PAUSA está activa,
  y activar la PAUSA espera a que termine la escritura en curso. Así, cuando set_pause(True)
  vuelve, ya no puede producirse ninguna escritura más.
- Recuperar borradores al arrancar NO ejecuta nada: solo vuelven a aparecer como pendientes.
"""
import json
import os
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = Path(os.environ.get("JARVIS_STATE_DIR") or (ROOT / "estado")).resolve()
PAUSE_FILE = STATE_DIR / "pausa.json"
DRAFTS_FILE = STATE_DIR / "borradores.json"
MAX_DECIDED = 200  # historial de borradores ya decididos que se conserva

LOCK = threading.RLock()
PAUSE = {"on": False, "since": None, "by": None}
APPROVALS = {}  # id -> borrador
WARNINGS = []   # avisos de carga (archivos ilegibles, etc.)


class Paused(Exception):
    """Se intentó una operación o escritura con la PAUSA activa."""


# ------------------------------------------------------------------ disco
def _write_json(path, obj):
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(STATE_DIR, 0o700)
    except OSError:
        pass
    tmp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)


def _read_json(path):
    """(contenido, error). Archivo inexistente -> (None, None)."""
    if not path.exists():
        return None, None
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except (OSError, ValueError) as e:
        return None, f"{path.name} ilegible ({e.__class__.__name__})"


def _quarantine(path):
    try:
        path.rename(path.with_name(f"{path.name}.ilegible-{time.strftime('%Y%m%d-%H%M%S')}"))
    except OSError:
        pass


def load():
    """Restaura la PAUSA y los borradores. Nunca ejecuta nada."""
    with LOCK:
        WARNINGS.clear()
        p, err = _read_json(PAUSE_FILE)
        if err:
            WARNINGS.append(err + ": arranco EN PAUSA por seguridad.")
            _quarantine(PAUSE_FILE)
            PAUSE.update(on=True, since=time.strftime("%H:%M:%S"), by="arranque (estado ilegible)")
        elif isinstance(p, dict):
            PAUSE.update(on=bool(p.get("on")), since=p.get("since"), by=p.get("by"))
        else:
            PAUSE.update(on=False, since=None, by=None)
        d, err = _read_json(DRAFTS_FILE)
        APPROVALS.clear()
        if err:
            WARNINGS.append(err + ": arranco EN PAUSA para que revises los borradores.")
            _quarantine(DRAFTS_FILE)
            PAUSE.update(on=True, since=time.strftime("%H:%M:%S"), by="arranque (borradores ilegibles)")
        elif isinstance(d, list):
            for a in d:
                if isinstance(a, dict) and a.get("id") and a["id"] not in APPROVALS:  # sin duplicados
                    APPROVALS[a["id"]] = a
        if PAUSE["on"]:
            _save_pause()
        return {"pause": dict(PAUSE), "drafts": len(APPROVALS), "warnings": list(WARNINGS)}


def _save_pause():
    try:
        _write_json(PAUSE_FILE, PAUSE)
    except OSError as e:
        WARNINGS.append(f"No pude guardar la pausa en disco ({e.__class__.__name__}); sigue activa en memoria.")


def _save_drafts():
    decided = [a for a in APPROVALS.values() if a["state"] != "pendiente"]
    if len(decided) > MAX_DECIDED:
        for a in sorted(decided, key=lambda a: a.get("decided_at", 0))[:len(decided) - MAX_DECIDED]:
            APPROVALS.pop(a["id"], None)
    _write_json(DRAFTS_FILE, list(APPROVALS.values()))


# ------------------------------------------------------------------ PAUSA
def paused():
    return PAUSE["on"]


def set_pause(on, by="botón"):
    """Activa o quita la PAUSA. Espera a que acabe la escritura en curso: al volver, no habrá más."""
    with LOCK:
        PAUSE.update(on=bool(on), since=time.strftime("%H:%M:%S") if on else None, by=by if on else None)
        _save_pause()
        return dict(PAUSE)


@contextmanager
def guarded_write():
    """Toda escritura con efectos va dentro de esto. Si la PAUSA está activa, no empieza."""
    with LOCK:
        if PAUSE["on"]:
            raise Paused("JARVIS está en pausa")
        yield


# ------------------------------------------------------------------ borradores
def add_draft(conv, text, question):
    with guarded_write():
        aid = uuid.uuid4().hex[:12]
        a = {"id": aid, "conv": conv, "kind": "borrador", "text": text, "state": "pendiente",
             "created": time.strftime("%Y-%m-%d %H:%M:%S"), "question": question}
        APPROVALS[aid] = a
        try:
            _save_drafts()
        except OSError:
            APPROVALS.pop(aid, None)
            raise
        return dict(a)


def decide(aid, decision):
    """Registra tu decisión. Aprobar NUNCA envía nada. En PAUSA no se decide (lanza Paused)."""
    with guarded_write():
        a = APPROVALS.get(aid)
        if not a:
            return None
        if a["state"] != "pendiente":
            return dict(a)  # idempotente: una decisión tomada no cambia
        a["state"] = "aprobado" if decision == "aprobar" else "descartado"
        a["decided"] = time.strftime("%Y-%m-%d %H:%M:%S")
        a["decided_at"] = time.time()
        a["result"] = ("Aprobado por ti. No se ha enviado nada: cópialo y envíalo tú." if a["state"] == "aprobado"
                       else "Descartado. No se ha enviado nada.")
        _save_drafts()
        return dict(a)


def pending(conv=None):
    return [dict(a) for a in APPROVALS.values()
            if a["state"] == "pendiente" and (conv is None or a["conv"] == conv)]
