"""Wrapper mínimo de la CLI de Claude Code con sesiones persistentes.

Uso como módulo:
    from claude_cli import preguntar_a_claude
    texto, sid = preguntar_a_claude("Dame 3 ideas de Shorts")
    texto, sid = preguntar_a_claude("Desarrollá la segunda", session_id=sid)

Uso interactivo:
    python3 claude_cli.py [--cwd /ruta/al/proyecto]
"""

import argparse
import json
import os
import shutil
import subprocess

PROMPT_VOZ = "Respondé breve y apto para leer en voz alta."


class ClaudeError(RuntimeError):
    """Falla al invocar la CLI o respuesta de error de Claude."""


def preguntar_a_claude(texto, session_id=None, cwd=None, timeout=300,
                       system_extra=PROMPT_VOZ):
    """Envía `texto` a Claude y devuelve (respuesta, session_id).

    Pasá el session_id devuelto en la llamada siguiente para mantener
    el contexto de la charla (--resume).
    """
    claude = shutil.which("claude")
    if not claude:
        raise ClaudeError("No encuentro el comando 'claude' en el PATH.")

    cmd = [claude, "-p", texto, "--output-format", "json"]
    if system_extra:
        cmd += ["--append-system-prompt", system_extra]
    if session_id:
        cmd += ["--resume", session_id]  # mantiene el contexto de la charla

    try:
        out = subprocess.run(cmd, capture_output=True, text=True,
                             cwd=cwd or os.getcwd(), timeout=timeout)
    except subprocess.TimeoutExpired as e:
        raise ClaudeError(f"Claude no respondió en {timeout}s.") from e

    try:
        data = json.loads(out.stdout)
    except json.JSONDecodeError as e:
        detalle = (out.stderr or out.stdout).strip()[:500]
        raise ClaudeError(
            f"Salida inválida de claude (código {out.returncode}): {detalle}"
        ) from e

    if data.get("is_error") or out.returncode != 0:
        raise ClaudeError(data.get("result") or out.stderr.strip()
                          or f"claude terminó con código {out.returncode}")

    return data.get("result", ""), data.get("session_id", session_id)


def main():
    ap = argparse.ArgumentParser(description="Charla con Claude desde la terminal.")
    ap.add_argument("--cwd", help="Directorio del proyecto donde corre Claude.")
    ap.add_argument("--resume", help="session_id para retomar una charla previa.")
    args = ap.parse_args()

    sid = args.resume
    print("Escribí tu pregunta (vacío o Ctrl+D para salir).")
    while True:
        try:
            texto = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not texto:
            break
        try:
            respuesta, sid = preguntar_a_claude(texto, sid, cwd=args.cwd)
        except ClaudeError as e:
            print(f"[error] {e}")
            continue
        print(respuesta)
    if sid:
        print(f"Sesión: {sid}  (retomala con --resume {sid})")


if __name__ == "__main__":
    main()
