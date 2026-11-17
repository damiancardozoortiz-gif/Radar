#!/usr/bin/env python3
"""Un teléfono Android de mentira que responde como `adb`. Para ver JARVIS funcionar sin teléfono y para las pruebas.

Uso:  JARVIS_TELEFONO_DEMO=1 ./jarvis.sh      (jarvis.sh pone JARVIS_ADB apuntando a este archivo)

Entiende los mismos comandos de adb que usa agent/telefono.py y guarda el estado del «teléfono» en un archivo
JSON, porque cada llamada a adb es un proceso nuevo. Tiene cuatro aplicaciones inventadas: WhatsApp (con tres
chats), Ajustes, Chrome y «Banco Demo». Nada de lo que pase aquí sale de esta computadora.
"""
import base64
import json
import os
import re
import struct
import sys
import zlib
from pathlib import Path

W, H = 1080, 2400
ESTADO = Path(os.environ.get("JARVIS_TELEFONO_DEMO_ESTADO") or Path(__file__).resolve().parent / "demo" / "telefono_estado.json")
INICIAL = {
    "pantalla": "inicio", "chat": None, "campo": "", "foco": False, "wifi": True, "url": "", "teclado_adb": True,
    "chats": {"Ana López": [["ella", "¿Vienes a cenar mañana?"]],
              "Carlos (taller)": [["él", "El coche está listo, puedes pasar a recogerlo."]],
              "Mamá": [["ella", "Llámame cuando puedas."]]},
    "enviados": [],
}
PAQUETES = {"whatsapp": "com.whatsapp", "ajustes": "com.android.settings", "chrome": "com.android.chrome",
            "banco": "com.bancodemo.app", "inicio": "com.android.launcher3"}


def cargar():
    try:
        return json.loads(ESTADO.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return json.loads(json.dumps(INICIAL))


def guardar(e):
    ESTADO.parent.mkdir(parents=True, exist_ok=True)
    ESTADO.write_text(json.dumps(e, ensure_ascii=False), encoding="utf-8")


# ---- cada pantalla es una lista de piezas: (clase, texto, descripción, caja, pulsable, acción, extra)
def piezas(e):
    p, s = [], e["pantalla"]
    def pon(clase, texto, desc, caja, pulsable=False, accion=None, **extra):
        p.append({"clase": clase, "texto": texto, "desc": desc, "caja": caja, "pulsable": pulsable, "accion": accion, **extra})
    if s == "inicio":
        pon("TextView", "10:30", "", [60, 60, 300, 130])
        for i, (nombre, destino) in enumerate([("WhatsApp", "whatsapp"), ("Chrome", "chrome"), ("Ajustes", "ajustes"), ("Banco Demo", "banco")]):
            x = 90 + i * 240
            pon("TextView", nombre, nombre, [x, 1900, x + 200, 2140], True, ["abrir", destino])
    elif s == "whatsapp":
        pon("TextView", "WhatsApp", "", [60, 150, 600, 250])
        pon("ImageButton", "", "Buscar", [900, 150, 1020, 250], True, None)
        for i, (nombre, mensajes) in enumerate(e["chats"].items()):
            y = 320 + i * 220
            pon("LinearLayout", "", "", [0, y, 1080, y + 200], True, ["chat", nombre],
                hijos=[["TextView", nombre, [220, y + 30, 900, y + 90]], ["TextView", mensajes[-1][1][:60], [220, y + 100, 1000, y + 160]]])
    elif s == "chat":
        pon("ImageButton", "", "Atrás", [20, 150, 140, 250], True, ["ir", "whatsapp"])
        pon("TextView", e["chat"], "", [180, 150, 800, 250])
        y = 340
        for quien, texto in e["chats"][e["chat"]][-8:]:
            x1, x2 = (380, 1040) if quien == "yo" else (40, 700)
            pon("TextView", texto, "", [x1, y, x2, y + 130], mio=quien == "yo")
            y += 170
        pon("EditText", e["campo"], "" if e["campo"] else "Mensaje", [40, 2180, 880, 2300], True, ["foco"], campo=True, foco=e["foco"])
        if e["campo"]:
            pon("ImageButton", "", "Enviar", [910, 2180, 1040, 2300], True, ["enviar"])
        else:
            pon("ImageButton", "", "Mensaje de voz", [910, 2180, 1040, 2300], True, None)
    elif s == "ajustes":
        pon("TextView", "Ajustes", "", [60, 150, 600, 250])
        pon("Switch", "Wi-Fi", "", [60, 340, 1020, 480], True, ["wifi"], casilla=True, marcada=e["wifi"])
        pon("TextView", "Bluetooth", "", [60, 520, 1020, 660], True, None)
        pon("TextView", "Batería · 82 %", "", [60, 700, 1020, 840], True, None)
        pon("TextView", "Restablecer datos de fábrica", "", [60, 880, 1020, 1020], True, ["fabrica"])
    elif s == "chrome":
        pon("EditText", e["url"], "" if e["url"] else "Buscar o escribir URL", [40, 150, 1040, 270], True, ["foco"], campo=True, foco=e["foco"])
        pon("TextView", ("Página abierta: " + e["url"]) if e["url"] else "Página nueva", "", [60, 400, 1020, 520])
    elif s == "banco":
        pon("TextView", "Banco Demo", "", [60, 150, 800, 250])
        pon("TextView", "Saldo disponible: 1.250,00 €", "", [60, 340, 1020, 460])
        pon("Button", "Transferir", "", [60, 560, 520, 700], True, ["transferir"])
        pon("Button", "Movimientos", "", [560, 560, 1020, 700], True, None)
    elif s == "fabrica":
        pon("TextView", "El teléfono de demo se habría borrado. (Es de mentira.)", "", [60, 400, 1020, 600])
    elif s == "transferencia":
        pon("TextView", "Transferencia enviada: 100,00 € (de mentira)", "", [60, 400, 1020, 600])
    return p


def tocar(e, x, y):
    for pz in reversed(piezas(e)):
        c = pz["caja"]
        if pz["pulsable"] and c[0] <= x <= c[2] and c[1] <= y <= c[3]:
            a = pz["accion"]
            if not a:
                return
            if a[0] == "abrir" or a[0] == "ir":
                e.update(pantalla=a[1], foco=False, campo="")
            elif a[0] == "chat":
                e.update(pantalla="chat", chat=a[1], campo="", foco=False)
            elif a[0] == "foco":
                e["foco"] = True
            elif a[0] == "enviar":
                e["chats"][e["chat"]].append(["yo", e["campo"]])
                e["enviados"].append({"a": e["chat"], "texto": e["campo"]})
                e["campo"] = ""
            elif a[0] == "wifi":
                e["wifi"] = not e["wifi"]
            elif a[0] == "fabrica":
                e["pantalla"] = "fabrica"
            elif a[0] == "transferir":
                e["pantalla"] = "transferencia"
            return


def escribir(e, texto):
    if not e["foco"]:
        return
    if e["pantalla"] == "chat":
        e["campo"] += texto
    elif e["pantalla"] == "chrome":
        e["url"] += texto


def tecla(e, k):
    s = e["pantalla"]
    if k == "KEYCODE_HOME":
        e.update(pantalla="inicio", foco=False, campo="")
    elif k == "KEYCODE_BACK":
        e.update(pantalla={"chat": "whatsapp", "fabrica": "ajustes", "transferencia": "banco"}.get(s, "inicio"), foco=False, campo="")
    elif k == "KEYCODE_DEL" and e["foco"]:
        if s == "chat":
            e["campo"] = e["campo"][:-1]
        elif s == "chrome":
            e["url"] = e["url"][:-1]
    elif k == "KEYCODE_ENTER" and s == "chat" and e["foco"] and e["campo"]:
        e["chats"][e["chat"]].append(["yo", e["campo"]])
        e["enviados"].append({"a": e["chat"], "texto": e["campo"]})
        e["campo"] = ""


def paquete(e):
    return PAQUETES.get({"chat": "whatsapp", "fabrica": "ajustes", "transferencia": "banco"}.get(e["pantalla"], e["pantalla"]), PAQUETES["inicio"])


def xml(e):
    esc = lambda t: str(t).replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    pkg = paquete(e)
    def nodo(clase, texto, desc, caja, pulsable, extra, hijos=""):
        return (f'<node index="0" text="{esc(texto)}" resource-id="" class="android.widget.{clase}" package="{pkg}" '
                f'content-desc="{esc(desc)}" checkable="{str(bool(extra.get("casilla"))).lower()}" checked="{str(bool(extra.get("marcada"))).lower()}" '
                f'clickable="{str(bool(pulsable)).lower()}" enabled="true" focusable="true" focused="{str(bool(extra.get("foco"))).lower()}" '
                f'scrollable="false" long-clickable="false" password="false" selected="false" '
                f'bounds="[{caja[0]},{caja[1]}][{caja[2]},{caja[3]}]">{hijos}</node>')
    dentro = "".join(nodo(p["clase"], p["texto"], p["desc"], p["caja"], p["pulsable"], p,
                          "".join(nodo(h[0], h[1], "", h[2], False, {}) for h in p.get("hijos", []))) for p in piezas(e))
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            + nodo("FrameLayout", "", "", [0, 0, W, H], False, {}, dentro) + "</hierarchy>")


def png(e):
    fondo = {"inicio": (18, 32, 58), "banco": (12, 46, 40)}.get(e["pantalla"], (14, 20, 26))
    try:
        from PIL import Image, ImageDraw, ImageFont
        im = Image.new("RGB", (W, H), fondo)
        d = ImageDraw.Draw(im)
        f = None
        for ruta in ("DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "Arial.ttf", "arial.ttf",
                     "/System/Library/Fonts/Helvetica.ttc", "/Library/Fonts/Arial.ttf", "segoeui.ttf",
                     "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"):
            try:
                f = ImageFont.truetype(ruta, 44)
                break
            except OSError:
                continue
        if f is None:
            try:
                f = ImageFont.load_default(size=44)
            except TypeError:
                f = ImageFont.load_default()
        d.rectangle([0, 0, W, 120], fill=(8, 12, 16))
        for p in piezas(e):
            c = p["caja"]
            if p["pulsable"] or p.get("mio") is not None:
                color = (0, 92, 75) if p.get("mio") else (38, 50, 62) if not p.get("campo") else (30, 40, 50)
                if p["desc"] == "Enviar":
                    color = (0, 168, 132)
                d.rounded_rectangle(c, radius=28, fill=color)
            etiqueta = p["texto"] or p["desc"]
            if p.get("casilla"):
                etiqueta += "   [ SÍ ]" if p.get("marcada") else "   [ NO ]"
            if etiqueta:
                d.text((c[0] + 24, c[1] + max(10, (c[3] - c[1] - 50) // 2)), etiqueta[:max(4, (c[2] - c[0] - 40) // 25)], font=f, fill=(232, 238, 242) if p["texto"] else (150, 165, 175))
            for h in p.get("hijos", []):
                d.text((h[2][0], h[2][1]), h[1][:38], font=f, fill=(232, 238, 242))
        import io
        b = io.BytesIO()
        im.save(b, "PNG")
        return b.getvalue()
    except ImportError:  # sin Pillow: bloques de color sin letras
        filas = [bytearray(bytes(fondo) * W) for _ in range(H)]
        for p in piezas(e):
            c = p["caja"]
            if p["pulsable"]:
                franja = bytes((38, 50, 62)) * (c[2] - c[0])
                for y in range(c[1], c[3]):
                    filas[y][c[0] * 3:c[2] * 3] = franja
        crudo = b"".join(b"\x00" + bytes(f) for f in filas)
        trozo = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        return (b"\x89PNG\r\n\x1a\n" + trozo(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))
                + trozo(b"IDAT", zlib.compress(crudo, 1)) + trozo(b"IEND", b""))


def shell(e, cmd):
    out = []
    for parte in [c.strip() for c in cmd.split(";") if c.strip()]:
        parte = parte.split("|")[0].strip()
        if parte.startswith("wm size"):
            out.append(f"Physical size: {W}x{H}")
        elif parte.startswith("getprop"):
            out.append({"ro.product.manufacturer": "Demo", "ro.product.model": "Teléfono simulado",
                        "ro.build.version.release": "15"}.get(parte.split()[-1], ""))
        elif parte.startswith("dumpsys battery"):
            out.append("  AC powered: false\n  USB powered: true\n  level: 82")
        elif parte.startswith("dumpsys power"):
            out.append("  mWakefulness=Awake")
        elif parte.startswith("dumpsys window"):
            out.append(f"  mCurrentFocus=Window{{abc u0 {paquete(e)}/.Main}}\n  mDreamingLockscreen=false")
        elif parte.startswith("dumpsys notification"):
            out.append("  NotificationRecord(0x1: pkg=com.whatsapp user=0 id=1)\n      extras={\n"
                       "        android.title=String (Ana López)\n        android.text=String (¿Vienes a cenar mañana?)\n      }\n"
                       "  NotificationRecord(0x2: pkg=com.bancodemo.app user=0 id=2)\n      extras={\n"
                       "        android.title=String (Banco Demo)\n        android.text=String (Recibo de luz cargado: 64,20 €)\n      }")
        elif parte.startswith("input tap"):
            _, _, x, y = parte.split()[:4]
            tocar(e, int(x), int(y))
        elif parte.startswith("input swipe"):
            v = parte.split()
            if v[2] == v[4] and v[3] == v[5]:
                tocar(e, int(v[2]), int(v[3]))
        elif parte.startswith("input keyevent"):
            tecla(e, parte.split()[-1])
        elif parte.startswith("input text"):
            m = re.match(r"input text '(.*)'$", parte, re.S)
            escribir(e, (m.group(1) if m else parte[11:]).replace("'\\''", "'").replace("%s", " "))
        elif parte.startswith("ime list"):
            out.append("com.android.inputmethod.latin/.LatinIME" + ("\ncom.android.adbkeyboard/.AdbIME" if e.get("teclado_adb") else ""))
        elif parte.startswith("settings get secure default_input_method"):
            out.append("com.android.inputmethod.latin/.LatinIME")
        elif parte.startswith("am broadcast -a ADB_INPUT_B64"):
            escribir(e, base64.b64decode(parte.split()[-1]).decode("utf-8"))
        elif parte.startswith("monkey -p"):
            pkg = parte.split()[2]
            destino = next((k for k, v in PAQUETES.items() if v == pkg), None)
            if destino:
                e.update(pantalla=destino, foco=False, campo="")
                out.append("Events injected: 1")
            else:
                out.append("No activities found to run, monkey aborted.")
        elif parte.startswith("cmd package query-activities"):
            out.append("\n".join(f"  {v}/.Main" for v in PAQUETES.values()))
        elif parte.startswith("pm list packages"):
            out.append("\n".join(f"package:{v}" for v in PAQUETES.values()))
        elif parte.startswith("am start"):
            m = re.search(r"-d '([^']+)'", parte)
            e.update(pantalla="chrome", url=m.group(1) if m else "", foco=False)
    return "\n".join(out) + "\n"


def main(argv):
    if argv[:1] == ["-s"]:
        argv = argv[2:]
    e = cargar()
    salida = sys.stdout.buffer
    if not argv or argv[0] == "devices":
        salida.write(b"List of devices attached\ndemo-0001\tdevice\n\n")
    elif argv[0] == "connect":
        salida.write(b"connected\n")
    elif argv[0] == "shell":
        salida.write(shell(e, " ".join(argv[1:])).encode("utf-8"))
    elif argv[0] == "exec-out" and argv[1] == "uiautomator":
        salida.write(xml(e).encode("utf-8") + b"UI hierchary dumped to: /dev/tty\n")
    elif argv[0] == "exec-out" and argv[1] == "screencap":
        salida.write(png(e))
    else:
        sys.stderr.write("adb de demo: comando no simulado: " + " ".join(argv) + "\n")
        return 1
    guardar(e)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
