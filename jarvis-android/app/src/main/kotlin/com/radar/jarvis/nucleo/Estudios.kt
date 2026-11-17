package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject

/** Un capítulo del plan. estado: pendiente, hecho o fallido. */
data class CapituloPlan(val numero: Int, val titulo: String, val alcance: String, val estado: String = "pendiente", val intentos: Int = 0)

/** El estudio de un tomo: su índice y por dónde va. estado: en_curso, terminado o detenido. */
data class PlanEstudio(
    val tomo: String, val descripcion: String, val capitulos: List<CapituloPlan>, val estado: String,
    val creado: Long, val reintentarEn: Long = 0,
) {
    val hechos get() = capitulos.count { it.estado == "hecho" }
    val fallidos get() = capitulos.filter { it.estado == "fallido" }
    fun resumen(): String = "«$tomo»: $hechos de ${capitulos.size} capítulos escritos" +
        (if (fallidos.isNotEmpty()) ", ${fallidos.size} sin poder escribirse (${fallidos.joinToString(", ") { "${it.numero}. ${it.titulo}" }})" else "") +
        when (estado) { "en_curso" -> "; sigue en marcha"; "detenido" -> "; detenido"; else -> "; terminado" }
}

/**
 * El estudio de un tomo como tarea larga: tiene un índice, guarda el progreso capítulo a capítulo y, si se corta
 * (se cierra la app, se pierde la red, se apaga el teléfono), sigue por donde iba sin repetir lo hecho.
 * Uno cada vez. Nada de Android aquí.
 */
class Estudios(private val caja: Caja, private val biblioteca: Biblioteca) {
    @Synchronized fun actual(): PlanEstudio? = try {
        val o = JSONObject(caja.leer("estudio").ifBlank { return null })
        val cs = o.getJSONArray("capitulos")
        PlanEstudio(o.getString("tomo"), o.optString("descripcion"), (0 until cs.length()).map { i ->
            cs.getJSONObject(i).let { c -> CapituloPlan(c.getInt("numero"), c.getString("titulo"), c.optString("alcance"), c.optString("estado", "pendiente"), c.optInt("intentos")) }
        }, o.optString("estado", "en_curso"), o.optLong("creado"), o.optLong("reintentarEn"))
    } catch (e: Exception) { null }

    private fun guardar(p: PlanEstudio) = caja.escribir("estudio", JSONObject().put("tomo", p.tomo).put("descripcion", p.descripcion).put("estado", p.estado)
        .put("creado", p.creado).put("reintentarEn", p.reintentarEn)
        .put("capitulos", JSONArray(p.capitulos.map { JSONObject().put("numero", it.numero).put("titulo", it.titulo).put("alcance", it.alcance).put("estado", it.estado).put("intentos", it.intentos) })).toString())

    /** Empieza a estudiar un tomo. Lanza ErrorManos si el índice no vale o si ya hay otro estudio en marcha. */
    @Synchronized fun empezar(tomo: String, descripcion: String, capitulos: List<Pair<String, String>>, ahora: Long = System.currentTimeMillis()): PlanEstudio {
        actual()?.takeIf { it.estado == "en_curso" }?.let { throw ErrorManos("ya hay un estudio en marcha (${it.resumen()}). Espera a que termine o detenlo con estudio_detener") }
        val t = tomo.trim().take(80)
        if (t.length < 3) throw ErrorManos("el tomo necesita un título")
        if (capitulos.size !in MIN_CAPITULOS..MAX_CAPITULOS) throw ErrorManos("un tomo tiene de $MIN_CAPITULOS a $MAX_CAPITULOS capítulos (hay ${capitulos.size}); si el tema es enorme, divídelo en varios tomos")
        val plan = capitulos.mapIndexed { i, (titulo, alcance) ->
            if (titulo.trim().length < 3) throw ErrorManos("el capítulo ${i + 1} necesita un título")
            if (alcance.trim().length < 15) throw ErrorManos("el capítulo ${i + 1} necesita su alcance: qué debe explicar, en una o dos frases")
            CapituloPlan(i + 1, titulo.trim().take(120), alcance.trim().take(500))
        }
        return PlanEstudio(t, descripcion.trim().take(300), plan, "en_curso", ahora).also { guardar(it) }
    }

    /**
     * El capítulo que toca escribir ahora, o null si no queda ninguno. Antes mira la biblioteca: un capítulo que ya está
     * escrito cuenta como hecho aunque el corte llegara antes de apuntarlo. Así nunca se repite lo que ya se hizo.
     */
    @Synchronized fun siguiente(): CapituloPlan? {
        var p = actual()?.takeIf { it.estado == "en_curso" } ?: return null
        val escritos = biblioteca.tomo(p.tomo)?.capitulos?.map { it.numero }?.toSet() ?: emptySet()
        if (p.capitulos.any { it.estado != "hecho" && it.numero in escritos }) {
            p = p.copy(capitulos = p.capitulos.map { if (it.numero in escritos) it.copy(estado = "hecho") else it }); guardar(p)
        }
        val s = p.capitulos.firstOrNull { it.estado == "pendiente" }
        if (s == null) guardar(p.copy(estado = "terminado"))
        return s
    }

    /** Apunta cómo acabó el intento de un capítulo. Tras MAX_INTENTOS fallos se da por imposible y se sigue con el siguiente. */
    @Synchronized fun apuntar(numero: Int, escrito: Boolean, ahora: Long = System.currentTimeMillis()): PlanEstudio? {
        val p = actual() ?: return null
        val nuevos = p.capitulos.map {
            if (it.numero != numero) it else if (escrito) it.copy(estado = "hecho", intentos = it.intentos + 1)
            else it.copy(intentos = it.intentos + 1, estado = if (it.intentos + 1 >= MAX_INTENTOS) "fallido" else "pendiente")
        }
        val quedan = nuevos.any { it.estado == "pendiente" }
        return p.copy(capitulos = nuevos, estado = if (p.estado == "en_curso" && !quedan) "terminado" else p.estado,
            reintentarEn = if (escrito) 0 else ahora + ESPERA_TRAS_FALLO).also { guardar(it) }
    }

    @Synchronized fun detener(): PlanEstudio? = actual()?.let { if (it.estado == "en_curso") it.copy(estado = "detenido").also { p -> guardar(p) } else it }

    /** ¿Hay un estudio que deba seguir ahora mismo? (en marcha y, si hubo un fallo, ya pasada la espera). */
    fun toca(ahora: Long = System.currentTimeMillis()): Boolean = actual()?.let { it.estado == "en_curso" && ahora >= it.reintentarEn } == true

    /** Lo que se le encarga al agente que escribe UN capítulo. No ve la conversación: aquí va todo lo que necesita. */
    fun encargo(p: PlanEstudio, c: CapituloPlan): String = buildString {
        append("Escribe el capítulo ${c.numero} del tomo «${p.tomo}»")
        if (p.descripcion.isNotEmpty()) append(" (${p.descripcion})")
        append(".\nTítulo del capítulo: ${c.titulo}\nQué debe explicar: ${c.alcance}\n\nÍndice del tomo, para que no repitas lo de otros capítulos ni dejes huecos:\n")
        p.capitulos.forEach { append("${it.numero}. ${it.titulo}${if (it.estado == "hecho") " (ya escrito)" else ""}${if (it.numero == c.numero) "  ← este" else ""}\n") }
        append("\nInvestiga, contrasta en las fuentes originales y guárdalo con biblioteca_escribir (tomo «${p.tomo}», capitulo ${c.numero}). Cuando esté guardado, responde solo «hecho».")
    }

    companion object {
        const val MIN_CAPITULOS = 2
        const val MAX_CAPITULOS = 20
        const val MAX_INTENTOS = 3
        const val ESPERA_TRAS_FALLO = 10 * 60_000L
        /** Lo único que puede usar el agente que escribe un capítulo: la biblioteca (y la web, que va aparte). Nada del teléfono. */
        val HERRAMIENTAS = setOf("biblioteca_indice", "biblioteca_consultar", "biblioteca_leer", "biblioteca_escribir", "biblioteca_corregir", "biblioteca_pendiente")

        val OFICIO = """

## Ahora eres el que escribe UN capítulo de un tomo
No hablas con la persona: trabajas en segundo plano y nadie te va a responder. Haz solo el capítulo que te encargan.
1. Busca en la web lo necesario. Tienes un cupo pequeño de búsquedas y de lecturas de página para este capítulo: úsalas bien (consultas concretas, una cada vez).
2. Lee con web_fetch al menos una fuente original fiable (universidad, libro de texto, artículo, documentación oficial) para contrastar los datos, en vez de fiarte de los fragmentos.
3. Escribe el capítulo con tus palabras, de 2500 a 7000 letras, dividido en secciones que empiecen con una línea «## Título de la sección». Debe llevar: los conceptos definidos, las fórmulas en texto plano con cada símbolo explicado, un ejemplo resuelto o un caso concreto, los errores típicos y, si viene al caso, cómo se aplica.
4. Guárdalo con biblioteca_escribir, con sus fuentes (título y dirección).
5. Si un dato no pudiste contrastarlo o las fuentes no coinciden, escríbelo con cautela y apúntalo con biblioteca_pendiente. No inventes datos, fechas ni citas.
Si te quedas sin búsquedas, escribe el capítulo con lo que ya tienes y con lo que sabes, y apunta como pendiente lo que no pudiste comprobar.
""".trimEnd()
    }
}

/**
 * Quien lleva el estudio de principio a fin: pide capítulo tras capítulo hasta que no queda ninguno.
 *
 * @param escribir encarga un capítulo y devuelve true si quedó guardado en la biblioteca.
 */
class Estudiante(private val estudios: Estudios, private val escribir: suspend (PlanEstudio, CapituloPlan) -> Boolean, private val alAvanzar: (PlanEstudio) -> Unit = {}) {
    /** Sigue hasta terminar el tomo, hasta que lo detengan o hasta un fallo (entonces lo deja para reintentarlo más tarde). Devuelve cómo quedó. */
    suspend fun correr(): PlanEstudio? {
        while (true) {
            val c = estudios.siguiente() ?: return estudios.actual()
            val p = estudios.actual() ?: return null
            val escrito = try { escribir(p, c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            val despues = estudios.apuntar(c.numero, escrito) ?: return null
            alAvanzar(despues)
            if (!escrito && despues.capitulos.first { it.numero == c.numero }.estado == "pendiente") return despues     // falló: se reintenta más tarde, no en bucle
        }
    }
}
