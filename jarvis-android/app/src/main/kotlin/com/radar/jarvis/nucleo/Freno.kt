package com.radar.jarvis.nucleo

import java.text.Normalizer

/**
 * El freno: qué puede hacer JARVIS solo y qué espera tu sí.
 *
 * Abrir, buscar, leer, rellenar y preparar lo hace solo. Lo que no se puede deshacer (enviar, pagar, comprar,
 * borrar, publicar, llamar, instalar) se para AQUÍ, antes de tocar nada, y espera que pulses Aprobar. No depende
 * de que el modelo «se porte bien»: se mira qué elemento se va a pulsar. Tres capas:
 *   1. lo que el propio agente declara como irreversible;
 *   2. lo que dice el elemento que se va a pulsar («Enviar», «Pagar», «Eliminar»…);
 *   3. zonas sensibles (bancos, pagos, monederos): cualquier acción dentro pide permiso una vez por tarea.
 * Y dos cosas que no hace nunca, ni con permiso: escribir contraseñas y datos de tarjeta.
 */
object Freno {
    private val IRREVERSIBLE = Regex(
        "\\b(" + listOf(
            "enviar\\w*", "envia\\w*", "send\\w*", "mandar", "pagar\\w*", "pago", "pay( now)?", "comprar\\w*", "compra ya",
            "buy\\w*", "purchase", "hacer pedido", "realizar pedido", "pedir ahora", "place order", "order now",
            "confirmar\\w*", "confirm\\w*", "transferir\\w*", "transfer\\w*", "eliminar\\w*", "borrar\\w*", "delete\\w*",
            "remove", "desinstalar\\w*", "uninstall\\w*", "instalar\\w*", "install\\w*", "publicar\\w*", "post", "tweet\\w*",
            "postear", "llamar\\w*", "call", "videollamada", "suscribir\\w*", "subscribe\\w*", "reservar\\w*", "book now",
            "checkout", "finalizar compra", "tramitar pedido", "donar\\w*", "donate", "retirar\\w*", "withdraw\\w*",
            "depositar\\w*", "firmar\\w*", "cerrar cuenta", "darse de baja", "dar de baja", "cancelar suscripcion",
            "restablecer\\w*", "reset\\w*", "formatear\\w*", "vaciar\\w*", "bloquear contacto", "reenviar\\w*", "forward",
            "aceptar y pagar", "apostar\\w*", "bet",
        ).joinToString("|") + ")\\b"
    )
    private val ZONA_SENSIBLE = Regex(
        "bank|banco|banca|bbva|santander|caixa|sabadell|bankinter|\\bing\\b|openbank|itau|galicia|macro|brubank|uala|" +
            "nubank|bancolombia|davivienda|daviplata|nequi|yape|plin|bizum|wallet|billetera|paypal|mercadopago|" +
            "mercado\\.?pago|venmo|cashapp|stripe|wise|revolut|n26|binance|coinbase|kraken|crypto|metamask|trading|" +
            "broker|pagos|payments|hacienda|agenciatributaria|afip|seguridadsocial|walletnfcrel",
        RegexOption.IGNORE_CASE
    )
    private val MENSAJERIA = Regex(
        "whatsapp|telegram|messaging|messenger|orca|signal|\\.gm\\b|mail|outlook|instagram|twitter|facebook|katana|" +
            "tiktok|discord|slack|linkedin|sms|mms|threads",
        RegexOption.IGNORE_CASE
    )

    fun norm(s: String?): String =
        Normalizer.normalize((s ?: "").lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Por qué esta acción necesita tu sí, o null si JARVIS puede hacerla solo. */
    fun motivo(etiqueta: String = "", zona: String = "", declarada: Boolean = false, intro: Boolean = false): String? {
        var e = norm(etiqueta)
        if (e.length > 60) e = e.take(30)   // una etiqueta larga es una fila o un texto, no un botón: cuenta cómo empieza
        if (IRREVERSIBLE.containsMatchIn(e)) return "el botón dice «${etiqueta.trim().take(60)}» y eso no se puede deshacer"
        if (intro && MENSAJERIA.containsMatchIn(zona)) return "pulsar Intro aquí envía lo escrito"
        if (declarada) return "JARVIS cree que esta acción no se puede deshacer"
        return null
    }

    fun zonaSensible(zona: String): Boolean = zona.isNotEmpty() && ZONA_SENSIBLE.containsMatchIn(zona)

    /** El motivo por el que NO se escribe en ese campo, o null si se puede. */
    fun prohibidoEscribir(password: Boolean, pista: String = ""): String? {
        if (password) return "Es un campo de contraseña. JARVIS no escribe contraseñas ni PIN: escríbelo tú y dile que siga."
        if (Regex("tarjeta|card number|cvv|cvc|caducidad|iban|numero de cuenta").containsMatchIn(norm(pista)))
            return "Es un campo de datos de pago. JARVIS no escribe tarjetas ni cuentas: hazlo tú y dile que siga."
        return null
    }
}
