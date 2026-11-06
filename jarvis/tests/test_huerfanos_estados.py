#!/usr/bin/env python3
"""Prueba ADICIONAL e independiente de procesos huérfanos tras un kill -9 del servidor.

La prueba original (test_criticos.py · C6 test_sin_huerfanos_tras_kill9_del_servidor) NO se modifica. Esa prueba
cuenta como "vivo" todo proceso con /proc/<pid>; un ZOMBI (estado Z: ya terminó, falta que su nuevo padre lo
recoja) también tiene /proc/<pid>. Si el PID 1 del sistema no recoge zombis enseguida (pasa en algunos
contenedores), la original falla aunque el `claude` haya terminado.

Esta prueba repite el MISMO escenario y registra, por repetición:
  procesos creados · terminados (sin /proc) · zombis (Z) · realmente activos (cualquier otro estado)
y cuál habría sido el veredicto de la original. Pasa solo si NINGÚN proceso queda realmente activo.

Uso:  python3 tests/test_huerfanos_estados.py [repeticiones=10]
En Termux funciona igual (usa /proc del propio usuario). Sin internet ni suscripción (claude simulado).
"""
import json
import os
import platform
import shutil
import signal
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_criticos as tc  # noqa: E402  (solo se reutiliza su servidor de prueba; sus pruebas no se ejecutan)


def estado(pid):
    try:
        with open(f"/proc/{pid}/stat") as f:
            s = f.read()
        st = s[s.rindex(")") + 2]
        ppid = int(s[s.rindex(")") + 2:].split()[1])
        return st, ppid
    except (FileNotFoundError, ProcessLookupError):
        return None, None


def una_vez(n):
    tmp = Path(tempfile.mkdtemp(prefix="jarvis-huerfanos-"))
    s = tc.Server(tmp)
    try:
        s.start()
        s.jpost("/api/ask", {"text": "Hola", "conv": "z"})
        pids = sorted({int(l.split()[1].split("=")[1]) for l in s.claude_lines() if l.startswith("start")})
        antes = {p: estado(p)[0] for p in pids}
        s.stop(signal.SIGKILL)
        time.sleep(2)  # la misma espera que la prueba original
        fila = {"rep": n, "creados": len(pids), "terminados": 0, "zombis": 0, "activos": 0, "detalle": []}
        for p in pids:
            st, ppid = estado(p)
            if st is None:
                fila["terminados"] += 1; clase = "terminado"
            elif st == "Z":
                fila["zombis"] += 1; clase = "zombi"
            else:
                fila["activos"] += 1; clase = "ACTIVO"
            fila["detalle"].append({"pid": p, "antes": antes[p], "despues": st or "-", "padre": ppid, "clase": clase})
        fila["original_diria"] = "PASA" if fila["zombis"] + fila["activos"] == 0 else "FALLA"
        fila["esta_prueba"] = "PASA" if fila["activos"] == 0 and fila["creados"] > 0 else "FALLA"
        return fila
    finally:
        if s.p and s.p.poll() is None:
            s.p.kill()
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    reps = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    tc.setUpModule()
    try:
        init = open("/proc/1/comm").read().strip()
    except OSError:
        init = "no legible"
    print(f"sistema: {platform.system()} {platform.release()} · {platform.machine()} · PID 1: {init}")
    filas = [una_vez(i + 1) for i in range(reps)]
    print("rep  creados  terminados  zombis  activos  original  esta-prueba")
    for f in filas:
        print(f"{f['rep']:>3}  {f['creados']:>7}  {f['terminados']:>10}  {f['zombis']:>6}  {f['activos']:>7}  {f['original_diria']:>8}  {f['esta_prueba']:>11}")
    tot = {k: sum(f[k] for f in filas) for k in ("creados", "terminados", "zombis", "activos")}
    orig_fallas = sum(f["original_diria"] == "FALLA" for f in filas)
    fallas = sum(f["esta_prueba"] == "FALLA" for f in filas)
    print(f"total: {tot} · la original fallaría {orig_fallas}/{reps} · esta prueba falla {fallas}/{reps}")
    padres = sorted({d["padre"] for f in filas for d in f["detalle"] if d["clase"] == "zombi"})
    if padres:
        print(f"los zombis quedaron a cargo del PID {padres} ({init}), que no los había recogido a los 2 s")
    Path(tempfile.gettempdir(), "huerfanos-resultado.json").write_text(json.dumps(filas, ensure_ascii=False, indent=1))
    sys.exit(0 if fallas == 0 else 1)


if __name__ == "__main__":
    main()
