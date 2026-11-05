#!/usr/bin/env python3
"""Convierte una exportación de correo (.mbox, p. ej. de Google Takeout) en data/real/inbox.json.

Solo lee el .mbox; escribe únicamente data/real/inbox.json. Librería estándar.
Uso:  python3 data/importar_correo.py ~/Downloads/Takeout/Correo/Recibidos.mbox [--max 200]
"""
import argparse
import json
import mailbox
import re
import sys
from email.header import decode_header, make_header
from email.utils import parseaddr, parsedate_to_datetime
from pathlib import Path

OUT = Path(__file__).resolve().parent / "real" / "inbox.json"


def dec(v):
    try:
        return str(make_header(decode_header(v or "")))
    except Exception:
        return v or ""


def text_of(msg):
    parts = msg.walk() if msg.is_multipart() else [msg]
    for p in parts:
        if p.get_content_type() == "text/plain" and not p.get_filename():
            try:
                raw = p.get_payload(decode=True) or b""
                return raw.decode(p.get_content_charset() or "utf-8", errors="replace")
            except Exception:
                continue
    for p in parts:
        if p.get_content_type() == "text/html":
            raw = p.get_payload(decode=True) or b""
            return re.sub(r"<[^>]+>", " ", raw.decode(p.get_content_charset() or "utf-8", errors="replace"))
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mbox")
    ap.add_argument("--max", type=int, default=200, help="cuántos correos recientes guardar")
    a = ap.parse_args()
    box = mailbox.mbox(a.mbox, create=False)
    items = []
    for i, msg in enumerate(box):
        name, email = parseaddr(dec(msg.get("From")))
        try:
            dt = parsedate_to_datetime(msg.get("Date"))
            date = dt.astimezone().replace(tzinfo=None).isoformat(timespec="minutes")
        except Exception:
            date = ""
        labels = (msg.get("X-Gmail-Labels") or "").lower()
        body = re.sub(r"\s+", " ", text_of(msg)).strip()
        items.append({"id": f"m{i + 1}", "from": name or email, "email": email, "subject": dec(msg.get("Subject")),
                      "date": date, "unread": "unread" in labels or "no leído" in labels or "no leidos" in labels,
                      "snippet": body[:300]})
    items.sort(key=lambda m: m["date"], reverse=True)
    items = items[:a.max]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(items)} correos -> {OUT} ({sum(m['unread'] for m in items)} sin leer)")


if __name__ == "__main__":
    sys.exit(main())
