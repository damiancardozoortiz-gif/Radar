package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** Un capítulo de un tomo. */
data class Capitulo(val numero: Int, val titulo: String, val fuentes: List<String>, val letras: Int)

/** Un tomo: lo que JARVIS ha estudiado y escrito sobre un tema. */
data class Tomo(val id: String, val titulo: String, val descripcion: String, val capitulos: List<Capitulo>, val fecha: Long)

/** Un trozo de la biblioteca que responde a una pregunta. */
data class Pasaje(
    val tomo: Tomo, val capitulo: Capitulo, val texto: String, val puntos: Double, val coinciden: Int = 0, val preguntadas: Int = 0,
    val seccion: String = "",
) {
    fun cita() = "«${tomo.titulo}», capítulo ${capitulo.numero}: ${capitulo.titulo}" + if (seccion.isNotEmpty()) " · sección «$seccion»" else ""
}

/** Un dato de un capítulo que JARVIS no pudo contrastar y tiene que volver a mirar. */
data class Pendiente(val id: String, val tomo: String, val capitulo: Int, val dato: String, val motivo: String, val fecha: Long)

/**
 * La biblioteca de JARVIS: tomos que él mismo investiga y escribe, capítulo a capítulo, con sus fuentes.
 * Después los consulta por significado de las palabras (BM25, el método clásico de los buscadores) y aplica
 * lo que encuentra. Todo vive en archivos de texto; nada de Android aquí.
 */
class Biblioteca(private val caja: Caja) {
    private class Trozo(val texto: String, val capitulo: Int, val seccion: String, val palabras: List<String>)
    private var indice: Map<String, List<Trozo>>? = null      // tomo -> sus trozos

    @Synchronized fun tomos(): List<Tomo> = try {
        JSONArray(caja.leer("biblioteca").ifBlank { "[]" }).let { a ->
            (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i); val cs = o.getJSONArray("capitulos")
                    Tomo(o.getString("id"), o.getString("titulo"), o.optString("descripcion"), (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j); val f = c.optJSONArray("fuentes") ?: JSONArray()
                        Capitulo(c.getInt("numero"), c.getString("titulo"), (0 until f.length()).map { f.getString(it) }, c.optInt("letras"))
                    }.sortedBy { it.numero }, o.optLong("fecha"))
                }.getOrNull()
            }
        }
    } catch (e: Exception) { emptyList() }

    private fun guardar(l: List<Tomo>) {
        caja.escribir("biblioteca", JSONArray(l.map { t ->
            JSONObject().put("id", t.id).put("titulo", t.titulo).put("descripcion", t.descripcion).put("fecha", t.fecha)
                .put("capitulos", JSONArray(t.capitulos.map { c ->
                    JSONObject().put("numero", c.numero).put("titulo", c.titulo).put("fuentes", JSONArray(c.fuentes)).put("letras", c.letras)
                }))
        }).toString())
        indice = null
    }

    private fun archivo(id: String, n: Int) = "tomo_${id}_$n"
    private fun idDe(titulo: String) = Freno.norm(titulo).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    /** El tomo con ese título o ese id, sin importar mayúsculas ni acentos. */
    fun tomo(nombre: String): Tomo? = idDe(nombre).let { q -> tomos().firstOrNull { it.id == q } ?: tomos().firstOrNull { q.isNotEmpty() && q in it.id } }

    /**
     * Escribe (o reescribe) un capítulo. Crea el tomo si no existía. Lanza ErrorManos si el capítulo no da la talla:
     * un capítulo es una explicación de verdad, no una frase.
     */
    @Synchronized fun escribir(tomoTitulo: String, descripcion: String, numero: Int, titulo: String, contenido: String, fuentes: List<String>, ahora: Long = System.currentTimeMillis()): Tomo {
        val tt = tomoTitulo.trim().take(80); val ct = titulo.trim().take(120); val texto = contenido.trim()
        val id = idDe(tt)
        if (id.length < 3) throw ErrorManos("el tomo necesita un título")
        if (ct.length < 3) throw ErrorManos("el capítulo necesita un título")
        if (numero !in 1..MAX_CAPITULOS) throw ErrorManos("el número de capítulo va de 1 a $MAX_CAPITULOS")
        if (texto.length < MIN_CAPITULO) throw ErrorManos("el capítulo es demasiado corto (${texto.length} letras): explícalo de verdad, con al menos $MIN_CAPITULO")
        if (texto.length > MAX_CAPITULO) throw ErrorManos("el capítulo es demasiado largo (${texto.length} letras): pártelo en dos, máximo $MAX_CAPITULO cada uno")
        if (Mente.pareceSecreto(texto.take(400))) throw ErrorManos("eso parece una contraseña o un código, no conocimiento")
        val todos = tomos()
        val viejo = todos.firstOrNull { it.id == id }
        if (viejo == null && todos.size >= MAX_TOMOS) throw ErrorManos("la biblioteca ya tiene $MAX_TOMOS tomos; borra alguno antes")
        val cap = Capitulo(numero, ct, fuentes.map { it.trim().take(300) }.filter { it.isNotEmpty() }.take(12), texto.length)
        val nuevo = Tomo(id, viejo?.titulo ?: tt, descripcion.trim().take(300).ifEmpty { viejo?.descripcion.orEmpty() },
            ((viejo?.capitulos ?: emptyList()).filter { it.numero != numero } + cap).sortedBy { it.numero }, ahora)
        caja.escribir(archivo(id, numero), texto)
        guardar(todos.filter { it.id != id } + nuevo)
        return nuevo
    }

    fun leer(tomo: Tomo, numero: Int): String? = tomo.capitulos.firstOrNull { it.numero == numero }?.let { caja.leer(archivo(tomo.id, numero)) }?.takeIf { it.isNotBlank() }

    @Synchronized fun borrar(id: String) {
        val t = tomos().firstOrNull { it.id == id } ?: return
        t.capitulos.forEach { caja.escribir(archivo(id, it.numero), "") }
        guardar(tomos().filter { it.id != id })
        guardarPendientes(pendientes().filter { it.tomo != id })
    }

    /**
     * Corrige una parte de un capítulo sin reescribirlo: sustituye `buscar` (que debe aparecer exactamente una vez) por `reemplazar`.
     * Las fuentes nuevas se añaden a las que ya tenía.
     */
    @Synchronized fun corregir(tomoNombre: String, numero: Int, buscar: String, reemplazar: String, fuentes: List<String> = emptyList()): Tomo {
        val t = tomo(tomoNombre) ?: throw ErrorManos("no hay ningún tomo parecido a «$tomoNombre»")
        val cap = t.capitulos.firstOrNull { it.numero == numero } ?: throw ErrorManos("«${t.titulo}» no tiene capítulo $numero")
        val texto = caja.leer(archivo(t.id, numero))
        if (buscar.trim().length < 8) throw ErrorManos("«buscar» es demasiado corto: copia un fragmento de al menos 8 letras, tal cual está en el capítulo")
        val veces = texto.windowed(buscar.length, 1).count { it == buscar }
        if (veces == 0) throw ErrorManos("ese fragmento no está en el capítulo tal cual (mayúsculas, acentos y espacios cuentan). Léelo con biblioteca_leer y cópialo exacto")
        if (veces > 1) throw ErrorManos("ese fragmento aparece $veces veces en el capítulo: copia un trozo más largo para que solo haya uno")
        val nuevo = texto.replace(buscar, reemplazar).trim()
        if (nuevo.length < MIN_CAPITULO) throw ErrorManos("tras el cambio el capítulo quedaría demasiado corto")
        if (nuevo.length > MAX_CAPITULO) throw ErrorManos("tras el cambio el capítulo quedaría demasiado largo (máximo $MAX_CAPITULO letras)")
        caja.escribir(archivo(t.id, numero), nuevo)
        val juntas = (cap.fuentes + fuentes.map { it.trim().take(300) }.filter { it.isNotEmpty() }).distinct().take(16)
        val mejor = t.copy(capitulos = t.capitulos.map { if (it.numero == numero) it.copy(fuentes = juntas, letras = nuevo.length) else it })
        guardar(tomos().filter { it.id != t.id } + mejor)
        return mejor
    }

    // ------------------------------------------------------------ datos pendientes de contrastar
    @Synchronized fun pendientes(tomoNombre: String? = null): List<Pendiente> {
        val solo = tomoNombre?.takeIf { it.isNotBlank() }?.let { tomo(it)?.id ?: return emptyList() }
        return try {
            JSONArray(caja.leer("biblioteca_pendientes").ifBlank { "[]" }).let { a ->
                (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
                    Pendiente(o.getString("id"), o.getString("tomo"), o.getInt("capitulo"), o.getString("dato"), o.optString("motivo"), o.optLong("fecha")) } }.getOrNull() }
            }.filter { solo == null || it.tomo == solo }
        } catch (e: Exception) { emptyList() }
    }

    private fun guardarPendientes(l: List<Pendiente>) = caja.escribir("biblioteca_pendientes", JSONArray(l.map {
        JSONObject().put("id", it.id).put("tomo", it.tomo).put("capitulo", it.capitulo).put("dato", it.dato).put("motivo", it.motivo).put("fecha", it.fecha)
    }).toString())

    /** Apunta un dato dudoso de un capítulo para volver a él. Si ya estaba apuntado, devuelve el que había. */
    @Synchronized fun marcarPendiente(tomoNombre: String, numero: Int, dato: String, motivo: String, ahora: Long = System.currentTimeMillis()): Pendiente {
        val t = tomo(tomoNombre) ?: throw ErrorManos("no hay ningún tomo parecido a «$tomoNombre»")
        if (t.capitulos.none { it.numero == numero }) throw ErrorManos("«${t.titulo}» no tiene capítulo $numero")
        val d = dato.trim().replace(Regex("\\s+"), " ").take(400)
        if (d.length < 5) throw ErrorManos("di qué dato es el dudoso, tal como está en el capítulo")
        val todos = pendientes()
        todos.firstOrNull { it.tomo == t.id && it.capitulo == numero && Freno.norm(it.dato) == Freno.norm(d) }?.let { return it }
        if (todos.size >= MAX_PENDIENTES) throw ErrorManos("ya hay $MAX_PENDIENTES datos pendientes: verifica algunos antes de apuntar más")
        val n = (todos.mapNotNull { it.id.removePrefix("d").toIntOrNull() }.maxOrNull() ?: 0) + 1
        return Pendiente("d$n", t.id, numero, d, motivo.trim().take(300), ahora).also { guardarPendientes(todos + it) }
    }

    /** Da por resuelto un dato pendiente (confirmado, corregido o descartado) y lo quita de la lista. */
    @Synchronized fun verificar(id: String): Pendiente? {
        val todos = pendientes()
        val p = todos.firstOrNull { it.id == id.trim() } ?: return null
        guardarPendientes(todos.filter { it.id != p.id })
        return p
    }

    // ------------------------------------------------------------ consulta
    /**
     * Parte un capítulo en trozos de unas 1200 letras, cortando entre párrafos y sin mezclar secciones.
     * Una sección empieza en una línea que comienza por «## ». Devuelve (texto del trozo, título de su sección).
     */
    private fun trozos(texto: String): List<Pair<String, String>> {
        val salida = mutableListOf<Pair<String, String>>(); val actual = StringBuilder(); var seccion = ""
        fun cerrar() { if (actual.isNotBlank()) salida += actual.toString().trim() to seccion; actual.clear() }
        for (p in texto.split(Regex("\n\\s*\n"))) {
            val lineas = p.trim().lines()
            val titulo = lineas.firstOrNull()?.takeIf { it.startsWith("#") }?.trimStart('#', ' ')?.trim()
            val cuerpo = if (titulo != null) { cerrar(); seccion = titulo.take(80); lineas.drop(1).joinToString("\n") } else p
            if (cuerpo.isBlank()) continue
            if (actual.isNotEmpty() && actual.length + cuerpo.length > TROZO) cerrar()
            if (cuerpo.length > TROZO * 2) { cerrar(); cuerpo.chunked(TROZO).forEach { salida += it to seccion } } else actual.append(cuerpo.trim()).append("\n\n")
        }
        cerrar()
        return salida
    }

    private fun palabrasDe(s: String): List<String> = Freno.norm(s).split(Regex("[^a-z0-9ñ]+")).filter { it.length >= 3 }

    @Synchronized private fun indice(): Map<String, List<Trozo>> = indice ?: tomos().associate { t ->
        t.id to t.capitulos.flatMap { c ->
            trozos(caja.leer(archivo(t.id, c.numero))).map { (texto, seccion) -> Trozo(texto, c.numero, seccion, palabrasDe(c.titulo + " " + seccion + " " + texto)) }
        }
    }.also { indice = it }

    /** Los pasajes que mejor responden a la pregunta, de toda la biblioteca o de un solo tomo. */
    fun consultar(pregunta: String, max: Int = 4, soloTomo: String? = null): List<Pasaje> {
        val q = palabrasDe(pregunta).filter { it !in VACIAS }.distinct()
        if (q.isEmpty()) return emptyList()
        val tomos = tomos().associateBy { it.id }
        val docs = indice().filterKeys { soloTomo == null || it == soloTomo }.flatMap { (id, l) -> l.map { id to it } }
        if (docs.isEmpty()) return emptyList()
        val media = docs.sumOf { it.second.palabras.size }.toDouble() / docs.size
        val frecuencia = q.associateWith { p -> docs.count { p in it.second.palabras } }
        return docs.map { (id, d) ->
            val puntos = q.sumOf { p ->
                val f = d.palabras.count { it == p }
                if (f == 0) 0.0 else {
                    val idf = ln(1 + (docs.size - frecuencia.getValue(p) + 0.5) / (frecuencia.getValue(p) + 0.5))
                    idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * d.palabras.size / media))
                }
            }
            Triple(id, d, puntos)
        }.filter { it.third > 0 }.sortedByDescending { it.third }.take(max).mapNotNull { (id, d, p) ->
            val t = tomos[id] ?: return@mapNotNull null
            Pasaje(t, t.capitulos.first { it.numero == d.capitulo }, d.texto, p, q.count { it in d.palabras }, q.size, d.seccion)
        }
    }

    /**
     * Los pasajes listos para leer: cada uno con su tomo, capítulo y sección, y las fuentes UNA sola vez al final
     * (una línea por capítulo citado), en vez de repetidas en cada pasaje.
     */
    fun respuesta(l: List<Pasaje>): String {
        if (l.isEmpty()) return ""
        val pasajes = l.mapIndexed { i, p -> "[${i + 1}] ${p.cita()}\n${p.texto}" }.joinToString("\n\n")
        val citados = l.map { it.tomo to it.capitulo }.distinctBy { it.first.id to it.second.numero }
        val fuentes = citados.joinToString("\n") { (t, c) ->
            "· «${t.titulo}», capítulo ${c.numero}: " + if (c.fuentes.isEmpty()) "(sin fuentes apuntadas)" else c.fuentes.joinToString("; ")
        }
        val dudas = pendientes().filter { p -> citados.any { it.first.id == p.tomo && it.second.numero == p.capitulo } }
        return pasajes + "\n\nFuentes:\n" + fuentes +
            if (dudas.isEmpty()) "" else "\n\nOjo, datos de estos capítulos pendientes de contrastar:\n" + dudas.joinToString("\n") { "· ${it.id}: ${it.dato}" }
    }

    /** Lo que viene al caso para un mensaje, listo para dárselo al agente. Vacío si la biblioteca no tiene nada que decir. */
    fun contexto(mensaje: String): String {
        // viene al caso si el pasaje contiene al menos dos de las palabras del mensaje y, como mínimo, un tercio de ellas
        val l = consultar(mensaje, 2).filter { it.coinciden >= 2 && it.coinciden * 3 >= it.preguntadas }
        if (l.isEmpty()) return ""
        return "De tu biblioteca (lo estudiaste tú; úsalo y, si te sirve, di de qué tomo sale):\n" +
            l.joinToString("\n") { "· ${it.cita()}:\n${it.texto.take(900)}" }
    }

    companion object {
        const val MAX_TOMOS = 40
        const val MAX_CAPITULOS = 30
        const val MIN_CAPITULO = 600
        const val MAX_CAPITULO = 9000
        const val TROZO = 1200
        const val MAX_PENDIENTES = 200
        private val VACIAS = setOf("que", "los", "las", "una", "uno", "del", "por", "para", "con", "sin", "como", "pero", "mas", "sus", "muy", "esta", "este",
            "esto", "hay", "son", "fue", "ser", "cuando", "donde", "quiero", "puedes", "dime", "sobre", "cual", "cuales", "entre", "desde", "hasta", "the", "and", "for")
    }
}
