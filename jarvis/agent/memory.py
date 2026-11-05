"""Memoria de JARVIS: un archivo markdown con fecha por cada cosa.

Escribe en memory/ y en ningún otro sitio. Claude nunca escribe:
devuelve un campo "recordar" en su JSON y este módulo lo guarda.
"""
import re
import unicodedata
from pathlib import Path

import data

MEMORY = (Path(__file__).resolve().parent.parent / "memory").resolve()


def _slug(text):
    s = unicodedata.normalize("NFD", text.lower())
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return s[:48] or "nota"


def save(text, source="conversación"):
    """Guarda UNA cosa en memory/ con la fecha de hoy (la fija de la demo en modo demo)."""
    text = " ".join(str(text).split())[:600]
    if not text:
        return None
    MEMORY.mkdir(exist_ok=True)
    day = data.today().isoformat()
    base = f"{day}-{_slug(text)}"
    path = MEMORY / f"{base}.md"
    i = 2
    while path.exists():
        path = MEMORY / f"{base}-{i}.md"
        i += 1
    path = path.resolve()
    if path.parent != MEMORY:  # nunca fuera de memory/
        raise PermissionError("La memoria solo se escribe en memory/")
    path.write_text(f"---\nfecha: {day}\norigen: {source}\nmodo: {data.mode()}\n---\n{text}\n", encoding="utf-8")
    return {"file": f"memory/{path.name}", "text": text, "date": day}


def items(limit=60):
    out = []
    if not MEMORY.exists():
        return out
    for p in sorted(MEMORY.glob("*.md"), reverse=True)[:limit]:
        raw = p.read_text(encoding="utf-8", errors="replace")
        m = re.match(r"^---\n(.*?)\n---\n(.*)$", raw, re.S)
        meta, body = ({}, raw) if not m else (dict(
            l.split(":", 1) for l in m.group(1).splitlines() if ":" in l), m.group(2))
        out.append({"file": f"memory/{p.name}", "date": meta.get("fecha", "").strip(),
                    "text": body.strip()})
    return out


def as_prompt():
    its = items()
    if not its:
        return "(todavía no hay nada guardado)"
    return "\n".join(f"- {i['date']}: {i['text']}" for i in reversed(its))
