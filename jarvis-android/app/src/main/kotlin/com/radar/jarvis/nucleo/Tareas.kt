package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject

data class PasoTarea(val texto: String, val hecho: Boolean = false, val resultado: String = "")

/**
 * Cómo se sabe que una tarea salió bien.
 *  - «pantalla»: la app mira la pantalla y busca ese texto (el mensaje en el chat, la alarma en la lista).
 *  - «capitulo»: la app mira la biblioteca: el capítulo existe, tiene secciones y cita fuentes. texto = «Tomo|número».
 *  - «manual»: lo comprueba JARVIS y tiene que decir qué vio que lo demuestra.
 */
data class Comprobacion(val tipo: String, val texto: String)

/**
 * Una tarea larga que sobrevive a que se corte el turno o se cierre la aplicación.
 * estado: en_curso (trabajando), esperando (paró para preguntar), terminada, fallida o detenida.
 */
data class Tarea(
    val id: String, val objetivo: String, val pasos: List<PasoTarea>, val comprobacion: Comprobacion, val estado: String,
    val creada: Long, val actualizada: Long, val reanudaciones: Int = 0, val fallosComprobacion: Int = 0, val resultado: String = "",
) {
    /** El número (desde 1) del primer paso que falta. */
    val actual get() = pasos.indexOfFirst { !it.hecho }.let { if (it < 0) pasos.size else it + 1 }
    val hechos get() = pasos.count { it.hecho }
    val abierta get() = estado == "en_curso" || estado == "esperando"

    fun resumen() = "«$objetivo»: $hechos de ${pasos.size} pasos hechos, $estado"

    fun detalle(): String = buildString {
        append("Tarea ${id}: $objetivo\n")
        pasos.forEachIndexed { i, p ->
            append("${i + 1}. ${if (p.hecho) "✓" else if (i + 1 == actual) "→" else "·"} ${p.texto}")
            if (p.resultado.isNotEmpty()) append(" — ${p.resultado}")
            append("\n")
        }
        append("Cómo se comprueba: ").append(when (comprobacion.tipo) {
            "pantalla" -> "que en pantalla aparezca «${comprobacion.texto}»"
            "capitulo" -> "que el capítulo ${comprobacion.texto.replace("|", ", ")} exista con secciones y fuentes"
            else -> comprobacion.texto
        })
    }
}

/** Las tareas largas: se guardan después de cada paso que cuenta, así que se retoman donde se quedaron. */
class Tareas(private val caja: Caja) {
    @Synchronized fun todas(): List<Tarea> = try {
        val a = JSONArray(caja.leer("tareas").ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ps = o.getJSONArray("pasos").let { p -> (0 until p.length()).map { p.getJSONObject(it).let { x -> PasoTarea(x.getString("texto"), x.optBoolean("hecho"), x.optString("resultado")) } } }
            val c = o.getJSONObject("comprobacion")
            Tarea(o.getString("id"), o.getString("objetivo"), ps, Comprobacion(c.getString("tipo"), c.optString("texto")), o.getString("estado"),
                o.getLong("creada"), o.getLong("actualizada"), o.optInt("reanudaciones"), o.optInt("fallosComprobacion"), o.optString("resultado"))
        }
    } catch (e: Exception) { emptyList() }

    private fun guardar(l: List<Tarea>) = caja.escribir("tareas", JSONArray(l.takeLast(MAX_GUARDADAS).map { t ->
        JSONObject().put("id", t.id).put("objetivo", t.objetivo).put("estado", t.estado).put("creada", t.creada).put("actualizada", t.actualizada)
            .put("reanudaciones", t.reanudaciones).put("fallosComprobacion", t.fallosComprobacion).put("resultado", t.resultado)
            .put("comprobacion", JSONObject().put("tipo", t.comprobacion.tipo).put("texto", t.comprobacion.texto))
            .put("pasos", JSONArray(t.pasos.map { JSONObject().put("texto", it.texto).put("hecho", it.hecho).put("resultado", it.resultado) }))
    }).toString())

    @Synchronized private fun cambiar(id: String, f: (Tarea) -> Tarea): Tarea {
        val l = todas()
        val t = l.firstOrNull { it.id == id } ?: throw ErrorManos("no hay ninguna tarea $id")
        val nueva = f(t)
        guardar(l.map { if (it.id == id) nueva else it })
        return nueva
    }

    /** La tarea abierta (en curso o esperando a la persona), si hay. Solo hay una a la vez. */
    fun abierta(): Tarea? = todas().lastOrNull { it.abierta }

    @Synchronized fun empezar(objetivo: String, pasos: List<String>, comprobacion: Comprobacion, ahora: Long = System.currentTimeMillis()): Tarea {
        val obj = objetivo.trim().take(300)
        val ps = pasos.map { it.trim().take(200) }.filter { it.isNotEmpty() }
        if (obj.length < 8) throw ErrorManos("di el objetivo de la tarea en una frase")
        if (ps.size !in 2..MAX_PASOS) throw ErrorManos("una tarea tiene de 2 a $MAX_PASOS pasos")
        when (comprobacion.tipo) {
            "pantalla" -> if (comprobacion.texto.trim().length < 2) throw ErrorManos("di qué texto debe verse en pantalla cuando esté hecha")
            "capitulo" -> if (!Regex(".+\\|\\d+").matches(comprobacion.texto.trim())) throw ErrorManos("para un capítulo, «comprobar_texto» es «Título del tomo|número»")
            "manual" -> if (comprobacion.texto.trim().length < 10) throw ErrorManos("di con qué criterio se comprueba que está hecha")
            else -> throw ErrorManos("comprobación desconocida: pantalla, capitulo o manual")
        }
        abierta()?.let { throw ErrorManos("ya hay una tarea abierta (${it.id}: ${it.objetivo}). Termínala, o ciérrala como no lograda, antes de empezar otra") }
        val t = Tarea("t" + java.lang.Long.toString(ahora, 36), obj, ps.map { PasoTarea(it) }, comprobacion.copy(texto = comprobacion.texto.trim()), "en_curso", ahora, ahora)
        guardar(todas() + t)
        return t
    }

    /** Marca un paso como hecho, con lo que dio. Se guarda en el acto: si la app se cierra después, se sigue desde el siguiente. */
    fun avanzar(id: String, paso: Int, resultado: String, ahora: Long = System.currentTimeMillis()): Tarea = cambiar(id) { t ->
        if (paso !in 1..t.pasos.size) throw ErrorManos("la tarea tiene ${t.pasos.size} pasos; el $paso no existe")
        t.copy(estado = "en_curso", actualizada = ahora, pasos = t.pasos.mapIndexed { i, p -> if (i + 1 == paso) p.copy(hecho = true, resultado = resultado.trim().take(300)) else p })
    }

    fun cerrar(id: String, estado: String, resultado: String, ahora: Long = System.currentTimeMillis()) =
        cambiar(id) { it.copy(estado = estado, resultado = resultado.trim().take(500), actualizada = ahora) }

    fun fallaComprobacion(id: String) = cambiar(id) { it.copy(fallosComprobacion = it.fallosComprobacion + 1) }

    /**
     * Lo que pasa con la tarea abierta cuando termina un turno. Si el turno acabó bien pero la tarea sigue en curso,
     * JARVIS paró para preguntar: queda esperando a la persona. Si se cortó (error, sin red), sigue en curso para retomarla.
     * Si la persona pulsó Detener, se detiene.
     */
    @Synchronized fun alTerminarTurno(como: String, ahora: Long = System.currentTimeMillis()) {
        val t = abierta() ?: return
        when (como) {
            "terminado" -> if (t.estado == "en_curso") cambiar(t.id) { it.copy(estado = "esperando", actualizada = ahora) }
            "detenido" -> cambiar(t.id) { it.copy(estado = "detenida", actualizada = ahora, resultado = "La persona la detuvo.") }
            else -> Unit   // cortado: se queda en curso
        }
    }

    /** La tarea que se cortó y conviene retomar sola, o null. No se retoma una tarea vieja ni una que ya se retomó muchas veces. */
    fun paraRetomar(ahora: Long = System.currentTimeMillis()): Tarea? =
        abierta()?.takeIf { it.estado == "en_curso" && it.reanudaciones < MAX_REANUDAR && ahora - it.actualizada < VIGENCIA_MS }

    /** Apunta que se retoma, y devuelve el encargo para el turno que la retoma. */
    fun retomar(id: String, ahora: Long = System.currentTimeMillis()): String {
        val t = cambiar(id) { it.copy(reanudaciones = it.reanudaciones + 1, actualizada = ahora) }
        return "Retoma la tarea que se cortó. NO repitas los pasos ya hechos: sigue desde el paso ${t.actual}. " +
            "Antes de actuar, mira la pantalla para ver en qué estado quedó.\n" + t.detalle()
    }

    /** Lo que el agente debe tener presente en cada mensaje mientras haya una tarea abierta. */
    fun contexto(): String {
        val t = abierta() ?: return ""
        return "[Tarea abierta (${if (t.estado == "esperando") "esperando a la persona" else "en curso"}). Sigue desde el paso ${t.actual}; " +
            "marca cada paso con tarea_avanzar y ciérrala con tarea_terminar. Si la persona pide otra cosa, ciérrala antes como no lograda.\n${t.detalle()}]"
    }

    companion object {
        const val MAX_PASOS = 30
        const val MAX_GUARDADAS = 40
        const val MAX_REANUDAR = 3
        const val VIGENCIA_MS = 12 * 3_600_000L
    }
}
