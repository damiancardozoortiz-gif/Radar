package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject

/** Una herramienta usada dentro de un turno: con qué, qué devolvió y cuánto tardó. */
data class Uso(val herramienta: String, val datos: String, val resultado: String, val error: Boolean, val ms: Long)

/** Un turno entero tal como pasó, para revisar los fallos sin depender de lo que JARVIS cuente. */
data class Turno(
    val fecha: Long, val mensaje: String, val canal: String, val usos: List<Uso>,
    val respuesta: String = "", val estado: String = "en curso", val coste: Double = 0.0,
)

/**
 * El registro por turno. Lo escribe la app (no el modelo) cada vez que se usa una herramienta, y se guarda en el acto:
 * si un turno se corta, lo que alcanzó a hacer queda apuntado.
 */
class Bitacora(private val caja: Caja) {
    private var actual: Turno? = null

    @Synchronized fun turnos(): List<Turno> = try {
        val a = JSONArray(caja.leer("bitacora").ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val us = o.getJSONArray("usos").let { u -> (0 until u.length()).map { u.getJSONObject(it).let { x ->
                Uso(x.getString("h"), x.optString("d"), x.optString("r"), x.optBoolean("e"), x.optLong("ms"))
            } } }
            Turno(o.getLong("fecha"), o.optString("mensaje"), o.optString("canal"), us, o.optString("respuesta"), o.optString("estado"), o.optDouble("coste", 0.0))
        }
    } catch (e: Exception) { emptyList() }

    private fun guardar(l: List<Turno>) = caja.escribir("bitacora", JSONArray(l.takeLast(MAX_TURNOS).map { t ->
        JSONObject().put("fecha", t.fecha).put("mensaje", t.mensaje).put("canal", t.canal).put("respuesta", t.respuesta)
            .put("estado", t.estado).put("coste", t.coste)
            .put("usos", JSONArray(t.usos.map { JSONObject().put("h", it.herramienta).put("d", it.datos).put("r", it.resultado).put("e", it.error).put("ms", it.ms) }))
    }).toString())

    @Synchronized private fun escribirActual() {
        val t = actual ?: return
        guardar(turnos().filter { it.fecha != t.fecha } + t)
    }

    @Synchronized fun empezar(fecha: Long, mensaje: String, canal: String) {
        actual = Turno(fecha, mensaje.take(600), canal, emptyList())
        escribirActual()
    }

    @Synchronized fun anotar(herramienta: String, datos: Map<String, Any?>, r: Resultado, ms: Long) {
        val t = actual ?: return
        if (t.usos.size >= MAX_USOS) return
        actual = t.copy(usos = t.usos + Uso(herramienta, resumir(datos), r.texto.replace(Regex("\\s+"), " ").take(MAX_RESULTADO) +
            (if (r.imagen != null || r.extra.isNotEmpty()) " [+${(if (r.imagen != null) 1 else 0) + r.extra.size} imagen]" else ""), r.error, ms))
        escribirActual()
    }

    @Synchronized fun cerrar(respuesta: String, estado: String, coste: Double) {
        val t = actual ?: return
        actual = t.copy(respuesta = respuesta.take(1500), estado = estado, coste = coste)
        escribirActual()
        actual = null
    }

    companion object {
        const val MAX_TURNOS = 30
        const val MAX_USOS = 120
        const val MAX_RESULTADO = 400

        /** Los datos de una herramienta en una línea. Lo largo (escenas, capítulos) se recorta. */
        fun resumir(d: Map<String, Any?>): String = d.entries.filter { it.key != "proposito" }.joinToString(", ") { (k, v) ->
            val s = when (v) {
                is Map<*, *> -> "{…${v.size} campos}"
                is List<*> -> "[${v.size}]"
                else -> v.toString().replace(Regex("\\s+"), " ")
            }
            "$k=" + if (s.length > 80) s.take(77) + "…" else s
        }.take(300)
    }
}
