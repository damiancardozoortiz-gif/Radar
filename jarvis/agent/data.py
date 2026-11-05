"""EL ÚNICO ARCHIVO QUE TOCA TUS DATOS REALES.

Todo lo demás (vault, tools, cerebro) pide los datos aquí.
JARVIS_DEMO solo se lee en este archivo. Por defecto = demo.
Todo es SOLO LECTURA: este archivo nunca escribe en tus carpetas.
"""
import json
import os
from datetime import date
from pathlib import Path

# ---- Tus carpetas reales (solo lectura, con todo lo que tengan dentro) ----
REAL_FOLDERS = [
    "/Users/yo/Documents/Clientes",
    "/Users/yo/Notas",
]

ROOT = Path(__file__).resolve().parent.parent
DEMO_DIR = ROOT / "data" / "demo"
REAL_DIR = ROOT / "data" / "real"

# Por defecto en demo: hay que elegir a propósito JARVIS_DEMO=0 para usar la vida real.
DEMO = os.environ.get("JARVIS_DEMO", "1").strip() != "0"


def mode():
    return "demo" if DEMO else "real"


def folders():
    """Carpetas que se leen (en solo lectura)."""
    if DEMO:
        return [DEMO_DIR / "vault"]
    return [Path(f).expanduser() for f in REAL_FOLDERS]


def today():
    """En demo, 'hoy' es una fecha fija guardada en los datos."""
    if DEMO:
        try:
            meta = json.loads((DEMO_DIR / "meta.json").read_text(encoding="utf-8"))
            return date.fromisoformat(meta["hoy"])
        except Exception:
            pass
    return date.today()


def _base():
    return DEMO_DIR if DEMO else REAL_DIR


def read_text(path, limit=None):
    """Lectura de un archivo de las carpetas permitidas. Nunca escribe."""
    path = Path(path)
    if not any(_inside(path, f) for f in folders()):
        raise PermissionError(f"Fuera de las carpetas permitidas: {path}")
    with open(path, "rb") as fh:
        data = fh.read(limit) if limit else fh.read()
    return data


def _inside(path, folder):
    try:
        path.resolve().relative_to(Path(folder).resolve())
        return True
    except ValueError:
        return False


def inbox():
    """Lista de correos o None si no está conectado."""
    p = _base() / "inbox.json"
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return None


def calendar():
    """Lista de eventos {title,start,minutes,with} o None si no está conectado.
    Acepta calendar.json o cualquier .ics de la carpeta."""
    base = _base()
    p = base / "calendar.json"
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return None
    ics = sorted(base.glob("*.ics")) if base.exists() else []
    if ics:
        return _parse_ics(ics[0].read_text(encoding="utf-8", errors="replace"))
    return None


def _parse_ics(text):
    # Desdoblar líneas plegadas (RFC 5545)
    lines = []
    for raw in text.splitlines():
        if raw[:1] in (" ", "\t") and lines:
            lines[-1] += raw[1:]
        else:
            lines.append(raw)
    events, cur = [], None
    for ln in lines:
        if ln == "BEGIN:VEVENT":
            cur = {}
        elif ln == "END:VEVENT" and cur is not None:
            if "start" in cur:
                events.append({"title": cur.get("title", "(sin título)"), "start": cur["start"],
                               "minutes": cur.get("minutes", 60), "with": cur.get("with", "")})
            cur = None
        elif cur is not None and ":" in ln:
            key, val = ln.split(":", 1)
            name = key.split(";")[0]
            if name == "SUMMARY":
                cur["title"] = val.replace("\\,", ",")
            elif name in ("DTSTART", "DTEND"):
                v = val.rstrip("Z")
                iso = f"{v[0:4]}-{v[4:6]}-{v[6:8]}" + (f"T{v[9:11]}:{v[11:13]}" if "T" in v else "T00:00")
                if name == "DTSTART":
                    cur["start"] = iso
                else:
                    cur["end"] = iso
                    if "start" in cur:
                        from datetime import datetime
                        try:
                            d = datetime.fromisoformat(iso) - datetime.fromisoformat(cur["start"])
                            cur["minutes"] = max(0, int(d.total_seconds() // 60))
                        except ValueError:
                            pass
            elif name == "LOCATION":
                cur["with"] = val
    return events
