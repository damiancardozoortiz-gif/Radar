package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject

/**
 * Un permiso que la persona dio UNA vez para no volver a preguntar.
 *  - tipo «app»: confirmar, guardar o aceptar dentro de esa aplicación (donde = su paquete).
 *  - tipo «puente»: usar esa herramienta de un puente (donde = su nombre, p_servicio__herramienta).
 * Nunca cubre dinero, borrar, publicar, instalar, llamar ni escribir a otras personas: eso lo decide el freno, no el permiso.
 */
data class PermisoFijo(val id: String, val tipo: String, val donde: String, val que: String, val fecha: Long)

/**
 * Los permisos fijos. Solo los crea la persona (con «Siempre» en la tarjeta de permiso, o en Ajustes); JARVIS no tiene
 * ninguna herramienta para darse permisos a sí mismo.
 */
class Permisos(private val caja: Caja) {
    @Synchronized fun lista(): List<PermisoFijo> = try {
        val a = JSONArray(caja.leer("permisos").ifBlank { "[]" })
        (0 until a.length()).map { a.getJSONObject(it).let { o -> PermisoFijo(o.getString("id"), o.getString("tipo"), o.getString("donde"), o.optString("que"), o.optLong("fecha")) } }
    } catch (e: Exception) { emptyList() }

    private fun guardar(l: List<PermisoFijo>) = caja.escribir("permisos", JSONArray(l.map {
        JSONObject().put("id", it.id).put("tipo", it.tipo).put("donde", it.donde).put("que", it.que).put("fecha", it.fecha)
    }).toString())

    @Synchronized fun conceder(p: PermisoFijo): PermisoFijo {
        val l = lista()
        l.firstOrNull { it.tipo == p.tipo && it.donde == p.donde }?.let { return it }
        val nuevo = p.copy(id = "f" + java.lang.Long.toString(System.nanoTime(), 36))
        guardar(l + nuevo)
        return nuevo
    }

    @Synchronized fun quitar(id: String) = guardar(lista().filter { it.id != id })

    /** Cómo se llama en las aplicaciones de mensajes el chat de la persona consigo misma. Vacío = ese permiso está apagado. */
    var chatPropio: String
        get() = caja.leer("permisos_chat").trim()
        set(v) = caja.escribir("permisos_chat", v.trim().take(60))

    /**
     * El permiso fijo que cubre esta acción, o null si hay que preguntar.
     * @param titulos los textos de la parte de arriba de la pantalla (donde las aplicaciones de mensajes ponen el nombre del chat).
     */
    fun cubre(etiqueta: String, zona: String, declarada: Boolean, intro: Boolean, titulos: List<String>): PermisoFijo? =
        when (Freno.clase(etiqueta, zona, declarada, intro)) {
            "enviar" -> chatPropio.takeIf { it.length >= 2 && Freno.esMensajeria(zona) }?.let { chat ->
                if (titulos.any { Freno.norm(it).trim() == Freno.norm(chat) }) PermisoFijo("chat", "chat", zona, "mandarte mensajes a tu chat «$chat»", 0) else null
            }
            "confirmar" -> if (Freno.esMensajeria(zona)) null else lista().firstOrNull { it.tipo == "app" && it.donde == zona }
            else -> null
        }

    fun cubrePuente(alias: String): PermisoFijo? =
        if (!Freno.puenteFijable(alias)) null else lista().firstOrNull { it.tipo == "puente" && it.donde == alias }

    companion object {
        /** El permiso que se podría dejar fijo para esta acción (lo que ofrece el botón «Siempre»), o null si no se puede. */
        fun propuesta(etiqueta: String, zona: String, declarada: Boolean, intro: Boolean): PermisoFijo? =
            if (zona.isEmpty() || Freno.esMensajeria(zona) || Freno.clase(etiqueta, zona, declarada, intro) != "confirmar") null
            else PermisoFijo("", "app", zona, "confirmar, guardar o aceptar en $zona", System.currentTimeMillis())

        fun propuestaPuente(alias: String, herramienta: String, puente: String): PermisoFijo? =
            if (!Freno.puenteFijable(alias)) null else PermisoFijo("", "puente", alias, "usar «$herramienta» de $puente", System.currentTimeMillis())
    }
}
