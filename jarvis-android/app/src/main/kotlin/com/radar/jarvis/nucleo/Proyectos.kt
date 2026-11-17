package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject

/** Un objetivo que dura semanas: «JARVIS 2.3», «videos explicativos». Con su siguiente paso y lo que falta. */
data class Proyecto(
    val id: String, val nombre: String, val objetivo: String, val siguiente: String, val pendientes: List<String>,
    val notas: List<String>, val estado: String, val creado: Long, val actualizado: Long,
) {
    fun ficha(): String = buildString {
        append("Proyecto «$nombre»")
        if (estado != "activo") append(" ($estado)")
        append(": ${objetivo.ifEmpty { "sin objetivo escrito" }}\n")
        append("Siguiente paso: ${siguiente.ifEmpty { "sin decidir" }}\n")
        if (pendientes.isNotEmpty()) append("Pendiente: ${pendientes.joinToString("; ")}\n")
        if (notas.isNotEmpty()) append("Notas: ${notas.takeLast(5).joinToString("; ")}\n")
    }.trim()
}

/** Las fichas de proyecto. Cuando el tema sale en la conversación, la ficha le llega sola a JARVIS. */
class Proyectos(private val caja: Caja) {
    @Synchronized fun lista(): List<Proyecto> = try {
        val a = JSONArray(caja.leer("proyectos").ifBlank { "[]" })
        fun l(o: JSONObject, k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            Proyecto(o.getString("id"), o.getString("nombre"), o.optString("objetivo"), o.optString("siguiente"), l(o, "pendientes"), l(o, "notas"),
                o.optString("estado", "activo"), o.optLong("creado"), o.optLong("actualizado"))
        } }
    } catch (e: Exception) { emptyList() }

    private fun guardar(l: List<Proyecto>) = caja.escribir("proyectos", JSONArray(l.map {
        JSONObject().put("id", it.id).put("nombre", it.nombre).put("objetivo", it.objetivo).put("siguiente", it.siguiente)
            .put("pendientes", JSONArray(it.pendientes)).put("notas", JSONArray(it.notas)).put("estado", it.estado)
            .put("creado", it.creado).put("actualizado", it.actualizado)
    }).toString())

    fun buscar(nombre: String): Proyecto? {
        val n = Freno.norm(nombre).trim()
        if (n.isEmpty()) return null
        val l = lista()
        return l.firstOrNull { Freno.norm(it.nombre) == n } ?: l.firstOrNull { n in Freno.norm(it.nombre) || Freno.norm(it.nombre) in n }
    }

    /**
     * Crea el proyecto o actualiza el que ya existe con ese nombre. Lo que llega null no se toca.
     * `hecho` quita ese pendiente de la lista (basta con un trozo de su texto).
     */
    @Synchronized fun guardar(nombre: String, objetivo: String? = null, siguiente: String? = null, pendientes: List<String>? = null,
                              hecho: String? = null, nota: String? = null, ahora: Long = System.currentTimeMillis()): Proyecto {
        val nom = nombre.trim().take(80)
        if (nom.length < 3) throw ErrorManos("el proyecto necesita un nombre")
        val l = lista()
        val viejo = buscar(nom)
        if (viejo == null && l.count { it.estado == "activo" } >= MAX_ACTIVOS) throw ErrorManos("ya hay $MAX_ACTIVOS proyectos activos; cierra alguno")
        var p = viejo ?: Proyecto("p" + java.lang.Long.toString(ahora, 36), nom, "", "", emptyList(), emptyList(), "activo", ahora, ahora)
        objetivo?.trim()?.takeIf { it.isNotEmpty() }?.let { p = p.copy(objetivo = it.take(400)) }
        siguiente?.trim()?.let { p = p.copy(siguiente = it.take(300)) }
        pendientes?.let { nuevos -> p = p.copy(pendientes = (p.pendientes + nuevos.map { it.trim().take(200) }.filter { it.isNotEmpty() }).distinct().takeLast(MAX_PENDIENTES)) }
        hecho?.trim()?.takeIf { it.length >= 3 }?.let { h -> p = p.copy(pendientes = p.pendientes.filterNot { Freno.norm(h) in Freno.norm(it) }) }
        nota?.trim()?.takeIf { it.isNotEmpty() }?.let { p = p.copy(notas = (p.notas + it.take(300)).takeLast(MAX_NOTAS)) }
        if (Mente.pareceSecreto(p.ficha())) throw ErrorManos("no guardo contraseñas, códigos ni tarjetas en un proyecto")
        p = p.copy(estado = "activo", actualizado = ahora)
        guardar(if (viejo == null) l + p else l.map { if (it.id == p.id) p else it })
        return p
    }

    @Synchronized fun cerrar(nombre: String): Proyecto? {
        val p = buscar(nombre) ?: return null
        val c = p.copy(estado = "cerrado", actualizado = System.currentTimeMillis())
        guardar(lista().map { if (it.id == p.id) c else it })
        return c
    }

    @Synchronized fun borrar(id: String) = guardar(lista().filter { it.id != id })

    /**
     * Los proyectos de los que habla el mensaje: comparten con el nombre una palabra con sustancia (4 letras o más),
     * o dos con el objetivo. «¿Cómo va lo de los videos?» encuentra «Videos explicativos».
     */
    fun relevantes(mensaje: String, max: Int = 2): List<Proyecto> {
        val q = Mente.palabras(mensaje).map { raiz(it) }.toSet()
        if (q.isEmpty()) return emptyList()
        return lista().filter { it.estado == "activo" }.map { p ->
            val nombre = Mente.palabras(p.nombre).filter { it.length >= 4 }.map { raiz(it) }.count { it in q }
            val objetivo = Mente.palabras(p.objetivo).filter { it.length >= 4 }.map { raiz(it) }.count { it in q }
            p to (nombre * 2 + objetivo)
        }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    fun contexto(mensaje: String): String {
        val l = relevantes(mensaje)
        val activos = lista().filter { it.estado == "activo" }
        if (l.isEmpty()) return if (activos.isEmpty() || !Freno.norm(mensaje).contains("proyecto")) ""
            else "[Proyectos activos: ${activos.joinToString(", ") { it.nombre }}.]"
        return "[Proyecto del que se habla (tu ficha; mantenla al día con proyecto_guardar):\n${l.joinToString("\n\n") { it.ficha() }}]"
    }

    private companion object {
        const val MAX_ACTIVOS = 20
        const val MAX_PENDIENTES = 15
        const val MAX_NOTAS = 20
        /** Singular y plural cuentan igual: «video» y «videos». */
        fun raiz(p: String) = if (p.length > 4 && p.endsWith("es")) p.dropLast(2) else if (p.length > 3 && p.endsWith("s")) p.dropLast(1) else p
    }
}
