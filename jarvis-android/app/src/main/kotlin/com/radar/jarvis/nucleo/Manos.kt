package com.radar.jarvis.nucleo

/** Un elemento de la pantalla, tal como lo ve JARVIS. Los toques van por su número en la lista. */
data class Elemento(
    val tipo: String,              // botón, campo, casilla, texto
    val nombre: String,
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    val paquete: String = "",
    val password: Boolean = false,
    val marcada: Boolean = false,
    val enfocado: Boolean = false,
    val desplazable: Boolean = false,
    val activo: Boolean = true,
    val valor: String = "",
) {
    fun contiene(x: Int, y: Int) = x in x1..x2 && y in y1..y2
    val area get() = (x2 - x1).toLong() * (y2 - y1)
}

/** Lo que hay en pantalla en un momento dado. */
data class Vista(val app: String, val elementos: List<Elemento>) {
    val desplazable get() = elementos.any { it.desplazable }

    fun filas(): List<String> = elementos.mapIndexed { i, e ->
        var extra = ""
        if (e.tipo == "campo") {
            extra = if (e.password) " (contraseña)" else if (e.valor.isNotEmpty()) " valor=«${e.valor.take(60)}»" else " (vacío)"
            if (e.enfocado) extra += " (con el cursor)"
        }
        if (e.tipo == "casilla") extra = if (e.marcada) " (marcada)" else " (sin marcar)"
        if (!e.activo) extra += " (desactivado)"
        "[${i + 1}] ${e.tipo} «${e.nombre}»$extra"
    }

    /** El elemento más pequeño que contiene el punto: para saber qué hay debajo del dedo antes de tocar. */
    fun elementoEn(x: Int, y: Int): Elemento? = elementos.filter { it.contiene(x, y) }.minByOrNull { it.area }
    fun campoEnfocado(): Elemento? = elementos.firstOrNull { it.tipo == "campo" && it.enfocado }
}

class ErrorManos(mensaje: String) : Exception(mensaje)

/**
 * Las manos y los ojos de JARVIS en el teléfono. En la app las implementa el servicio de accesibilidad;
 * en las pruebas, un teléfono de mentira.
 */
interface Manos {
    /** null si está listo; si no, el motivo en una frase (p. ej. «el servicio de accesibilidad está apagado»). */
    fun noDisponible(): String?
    fun mirar(): Vista
    /** Toca el elemento número `indice` (desde 0) de la ÚLTIMA vista devuelta por mirar(). */
    fun tocarElemento(indice: Int, largo: Boolean)
    fun tocarPunto(x: Int, y: Int, largo: Boolean)
    /** Sustituye el texto del campo que tiene el cursor. */
    fun escribir(texto: String)
    fun intro()
    fun deslizar(direccion: String)
    fun tecla(nombre: String)
    /** Abre la aplicación y devuelve su paquete. */
    fun abrirApp(nombre: String): String
    /** nombre visible -> paquete, de las aplicaciones que se pueden abrir. */
    fun apps(): List<Pair<String, String>>
    /** El paquete que se abriría con ese nombre, sin abrirlo. */
    fun resolverApp(nombre: String): String
    fun abrirUrl(url: String)
    /** JPEG de la pantalla, o null si este teléfono no deja capturar. */
    fun captura(): ByteArray?
    fun tamano(): Pair<Int, Int>

    companion object {
        val TECLAS = listOf("atras", "inicio", "recientes", "notificaciones", "ajustes_rapidos")
        val DIRECCIONES = listOf("arriba", "abajo", "izquierda", "derecha")
    }
}
