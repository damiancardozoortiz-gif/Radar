"""El navegador propio de JARVIS. Solo librería estándar.

Arranca un Chrome/Chromium/Edge con SU PROPIO perfil (perfil_navegador/, fuera de git) y lo maneja
por el protocolo de depuración (CDP) a través de un WebSocket local. No usa tu navegador ni tus
sesiones: lo que JARVIS tenga abierto lo ha abierto él.

Cómo ve la página: una lista numerada de los elementos con los que se puede interactuar
([7] botón «Buscar») más el texto visible. Los clics van por número, no por coordenadas: así el
servidor sabe QUÉ se va a pulsar antes de pulsarlo, y el freno puede pararlo.
"""
import atexit
import base64
import ipaddress
import json
import os
import platform
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent
PERFIL = ROOT / "perfil_navegador"
MARCA = "JarvisAgente/1"          # el servidor de JARVIS rechaza cualquier petición con esta marca
ANCHO, ALTO = 1280, 860
MAX_ELEMENTOS = 140


class NavegadorError(Exception):
    pass


# ------------------------------------------------------------------ direcciones permitidas
def _locales_permitidos():
    """host:puerto locales que se permiten (solo para las pruebas). Nunca el propio JARVIS."""
    return {h.strip().lower() for h in os.environ.get("JARVIS_WEB_PRUEBAS", "").split(",") if h.strip()}


def url_bloqueada(url):
    """Devuelve el motivo si JARVIS no debe abrir esa dirección, o None si puede."""
    try:
        u = urlparse(url)
    except ValueError:
        return "dirección no válida"
    if u.scheme in ("about",) and url in ("about:blank",):
        return None
    if u.scheme not in ("http", "https"):
        return f"solo abro páginas http y https (no «{u.scheme}:»)"
    host = (u.hostname or "").lower()
    if not host:
        return "dirección sin dominio"
    puerto = u.port or (443 if u.scheme == "https" else 80)
    if f"{host}:{puerto}" in _locales_permitidos() and str(puerto) != os.environ.get("JARVIS_PORT", "8790"):
        return None
    local = host == "localhost" or host.endswith(".localhost") or host.endswith(".local") or host.endswith(".internal")
    try:
        ip = ipaddress.ip_address(host.strip("[]"))
        local = local or ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_reserved
    except ValueError:
        pass
    if local:
        return "no abro direcciones de tu red local ni de esta computadora"
    return None


# ------------------------------------------------------------------ WebSocket mínimo (cliente)
class _WS:
    def __init__(self, url, timeout=10):
        u = urlparse(url)
        self.sock = socket.create_connection((u.hostname, u.port), timeout=timeout)
        clave = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {u.path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {clave}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        cab = b""
        while b"\r\n\r\n" not in cab:
            trozo = self.sock.recv(4096)
            if not trozo:
                raise NavegadorError("el navegador cerró la conexión")
            cab += trozo
        if b" 101 " not in cab.split(b"\r\n", 1)[0]:
            raise NavegadorError("el navegador rechazó la conexión: " + cab[:80].decode("latin1"))
        self.resto = cab.split(b"\r\n\r\n", 1)[1]
        self.sock.settimeout(None)
        self.candado = threading.Lock()

    def _leer(self, n):
        while len(self.resto) < n:
            trozo = self.sock.recv(65536)
            if not trozo:
                raise ConnectionError("conexión cerrada")
            self.resto += trozo
        dato, self.resto = self.resto[:n], self.resto[n:]
        return dato

    def _trama(self, op, datos):
        mascara = os.urandom(4)
        n = len(datos)
        cab = bytes([0x80 | op])
        if n < 126:
            cab += bytes([0x80 | n])
        elif n < 65536:
            cab += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            cab += bytes([0x80 | 127]) + struct.pack(">Q", n)
        cuerpo = bytes(b ^ mascara[i % 4] for i, b in enumerate(datos)) if n < 4096 else _xor(datos, mascara)
        with self.candado:
            self.sock.sendall(cab + mascara + cuerpo)

    def enviar(self, texto):
        self._trama(0x1, texto.encode("utf-8"))

    def recibir(self):
        partes = []
        while True:
            b0, b1 = self._leer(2)
            op, n = b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._leer(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._leer(8))[0]
            mascara = self._leer(4) if b1 & 0x80 else None
            datos = self._leer(n)
            if mascara:
                datos = _xor(datos, mascara)
            if op == 0x8:
                raise ConnectionError("conexión cerrada por el navegador")
            if op == 0x9:
                self._trama(0xA, datos)
                continue
            if op == 0xA:
                continue
            partes.append(datos)
            if b0 & 0x80:
                return b"".join(partes).decode("utf-8", "replace")

    def cerrar(self):
        try:
            self.sock.close()
        except OSError:
            pass


def _xor(datos, mascara):
    n = len(datos)
    clave = (mascara * (n // 4 + 1))[:n]
    return (int.from_bytes(datos, "big") ^ int.from_bytes(clave, "big")).to_bytes(n, "big") if n else b""


# ------------------------------------------------------------------ una pestaña por CDP
class _Pestana:
    def __init__(self, ws_url, tid):
        self.id = tid
        self.ws = _WS(ws_url)
        self.n = 0
        self.espera = {}
        self.candado = threading.Lock()
        self.viva = True
        self.dialogo = None
        self.cargada = threading.Event()
        threading.Thread(target=self._lector, daemon=True).start()
        self.cmd("Page.enable")
        self.cmd("Runtime.enable")

    def _lector(self):
        try:
            while True:
                m = json.loads(self.ws.recibir())
                if "id" in m:
                    with self.candado:
                        caja = self.espera.pop(m["id"], None)
                    if caja:
                        caja[1] = m
                        caja[0].set()
                elif m.get("method") == "Page.javascriptDialogOpening":
                    p = m["params"]
                    # Un «¿seguro?» se rechaza siempre; un aviso simple se cierra. JARVIS lo cuenta después.
                    aceptar = p.get("type") in ("alert", "beforeunload")
                    self.dialogo = f"La página mostró un cuadro de tipo {p.get('type')}: «{p.get('message', '')[:200]}». " + (
                        "Lo cerré." if aceptar else "Lo rechacé (no confirmo cuadros de la página).")
                    threading.Thread(target=self._cerrar_dialogo, args=(aceptar,), daemon=True).start()
                elif m.get("method") == "Page.loadEventFired":
                    self.cargada.set()
        except Exception:
            self.viva = False
            with self.candado:
                for caja in self.espera.values():
                    caja[0].set()
                self.espera.clear()

    def _cerrar_dialogo(self, aceptar):
        try:
            self.cmd("Page.handleJavaScriptDialog", {"accept": aceptar}, timeout=5)
        except NavegadorError:
            pass

    def cmd(self, metodo, params=None, timeout=30):
        if not self.viva:
            raise NavegadorError("la pestaña se cerró")
        with self.candado:
            self.n += 1
            i = self.n
            caja = self.espera[i] = [threading.Event(), None]
        self.ws.enviar(json.dumps({"id": i, "method": metodo, "params": params or {}}))
        if not caja[0].wait(timeout):
            with self.candado:
                self.espera.pop(i, None)
            raise NavegadorError(f"el navegador no respondió a tiempo ({metodo})")
        m = caja[1]
        if m is None:
            raise NavegadorError("la pestaña se cerró")
        if "error" in m:
            raise NavegadorError(f"{metodo}: {m['error'].get('message', 'error')}")
        return m.get("result", {})

    def js(self, expresion, timeout=20):
        r = self.cmd("Runtime.evaluate", {"expression": expresion, "returnByValue": True, "awaitPromise": True},
                     timeout=timeout)
        if r.get("exceptionDetails"):
            d = r["exceptionDetails"]
            raise NavegadorError("error en la página: " + str((d.get("exception") or {}).get("description") or d.get("text"))[:200])
        return r.get("result", {}).get("value")


# ------------------------------------------------------------------ JavaScript que corre en la página
_JS_MIRAR = r"""
(() => {
  const MAX = %d;
  const SEL = 'a[href],button,input,select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],' +
    '[role=checkbox],[role=radio],[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],' +
    '[contenteditable=""],[contenteditable=true],[onclick]';
  const limpio = s => (s || '').replace(/\s+/g, ' ').trim();
  const dentro = [], fuera = [];
  function nombre(el) {
    let n = el.getAttribute('aria-label');
    if (!n && el.labels && el.labels.length) n = el.labels[0].innerText;
    if (!n && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) n = el.placeholder || (/^(submit|button|reset)$/.test(el.type) ? el.value : '') || el.name;
    if (!n) n = el.innerText;
    if (!limpio(n)) { const im = el.querySelector && el.querySelector('img[alt],svg[aria-label]'); if (im) n = im.getAttribute('alt') || im.getAttribute('aria-label'); }
    if (!limpio(n)) n = el.title || el.getAttribute('name') || el.id || '';
    return limpio(n).slice(0, 90);
  }
  function clase(el) {
    const t = el.tagName, rol = el.getAttribute('role') || '', tipo = (el.type || '').toLowerCase();
    if (t === 'SELECT' || rol === 'combobox') return 'lista';
    if (tipo === 'checkbox' || rol === 'checkbox' || rol === 'switch') return 'casilla';
    if (tipo === 'radio' || rol === 'radio' || rol === 'option') return 'opción';
    if (t === 'TEXTAREA' || rol === 'textbox' || rol === 'searchbox' || el.isContentEditable) return 'campo';
    if (t === 'INPUT') return /^(submit|button|reset|image)$/.test(tipo) ? 'botón' : (tipo === 'file' ? 'archivo' : 'campo');
    if (t === 'A' || rol === 'link') return 'enlace';
    if (t === 'BUTTON' || rol === 'button' || t === 'SUMMARY') return 'botón';
    if (rol === 'tab') return 'pestaña';
    return 'elemento';
  }
  function visitar(raiz, ox, oy) {
    let todos;
    try { todos = raiz.querySelectorAll('*'); } catch (e) { return; }
    const vista = (raiz.defaultView || (raiz.ownerDocument && raiz.ownerDocument.defaultView) || window);
    for (const el of todos) {
      if (el.shadowRoot) visitar(el.shadowRoot, ox, oy);
      if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') {
        try { const d = el.contentDocument; if (d) { const r = el.getBoundingClientRect(); visitar(d, ox + r.left, oy + r.top); } } catch (e) {}
        continue;
      }
      if (!el.matches(SEL)) continue;
      if (el.tagName === 'INPUT' && el.type === 'hidden') continue;
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) continue;
      const cs = vista.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none') continue;
      const cx = ox + r.left + r.width / 2, cy = oy + r.top + r.height / 2;
      const visible = cx >= 0 && cy >= 0 && cx <= innerWidth && cy <= innerHeight;
      (visible ? dentro : fuera).push(el);
    }
  }
  visitar(document, 0, 0);
  const elegidos = dentro.concat(fuera).slice(0, MAX);
  window.__jarvis = elegidos;
  const filas = elegidos.map((el, i) => {
    const c = clase(el), tipo = (el.type || '').toLowerCase();
    let extra = '';
    if (c === 'campo') extra = tipo === 'password' ? ' (contraseña)' : (el.value || (el.isContentEditable ? limpio(el.innerText) : '')) ? ' valor=«' + limpio(el.value || el.innerText).slice(0, 60) + '»' : ' (vacío)';
    if (c === 'lista' && el.options) extra = ' elegido=«' + limpio((el.options[el.selectedIndex] || {}).text) + '» opciones: ' + Array.from(el.options).slice(0, 12).map(o => limpio(o.text)).join(' | ');
    if (c === 'casilla' || c === 'opción') extra = (el.checked || el.getAttribute('aria-checked') === 'true' || el.getAttribute('aria-selected') === 'true') ? ' (marcada)' : ' (sin marcar)';
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') extra += ' (desactivado)';
    if (i >= dentro.length) extra += ' (fuera de la vista)';
    return '[' + (i + 1) + '] ' + c + ' «' + nombre(el) + '»' + extra;
  });
  const texto = limpio(document.body ? document.body.innerText : '');
  return { titulo: document.title, url: location.href, filas, omitidos: dentro.length + fuera.length - elegidos.length,
           texto: texto.slice(0, %d), largo: texto.length,
           abajo: Math.max(0, Math.round((document.documentElement.scrollHeight - innerHeight - scrollY))) };
})()
"""

_JS_INFO = r"""
(() => {
  const el = %s;
  if (!el || !el.isConnected || el === document.body) return null;
  const limpio = s => (s || '').replace(/\s+/g, ' ').trim();
  const f = el.form || el.closest('form');
  const tipo = (el.type || '').toLowerCase();
  const pista = [el.name, el.id, el.getAttribute('autocomplete'), el.getAttribute('aria-label'), el.placeholder].join(' ').toLowerCase();
  let n = el.getAttribute('aria-label') || (el.labels && el.labels.length ? el.labels[0].innerText : '') ||
          (el.tagName === 'INPUT' ? (el.placeholder || el.value || el.name) : el.innerText) || el.title || '';
  return { nombre: limpio(n).slice(0, 120), etiqueta: el.tagName, tipo,
           password: tipo === 'password',
           tarjeta: /cc-|card|tarjeta|cvv|cvc|caducidad|expir|iban/.test(pista),
           busqueda: tipo === 'search' || el.getAttribute('role') === 'searchbox' || /(^|[^a-z])(q|query|search|buscar|busqueda|s|k)([^a-z]|$)/.test((el.name || '').toLowerCase()) || !!(f && (f.getAttribute('role') === 'search' || /search|buscar/.test((f.action || '') + ' ' + (f.id || '') + ' ' + (f.className || '')))),
           formPost: !!(f && (f.method || 'get').toLowerCase() === 'post'),
           enFormulario: !!f, esLista: el.tagName === 'SELECT', editable: el.isContentEditable || el.tagName === 'INPUT' || el.tagName === 'TEXTAREA',
           envia: tipo === 'submit' || (el.tagName === 'BUTTON' && !!f && tipo !== 'button' && tipo !== 'reset') };
})()
"""

_JS_PUNTO = r"""
(async () => {
  const el = (window.__jarvis || [])[%d];
  if (!el || !el.isConnected) return null;
  el.scrollIntoView({ block: 'center', inline: 'center' });
  await new Promise(r => setTimeout(r, 120));
  const r = el.getBoundingClientRect();
  let x = r.left + r.width / 2, y = r.top + r.height / 2, w = el.ownerDocument.defaultView;
  while (w && w.frameElement) { const fr = w.frameElement.getBoundingClientRect(); x += fr.left; y += fr.top; w = w.parent; }
  return { x, y, ancho: innerWidth, alto: innerHeight };
})()
"""

_JS_ENFOCAR = r"""
(() => {
  const el = (window.__jarvis || [])[%d];
  if (!el || !el.isConnected) return false;
  el.focus();
  if (el.select) { try { el.select(); } catch (e) {} }
  else if (el.isContentEditable) { const s = el.ownerDocument.getSelection(); s.selectAllChildren(el); }
  return true;
})()
"""

_JS_ELEGIR = r"""
(() => {
  const el = (window.__jarvis || [])[%d], q = %s.toLowerCase().trim();
  if (!el || !el.isConnected || !el.options) return null;
  const ops = Array.from(el.options);
  const o = ops.find(o => o.text.trim().toLowerCase() === q) || ops.find(o => o.text.toLowerCase().includes(q)) || ops.find(o => o.value.toLowerCase() === q);
  if (!o) return { ok: false, opciones: ops.map(o => o.text.trim()).slice(0, 30) };
  el.value = o.value;
  el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
  return { ok: true, elegido: o.text.trim() };
})()
"""

TECLAS = {  # nombre -> (key, code, código virtual, texto)
    "enter": ("Enter", "Enter", 13, "\r"), "tab": ("Tab", "Tab", 9, ""), "escape": ("Escape", "Escape", 27, ""),
    "borrar": ("Backspace", "Backspace", 8, ""), "suprimir": ("Delete", "Delete", 46, ""),
    "arriba": ("ArrowUp", "ArrowUp", 38, ""), "abajo": ("ArrowDown", "ArrowDown", 40, ""),
    "izquierda": ("ArrowLeft", "ArrowLeft", 37, ""), "derecha": ("ArrowRight", "ArrowRight", 39, ""),
    "pagina_abajo": ("PageDown", "PageDown", 34, ""), "pagina_arriba": ("PageUp", "PageUp", 33, ""),
    "inicio": ("Home", "Home", 36, ""), "fin": ("End", "End", 35, ""), "espacio": (" ", "Space", 32, " "),
}


# ------------------------------------------------------------------ el navegador
def buscar_chrome():
    """El ejecutable de Chrome, Chromium, Edge o Brave. JARVIS_CHROME lo fija a mano."""
    fijo = os.environ.get("JARVIS_CHROME", "").strip()
    if fijo:
        return fijo if Path(fijo).exists() else None
    for nombre in ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge",
                   "brave-browser", "chrome"):
        exe = shutil.which(nombre)
        if exe:
            return exe
    candidatos = [
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
    ]
    for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), os.environ.get("LOCALAPPDATA")):
        if base:
            candidatos += [base + r"\Google\Chrome\Application\chrome.exe", base + r"\Microsoft\Edge\Application\msedge.exe"]
    for c in candidatos:
        if Path(c).exists():
            return c
    return None


def _agente_de_usuario(exe):
    """Un agente de usuario normal de Chrome con la marca de JARVIS al final, desde el primer momento."""
    version = "140.0.0.0"
    try:
        salida = subprocess.run([exe, "--version"], capture_output=True, text=True, timeout=10).stdout
        m = re.search(r"(\d+)\.\d+\.\d+\.\d+", salida)
        if m:
            version = m.group(1) + ".0.0.0"
    except (OSError, subprocess.SubprocessError):
        pass
    so = {"Darwin": "Macintosh; Intel Mac OS X 10_15_7", "Windows": "Windows NT 10.0; Win64; x64"}.get(
        platform.system(), "X11; Linux x86_64")
    return f"Mozilla/5.0 ({so}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{version} Safari/537.36 {MARCA}"


class Navegador:
    def __init__(self):
        self.proc = None
        self.puerto = None
        self.pestanas = {}
        self.actual = None
        self.candado = threading.RLock()
        self.ultimo_dialogo = None

    # ---------- arranque ----------
    def disponible(self):
        return buscar_chrome() is not None

    def abierto(self):
        return self.proc is not None and self.proc.poll() is None

    def _oculto(self):
        modo = os.environ.get("JARVIS_NAVEGADOR", "").strip().lower()
        if modo in ("oculto", "visible"):
            return modo == "oculto"
        return platform.system() == "Linux" and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))

    def arrancar(self):
        with self.candado:
            if self.abierto():
                return
            exe = buscar_chrome()
            if not exe:
                raise NavegadorError("no encuentro Chrome, Chromium ni Edge en esta computadora (instala uno o pon JARVIS_CHROME)")
            PERFIL.mkdir(exist_ok=True)
            marca = PERFIL / "DevToolsActivePort"
            self._cerrar_huerfano(marca)
            marca.unlink(missing_ok=True)
            args = [exe, "--remote-debugging-port=0", f"--user-data-dir={PERFIL}", "--no-first-run",
                    "--no-default-browser-check", "--disable-default-apps", "--disable-popup-blocking",
                    f"--window-size={ANCHO},{ALTO}", f"--user-agent={_agente_de_usuario(exe)}",
                    "--disable-features=Translate,MediaRouter", "--password-store=basic",
                    "--disable-background-networking", "--disable-component-update"]
            if self._oculto():
                args.append("--headless=new")
            if hasattr(os, "geteuid") and os.geteuid() == 0:
                args.append("--no-sandbox")
            args += os.environ.get("JARVIS_CHROME_ARGS", "").split()
            args.append("about:blank")
            self.proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            limite = time.time() + 25
            while time.time() < limite:
                if self.proc.poll() is not None:
                    raise NavegadorError("el navegador se cerró al arrancar")
                if marca.exists():
                    lineas = marca.read_text().split()
                    if lineas:
                        self.puerto = int(lineas[0])
                        break
                time.sleep(0.1)
            else:
                self.cerrar()
                raise NavegadorError("el navegador no arrancó a tiempo")
            self.pestanas, self.actual = {}, None
            self._elegir_pestana()

    def _cerrar_huerfano(self, marca):
        """Si un JARVIS anterior murió y dejó su navegador abierto con este perfil, lo cierra antes de abrir otro."""
        try:
            self.puerto = int(marca.read_text().split()[0])
            ws = _WS(self._http("/json/version")["webSocketDebuggerUrl"], timeout=3)
            ws.enviar(json.dumps({"id": 1, "method": "Browser.close"}))
            time.sleep(1.5)
            ws.cerrar()
        except (OSError, ValueError, IndexError, KeyError, TypeError, NavegadorError):
            pass
        self.puerto = None
        try:  # sin puerto conocido: el perfil apunta al proceso que lo tiene abierto (Linux y Mac)
            pid = int(os.readlink(PERFIL / "SingletonLock").rsplit("-", 1)[1])
            os.kill(pid, 15)
            time.sleep(1.0)
        except (OSError, ValueError, IndexError):
            pass

    def cerrar(self):
        with self.candado:
            for p in self.pestanas.values():
                p.ws.cerrar()
            self.pestanas, self.actual = {}, None
            if self.proc:
                try:
                    self.proc.terminate()
                    self.proc.wait(timeout=5)
                except (OSError, subprocess.SubprocessError):
                    try:
                        self.proc.kill()
                    except OSError:
                        pass
            self.proc = None

    def _http(self, ruta, metodo="GET"):
        req = urllib.request.Request(f"http://127.0.0.1:{self.puerto}{ruta}", method=metodo)
        abridor = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with abridor.open(req, timeout=10) as r:
            cuerpo = r.read()
        try:
            return json.loads(cuerpo)
        except ValueError:  # /json/close responde con texto
            return None

    def _paginas(self):
        return [t for t in self._http("/json/list") if t.get("type") == "page"]

    def _elegir_pestana(self, preferida=None):
        """Se queda con la pestaña pedida, o la más nueva que no conocía, o la actual."""
        paginas = self._paginas()
        vivas = {t["id"] for t in paginas}
        for tid in list(self.pestanas):
            if tid not in vivas or not self.pestanas[tid].viva:
                self.pestanas.pop(tid).ws.cerrar()
        if not paginas:
            nueva = self._http("/json/new?about:blank", "PUT")
            paginas = [nueva]
        nuevas = [t for t in paginas if t["id"] not in self.pestanas]
        objetivo = preferida or (nuevas[0]["id"] if nuevas and self.actual else None) or \
            (self.actual if self.actual in vivas else paginas[0]["id"])
        for t in paginas:
            if t["id"] not in self.pestanas:
                try:
                    self.pestanas[t["id"]] = _Pestana(t["webSocketDebuggerUrl"], t["id"])
                except (OSError, NavegadorError):
                    continue
        if objetivo not in self.pestanas:
            objetivo = next(iter(self.pestanas), None)
        if objetivo is None:
            raise NavegadorError("no hay ninguna pestaña abierta")
        cambio = objetivo != self.actual
        self.actual = objetivo
        if cambio:
            try:
                self.pestanas[objetivo].cmd("Page.bringToFront", timeout=5)
            except NavegadorError:
                pass
        return self.pestanas[objetivo]

    def _p(self):
        if not self.abierto():
            self.arrancar()
        p = self.pestanas.get(self.actual)
        if not p or not p.viva:
            p = self._elegir_pestana()
        return p

    # ---------- esperas y vigilancia ----------
    def _asentar(self, p, maximo=8.0):
        """Espera a que la página termine de cargar después de una acción."""
        time.sleep(0.35)
        fin = time.time() + maximo
        while time.time() < fin:
            try:
                if p.js("document.readyState", timeout=4) == "complete":
                    break
            except NavegadorError:
                pass
            time.sleep(0.25)
        time.sleep(0.25)

    def _vigilar(self):
        """Después de cada acción: sigue a la pestaña nueva si se abrió una y corta si acabó en una dirección prohibida."""
        p = self._elegir_pestana()
        try:
            url = p.js("location.href", timeout=5) or ""
        except NavegadorError:
            url = ""
        motivo = url_bloqueada(url) if url and not url.startswith("about:") and not url.startswith("chrome-error:") else None
        if motivo:
            p.cmd("Page.navigate", {"url": "about:blank"})
            raise NavegadorError(f"la página intentó llevarme a {url[:80]} y lo he cortado: {motivo}")
        return p

    def _recoger_dialogo(self, p):
        d, p.dialogo = p.dialogo, None
        return d

    # ---------- acciones ----------
    def ir(self, url):
        url = url.strip()
        if not re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", url):
            url = "https://" + url
        motivo = url_bloqueada(url)
        if motivo:
            raise NavegadorError(motivo)
        with self.candado:
            p = self._p()
            p.cargada.clear()
            r = p.cmd("Page.navigate", {"url": url})
            if r.get("errorText") and "ERR_ABORTED" not in r["errorText"]:
                raise NavegadorError(f"no pude abrir {url}: {r['errorText']}")
            p.cargada.wait(20)
            self._asentar(p, 4)
            self._vigilar()

    def mirar(self, texto=1500):
        with self.candado:
            p = self._vigilar() if self.abierto() else self._p()
            d = p.js(_JS_MIRAR % (MAX_ELEMENTOS, texto))
            d["dialogo"] = self._recoger_dialogo(p)
            return d

    def leer(self, desde=0, cuanto=6000):
        with self.candado:
            p = self._p()
            t = p.js("(document.body ? document.body.innerText : '').replace(/[ \\t]+/g, ' ').replace(/\\n\\s*\\n+/g, '\\n')")
            return {"titulo": p.js("document.title"), "url": p.js("location.href"), "texto": t[desde:desde + cuanto],
                    "largo": len(t), "desde": desde}

    def info(self, ref):
        with self.candado:
            d = self._p().js(_JS_INFO % f"(window.__jarvis || [])[{int(ref) - 1}]")
        if d is None:
            raise NavegadorError(f"el elemento [{ref}] ya no existe: la página cambió. Vuelve a mirarla.")
        return d

    def info_activo(self):
        """Lo mismo que info(), pero del elemento que tiene el cursor ahora (o None si no hay ninguno)."""
        with self.candado:
            if not self.abierto():
                return None
            return self._p().js(_JS_INFO % "document.activeElement")

    def donde(self):
        with self.candado:
            if not self.abierto():
                return ""
            try:
                return self._p().js("location.href", timeout=5) or ""
            except NavegadorError:
                return ""

    def _raton(self, p, x, y):
        for tipo in ("mouseMoved", "mousePressed", "mouseReleased"):
            p.cmd("Input.dispatchMouseEvent", {"type": tipo, "x": x, "y": y, "button": "none" if tipo == "mouseMoved" else "left",
                                               "clickCount": 0 if tipo == "mouseMoved" else 1})

    def clic(self, ref):
        """Pulsa el elemento [ref]. Devuelve dónde pulsó (en fracción de la ventana) para pintarlo en pantalla."""
        with self.candado:
            p = self._p()
            pt = p.js(_JS_PUNTO % (int(ref) - 1))
            if pt is None:
                raise NavegadorError(f"el elemento [{ref}] ya no existe: la página cambió. Vuelve a mirarla.")
            self._raton(p, pt["x"], pt["y"])
            self._asentar(p)
            self._vigilar()
            return {"fx": pt["x"] / max(1, pt["ancho"]), "fy": pt["y"] / max(1, pt["alto"])}

    def escribir(self, ref, texto, enter=False):
        with self.candado:
            p = self._p()
            pt = p.js(_JS_PUNTO % (int(ref) - 1))
            if pt is None:
                raise NavegadorError(f"el elemento [{ref}] ya no existe: la página cambió. Vuelve a mirarla.")
            self._raton(p, pt["x"], pt["y"])
            time.sleep(0.15)
            p.js(_JS_ENFOCAR % (int(ref) - 1))
            if texto:
                p.cmd("Input.insertText", {"text": texto})
            else:
                self._tecla(p, "suprimir")
            if enter:
                time.sleep(0.15)
                self._tecla(p, "enter")
                self._asentar(p)
            else:
                time.sleep(0.3)
            self._vigilar()
            return {"fx": pt["x"] / max(1, pt["ancho"]), "fy": pt["y"] / max(1, pt["alto"])}

    def elegir(self, ref, opcion):
        with self.candado:
            p = self._p()
            r = p.js(_JS_ELEGIR % (int(ref) - 1, json.dumps(opcion)))
            if r is None:
                raise NavegadorError(f"el elemento [{ref}] ya no existe o no es una lista.")
            if not r["ok"]:
                raise NavegadorError("esa opción no está en la lista. Opciones: " + " | ".join(r["opciones"]))
            self._asentar(p, 3)
            return r["elegido"]

    def _tecla(self, p, nombre):
        k = TECLAS.get(nombre)
        if not k:
            raise NavegadorError("tecla desconocida. Teclas: " + ", ".join(TECLAS))
        base = {"key": k[0], "code": k[1], "windowsVirtualKeyCode": k[2], "nativeVirtualKeyCode": k[2]}
        p.cmd("Input.dispatchKeyEvent", {**base, "type": "keyDown", **({"text": k[3]} if k[3] else {})})
        p.cmd("Input.dispatchKeyEvent", {**base, "type": "keyUp"})

    def tecla(self, nombre):
        with self.candado:
            p = self._p()
            self._tecla(p, nombre)
            self._asentar(p, 4)
            self._vigilar()

    def desplazar(self, direccion):
        with self.candado:
            p = self._p()
            paso = {"abajo": "scrollBy(0, innerHeight * 0.85)", "arriba": "scrollBy(0, -innerHeight * 0.85)",
                    "final": "scrollTo(0, document.documentElement.scrollHeight)", "principio": "scrollTo(0, 0)"}.get(direccion)
            if not paso:
                raise NavegadorError("dirección desconocida: abajo, arriba, final o principio")
            p.js(f"window.{paso}")
            time.sleep(0.5)

    def atras(self):
        with self.candado:
            p = self._p()
            h = p.cmd("Page.getNavigationHistory")
            if h["currentIndex"] > 0:
                p.cmd("Page.navigateToHistoryEntry", {"entryId": h["entries"][h["currentIndex"] - 1]["id"]})
                self._asentar(p)
            elif len(self.pestanas) > 1:  # una pestaña que abrió la página: se cierra y se vuelve a la anterior
                self._http(f"/json/close/{p.id}")
                time.sleep(0.3)
                self.actual = None
            else:
                raise NavegadorError("no hay página anterior")
            self._vigilar()

    def captura(self, calidad=60):
        """JPEG de lo que se ve ahora, o None si el navegador no está abierto."""
        with self.candado:
            if not self.abierto():
                return None
            try:
                r = self._p().cmd("Page.captureScreenshot", {"format": "jpeg", "quality": calidad}, timeout=15)
            except NavegadorError:
                return None
            return base64.b64decode(r["data"])


NAVEGADOR = Navegador()
atexit.register(NAVEGADOR.cerrar)
