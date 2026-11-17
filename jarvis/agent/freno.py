"""El freno: qué puede hacer JARVIS solo y qué espera tu sí.

JARVIS navega, abre aplicaciones, busca, lee y prepara todo sin preguntar. Pero lo que no se puede deshacer
(enviar, pagar, comprar, borrar, publicar, llamar, instalar) se para aquí, en el servidor, ANTES de tocar nada,
y espera un clic tuyo en pantalla. No depende de que el modelo «se porte bien»: el servidor mira qué elemento se
va a pulsar y decide. Tres capas:

  1. Lo que el propio agente declara como irreversible.
  2. Lo que dice el elemento que se va a pulsar («Enviar», «Pagar», «Eliminar»…), venga de donde venga la orden.
  3. Zonas sensibles (bancos, pagos, monederos): cualquier acción dentro pide permiso una vez por tarea.

Y dos cosas que no hace nunca, ni con permiso: escribir contraseñas y escribir datos de tarjeta.
"""
import re
import threading
import time
import unicodedata

ESPERA_MAX = 600          # segundos que una acción espera tu respuesta antes de darse por rechazada

IRREVERSIBLE = re.compile(r"\b(" + "|".join([
    r"enviar\w*", r"envia\w*", r"send\w*", r"mandar", r"pagar\w*", r"pago", r"pay( now)?", r"comprar\w*", r"compra ya",
    r"buy\w*", r"purchase", r"hacer pedido", r"realizar pedido", r"pedir ahora", r"place order", r"order now",
    r"confirmar\w*", r"confirm\w*", r"transferir\w*", r"transfer\w*", r"eliminar\w*", r"borrar\w*", r"delete\w*", r"remove",
    r"desinstalar\w*", r"uninstall\w*", r"instalar\w*", r"install\w*", r"publicar\w*", r"post", r"tweet\w*", r"postear",
    r"llamar\w*", r"call", r"videollamada", r"suscribir\w*", r"subscribe\w*", r"reservar\w*", r"book now", r"checkout",
    r"finalizar compra", r"tramitar pedido", r"donar\w*", r"donate", r"retirar\w*", r"withdraw\w*", r"depositar\w*", r"firmar\w*",
    r"cerrar cuenta", r"darse de baja", r"dar de baja", r"cancelar suscripcion", r"restablecer\w*", r"reset\w*", r"formatear\w*",
    r"vaciar\w*", r"bloquear contacto", r"reenviar\w*", r"forward", r"aceptar y pagar", r"apostar\w*", r"bet",
]) + r")\b")
ZONA_SENSIBLE = re.compile(
    r"bank|banco|banca|bbva|santander|caixa|sabadell|bankinter|\bing\b|openbank|itau|galicia|macro|brubank|uala|"
    r"nubank|bancolombia|davivienda|daviplata|nequi|yape|plin|bizum|wallet|billetera|paypal|mercadopago|mercado\.?pago|"
    r"venmo|cashapp|stripe|wise|revolut|n26|binance|coinbase|kraken|crypto|metamask|trading|broker|pagos|payments|"
    r"hacienda|agenciatributaria|afip|sat\.gob|seguridadsocial|pay\.google|com\.google\.android\.apps\.walletnfcrel", re.I)
MENSAJERIA = re.compile(r"whatsapp|telegram|messaging|messenger|orca|signal|gm$|\.gm\b|mail|outlook|instagram|twitter|"
                        r"facebook|katana|tiktok|discord|slack|linkedin|sms|mms|threads", re.I)


def norm(s):
    s = unicodedata.normalize("NFD", str(s or "").lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


class Rechazo(Exception):
    """La acción no se hace. El mensaje se le devuelve al agente tal cual."""


class Freno:
    def __init__(self):
        self.pendientes = {}
        self.candado = threading.Lock()
        self.n = 0
        self.zonas_ok = set()          # zonas sensibles que aprobaste para la tarea en curso
        self.cancelado = threading.Event()
        self.al_cambiar = None         # aviso a la pantalla

    # ---------- reglas ----------
    @staticmethod
    def motivo(etiqueta="", zona="", declarada=False, enter=False, formulario_post=False, busqueda=False):
        """Por qué esta acción necesita tu sí, o None si JARVIS puede hacerla solo."""
        e = norm(etiqueta)
        if len(e) > 60:      # una etiqueta larga es una fila o un texto, no un botón: solo cuenta cómo empieza
            e = e[:30]
        m = IRREVERSIBLE.search(e)
        if m:
            return f"el botón dice «{etiqueta.strip()[:60]}» y eso no se puede deshacer"
        if enter and not busqueda and (formulario_post or MENSAJERIA.search(zona or "")):
            return "pulsar Intro aquí envía lo escrito"
        if declarada:
            return "JARVIS cree que esta acción no se puede deshacer"
        return None

    @staticmethod
    def zona_sensible(zona):
        return bool(zona and ZONA_SENSIBLE.search(zona))

    @staticmethod
    def prohibido_escribir(password=False, tarjeta=False):
        if password:
            return "Es un campo de contraseña. JARVIS no escribe contraseñas: escríbela tú y dile que siga."
        if tarjeta:
            return "Es un campo de datos de pago. JARVIS no escribe tarjetas ni cuentas: hazlo tú y dile que siga."
        return None

    # ---------- pedir permiso (bloquea hasta tu clic) ----------
    def nueva_tarea(self):
        self.zonas_ok.clear()
        self.cancelado.clear()

    def cancelar(self):
        """PAUSA o «detener»: todo lo que esperaba tu sí queda rechazado."""
        self.cancelado.set()
        with self.candado:
            for a in self.pendientes.values():
                if a["state"] == "pendiente":
                    a["state"], a["result"] = "descartado", "Cancelado."
                    a["evento"].set()

    def pedir(self, accion, donde, motivo, canal, detalle=""):
        """Bloquea hasta que apruebes o rechaces en pantalla. Lanza Rechazo si no apruebas."""
        if self.cancelado.is_set():
            raise Rechazo("La tarea está detenida.")
        with self.candado:
            self.n += 1
            aid = f"f{self.n}"
            a = self.pendientes[aid] = {"id": aid, "kind": "accion", "accion": accion, "donde": donde, "motivo": motivo,
                                        "canal": canal, "text": detalle, "state": "pendiente",
                                        "created": time.strftime("%H:%M:%S"), "evento": threading.Event()}
        self._avisar()
        a["evento"].wait(ESPERA_MAX)
        with self.candado:
            if a["state"] == "pendiente":
                a["state"], a["result"] = "descartado", f"Nadie respondió en {ESPERA_MAX // 60} minutos."
        self._avisar()
        if a["state"] != "aprobado":
            raise Rechazo(f"NO APROBADO por la persona ({a.get('result', 'rechazado')}). No lo intentes de otra manera: "
                          "cuéntale qué quedó preparado y termina.")
        return a

    def pedir_zona(self, zona, canal):
        if zona in self.zonas_ok:
            return
        self.pedir(f"Actuar dentro de {zona}", zona, "es una zona sensible (banco, pagos o dinero)", canal,
                   "Si apruebas, JARVIS podrá moverse por aquí durante esta tarea. Pagar, transferir o confirmar "
                   "seguirá pidiendo tu sí cada vez.")
        self.zonas_ok.add(zona)

    def decidir(self, aid, decision):
        with self.candado:
            a = self.pendientes.get(aid)
            if not a:
                return None
            if a["state"] == "pendiente":
                a["state"] = "aprobado" if decision == "aprobar" else "descartado"
                a["decided"] = time.strftime("%H:%M:%S")
                a["result"] = "Aprobado por ti." if a["state"] == "aprobado" else "Rechazado por ti."
                a["evento"].set()
        self._avisar()
        return self.publico(a)

    @staticmethod
    def publico(a):
        return {k: v for k, v in a.items() if k != "evento"}

    def lista(self):
        with self.candado:
            return [self.publico(a) for a in self.pendientes.values() if a["state"] == "pendiente"]

    def _avisar(self):
        if self.al_cambiar:
            try:
                self.al_cambiar()
            except Exception:
                pass


FRENO = Freno()
