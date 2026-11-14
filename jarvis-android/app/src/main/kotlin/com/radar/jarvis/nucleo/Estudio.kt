package com.radar.jarvis.nucleo

/** Un video o un audio que JARVIS puede usar: adjuntado por ti, de la galería o montado por él. */
data class Medio(
    val id: String,
    val nombre: String,
    val tipo: String,               // video o audio
    val duracionMs: Long,
    val ancho: Int = 0,
    val alto: Int = 0,
    val fecha: String = "",
    val origen: String = "adjunto",  // adjunto, galería o montaje
) {
    fun linea(): String = "$id · $tipo · ${Montaje.reloj(duracionMs)}" + (if (tipo == "video") " · ${ancho}×$alto" else "") +
        " · $origen" + (if (fecha.isNotEmpty()) " · $fecha" else "") + " · «$nombre»"
}

/** Un trozo de un video dentro del montaje. */
data class Corte(val id: String, val desdeMs: Long, val hastaMs: Long, val texto: String = "", val sinSonido: Boolean = false)

/** El montaje que se va a exportar, ya comprobado. */
data class Plan(
    val cortes: List<Corte>,
    val ancho: Int,
    val alto: Int,
    val recortar: Boolean,           // true: llena el cuadro recortando; false: encaja el video entero con bandas
    val musicaId: String? = null,
    val volumenMusica: Float = 0.25f,
    val narracionId: String? = null,   // una voz que suena una vez, a volumen completo, por encima de todo
    val nombre: String = "Montaje",
) {
    val duracionMs get() = cortes.sumOf { it.hastaMs - it.desdeMs }
}

/**
 * El estudio de video de JARVIS: ver, escuchar y montar. En la app lo implementa Android (Media3);
 * en las pruebas, uno de mentira.
 */
interface Estudio {
    fun medios(): List<Medio>
    /** Una hoja de contactos (JPEG): `cuadros` fotogramas repartidos entre desdeMs y hastaMs, cada uno con su minuto y segundo. */
    fun hojaDeContactos(id: String, desdeMs: Long, hastaMs: Long, cuadros: Int): ByteArray
    /** Lo que se dice en el video, con marcas de tiempo. Lanza ErrorManos si no se puede transcribir. */
    suspend fun transcribir(id: String): String
    /** Convierte un guion en una narración hablada y la deja como un audio más. Lanza ErrorManos si no se puede. */
    suspend fun narrar(texto: String): Medio
    /** Abre el menú de compartir del teléfono con ese video, hacia la aplicación indicada si está instalada. */
    fun compartir(id: String, app: String)
    /** Exporta el montaje y devuelve el video nuevo. `progreso` recibe de 0 a 100. */
    suspend fun montar(plan: Plan, progreso: (Int) -> Unit): Medio
}

object Montaje {
    const val MAX_CORTES = 40
    const val MAX_DURACION_MS = 10 * 60 * 1000L
    const val MIN_CORTE_MS = 300L
    val FORMATOS = mapOf("vertical" to (1080 to 1920), "horizontal" to (1920 to 1080), "cuadrado" to (1080 to 1080))

    fun reloj(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun segundos(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.replace(',', '.')?.toDoubleOrNull()
    private fun par(n: Int) = (n / 2 * 2).coerceAtLeast(2)

    /**
     * Convierte lo que pide el agente en un Plan comprobado, o lanza ErrorManos explicando qué está mal.
     * Nada de esto toca los archivos: se valida todo antes de empezar a exportar.
     */
    fun plan(a: Map<String, Any?>, medios: List<Medio>): Plan {
        val porId = medios.associateBy { it.id }
        val lista = a["clips"] as? List<*> ?: throw ErrorManos("falta «clips»: la lista de trozos, en orden")
        if (lista.isEmpty()) throw ErrorManos("el montaje no tiene ningún trozo")
        if (lista.size > MAX_CORTES) throw ErrorManos("demasiados trozos (${lista.size}); el máximo es $MAX_CORTES")
        val cortes = lista.mapIndexed { i, crudo ->
            val c = crudo as? Map<*, *> ?: throw ErrorManos("el trozo ${i + 1} no es válido")
            val id = c["id"]?.toString().orEmpty()
            val m = porId[id] ?: throw ErrorManos("el trozo ${i + 1} usa «$id», que no está entre los medios disponibles")
            if (m.tipo != "video") throw ErrorManos("«$id» es un audio: como trozo solo valen videos")
            val desde = ((segundos(c["desde"]) ?: 0.0) * 1000).toLong().coerceAtLeast(0)
            val hasta = (segundos(c["hasta"])?.let { (it * 1000).toLong() } ?: m.duracionMs).coerceAtMost(m.duracionMs)
            if (desde >= m.duracionMs) throw ErrorManos("el trozo ${i + 1} empieza en ${reloj(desde)}, pero «$id» dura ${reloj(m.duracionMs)}")
            if (hasta - desde < MIN_CORTE_MS) throw ErrorManos("el trozo ${i + 1} es demasiado corto o termina antes de empezar (${reloj(desde)}–${reloj(hasta)})")
            Corte(id, desde, hasta, c["texto"]?.toString()?.trim().orEmpty().take(120), c["sin_sonido"] == true)
        }
        val total = cortes.sumOf { it.hastaMs - it.desdeMs }
        if (total > MAX_DURACION_MS) throw ErrorManos("el montaje duraría ${reloj(total)}; el máximo es ${reloj(MAX_DURACION_MS)}")

        val formato = a["formato"]?.toString()?.lowercase() ?: "original"
        val primero = porId.getValue(cortes[0].id)
        val (ancho, alto) = FORMATOS[formato] ?: if (formato == "original") {
            // el tamaño del primer trozo, sin pasar de 1920 en el lado largo y con medidas pares (lo exigen los codificadores)
            val largo = maxOf(primero.ancho, primero.alto).coerceAtLeast(2)
            val f = minOf(1.0, 1920.0 / largo)
            par((primero.ancho * f).toInt()) to par((primero.alto * f).toInt())
        } else throw ErrorManos("formato desconocido: original, ${FORMATOS.keys.joinToString()}")

        val musica = a["musica"]?.toString()?.takeIf { it.isNotBlank() }
        if (musica != null) {
            val m = porId[musica] ?: throw ErrorManos("la música «$musica» no está entre los medios disponibles")
            if (m.tipo != "audio") throw ErrorManos("«$musica» no es un audio")
        }
        val narracion = a["narracion"]?.toString()?.takeIf { it.isNotBlank() }
        if (narracion != null) {
            val m = porId[narracion] ?: throw ErrorManos("la narración «$narracion» no está entre los medios disponibles")
            if (m.tipo != "audio") throw ErrorManos("«$narracion» no es un audio")
        }
        if (narracion != null && porId.getValue(narracion).duracionMs > total + 1500) throw ErrorManos(
            "la narración dura ${reloj(porId.getValue(narracion).duracionMs)} y el video ${reloj(total)}: alarga o añade trozos hasta cubrirla, o acorta el guion")
        val volumen = (segundos(a["volumen_musica"]) ?: if (narracion != null) 0.12 else 0.25).coerceIn(0.0, 1.0).toFloat()
        val nombre = a["nombre"]?.toString()?.replace(Regex("[^\\p{L}\\p{N} _-]"), "")?.trim()?.take(40).orEmpty().ifEmpty { "Montaje" }
        return Plan(cortes, ancho, alto, a["encuadre"]?.toString() != "encajar" && formato != "original", musica, volumen, narracion, nombre)
    }
}
