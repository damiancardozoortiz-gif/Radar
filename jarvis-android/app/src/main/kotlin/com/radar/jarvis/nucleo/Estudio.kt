package com.radar.jarvis.nucleo

/** Un video o un audio que JARVIS puede usar: adjuntado por ti, de la galería o montado por él. */
data class Medio(
    val id: String,
    val nombre: String,
    val tipo: String,               // video, audio o imagen (una imagen no tiene duración propia)
    val duracionMs: Long,
    val ancho: Int = 0,
    val alto: Int = 0,
    val fecha: String = "",
    val origen: String = "adjunto",  // adjunto, galería o montaje
) {
    fun linea(): String = "$id · $tipo" + (if (tipo != "imagen") " · ${Montaje.reloj(duracionMs)}" else "") + (if (tipo != "audio") " · ${ancho}×$alto" else "") +
        " · $origen" + (if (fecha.isNotEmpty()) " · $fecha" else "") + " · «$nombre»"
}

/** Un trozo de un video dentro del montaje. */
data class Corte(
    val id: String, val desdeMs: Long, val hastaMs: Long, val texto: String = "", val sinSonido: Boolean = false,
    val velocidad: Float = 1f,        // 0.25 a 4: más de 1 acelera, menos de 1 es cámara lenta
    val volumen: Float = 1f,          // del sonido propio del trozo: 0 a 2
    val fundido: String = "",         // "", entrada, salida o ambos: aparece desde negro o se va a negro
    val movimiento: String = "",      // "", acercar, alejar, izquierda o derecha: movimiento lento, pensado para imágenes fijas
    val imagen: Boolean = false,      // una foto que se queda en pantalla (hastaMs - desdeMs es lo que dura)
) {
    /** Lo que ocupa en el video final, ya con el cambio de velocidad. */
    val duracionMs get() = ((hastaMs - desdeMs) / velocidad).toLong()
}

/** Una palabra de la narración, con el momento en que se dice. */
data class Palabra(val texto: String, val desdeMs: Long, val hastaMs: Long)

/** Un rótulo que aparece y desaparece en un momento dado. */
data class Rotulo(val desdeMs: Long, val hastaMs: Long, val texto: String)

/** Un tramo del video final con otro volumen para la música de fondo. */
data class TramoMusica(val desdeMs: Long, val hastaMs: Long, val volumen: Float)

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
    val subtitulos: List<Rotulo> = emptyList(),        // en tiempos del video final, sincronizados con la narración
    val tramosMusica: List<TramoMusica> = emptyList(), // dónde la música suena a otro volumen
) {
    val duracionMs get() = cortes.sumOf { it.duracionMs }

    /** Los subtítulos que caen sobre el trozo `i`, con tiempos contados desde que empieza ese trozo. */
    fun subtitulosDe(i: Int): List<Rotulo> {
        val inicio = cortes.take(i).sumOf { it.duracionMs }
        val fin = inicio + cortes[i].duracionMs
        return subtitulos.filter { it.hastaMs > inicio && it.desdeMs < fin }
            .map { Rotulo(maxOf(it.desdeMs, inicio) - inicio, minOf(it.hastaMs, fin) - inicio, it.texto) }
    }

    /** El volumen de la música en ese instante del video final. */
    fun volumenMusicaEn(ms: Long): Float = tramosMusica.lastOrNull { ms >= it.desdeMs && ms < it.hastaMs }?.volumen ?: volumenMusica
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
    /** Cuándo se dice cada palabra de una narración hecha con narrar, o null si no se sabe. */
    fun tiemposDeVoz(id: String): List<Palabra>? = null
    /** Abre el menú de compartir del teléfono con ese video, hacia la aplicación indicada si está instalada. */
    fun compartir(id: String, app: String)
    /** Exporta el montaje y devuelve el video nuevo. `progreso` recibe de 0 a 100. */
    suspend fun montar(plan: Plan, progreso: (Int) -> Unit): Medio
}

object Montaje {
    const val MAX_CORTES = 40
    const val MAX_DURACION_MS = 10 * 60 * 1000L
    const val MIN_CORTE_MS = 300L
    const val FUNDIDO_MS = 500L
    const val IMAGEN_MS = 4000L
    val FUNDIDOS = setOf("entrada", "salida", "ambos")
    val MOVIMIENTOS = setOf("acercar", "alejar", "izquierda", "derecha")
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
    fun plan(a: Map<String, Any?>, medios: List<Medio>, tiempos: (String) -> List<Palabra>? = { null }): Plan {
        val porId = medios.associateBy { it.id }
        val lista = a["clips"] as? List<*> ?: throw ErrorManos("falta «clips»: la lista de trozos, en orden")
        if (lista.isEmpty()) throw ErrorManos("el montaje no tiene ningún trozo")
        if (lista.size > MAX_CORTES) throw ErrorManos("demasiados trozos (${lista.size}); el máximo es $MAX_CORTES")
        val cortes = lista.mapIndexed { i, crudo ->
            val c = crudo as? Map<*, *> ?: throw ErrorManos("el trozo ${i + 1} no es válido")
            val id = c["id"]?.toString().orEmpty()
            val m = porId[id] ?: throw ErrorManos("el trozo ${i + 1} usa «$id», que no está entre los medios disponibles")
            if (m.tipo == "audio") throw ErrorManos("«$id» es un audio: como trozo solo valen videos e imágenes")
            val texto = c["texto"]?.toString()?.trim().orEmpty().take(120)
            val fundido = c["fundido"]?.toString()?.lowercase()?.trim().orEmpty().let { if (it == "ninguno") "" else it }
            if (fundido.isNotEmpty() && fundido !in FUNDIDOS) throw ErrorManos("el trozo ${i + 1} pide el fundido «$fundido»: vale entrada, salida o ambos")
            val movimiento = c["movimiento"]?.toString()?.lowercase()?.trim().orEmpty().let { if (it == "ninguno") "" else it }
            if (movimiento.isNotEmpty() && movimiento !in MOVIMIENTOS) throw ErrorManos("el trozo ${i + 1} pide el movimiento «$movimiento»: vale ${MOVIMIENTOS.joinToString()}")
            if (m.tipo == "imagen") {
                val dura = ((segundos(c["duracion"]) ?: (IMAGEN_MS / 1000.0)) * 1000).toLong()
                if (dura < MIN_CORTE_MS || dura > 60_000) throw ErrorManos("la imagen del trozo ${i + 1} debe durar entre 0,3 y 60 segundos")
                return@mapIndexed Corte(id, 0, dura, texto, sinSonido = true, fundido = fundido, movimiento = movimiento, imagen = true)
            }
            val desde = ((segundos(c["desde"]) ?: 0.0) * 1000).toLong().coerceAtLeast(0)
            val hasta = (segundos(c["hasta"])?.let { (it * 1000).toLong() } ?: m.duracionMs).coerceAtMost(m.duracionMs)
            if (desde >= m.duracionMs) throw ErrorManos("el trozo ${i + 1} empieza en ${reloj(desde)}, pero «$id» dura ${reloj(m.duracionMs)}")
            if (hasta - desde < MIN_CORTE_MS) throw ErrorManos("el trozo ${i + 1} es demasiado corto o termina antes de empezar (${reloj(desde)}–${reloj(hasta)})")
            val velocidad = (segundos(c["velocidad"]) ?: 1.0).toFloat()
            if (velocidad < 0.25f || velocidad > 4f) throw ErrorManos("la velocidad del trozo ${i + 1} debe estar entre 0.25 y 4")
            val volumen = (segundos(c["volumen"]) ?: 1.0).toFloat()
            if (volumen < 0f || volumen > 2f) throw ErrorManos("el volumen del trozo ${i + 1} debe estar entre 0 y 2")
            val corte = Corte(id, desde, hasta, texto, c["sin_sonido"] == true || volumen == 0f, velocidad, volumen, fundido, movimiento)
            if (corte.duracionMs < MIN_CORTE_MS) throw ErrorManos("el trozo ${i + 1}, acelerado, quedaría demasiado corto")
            corte
        }
        val total = cortes.sumOf { it.duracionMs }
        if (total > MAX_DURACION_MS) throw ErrorManos("el montaje duraría ${reloj(total)}; el máximo es ${reloj(MAX_DURACION_MS)}")

        val formato = a["formato"]?.toString()?.lowercase() ?: "original"
        val primero = porId.getValue(cortes[0].id)
        if (primero.ancho <= 0 || primero.alto <= 0) throw ErrorManos("no conozco el tamaño de «${primero.id}»; elige un formato: ${FORMATOS.keys.joinToString()}")
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
        // subtítulos sincronizados con la narración
        val subtitulos = if (a["subtitulos"] == true) {
            if (narracion == null) throw ErrorManos("los subtítulos sincronizados necesitan una narración hecha con narrar (narracion=\"id\")")
            val palabras = tiempos(narracion)
            if (palabras.isNullOrEmpty()) throw ErrorManos("de la narración «$narracion» no se guardaron los tiempos de cada palabra; vuelve a grabarla con narrar")
            if (cortes.any { it.texto.isNotEmpty() }) throw ErrorManos("un trozo no puede llevar a la vez su propio texto y subtítulos sincronizados: quita uno de los dos")
            rotulos(palabras).filter { it.desdeMs < total }.map { it.copy(hastaMs = minOf(it.hastaMs, total)) }
        } else emptyList()

        // música a otro volumen en algunos tramos del video final
        val tramos = (a["volumen_musica_tramos"] as? List<*>).orEmpty().mapIndexed { i, crudo ->
            val t = crudo as? Map<*, *> ?: throw ErrorManos("el tramo de música ${i + 1} no es válido")
            if (musica == null) throw ErrorManos("hay tramos de volumen de música pero no hay música")
            val d = ((segundos(t["desde"]) ?: throw ErrorManos("al tramo de música ${i + 1} le falta «desde»")) * 1000).toLong()
            val h = ((segundos(t["hasta"]) ?: throw ErrorManos("al tramo de música ${i + 1} le falta «hasta»")) * 1000).toLong().coerceAtMost(total)
            val v = (segundos(t["volumen"]) ?: throw ErrorManos("al tramo de música ${i + 1} le falta «volumen»")).toFloat()
            if (d < 0 || h <= d) throw ErrorManos("el tramo de música ${i + 1} termina antes de empezar o cae fuera del video (${reloj(total)})")
            if (v < 0f || v > 1f) throw ErrorManos("el volumen del tramo de música ${i + 1} debe estar entre 0 y 1")
            TramoMusica(d, h, v)
        }
        if (tramos.size > 20) throw ErrorManos("demasiados tramos de música (${tramos.size}); el máximo es 20")
        return Plan(cortes, ancho, alto, a["encuadre"]?.toString() != "encajar" && formato != "original", musica, volumen, narracion, nombre, subtitulos, tramos)
    }

    /** Junta las letras con tiempos que devuelve la voz en palabras con su principio y su final. */
    fun palabras(letras: List<String>, empiezan: List<Double>, terminan: List<Double>): List<Palabra> {
        val n = minOf(letras.size, empiezan.size, terminan.size)
        val salida = mutableListOf<Palabra>()
        val actual = StringBuilder(); var desde = 0.0; var hasta = 0.0
        fun cerrar() { if (actual.isNotEmpty()) salida += Palabra(actual.toString(), (desde * 1000).toLong(), (hasta * 1000).toLong()); actual.setLength(0) }
        for (i in 0 until n) {
            if (letras[i].isBlank()) { cerrar(); continue }
            if (actual.isEmpty()) desde = empiezan[i]
            actual.append(letras[i]); hasta = terminan[i]
        }
        cerrar()
        return salida
    }

    /**
     * Agrupa las palabras en rótulos cortos (hasta `maxLetras`), que cambian al ritmo de la voz.
     * Corta también en los signos de puntuación y en los silencios, y ningún rótulo se queda en pantalla
     * mucho después de que se termine de decir.
     */
    fun rotulos(palabras: List<Palabra>, maxLetras: Int = 28): List<Rotulo> {
        val grupos = mutableListOf<MutableList<Palabra>>()
        for (p in palabras) {
            val g = grupos.lastOrNull()
            val cabe = g != null && g.sumOf { it.texto.length + 1 } + p.texto.length <= maxLetras
            val pausa = g != null && p.desdeMs - g.last().hastaMs > 600
            val frase = g != null && g.last().texto.last() in ".!?…:;"
            if (g == null || !cabe || pausa || frase) grupos += mutableListOf(p) else g += p
        }
        return grupos.mapIndexed { i, g ->
            val fin = g.last().hastaMs
            val siguiente = grupos.getOrNull(i + 1)?.first()?.desdeMs
            // se queda hasta que empieza el siguiente si viene enseguida; si no, 400 ms más
            val hasta = if (siguiente != null && siguiente - fin <= 400) siguiente else fin + 400
            Rotulo(g.first().desdeMs, maxOf(hasta, g.first().desdeMs + 1), g.joinToString(" ") { it.texto })
        }
    }

    /** Cuánto se ve la imagen (0 negro, 1 entera) a `ms` del principio de un trozo que dura `duracionMs`. */
    fun brillo(fundido: String, ms: Long, duracionMs: Long): Float {
        val f = minOf(FUNDIDO_MS, duracionMs / 2).coerceAtLeast(1)
        var b = 1f
        if (fundido == "entrada" || fundido == "ambos") b = minOf(b, ms.toFloat() / f)
        if (fundido == "salida" || fundido == "ambos") b = minOf(b, (duracionMs - ms).toFloat() / f)
        return b.coerceIn(0f, 1f)
    }

    /**
     * El encuadre del movimiento lento a `ms` del principio: (escala, desplazamiento horizontal).
     * La escala nunca baja de 1 y el desplazamiento nunca deja ver el borde, así que no asoman bandas.
     */
    fun encuadre(movimiento: String, ms: Long, duracionMs: Long): Pair<Float, Float> {
        val t = (ms.toFloat() / duracionMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        return when (movimiento) {
            "acercar" -> (1f + 0.18f * t) to 0f
            "alejar" -> (1.18f - 0.18f * t) to 0f
            "izquierda" -> 1.15f to (0.15f - 0.30f * t)      // la imagen se desliza hacia la izquierda
            "derecha" -> 1.15f to (-0.15f + 0.30f * t)
            else -> 1f to 0f
        }
    }
}
