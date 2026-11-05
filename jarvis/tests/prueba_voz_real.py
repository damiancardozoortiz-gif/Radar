#!/usr/bin/env python3
"""Prueba de la voz REAL de ElevenLabs (no simulada), para ejecutar en tu máquina.

1. Texto -> voz con tu clave (eleven_flash_v2_5, es) y guarda el audio en /tmp para que lo escuches.
2. Ese audio -> texto con Scribe (scribe_v1, es).
3. Comprueba que el texto vuelve, y mide cuánto tarda cada paso.

Usa tu clave de jarvis/.env (nunca la imprime). Gasta unos ~100 créditos de ElevenLabs.
Uso:  python3 tests/prueba_voz_real.py
"""
import os
import re
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "agent"))

env = ROOT / ".env"
if env.exists():
    for line in env.read_text().splitlines():
        m = re.match(r"^\s*([A-Z_]+)=(.*)$", line)
        if m and m.group(1).startswith("ELEVENLABS_") and not os.environ.get(m.group(1)):
            os.environ[m.group(1)] = m.group(2).strip()

import voice  # noqa: E402

FRASE = "Hola, soy JARVIS. La prueba de voz real ha funcionado."


def main():
    if not voice.configured():
        print("FALLA  no hay ELEVENLABS_API_KEY en jarvis/.env")
        return 1
    print(f"Voz: {os.environ.get('ELEVENLABS_VOICE_ID') or '(la primera de tu cuenta)'} · servidor: {voice.BASE}")
    t = time.time()
    try:
        audio, ctype = voice.speak(FRASE)
    except voice.VoiceError as e:
        if "sin conexión" in str(e):
            print(f"PENDIENTE  no hay conexión con ElevenLabs desde esta máquina ({e}). Ejecútala donde haya red.")
            return 2
        print(f"FALLA  texto a voz: {e}")
        return 1
    tts = time.time() - t
    out = Path(tempfile.gettempdir()) / "jarvis_prueba_voz.mp3"
    out.write_bytes(audio)
    print(f"PASA   texto a voz: {len(audio)} bytes ({ctype}) en {tts:.2f} s · escúchalo: {out}")
    t = time.time()
    try:
        heard = voice.listen(audio, ctype)
    except voice.VoiceError as e:
        print(f"FALLA  voz a texto (Scribe): {e}")
        return 1
    stt = time.time() - t
    ok = all(w in heard.lower() for w in ("jarvis", "prueba", "voz"))
    print(f"{'PASA ' if ok else 'FALLA'}  voz a texto (Scribe) en {stt:.2f} s: «{heard}»")
    print("\nAhora pruébalo de verdad: ./jarvis.sh iniciar, abre http://localhost:8790, pulsa el micrófono y habla.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
