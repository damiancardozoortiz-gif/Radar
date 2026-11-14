package com.radar.jarvis.nucleo

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Dónde se guarda la mente. En la app son archivos del teléfono (con copia en Google Drive); en las pruebas, un mapa. */
interface Caja {
    fun leer(nombre: String): String
    fun escribir(nombre: String, texto: String)
}

/** Algo que JARVIS recuerda. Capas: perfil (quién eres), hecho (personas, lugares, datos), episodio (qué hizo), leccion (qué aprendió). */
data class Recuerdo(val id: String, val capa: String, val texto: String, val fecha: Long, val origen: String)

/** Una receta que JARVIS sabe seguir. No es código: son instrucciones. */
data class Habilidad(
    val id: String, val nombre: String, val cuando: String, val pasos: String, val origen: String,
    val exitos: Int = 0, val fallos: Int = 0,
) {
    /** Una habilidad que falla seguido se aparta hasta que alguien la corrija. */
    val enRevision get() = fallos >= 3 && fallos > exitos
}

/** Cuándo salta una rutina. tipo: hora, notificacion, lugar o calendario. */
data class Disparador(
    val tipo: String,
    val minuto: Int = -1,                 // hora: minutos desde medianoche
    val dias: Set<Int> = emptySet(),      // hora: 1 = lunes … 7 = domingo; vacío = todos los días
    val contiene: String = "",            // notificacion: texto o aplicación; calendario: texto del evento (vacío = cualquiera)
    val lat: Double = 0.0, val lon: Double = 0.0, val radio: Int = 200, val lugar: String = "",
    val antes: Int = 15,                  // calendario: minutos antes del evento
) {
    fun frase(): String = when (tipo) {
        "hora" -> "a las %d:%02d".format(minuto / 60, minuto % 60) + if (dias.isEmpty() || dias.size == 7) ", todos los días"
            else ", " + dias.sorted().joinToString(" ") { DIAS[it - 1] }
        "notificacion" -> "cuando llegue una notificación con «$contiene»"
        "lugar" -> "al llegar a ${lugar.ifEmpty { "un lugar" }}"
        "calendario" -> "$antes minutos antes de un evento" + if (contiene.isNotEmpty()) " con «$contiene»" else ""
        else -> tipo
    }
    private companion object { val DIAS = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom") }
}

/** Algo que JARVIS hace por iniciativa. nivel: avisar (solo te cuenta), preparar (deja listo) o hacer (lo hace; el freno sigue). */
data class Rutina(
    val id: String, val nombre: String, val disparador: Disparador, val encargo: String, val nivel: String,
    val activa: Boolean = true, val creadaPor: String = "jarvis", val ultima: Long = 0, val veces: Int = 0, val marca: String = "",
)

/** Cómo salió una tarea. voto: 1 te gustó, -1 no, 0 sin votar. sola: la lanzó una rutina. */
data class Evaluacion(
    val fecha: Long, val tarea: String, val logrado: Boolean, val nota: Int, val pasos: Int, val ms: Long,
    val coste: Double, val voto: Int = 0, val sola: Boolean = false,
)

data class Aviso(val app: String, val titulo: String, val texto: String, val hora: Long) {
    val clave get() = "$app|$titulo|$texto|$hora"
}
data class Cita(val titulo: String, val inicio: Long, val fin: Long, val lugar: String = "") {
    val clave get() = "$titulo|$inicio"
}
data class Posicion(val lat: Double, val lon: Double, val nombre: String = "")

/** Lo que pasa ahora mismo, para decidir qué rutinas saltan. */
data class Momento(
    val ahora: Long, val zona: ZoneId = ZoneId.systemDefault(),
    val avisos: List<Aviso> = emptyList(), val posicion: Posicion? = null, val citas: List<Cita> = emptyList(),
)

data class Panel(val tareas: Int, val logradas: Int, val gustaron: Int, val noGustaron: Int, val coste: Double, val solas: Int, val msMedio: Long)

/**
 * La mente de JARVIS: lo que recuerda, lo que sabe hacer, lo que hace por iniciativa y cómo le va.
 * Todo vive en cuatro archivos JSON. No hay nada de Android aquí: se prueba en cualquier computadora.
 */
class Mente(private val caja: Caja) {
    private var n = 0L
    private fun id(prefijo: String) = prefijo + java.lang.Long.toString(System.nanoTime() + n++, 36)

    private fun lista(nombre: String): JSONArray = try { JSONArray(caja.leer(nombre).ifBlank { "[]" }) } catch (e: Exception) { JSONArray() }
    private fun <T> leer(nombre: String, de: (JSONObject) -> T): List<T> =
        lista(nombre).let { a -> (0 until a.length()).mapNotNull { runCatching { de(a.getJSONObject(it)) }.getOrNull() } }
    private fun <T> guardar(nombre: String, l: List<T>, a: (T) -> JSONObject) = caja.escribir(nombre, JSONArray(l.map(a)).toString())

    // ============================================================ memoria
    @Synchronized fun recuerdos(): List<Recuerdo> = leer("recuerdos") {
        Recuerdo(it.getString("id"), it.getString("capa"), it.getString("texto"), it.getLong("fecha"), it.optString("origen"))
    }
    private fun guardarRecuerdos(l: List<Recuerdo>) = guardar("recuerdos", l) {
        JSONObject().put("id", it.id).put("capa", it.capa).put("texto", it.texto).put("fecha", it.fecha).put("origen", it.origen)
    }

    /**
     * Guarda un recuerdo. Devuelve null (y no guarda) si está vacío, si ya lo sabía o si parece un secreto:
     * JARVIS nunca recuerda contraseñas, PIN, códigos ni tarjetas.
     */
    @Synchronized fun recordar(capa: String, texto: String, origen: String, ahora: Long = System.currentTimeMillis()): Recuerdo? {
        val t = texto.trim().replace(Regex("\\s+"), " ").take(400)
        if (t.length < 4 || capa !in CAPAS || pareceSecreto(t)) return null
        val todos = recuerdos()
        val mias = palabras(t)
        if (todos.any { it.capa == capa && parecido(mias, palabras(it.texto)) >= 0.8 }) return null
        val nuevo = Recuerdo(id("r"), capa, t, ahora, origen)
        var l = todos + nuevo
        if (l.size > MAX_RECUERDOS) {               // si no cabe, se van los episodios más viejos; el perfil y los hechos se quedan
            val sobran = l.size - MAX_RECUERDOS
            val viejos = l.filter { it.capa == "episodio" }.sortedBy { it.fecha }.take(sobran).map { it.id }.toSet()
            l = l.filter { it.id !in viejos }.takeLast(MAX_RECUERDOS)
        }
        guardarRecuerdos(l)
        return nuevo
    }

    @Synchronized fun editarRecuerdo(id: String, texto: String): Boolean {
        val t = texto.trim().take(400)
        if (t.length < 4 || pareceSecreto(t)) return false
        val l = recuerdos()
        if (l.none { it.id == id }) return false
        guardarRecuerdos(l.map { if (it.id == id) it.copy(texto = t) else it })
        return true
    }

    @Synchronized fun borrarRecuerdo(id: String) = guardarRecuerdos(recuerdos().filter { it.id != id })
    @Synchronized fun borrarMemoria() = guardarRecuerdos(emptyList())

    /** Los recuerdos de una capa que tienen que ver con la consulta, los más parecidos primero. */
    fun relevantes(consulta: String, capa: String, max: Int): List<Recuerdo> {
        val q = palabras(consulta)
        if (q.isEmpty()) return emptyList()
        return recuerdos().filter { it.capa == capa }.map { it to palabras(it.texto).count { p -> p in q } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Recuerdo, Int>> { it.second }.thenByDescending { it.first.fecha })
            .take(max).map { it.first }
    }

    // ============================================================ habilidades
    @Synchronized fun habilidades(): List<Habilidad> = leer("habilidades") {
        Habilidad(it.getString("id"), it.getString("nombre"), it.getString("cuando"), it.getString("pasos"), it.optString("origen"),
            it.optInt("exitos"), it.optInt("fallos"))
    }
    private fun guardarHabilidades(l: List<Habilidad>) = guardar("habilidades", l) {
        JSONObject().put("id", it.id).put("nombre", it.nombre).put("cuando", it.cuando).put("pasos", it.pasos).put("origen", it.origen)
            .put("exitos", it.exitos).put("fallos", it.fallos)
    }

    /** Aprende una habilidad nueva, o corrige la que ya tenía con ese nombre (y le borra los fallos: es una versión nueva). */
    @Synchronized fun aprender(nombre: String, cuando: String, pasos: String, origen: String): Habilidad? {
        val nom = nombre.trim().take(60); val cu = cuando.trim().take(200); val pa = pasos.trim().take(1500)
        if (nom.length < 3 || pa.length < 10 || pareceSecreto(pa)) return null
        val l = habilidades()
        val vieja = l.firstOrNull { Freno.norm(it.nombre) == Freno.norm(nom) }
        val nueva = Habilidad(vieja?.id ?: id("h"), nom, cu, pa, origen, vieja?.exitos ?: 0, 0)
        val resto = l.filter { it.id != nueva.id }
        if (vieja == null && resto.size >= MAX_HABILIDADES) return null
        guardarHabilidades(resto + nueva)
        return nueva
    }

    @Synchronized fun borrarHabilidad(id: String) = guardarHabilidades(habilidades().filter { it.id != id })

    /** Las habilidades que encajan con lo pedido. Las que están en revisión no se ofrecen. */
    fun habilidadesPara(consulta: String, max: Int = 2): List<Habilidad> {
        val q = palabras(consulta)
        return habilidades().filter { !it.enRevision }.map { it to palabras(it.nombre + " " + it.cuando).count { p -> p in q } }
            .filter { it.second >= 2 || (it.second == 1 && q.size <= 3) }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** Apunta cómo le fue a cada habilidad que se usó en una tarea. */
    @Synchronized fun anotarUso(ids: Collection<String>, exito: Boolean) {
        if (ids.isEmpty()) return
        guardarHabilidades(habilidades().map {
            if (it.id !in ids) it else if (exito) it.copy(exitos = it.exitos + 1) else it.copy(fallos = it.fallos + 1)
        })
    }

    /**
     * Importa habilidades de un archivo de texto. Formato, una o varias veces:
     *   # Nombre de la habilidad
     *   cuando: en qué situaciones usarla
     *   (el resto de las líneas son los pasos)
     */
    fun importar(texto: String): Int {
        var cuenta = 0
        for (bloque in texto.split(Regex("(?m)^#\\s*")).drop(1)) {
            val lineas = bloque.trim().lines()
            val cuando = lineas.drop(1).firstOrNull { it.trim().lowercase().startsWith("cuando:") }
            val pasos = lineas.drop(1).filter { it !== cuando }.joinToString("\n").trim()
            if (aprender(lineas.firstOrNull().orEmpty(), cuando?.substringAfter(":").orEmpty(), pasos, "importada") != null) cuenta++
        }
        return cuenta
    }

    // ============================================================ rutinas
    @Synchronized fun rutinas(): List<Rutina> = leer("rutinas") { o ->
        val d = o.getJSONObject("disparador")
        Rutina(o.getString("id"), o.getString("nombre"),
            Disparador(d.getString("tipo"), d.optInt("minuto", -1), d.optJSONArray("dias")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                d.optString("contiene"), d.optDouble("lat", 0.0), d.optDouble("lon", 0.0), d.optInt("radio", 200), d.optString("lugar"), d.optInt("antes", 15)),
            o.getString("encargo"), o.optString("nivel", "hacer"), o.optBoolean("activa", true), o.optString("creadaPor", "jarvis"),
            o.optLong("ultima"), o.optInt("veces"), o.optString("marca"))
    }
    private fun guardarRutinas(l: List<Rutina>) = guardar("rutinas", l) { r ->
        val d = r.disparador
        JSONObject().put("id", r.id).put("nombre", r.nombre).put("encargo", r.encargo).put("nivel", r.nivel).put("activa", r.activa)
            .put("creadaPor", r.creadaPor).put("ultima", r.ultima).put("veces", r.veces).put("marca", r.marca)
            .put("disparador", JSONObject().put("tipo", d.tipo).put("minuto", d.minuto).put("dias", JSONArray(d.dias.sorted()))
                .put("contiene", d.contiene).put("lat", d.lat).put("lon", d.lon).put("radio", d.radio).put("lugar", d.lugar).put("antes", d.antes))
    }

    /** Crea una rutina, o lanza ErrorManos explicando qué le falta. */
    @Synchronized fun crearRutina(nombre: String, d: Disparador, encargo: String, nivel: String, creadaPor: String): Rutina {
        val nom = nombre.trim().take(60); val enc = encargo.trim().take(600)
        if (nom.length < 3) throw ErrorManos("la rutina necesita un nombre")
        if (enc.length < 8) throw ErrorManos("la rutina necesita un encargo: qué hay que hacer, entero y con nombres")
        if (nivel !in NIVELES) throw ErrorManos("nivel desconocido: ${NIVELES.joinToString()}")
        when (d.tipo) {
            "hora" -> if (d.minuto !in 0 until 1440 || d.dias.any { it !in 1..7 }) throw ErrorManos("la hora no es válida (formato 08:30; días del 1 = lunes al 7 = domingo)")
            "notificacion" -> if (d.contiene.trim().length < 2) throw ErrorManos("di qué texto o aplicación debe tener la notificación")
            "lugar" -> if (d.lat == 0.0 && d.lon == 0.0 || d.radio !in 50..5000) throw ErrorManos("el lugar necesita latitud, longitud y un radio de 50 a 5000 metros")
            "calendario" -> if (d.antes !in 1..240) throw ErrorManos("los minutos de antelación van de 1 a 240")
            else -> throw ErrorManos("tipo de disparador desconocido: hora, notificacion, lugar o calendario")
        }
        val l = rutinas()
        if (l.any { Freno.norm(it.nombre) == Freno.norm(nom) }) throw ErrorManos("ya hay una rutina que se llama «$nom»")
        if (l.size >= MAX_RUTINAS) throw ErrorManos("ya hay $MAX_RUTINAS rutinas; borra alguna antes de crear otra")
        return Rutina(id("u"), nom, d, enc, nivel, true, creadaPor).also { guardarRutinas(l + it) }
    }

    @Synchronized fun borrarRutina(id: String): Boolean = rutinas().let { l -> guardarRutinas(l.filter { it.id != id }); l.any { it.id == id } }
    @Synchronized fun activarRutina(id: String, activa: Boolean) = guardarRutinas(rutinas().map { if (it.id == id) it.copy(activa = activa) else it })

    /**
     * Las rutinas que tocan ahora, con el motivo. Las deja marcadas como atendidas, así que cada llamada devuelve
     * cada disparo UNA sola vez: una hora salta una vez al día, un evento una vez, un lugar al entrar.
     */
    @Synchronized fun debidas(m: Momento): List<Pair<Rutina, String>> {
        val local = Instant.ofEpochMilli(m.ahora).atZone(m.zona)
        val hoy = local.toLocalDate()
        val minuto = local.hour * 60 + local.minute
        val salen = mutableListOf<Pair<Rutina, String>>()
        val nuevas = rutinas().map { r ->
            if (!r.activa) return@map r
            val d = r.disparador
            var marca = r.marca
            var motivo: String? = null
            when (d.tipo) {
                "hora" -> {
                    val yaHoy = r.ultima > 0 && Instant.ofEpochMilli(r.ultima).atZone(m.zona).toLocalDate() == hoy
                    val diaOk = d.dias.isEmpty() || local.dayOfWeek.value in d.dias
                    if (!yaHoy && diaOk && minuto in d.minuto until d.minuto + VENTANA_MIN) motivo = "son las %d:%02d".format(local.hour, local.minute)
                }
                "notificacion" -> {
                    val q = Freno.norm(d.contiene)
                    val a = m.avisos.firstOrNull { q in Freno.norm(it.app + " " + it.titulo + " " + it.texto) && it.clave != r.marca }
                    if (a != null && m.ahora - r.ultima > ENFRIAR_MS) { motivo = "llegó una notificación de ${a.app}: ${a.titulo} ${a.texto}".take(300); marca = a.clave }
                }
                "lugar" -> if (m.posicion != null) {
                    val dentro = metros(m.posicion.lat, m.posicion.lon, d.lat, d.lon) <= d.radio
                    if (dentro && r.marca != "dentro") motivo = "acabas de llegar a ${d.lugar.ifEmpty { "ese lugar" }}"
                    marca = if (dentro) "dentro" else "fuera"
                }
                "calendario" -> {
                    val q = Freno.norm(d.contiene)
                    val c = m.citas.firstOrNull { it.inicio - m.ahora in 1..d.antes * 60_000L && (q.isEmpty() || q in Freno.norm(it.titulo)) && it.clave != r.marca }
                    if (c != null) { motivo = "en ${(c.inicio - m.ahora) / 60_000 + 1} minutos empieza «${c.titulo}»"; marca = c.clave }
                }
            }
            if (motivo == null) (if (marca != r.marca) r.copy(marca = marca) else r)
            else r.copy(ultima = m.ahora, veces = r.veces + 1, marca = marca).also { salen += it to motivo }
        }
        guardarRutinas(nuevas)
        return salen
    }

    /** La próxima hora (en milisegundos) a la que salta alguna rutina de reloj, o null si no hay ninguna. */
    fun proximaHora(ahora: Long, zona: ZoneId = ZoneId.systemDefault()): Long? {
        val local = Instant.ofEpochMilli(ahora).atZone(zona)
        return rutinas().filter { it.activa && it.disparador.tipo == "hora" }.mapNotNull { r ->
            (0..7).firstNotNullOfOrNull { mas ->
                val dia = local.toLocalDate().plusDays(mas.toLong())
                val t = dia.atStartOfDay(zona).plusMinutes(r.disparador.minuto.toLong()).toInstant().toEpochMilli()
                if (t > ahora && (r.disparador.dias.isEmpty() || dia.dayOfWeek.value in r.disparador.dias)) t else null
            }
        }.minOrNull()
    }

    // ============================================================ evaluación
    @Synchronized fun evaluaciones(): List<Evaluacion> = leer("evaluaciones") {
        Evaluacion(it.getLong("fecha"), it.getString("tarea"), it.getBoolean("logrado"), it.optInt("nota", 3), it.optInt("pasos"),
            it.optLong("ms"), it.optDouble("coste", 0.0), it.optInt("voto"), it.optBoolean("sola"))
    }
    private fun guardarEvaluaciones(l: List<Evaluacion>) = guardar("evaluaciones", l.takeLast(MAX_EVALUACIONES)) {
        JSONObject().put("fecha", it.fecha).put("tarea", it.tarea).put("logrado", it.logrado).put("nota", it.nota).put("pasos", it.pasos)
            .put("ms", it.ms).put("coste", it.coste).put("voto", it.voto).put("sola", it.sola)
    }

    @Synchronized fun evaluar(e: Evaluacion) = guardarEvaluaciones(evaluaciones() + e.copy(tarea = e.tarea.take(200)))

    /** Cambia la evaluación de esa fecha (la revisión posterior o tu voto). */
    @Synchronized fun corregir(fecha: Long, cambio: (Evaluacion) -> Evaluacion) =
        guardarEvaluaciones(evaluaciones().map { if (it.fecha == fecha) cambio(it) else it })

    fun panel(desde: Long): Panel {
        val l = evaluaciones().filter { it.fecha >= desde }
        return Panel(l.size, l.count { it.logrado }, l.count { it.voto > 0 }, l.count { it.voto < 0 }, l.sumOf { it.coste },
            l.count { it.sola }, if (l.isEmpty()) 0 else l.sumOf { it.ms } / l.size)
    }

    /** Lo que han gastado HOY las tareas que JARVIS lanzó solo. Es lo que vigila el fusible. */
    fun gastoSoloHoy(ahora: Long, zona: ZoneId = ZoneId.systemDefault()): Double {
        val inicio = Instant.ofEpochMilli(ahora).atZone(zona).toLocalDate().atStartOfDay(zona).toInstant().toEpochMilli()
        return evaluaciones().filter { it.sola && it.fecha >= inicio }.sumOf { it.coste }
    }

    // ============================================================ lo que se le cuenta al agente
    data class Contexto(val texto: String, val habilidades: List<String>)

    /** Lo que JARVIS debe tener presente para este mensaje: quién eres, lo que viene al caso, lecciones y habilidades. */
    fun contexto(mensaje: String): Contexto {
        val todos = recuerdos()
        val perfil = todos.filter { it.capa == "perfil" }.takeLast(15)
        val hechos = relevantes(mensaje, "hecho", 8)
        val episodios = relevantes(mensaje, "episodio", 4)
        val lecciones = relevantes(mensaje, "leccion", 5)
        val habs = habilidadesPara(mensaje)
        val otras = habilidades().filter { h -> !h.enRevision && habs.none { it.id == h.id } }
        val s = StringBuilder()
        fun bloque(titulo: String, l: List<Recuerdo>) { if (l.isNotEmpty()) s.append("$titulo:\n").append(l.joinToString("\n") { "- ${it.texto}" }).append("\n") }
        bloque("Lo que sabes de la persona", perfil)
        bloque("Datos que vienen al caso", hechos)
        bloque("Veces anteriores parecidas", episodios)
        bloque("Lecciones que aprendiste (aplícalas)", lecciones)
        for (h in habs) s.append("Habilidad «${h.nombre}» (${h.cuando}). Síguela si encaja:\n${h.pasos}\n")
        if (otras.isNotEmpty()) s.append("Otras habilidades que tienes: ${otras.joinToString(", ") { it.nombre }}.\n")
        return Contexto(s.toString().trim(), habs.map { it.id })
    }

    companion object {
        val CAPAS = setOf("perfil", "hecho", "episodio", "leccion")
        val NIVELES = listOf("avisar", "preparar", "hacer")
        const val MAX_RECUERDOS = 500
        const val MAX_HABILIDADES = 60
        const val MAX_RUTINAS = 30
        const val MAX_EVALUACIONES = 600
        const val VENTANA_MIN = 45              // una rutina de reloj puede saltar hasta 45 minutos tarde (si el teléfono dormía)
        const val ENFRIAR_MS = 5 * 60_000L      // una rutina de notificaciones no salta dos veces en cinco minutos

        /** Dólares por millón de tokens (entrada, salida). Precios de lista del 3 de noviembre de 2026. */
        val PRECIOS = mapOf("claude-opus-5-5" to (4.0 to 20.0), "claude-sonnet-5-5" to (2.0 to 10.0), "claude-haiku-5-5" to (0.10 to 0.50))

        /** Coste estimado, por arriba: no descuenta lo que se cobra más barato por estar ya enviado. */
        fun coste(modelo: String, entrada: Long, salida: Long): Double {
            val (e, s) = PRECIOS[modelo] ?: (4.0 to 20.0)
            return (entrada * e + salida * s) / 1_000_000.0
        }

        private val VACIAS = setOf("que", "los", "las", "una", "uno", "del", "por", "para", "con", "sin", "como", "pero", "mas", "sus", "muy",
            "esta", "este", "esto", "eso", "esa", "ese", "hay", "son", "fue", "ser", "the", "and", "for", "me", "mi", "tu", "le", "se", "lo", "la",
            "el", "en", "de", "al", "ya", "no", "si", "es", "un", "su", "te", "nos", "cuando", "donde", "quiero", "puedes", "hazme", "dime")

        fun palabras(s: String): Set<String> =
            Freno.norm(s).split(Regex("[^a-z0-9ñ]+")).filter { it.length >= 2 && it !in VACIAS }.toSet()

        private fun parecido(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.count { it in b }.toDouble() / (a.size + b.size - a.count { it in b })

        private val SECRETO = Regex("contrasen|password|passwd|\\bpin\\b|\\bcvv\\b|\\bcvc\\b|clave de (acceso|api|seguridad)|codigo de (verificacion|seguridad)|" +
            "token|\\bsk-[a-z0-9-]{8,}|numero de tarjeta")
        /** ¿Parece una contraseña, un código o una tarjeta? Eso no se guarda nunca. */
        fun pareceSecreto(t: String): Boolean =
            SECRETO.containsMatchIn(Freno.norm(t)) || Regex("(\\d[ -]?){13,19}").containsMatchIn(t)

        /** Distancia en metros entre dos puntos del mapa. */
        fun metros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = Math.toRadians(lat2 - lat1); val g = Math.toRadians(lon2 - lon1)
            val a = sin(r / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(g / 2).pow(2)
            return 2 * 6_371_000.0 * asin(sqrt(a))
        }
    }
}
