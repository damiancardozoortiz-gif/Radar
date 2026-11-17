"""El teléfono Android, manejado por ADB (el puente oficial de depuración de Android).

Qué hace falta en el teléfono: activar «Opciones de desarrollador» → «Depuración USB» y aceptar la huella
de esta computadora. Nada más: no se instala nada en el teléfono y no hace falta root.

Cómo ve la pantalla: pide a Android el árbol de la interfaz (uiautomator) y lo convierte en una lista
numerada ([4] botón «Enviar»). Los toques van por número, así el servidor sabe QUÉ se toca antes de tocarlo.
La captura de pantalla solo se usa cuando el árbol no basta (juegos, mapas, imágenes).
"""
import base64
import io
import os
import re
import shutil
import subprocess
import threading
import time
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_ELEMENTOS = 120
ALTO_CAPTURA = 1280        # las capturas se reducen a esta altura (si está Pillow) para que pesen poco

APPS = {  # nombres que dice la gente -> paquete de Android
    "whatsapp": "com.whatsapp", "telegram": "org.telegram.messenger", "chrome": "com.android.chrome",
    "youtube": "com.google.android.youtube", "gmail": "com.google.android.gm", "correo": "com.google.android.gm",
    "maps": "com.google.android.apps.maps", "mapas": "com.google.android.apps.maps",
    "google maps": "com.google.android.apps.maps", "instagram": "com.instagram.android",
    "facebook": "com.facebook.katana", "messenger": "com.facebook.orca", "tiktok": "com.zhiliaoapp.musically",
    "x": "com.twitter.android", "twitter": "com.twitter.android", "spotify": "com.spotify.music",
    "netflix": "com.netflix.mediaclient", "ajustes": "com.android.settings", "configuracion": "com.android.settings",
    "play store": "com.android.vending", "tienda": "com.android.vending", "calendario": "com.google.android.calendar",
    "fotos": "com.google.android.apps.photos", "drive": "com.google.android.apps.docs",
    "mensajes": "com.google.android.apps.messaging", "sms": "com.google.android.apps.messaging",
    "telefono": "com.google.android.dialer", "contactos": "com.google.android.contacts",
    "reloj": "com.google.android.deskclock", "alarma": "com.google.android.deskclock",
    "calculadora": "com.google.android.calculator", "camara": "com.android.camera2", "keep": "com.google.android.keep",
    "notas": "com.google.android.keep", "uber": "com.ubercab", "mercado libre": "com.mercadolibre",
    "mercado pago": "com.mercadopago.wallet", "amazon": "com.amazon.mShop.android.shopping", "waze": "com.waze",
    "linkedin": "com.linkedin.android", "discord": "com.discord", "zoom": "us.zoom.videomeetings",
    "meet": "com.google.android.apps.tachyon", "google": "com.google.android.googlequicksearchbox",
}
TECLAS = {
    "inicio": "KEYCODE_HOME", "atras": "KEYCODE_BACK", "recientes": "KEYCODE_APP_SWITCH", "enter": "KEYCODE_ENTER",
    "borrar": "KEYCODE_DEL", "tab": "KEYCODE_TAB", "buscar": "KEYCODE_SEARCH", "menu": "KEYCODE_MENU",
    "subir_volumen": "KEYCODE_VOLUME_UP", "bajar_volumen": "KEYCODE_VOLUME_DOWN", "silenciar": "KEYCODE_VOLUME_MUTE",
    "despertar": "KEYCODE_WAKEUP", "dormir": "KEYCODE_SLEEP", "reproducir_pausa": "KEYCODE_MEDIA_PLAY_PAUSE",
    "siguiente": "KEYCODE_MEDIA_NEXT", "anterior": "KEYCODE_MEDIA_PREVIOUS", "escape": "KEYCODE_ESCAPE",
}
TECLADO_ADB = "com.android.adbkeyboard/.AdbIME"   # teclado opcional para escribir acentos, eñes y emojis


class TelefonoError(Exception):
    pass


def norm(s):
    s = unicodedata.normalize("NFD", str(s).lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def buscar_adb():
    fijo = os.environ.get("JARVIS_ADB", "").strip()
    if fijo:
        return fijo
    exe = shutil.which("adb")
    if exe:
        return exe
    for c in (ROOT / "platform-tools" / "adb", ROOT / "platform-tools" / "adb.exe",
              Path.home() / "Library/Android/sdk/platform-tools/adb", Path.home() / "Android/Sdk/platform-tools/adb"):
        if c.exists():
            return str(c)
    return None


class Telefono:
    def __init__(self):
        self.candado = threading.RLock()
        self.serie = None
        self.elementos = []        # la última lista numerada que vio JARVIS
        self.arbol_t = 0
        self.tamano = None
        self._paquetes = None

    # ---------- ADB ----------
    def disponible(self):
        return buscar_adb() is not None

    def _adb(self, *args, timeout=20, serie=True, binario=False):
        exe = buscar_adb()
        if not exe:
            raise TelefonoError("no encuentro el programa adb en esta computadora (instala «platform-tools» de Android)")
        cmd = exe.split() if " " in exe and not Path(exe).exists() else [exe]
        if serie and self.serie:
            cmd += ["-s", self.serie]
        try:
            p = subprocess.run(cmd + list(args), capture_output=True, timeout=timeout, stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            raise TelefonoError("el teléfono no respondió a tiempo")
        except OSError as e:
            raise TelefonoError(f"no pude ejecutar adb: {e}")
        if p.returncode != 0:
            err = (p.stderr or p.stdout).decode("utf-8", "replace").strip()[:200]
            if "unauthorized" in err:
                raise TelefonoError("el teléfono no ha autorizado a esta computadora: desbloquéalo y acepta «¿Permitir depuración USB?»")
            if "no devices" in err or "not found" in err or "offline" in err:
                self.serie = None
                raise TelefonoError("no hay ningún teléfono conectado")
            raise TelefonoError(err or f"adb falló (código {p.returncode})")
        return p.stdout if binario else p.stdout.decode("utf-8", "replace")

    def sh(self, comando, timeout=20):
        """Un comando en el teléfono. `comando` es UNA cadena que interpreta el teléfono."""
        self.conectar()
        return self._adb("shell", comando, timeout=timeout)

    def dispositivos(self):
        out = self._adb("devices", serie=False, timeout=10)
        ds = []
        for linea in out.splitlines()[1:]:
            partes = linea.split()
            if len(partes) >= 2:
                ds.append({"serie": partes[0], "estado": partes[1]})
        return ds

    def conectar(self):
        """Elige el teléfono: JARVIS_TELEFONO (serie o ip:puerto) o el primero que esté listo."""
        if self.serie:
            return self.serie
        quiero = os.environ.get("JARVIS_TELEFONO", "").strip()
        if quiero and ":" in quiero:
            try:
                self._adb("connect", quiero, serie=False, timeout=10)
            except TelefonoError:
                pass
        ds = self.dispositivos()
        if quiero:
            ds = [d for d in ds if d["serie"] == quiero]
        listos = [d for d in ds if d["estado"] == "device"]
        if not listos:
            if any(d["estado"] == "unauthorized" for d in ds):
                raise TelefonoError("el teléfono no ha autorizado a esta computadora: desbloquéalo y acepta «¿Permitir depuración USB?»")
            raise TelefonoError("no hay ningún teléfono conectado")
        self.serie = listos[0]["serie"]
        self.tamano = None
        self._paquetes = None
        return self.serie

    # ---------- estado ----------
    def _tamano(self):
        if not self.tamano:
            m = re.findall(r"(\d+)x(\d+)", self.sh("wm size"))
            if not m:
                raise TelefonoError("no pude leer el tamaño de la pantalla")
            self.tamano = (int(m[-1][0]), int(m[-1][1]))
        return self.tamano

    def app_actual(self):
        out = self.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        m = re.search(r"([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+", out)
        return m.group(1) if m else ""

    def estado(self):
        with self.candado:
            self.conectar()
            modelo = (self.sh("getprop ro.product.manufacturer").strip() + " " + self.sh("getprop ro.product.model").strip()).strip()
            android = self.sh("getprop ro.build.version.release").strip()
            bat = self.sh("dumpsys battery")
            nivel = re.search(r"level: (\d+)", bat)
            cargando = re.search(r"(AC|USB|Wireless) powered: true", bat) is not None
            energia = self.sh("dumpsys power | grep -E 'mWakefulness=|Display Power: state='")
            encendida = "Awake" in energia or "state=ON" in energia
            ventana = self.sh("dumpsys window | grep -E 'mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen|showing='")
            bloqueado = bool(re.search(r"(mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen|showing)=true", ventana))
            w, h = self._tamano()
            return {"conectado": True, "serie": self.serie, "modelo": modelo, "android": android,
                    "bateria": int(nivel.group(1)) if nivel else None, "cargando": cargando,
                    "pantalla_encendida": encendida, "bloqueado": bloqueado, "app": self.app_actual(),
                    "ancho": w, "alto": h}

    def despertar(self):
        """Enciende la pantalla y desliza para quitar el bloqueo simple. Nunca escribe tu PIN."""
        with self.candado:
            self.sh("input keyevent KEYCODE_WAKEUP")
            time.sleep(0.5)
            w, h = self._tamano()
            self.sh(f"input swipe {w // 2} {int(h * 0.8)} {w // 2} {int(h * 0.25)} 200")
            time.sleep(0.6)

    # ---------- ver ----------
    def _volcado(self):
        out = self._adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=25)
        i, j = out.find("<?xml"), out.rfind("</hierarchy>")
        if i < 0 or j < 0:  # algunos teléfonos no saben volcar a la terminal: se vuelca a un archivo y se lee
            self.sh("uiautomator dump /sdcard/window_dump.xml", timeout=25)
            out = self._adb("exec-out", "cat", "/sdcard/window_dump.xml", timeout=15)
            i, j = out.find("<?xml"), out.rfind("</hierarchy>")
            if i < 0 or j < 0:
                raise TelefonoError("no pude leer la pantalla (¿está en una pantalla protegida o reproduciendo vídeo?)")
        return out[i:j + len("</hierarchy>")]

    def mirar(self):
        """Lee la pantalla y devuelve {app, filas, elementos}. Guarda la lista para los toques por número."""
        with self.candado:
            self.conectar()
            try:
                raiz = ET.fromstring(self._volcado())
            except ET.ParseError:
                raise TelefonoError("la pantalla cambió mientras la leía; vuelve a mirar")
            elementos = []

            def textos(nodo, limite=4):
                out = []
                for n in nodo.iter("node"):
                    t = (n.get("text") or n.get("content-desc") or "").strip()
                    if t and t not in out:
                        out.append(t)
                        if len(out) >= limite:
                            break
                return out

            def visitar(nodo, dentro_pulsable):
                for n in nodo.findall("node"):
                    m = re.findall(r"\[(-?\d+),(-?\d+)\]", n.get("bounds", ""))
                    caja = [int(v) for par in m for v in par] if len(m) == 2 else None
                    cls = n.get("class", "").rsplit(".", 1)[-1]
                    pulsable = n.get("clickable") == "true" or n.get("checkable") == "true" or n.get("long-clickable") == "true"
                    campo = "EditText" in cls or "AutoComplete" in cls
                    texto = (n.get("text") or "").strip()
                    desc = (n.get("content-desc") or "").strip()
                    util = caja and caja[2] - caja[0] > 4 and caja[3] - caja[1] > 4
                    if util and (pulsable or campo or ((texto or desc) and not dentro_pulsable)):
                        nombre = texto or desc or " · ".join(textos(n)) or n.get("resource-id", "").rsplit("/", 1)[-1]
                        if campo:
                            tipo = "campo"
                        elif n.get("checkable") == "true":
                            tipo = "casilla"
                        elif pulsable:
                            tipo = "botón"
                        else:
                            tipo = "texto"
                        elementos.append({
                            "tipo": tipo, "nombre": nombre[:120], "caja": caja, "paquete": n.get("package", ""),
                            "password": n.get("password") == "true", "marcada": n.get("checked") == "true",
                            "enfocado": n.get("focused") == "true", "desplazable": n.get("scrollable") == "true",
                            "activo": n.get("enabled", "true") != "false", "valor": texto if campo else "",
                            "id": n.get("resource-id", "")})
                    visitar(n, dentro_pulsable or pulsable)

            visitar(raiz, False)
            elementos = elementos[:MAX_ELEMENTOS]
            filas = []
            for i, e in enumerate(elementos, 1):
                extra = ""
                if e["tipo"] == "campo":
                    extra = " (contraseña)" if e["password"] else (f" valor=«{e['valor'][:60]}»" if e["valor"] else " (vacío)")
                    if e["enfocado"]:
                        extra += " (con el cursor)"
                if e["tipo"] == "casilla":
                    extra = " (marcada)" if e["marcada"] else " (sin marcar)"
                if not e["activo"]:
                    extra += " (desactivado)"
                filas.append(f"[{i}] {e['tipo']} «{e['nombre']}»{extra}")
            self.elementos, self.arbol_t = elementos, time.time()
            paquetes = [e["paquete"] for e in elementos if e["paquete"]]
            app = max(set(paquetes), key=paquetes.count) if paquetes else self.app_actual()
            return {"app": app, "filas": filas, "elementos": elementos,
                    "desplazable": any(e["desplazable"] for e in elementos)}

    def elemento(self, ref):
        try:
            return self.elementos[int(ref) - 1]
        except (IndexError, ValueError, TypeError):
            raise TelefonoError(f"el elemento [{ref}] no está en la última lista. Vuelve a mirar la pantalla.")

    def elemento_en(self, x, y):
        """El elemento más pequeño de la última lista que contiene el punto (para saber qué se va a tocar)."""
        mejor = None
        for e in self.elementos:
            c = e["caja"]
            if c and c[0] <= x <= c[2] and c[1] <= y <= c[3]:
                area = (c[2] - c[0]) * (c[3] - c[1])
                if mejor is None or area < mejor[0]:
                    mejor = (area, e)
        return mejor[1] if mejor else None

    def campo_enfocado(self):
        return next((e for e in self.elementos if e["tipo"] == "campo" and e["enfocado"]), None)

    def captura(self):
        """(bytes, tipo MIME, escala): la pantalla ahora. Reducida a JPEG si está Pillow; si no, el PNG tal cual."""
        with self.candado:
            self.conectar()
            png = self._adb("exec-out", "screencap", "-p", timeout=25, binario=True)
        if not png.startswith(b"\x89PNG"):
            raise TelefonoError("no pude capturar la pantalla (¿aplicación protegida?)")
        try:
            from PIL import Image
        except ImportError:
            return png, "image/png", 1.0
        im = Image.open(io.BytesIO(png)).convert("RGB")
        escala = min(1.0, ALTO_CAPTURA / im.height)
        if escala < 1.0:
            im = im.resize((round(im.width * escala), round(im.height * escala)), Image.LANCZOS)
        b = io.BytesIO()
        im.save(b, "JPEG", quality=72)
        return b.getvalue(), "image/jpeg", escala

    # ---------- actuar ----------
    def tocar(self, x, y, largo=False):
        with self.candado:
            w, h = self._tamano()
            x, y = int(x), int(y)
            if not (0 <= x <= w and 0 <= y <= h):
                raise TelefonoError(f"el punto ({x}, {y}) está fuera de la pantalla ({w}×{h})")
            self.sh(f"input swipe {x} {y} {x} {y} 650" if largo else f"input tap {x} {y}")
            return {"fx": x / w, "fy": y / h}

    def centro(self, e):
        c = e["caja"]
        return (c[0] + c[2]) // 2, (c[1] + c[3]) // 2

    def deslizar(self, direccion):
        with self.candado:
            w, h = self._tamano()
            cx, cy = w // 2, h // 2
            rutas = {"arriba": (cx, int(h * 0.72), cx, int(h * 0.28)),       # el dedo sube: se ve lo de más abajo
                     "abajo": (cx, int(h * 0.30), cx, int(h * 0.74)),
                     "izquierda": (int(w * 0.82), cy, int(w * 0.18), cy),
                     "derecha": (int(w * 0.18), cy, int(w * 0.82), cy)}
            if direccion not in rutas:
                raise TelefonoError("dirección desconocida: arriba, abajo, izquierda o derecha")
            x1, y1, x2, y2 = rutas[direccion]
            self.sh(f"input swipe {x1} {y1} {x2} {y2} 320")

    def tecla(self, nombre):
        codigo = TECLAS.get(nombre)
        if not codigo:
            raise TelefonoError("tecla desconocida. Teclas: " + ", ".join(TECLAS))
        with self.candado:
            self.sh(f"input keyevent {codigo}")

    def escribir(self, texto):
        """Escribe en el campo que tiene el cursor. Devuelve un aviso si tuvo que quitar acentos."""
        with self.candado:
            aviso = None
            if all(ord(c) < 128 for c in texto):
                self._escribir_ascii(texto)
            elif TECLADO_ADB in self.sh("ime list -s"):
                anterior = self.sh("settings get secure default_input_method").strip()
                if anterior != TECLADO_ADB:
                    self.sh(f"ime enable {TECLADO_ADB}; ime set {TECLADO_ADB}")
                    time.sleep(0.6)
                b64 = base64.b64encode(texto.encode("utf-8")).decode()
                self.sh(f"am broadcast -a ADB_INPUT_B64 --es msg {b64}")
                time.sleep(0.3)
                if anterior and anterior != TECLADO_ADB and anterior != "null":
                    self.sh(f"ime set {anterior}")
            else:
                plano = unicodedata.normalize("NFD", texto)
                plano = "".join(c for c in plano if unicodedata.category(c) != "Mn")
                plano = plano.replace("¿", "").replace("¡", "").encode("ascii", "ignore").decode()
                self._escribir_ascii(plano)
                aviso = ("Escrito SIN acentos, eñes ni emojis: este teléfono no tiene el teclado ADBKeyboard. "
                         f"Quedó así: «{plano}»")
            return aviso

    def _escribir_ascii(self, texto):
        for linea_i, linea in enumerate(texto.split("\n")):
            if linea_i:
                self.sh("input keyevent KEYCODE_ENTER")
            for i in range(0, len(linea), 80):  # en trozos: los teléfonos lentos pierden letras con textos largos
                trozo = linea[i:i + 80].replace(" ", "%s")   # «input text» escribe un espacio donde lee %s
                self.sh("input text '" + trozo.replace("'", "'\\''") + "'")

    # ---------- aplicaciones ----------
    def paquetes(self):
        if self._paquetes is None:
            out = self.sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
            ps = sorted({m.group(1) for m in re.finditer(r"^\s*([A-Za-z0-9_.]+)/", out, re.M)})
            if not ps:
                ps = sorted(l.split(":", 1)[1].strip() for l in self.sh("pm list packages").splitlines() if ":" in l)
            self._paquetes = ps
        return self._paquetes

    def resolver_app(self, nombre):
        """Nombre que dice la persona -> paquete instalado, o lanza un error con las candidatas."""
        ps = self.paquetes()
        n = norm(nombre).strip()
        if n in ps:
            return n
        alias = APPS.get(n)
        if alias and alias in ps:
            return alias
        clave = re.sub(r"[^a-z0-9]", "", n)
        hits = [p for p in ps if clave and clave in re.sub(r"[^a-z0-9]", "", p.lower())]
        if alias:
            base = alias.rsplit(".", 1)[-1]
            hits += [p for p in ps if base in p and p not in hits]
        if len(hits) == 1:
            return hits[0]
        if hits:
            hits.sort(key=len)
            exacto = [p for p in hits if p.rsplit(".", 1)[-1] == clave]
            if exacto:
                return exacto[0]
            raise TelefonoError(f"hay varias aplicaciones parecidas a «{nombre}»: {', '.join(hits[:8])}. Dime el paquete exacto.")
        raise TelefonoError(f"no encuentro ninguna aplicación instalada parecida a «{nombre}». Usa telefono_apps para ver las que hay.")

    def abrir_app(self, nombre):
        with self.candado:
            pkg = self.resolver_app(nombre)
            out = self.sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
            if "No activities found" in out:
                raise TelefonoError(f"{pkg} no se puede abrir desde el menú de aplicaciones")
            time.sleep(1.6)
            return pkg

    def abrir_url(self, url):
        if not re.match(r"^https?://[^\s'\"]+$", url):
            raise TelefonoError("solo abro direcciones http o https en el teléfono")
        with self.candado:
            self.sh("am start -a android.intent.action.VIEW -d '" + url.replace("'", "%27") + "'")
            time.sleep(1.6)

    def notificaciones(self, limite=25):
        """Las notificaciones que hay ahora: [{app, titulo, texto}]. Depende de la versión de Android."""
        out = self.sh("dumpsys notification --noredact", timeout=30)
        res, vistos = [], set()
        for bloque in re.split(r"(?:^|\n)\s*NotificationRecord\(", out)[1:]:
            pkg = re.search(r"pkg=([A-Za-z0-9_.]+)", bloque)
            titulo = re.search(r"android\.title=(?:String|SpannableString) \((.*?)\)\s*\n", bloque)
            texto = re.search(r"android\.(?:bigText|text)=(?:String|SpannableString) \((.*?)\)\s*\n", bloque, re.S)
            if not pkg or not (titulo or texto):
                continue
            item = {"app": pkg.group(1), "titulo": titulo.group(1).strip() if titulo else "",
                    "texto": " ".join(texto.group(1).split())[:300] if texto else ""}
            clave = (item["app"], item["titulo"], item["texto"])
            if clave not in vistos:
                vistos.add(clave)
                res.append(item)
        return res[:limite]


TELEFONO = Telefono()
