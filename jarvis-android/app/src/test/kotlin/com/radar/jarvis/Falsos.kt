package com.radar.jarvis

import com.radar.jarvis.nucleo.Elemento
import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Manos
import com.radar.jarvis.nucleo.Memoria
import com.radar.jarvis.nucleo.Vista

/** Un teléfono de mentira: un chat de WhatsApp con un campo y, si hay texto, un botón Enviar. */
class TelefonoFalso : Manos {
    var app = "com.whatsapp"
    var campo = ""
    var foco = false
    var esPassword = false
    val enviados = mutableListOf<String>()
    val toques = mutableListOf<String>()
    var apagado: String? = null

    override fun noDisponible() = apagado
    override fun mirar(): Vista {
        val e = mutableListOf(
            Elemento("texto", "Ana López", 180, 150, 800, 250, app),
            Elemento("texto", "¿Vienes a cenar mañana?", 40, 340, 700, 470, app),
            Elemento("campo", if (campo.isEmpty()) "Mensaje" else campo, 40, 2180, 880, 2300, app, password = esPassword, enfocado = foco, valor = campo),
        )
        e += if (campo.isNotEmpty()) Elemento("botón", "Enviar", 910, 2180, 1040, 2300, app)
        else Elemento("botón", "Mensaje de voz", 910, 2180, 1040, 2300, app)
        return Vista(app, e)
    }
    override fun tocarElemento(indice: Int, largo: Boolean) {
        val e = mirar().elementos[indice]
        toques += e.nombre
        if (e.tipo == "campo") foco = true
        if (e.nombre == "Enviar") { enviados += campo; campo = "" }
    }
    override fun tocarPunto(x: Int, y: Int, largo: Boolean) {
        val i = mirar().elementos.indexOfFirst { it === mirar().elementoEn(x, y) }
        val e = mirar().elementoEn(x, y) ?: return
        tocarElemento(mirar().elementos.indexOf(e).takeIf { it >= 0 } ?: i, largo)
    }
    override fun escribir(texto: String) { if (foco) campo = texto }
    override fun intro() { if (foco && campo.isNotEmpty()) { enviados += campo; campo = "" } }
    override fun deslizar(direccion: String) {}
    override fun tecla(nombre: String) {}
    override fun resolverApp(nombre: String) = when (nombre.lowercase()) {
        "whatsapp" -> "com.whatsapp"; "banco" -> "com.bancodemo.app"
        else -> throw ErrorManos("no encuentro ninguna aplicación parecida a «$nombre»")
    }
    override fun abrirApp(nombre: String) = resolverApp(nombre).also { app = it }
    override fun apps() = listOf("WhatsApp" to "com.whatsapp", "Banco" to "com.bancodemo.app")
    override fun abrirUrl(url: String) {}
    override fun captura(): ByteArray? = byteArrayOf(1, 2, 3)
    override fun tamano() = 1080 to 2400
}

class MemoriaFalsa : Memoria {
    val notas = mutableListOf<String>()
    override fun guardar(texto: String) { notas += texto }
    override fun todo() = notas.toList()
}
