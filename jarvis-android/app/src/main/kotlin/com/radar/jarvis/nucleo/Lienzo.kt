package com.radar.jarvis.nucleo

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Un cambio de una propiedad a lo largo del tiempo. prop: x, y, escala, giro u opacidad. Tiempos en segundos de la escena. */
data class Clave(val prop: String, val de: Double, val a: Double, val desde: Double, val hasta: Double, val curva: String)

/** Una orden de un trazado, ya en coordenadas absolutas. orden: M (mover), L (línea), C (curva de 3 puntos), Q (curva de 2) o Z (cerrar). */
data class Orden(val orden: Char, val n: List<Double>)

/** Una cosa dibujada en una escena. */
data class Capa(
    val tipo: String,                       // texto, rect, circulo, linea, trazado, imagen
    val x: Double, val y: Double,           // el centro (en linea y trazado, un desplazamiento)
    val ancho: Double, val alto: Double, val radio: Double,
    val giro: Double, val escala: Double, val opacidad: Double,
    val relleno: Int?, val relleno2: Int?, val borde: Int?, val grosor: Double, val sombra: Boolean,
    val texto: String, val tam: Double, val peso: String, val fuente: String, val alinea: String,
    val ordenes: List<Orden>,
    val medio: String, val segundo: Double, val encaje: String,
    val desde: Double, val hasta: Double,   // cuándo está en pantalla
    val claves: List<Clave>,
    val trazo: Pair<Double, Double>?,       // se va escribiendo (texto) o dibujando (linea, trazado) entre estos dos segundos
)

data class Escena(val dura: Double, val fondo: Int, val fondo2: Int?, val angulo: Double, val fundido: Double, val capas: List<Capa>)

/** Lo que JARVIS quiere dibujar: una o varias escenas. */
data class Guion(val ancho: Int, val alto: Int, val fps: Int, val escenas: List<Escena>) {
    val duracion get() = escenas.sumOf { it.dura }
    val cuadros get() = (duracion * fps).toInt().coerceAtLeast(1)
}

/** Cómo está una capa en un instante. progreso: de 0 a 1, cuánto se ha escrito o dibujado. */
data class Pose(val visible: Boolean, val x: Double, val y: Double, val escala: Double, val giro: Double, val opacidad: Double, val progreso: Double)

/**
 * El lienzo: el lenguaje con el que JARVIS dibuja imágenes y anima videos escribiendo código, sin ningún servicio
 * de fuera. Aquí se lee y se comprueba lo que escribe, y se calcula cómo está cada capa en cada instante.
 * Pintarlo de verdad es cosa de Android (Pintor).
 */
object Lienzo {
    const val MAX_ESCENAS = 60
    const val MAX_CAPAS = 60
    const val MAX_SEGUNDOS = 180.0
    val TIPOS = setOf("texto", "rect", "circulo", "linea", "trazado", "imagen")
    val PROPS = setOf("x", "y", "escala", "giro", "opacidad")
    val CURVAS = setOf("lineal", "suave", "entra", "sale", "rebote")
    private val COLORES = mapOf("blanco" to 0xFFFFFFFF, "negro" to 0xFF000000, "rojo" to 0xFFE53935, "verde" to 0xFF43A047, "azul" to 0xFF1E88E5,
        "amarillo" to 0xFFFDD835, "naranja" to 0xFFFB8C00, "morado" to 0xFF8E24AA, "rosa" to 0xFFD81B60, "gris" to 0xFF9E9E9E, "cian" to 0xFF00ACC1,
        "transparente" to 0x00000000)

    // ------------------------------------------------------------ leer
    private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.replace(',', '.')?.toDoubleOrNull()

    /** #rgb, #rrggbb, #rrggbbaa o un nombre. Devuelve el color como ARGB, o null si no se entiende. */
    fun color(v: Any?): Int? {
        val s = v?.toString()?.trim()?.lowercase() ?: return null
        COLORES[Freno.norm(s)]?.let { return it.toInt() }
        val h = s.removePrefix("#")
        if (!s.startsWith("#") || !h.all { it in "0123456789abcdef" }) return null
        return when (h.length) {
            3 -> (0xFF000000 or h.map { "$it$it" }.joinToString("").toLong(16)).toInt()
            6 -> (0xFF000000 or h.toLong(16)).toInt()
            8 -> ((h.substring(6).toLong(16) shl 24) or h.substring(0, 6).toLong(16)).toInt()
            else -> null
        }
    }

    /** Lee un trazado al estilo SVG (M L H V C Q Z, en mayúsculas absolutas o minúsculas relativas). */
    fun trazado(d: String): List<Orden> {
        val fichas = Regex("[MLHVCQZmlhvcqz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val salida = mutableListOf<Orden>()
        var i = 0; var cx = 0.0; var cy = 0.0; var ix = 0.0; var iy = 0.0; var orden = ' '
        fun n(): Double = fichas.getOrNull(i++)?.toDoubleOrNull() ?: throw ErrorManos("el trazado «${d.take(40)}» está incompleto")
        while (i < fichas.size) {
            if (fichas[i][0].isLetter()) orden = fichas[i++][0] else if (orden == ' ') throw ErrorManos("el trazado debe empezar por M")
            val rel = orden.isLowerCase()
            fun px(v: Double) = if (rel) cx + v else v
            fun py(v: Double) = if (rel) cy + v else v
            when (orden.uppercaseChar()) {
                'M' -> { cx = px(n()); cy = py(n()); ix = cx; iy = cy; salida += Orden('M', listOf(cx, cy)); orden = if (rel) 'l' else 'L' }
                'L' -> { cx = px(n()); cy = py(n()); salida += Orden('L', listOf(cx, cy)) }
                'H' -> { cx = px(n()); salida += Orden('L', listOf(cx, cy)) }
                'V' -> { cy = py(n()); salida += Orden('L', listOf(cx, cy)) }
                'C' -> { val a = px(n()); val b = py(n()); val c = px(n()); val e = py(n()); val x = px(n()); val y = py(n())
                    salida += Orden('C', listOf(a, b, c, e, x, y)); cx = x; cy = y }
                'Q' -> { val a = px(n()); val b = py(n()); val x = px(n()); val y = py(n()); salida += Orden('Q', listOf(a, b, x, y)); cx = x; cy = y }
                'Z' -> { salida += Orden('Z', emptyList()); cx = ix; cy = iy }
            }
            if (salida.size > 2000) throw ErrorManos("el trazado es demasiado largo")
        }
        if (salida.isEmpty() || salida[0].orden != 'M') throw ErrorManos("el trazado debe empezar por M")
        return salida
    }

    /** Las entradas y salidas ya hechas («sube», «aparece»…) convertidas en cambios normales. */
    private fun efecto(nombre: String, entra: Boolean, t0: Double, dura: Double, x: Double, y: Double, lado: Double, donde: String): List<Clave> {
        val a = if (entra) t0 else t0 - dura; val b = a + dura
        fun c(prop: String, fuera: Double, dentro: Double) =
            if (entra) Clave(prop, fuera, dentro, a, b, "sale") else Clave(prop, dentro, fuera, a, b, "entra")
        val op = c("opacidad", 0.0, 1.0)
        return when (nombre) {
            "aparece", "desaparece" -> listOf(op)
            "sube" -> listOf(op, c("y", y + lado * 0.06, y))
            "baja" -> listOf(op, c("y", y - lado * 0.06, y))
            "izquierda" -> listOf(op, c("x", x + lado * 0.08, x))
            "derecha" -> listOf(op, c("x", x - lado * 0.08, x))
            "crece" -> listOf(op, c("escala", 0.6, 1.0))
            "encoge" -> listOf(op, c("escala", 1.25, 1.0))
            else -> throw ErrorManos("$donde: efecto «$nombre» desconocido (aparece, sube, baja, izquierda, derecha, crece, encoge)")
        }
    }

    private fun capa(m: Map<*, *>, w: Int, h: Int, dura: Double, medios: List<Medio>, donde: String): Capa {
        val tipo = Freno.norm(m["tipo"]?.toString())
        if (tipo !in TIPOS) throw ErrorManos("$donde: tipo «${m["tipo"]}» desconocido (${TIPOS.joinToString()})")
        fun n(k: String, d: Double) = num(m[k]) ?: d
        fun col(k: String): Int? = m[k]?.let { color(it) ?: throw ErrorManos("$donde: no entiendo el color «$it» de $k (usa #rrggbb)") }
        val libre = tipo == "linea" || tipo == "trazado"
        val x = n("x", if (libre) 0.0 else w / 2.0); val y = n("y", if (libre) 0.0 else h / 2.0)
        val desde = n("desde", 0.0).coerceIn(0.0, dura); val hasta = n("hasta", dura).coerceIn(0.0, dura)
        if (hasta <= desde) throw ErrorManos("$donde: «hasta» ($hasta) debe ser mayor que «desde» ($desde), y los dos caber en los $dura s de la escena")

        val ordenes = when (tipo) {
            "trazado" -> trazado(m["d"]?.toString() ?: throw ErrorManos("$donde: al trazado le falta «d»"))
            "linea" -> {
                val p = (m["puntos"] as? List<*>)?.map { par -> (par as? List<*>)?.mapNotNull { num(it) }?.takeIf { it.size == 2 } ?: throw ErrorManos("$donde: cada punto es [x, y]") }
                    ?: throw ErrorManos("$donde: a la línea le faltan «puntos»: [[x, y], [x, y], …]")
                if (p.size < 2) throw ErrorManos("$donde: una línea necesita al menos dos puntos")
                p.mapIndexed { i, q -> Orden(if (i == 0) 'M' else 'L', q) }
            }
            else -> emptyList()
        }
        val texto = m["texto"]?.toString().orEmpty()
        if (tipo == "texto" && texto.isBlank()) throw ErrorManos("$donde: al texto le falta «texto»")
        if (texto.length > 600) throw ErrorManos("$donde: el texto es demasiado largo (máximo 600 letras por capa)")
        val medio = m["medio"]?.toString().orEmpty()
        if (tipo == "imagen") {
            val md = medios.firstOrNull { it.id == medio } ?: throw ErrorManos("$donde: la imagen usa el medio «$medio», que no existe (mira medios)")
            if (md.tipo == "audio") throw ErrorManos("$donde: «$medio» es un audio")
        }
        val radio = n("radio", if (tipo == "circulo") minOf(w, h) * 0.1 else 0.0)
        if (tipo == "circulo" && radio <= 0) throw ErrorManos("$donde: el círculo necesita un «radio» mayor que cero")

        val claves = mutableListOf<Clave>()
        (m["anima"] as? List<*>)?.forEachIndexed { i, crudo ->
            val k = crudo as? Map<*, *> ?: throw ErrorManos("$donde, anima ${i + 1}: no es válido")
            val prop = Freno.norm(k["prop"]?.toString())
            if (prop !in PROPS) throw ErrorManos("$donde, anima ${i + 1}: solo se animan ${PROPS.joinToString()}")
            val curva = Freno.norm(k["curva"]?.toString()).ifEmpty { "suave" }
            if (curva !in CURVAS) throw ErrorManos("$donde, anima ${i + 1}: curva «$curva» desconocida (${CURVAS.joinToString()})")
            val a = num(k["desde"]) ?: desde; val b = num(k["hasta"]) ?: (a + 0.6)
            if (b <= a) throw ErrorManos("$donde, anima ${i + 1}: «hasta» debe ser mayor que «desde»")
            claves += Clave(prop, num(k["de"]) ?: throw ErrorManos("$donde, anima ${i + 1}: falta «de»"), num(k["a"]) ?: throw ErrorManos("$donde, anima ${i + 1}: falta «a»"), a, b, curva)
        }
        val lado = minOf(w, h).toDouble()
        m["entra"]?.toString()?.let { claves += efecto(Freno.norm(it), true, desde, n("entra_dura", 0.5).coerceIn(0.05, hasta - desde), x, y, lado, donde) }
        m["sale"]?.toString()?.let { claves += efecto(Freno.norm(it), false, hasta, n("sale_dura", 0.4).coerceIn(0.05, hasta - desde), x, y, lado, donde) }

        val trazo = (m["trazo"] as? Map<*, *>)?.let { t ->
            val a = num(t["desde"]) ?: desde; val b = num(t["hasta"]) ?: (a + 1.0)
            if (b <= a) throw ErrorManos("$donde: en «trazo», «hasta» debe ser mayor que «desde»")
            a to b
        }
        val peso = Freno.norm(m["peso"]?.toString()).ifEmpty { "normal" }
        val alinea = Freno.norm(m["alinea"]?.toString()).ifEmpty { "centro" }
        if (alinea !in setOf("centro", "izquierda", "derecha")) throw ErrorManos("$donde: «alinea» es centro, izquierda o derecha")
        val ancho = n("ancho", if (tipo == "texto") w * 0.86 else if (tipo == "imagen") w.toDouble() else w * 0.4)
        val alto = n("alto", if (tipo == "imagen") h.toDouble() else ancho * 0.6)
        if (!libre && tipo != "circulo" && (ancho <= 0 || alto <= 0)) throw ErrorManos("$donde: «ancho» y «alto» deben ser mayores que cero")
        return Capa(tipo, x, y, ancho, alto, radio, n("giro", 0.0), n("escala", 1.0), n("opacidad", 1.0).coerceIn(0.0, 1.0),
            col("relleno") ?: if (tipo == "texto") 0xFFFFFFFF.toInt() else if (libre || col("borde") != null) null else 0xFFFFFFFF.toInt(),
            col("relleno2"), col("borde") ?: if (libre && col("relleno") == null) 0xFFFFFFFF.toInt() else null,
            n("grosor", lado * 0.006).coerceIn(0.5, 200.0), m["sombra"] == true,
            texto, n("tam", lado * 0.06).coerceIn(8.0, 600.0), if (peso in setOf("negrita", "bold")) "negrita" else "normal",
            Freno.norm(m["fuente"]?.toString()).let { if (it in setOf("serif", "mono")) it else "sans" }, alinea,
            ordenes, medio, n("segundo", 0.0).coerceAtLeast(0.0), if (Freno.norm(m["encaje"]?.toString()) == "contener") "contener" else "cubrir",
            desde, hasta, claves, trazo)
    }

    private fun escena(m: Map<*, *>, w: Int, h: Int, medios: List<Medio>, donde: String, duraFija: Double? = null): Escena {
        val dura = duraFija ?: num(m["dura"]) ?: 4.0
        if (dura !in 0.3..60.0) throw ErrorManos("$donde: «dura» va de 0.3 a 60 segundos")
        val capas = (m["capas"] as? List<*>) ?: throw ErrorManos("$donde: faltan las «capas»")
        if (capas.isEmpty()) throw ErrorManos("$donde: no tiene ninguna capa")
        if (capas.size > MAX_CAPAS) throw ErrorManos("$donde: demasiadas capas (${capas.size}); el máximo es $MAX_CAPAS")
        val fondo = m["fondo"]?.let { color(it) ?: throw ErrorManos("$donde: no entiendo el color de fondo «$it»") } ?: 0xFF000000.toInt()
        return Escena(dura, fondo, m["fondo2"]?.let { color(it) ?: throw ErrorManos("$donde: no entiendo el color «$it» de fondo2") },
            num(m["angulo"]) ?: 90.0, (num(m["fundido"]) ?: 0.0).coerceIn(0.0, dura / 2),
            capas.mapIndexed { i, c -> capa(c as? Map<*, *> ?: throw ErrorManos("$donde, capa ${i + 1}: no es válida"), w, h, dura, medios, "$donde, capa ${i + 1}") })
    }

    private fun medidas(a: Map<String, Any?>): Pair<Int, Int> {
        val formato = Freno.norm(a["formato"]?.toString()).ifEmpty { "vertical" }
        val (w, h) = Montaje.FORMATOS[formato] ?: throw ErrorManos("formato desconocido: ${Montaje.FORMATOS.keys.joinToString()}")
        return w to h
    }

    /** Lo que escribe el agente para un video -> un Guion comprobado, o ErrorManos diciendo exactamente qué está mal y dónde. */
    fun video(a: Map<String, Any?>, medios: List<Medio>): Guion {
        val (w, h) = medidas(a)
        val fps = (num(a["fps"]) ?: 30.0).toInt().let { if (it in setOf(24, 25, 30)) it else throw ErrorManos("«fps» es 24, 25 o 30") }
        val l = a["escenas"] as? List<*> ?: throw ErrorManos("faltan las «escenas»")
        if (l.isEmpty()) throw ErrorManos("el video no tiene ninguna escena")
        if (l.size > MAX_ESCENAS) throw ErrorManos("demasiadas escenas (${l.size}); el máximo es $MAX_ESCENAS")
        val g = Guion(w, h, fps, l.mapIndexed { i, e -> escena(e as? Map<*, *> ?: throw ErrorManos("escena ${i + 1}: no es válida"), w, h, medios, "escena ${i + 1}") })
        if (g.duracion > MAX_SEGUNDOS) throw ErrorManos("el video duraría ${g.duracion.toInt()} s; el máximo es ${MAX_SEGUNDOS.toInt()}")
        return g
    }

    /** Lo que escribe el agente para una imagen: una sola escena, que se pinta en su instante final (o en «instante»). */
    fun imagen(a: Map<String, Any?>, medios: List<Medio>): Pair<Guion, Double> {
        val (w, h) = medidas(a)
        val e = escena(a, w, h, medios, "imagen", duraFija = (num(a["dura"]) ?: 4.0).coerceIn(0.3, 60.0))
        return Guion(w, h, 30, listOf(e)) to (num(a["instante"]) ?: e.dura).coerceIn(0.0, e.dura)
    }

    // ------------------------------------------------------------ calcular
    fun curva(nombre: String, t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (nombre) {
            "lineal" -> x
            "entra" -> x * x * x
            "sale" -> 1 - (1 - x).pow(3)
            "rebote" -> 1 + 2.70158 * (x - 1).pow(3) + 1.70158 * (x - 1).pow(2)      // se pasa un poco y vuelve
            else -> if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2          // suave
        }
    }

    private fun valor(base: Double, prop: String, claves: List<Clave>, t: Double): Double {
        val mias = claves.filter { it.prop == prop }
        if (mias.isEmpty()) return base
        // manda el último cambio que ya empezó; antes del primero, vale su punto de partida
        val k = mias.filter { it.desde <= t }.maxByOrNull { it.desde } ?: return mias.minBy { it.desde }.de
        return k.de + (k.a - k.de) * curva(k.curva, (t - k.desde) / (k.hasta - k.desde))
    }

    /** Cómo está la capa en el segundo `t` de su escena. */
    fun pose(c: Capa, t: Double): Pose {
        val visible = t >= c.desde && t <= c.hasta
        val progreso = c.trazo?.let { ((t - it.first) / (it.second - it.first)).coerceIn(0.0, 1.0) } ?: 1.0
        return Pose(visible, valor(c.x, "x", c.claves, t), valor(c.y, "y", c.claves, t), valor(c.escala, "escala", c.claves, t),
            valor(c.giro, "giro", c.claves, t), (c.opacidad * valor(1.0, "opacidad", c.claves, t)).coerceIn(0.0, 1.0), progreso)
    }

    /** Qué escena toca en el segundo `t` del video, en qué segundo de esa escena, y cuánto se ve (por el fundido). */
    fun en(g: Guion, t: Double): Triple<Escena, Double, Double> {
        var resto = t.coerceIn(0.0, g.duracion)
        for ((i, e) in g.escenas.withIndex()) {
            if (resto <= e.dura || i == g.escenas.lastIndex) {
                val local = resto.coerceAtMost(e.dura)
                val alfa = if (e.fundido <= 0) 1.0 else minOf(1.0, local / e.fundido, (e.dura - local) / e.fundido).coerceIn(0.0, 1.0)
                return Triple(e, local, alfa)
            }
            resto -= e.dura
        }
        return Triple(g.escenas.last(), g.escenas.last().dura, 1.0)
    }

    /** Los dos extremos de un degradado que cruza el rectángulo con ese ángulo (90 = de arriba abajo). */
    fun degradado(w: Double, h: Double, angulo: Double): List<Double> {
        val r = Math.toRadians(angulo); val dx = cos(r) * w / 2; val dy = sin(r) * h / 2
        return listOf(w / 2 - dx, h / 2 - dy, w / 2 + dx, h / 2 + dy)
    }

    /** La guía del lenguaje, para el agente. */
    val GUIA = """
Dibujas escribiendo una escena como datos. El lienzo mide 1080×1920 (formato vertical), 1920×1080 (horizontal) o 1080×1080 (cuadrado); el origen es la esquina de arriba a la izquierda y las medidas son píxeles.

Escena: {"dura": 4, "fondo": "#0b1020", "fondo2": "#1b2a6b", "angulo": 90, "fundido": 0.4, "capas": [...]}. fondo2 hace un degradado; fundido funde la escena con negro al entrar y al salir. Las capas se pintan en orden: la última queda encima.

Capas (x, y son el CENTRO de la capa):
- texto: {"tipo": "texto", "texto": "...", "x", "y", "tam": 90, "relleno": "#fff", "peso": "negrita", "fuente": "sans|serif|mono", "alinea": "centro|izquierda|derecha", "ancho": 900, "sombra": true}. Se parte en líneas dentro de «ancho».
- rect: {"tipo": "rect", "x", "y", "ancho", "alto", "radio": 24, "relleno", "relleno2", "borde", "grosor"}.
- circulo: {"tipo": "circulo", "x", "y", "radio", "relleno", "borde", "grosor"}.
- linea: {"tipo": "linea", "puntos": [[x, y], [x, y]], "borde": "#fff", "grosor": 8}. Coordenadas absolutas.
- trazado: {"tipo": "trazado", "d": "M 100 100 L 300 100 C ... Q ... Z", "relleno", "borde", "grosor"}. Como un path de SVG (M L H V C Q Z). Para iconos, flechas, curvas y formas libres.
- imagen: {"tipo": "imagen", "medio": "v1", "segundo": 3.5, "x", "y", "ancho", "alto", "encaje": "cubrir|contener", "radio": 30}. Un fotograma de un video, o una imagen adjunta o creada antes.
Todas aceptan "opacidad" (0 a 1), "giro" (grados), "escala", y "desde"/"hasta" (segundos de la escena en que están en pantalla).

Movimiento:
- Atajos: "entra": "aparece|sube|baja|izquierda|derecha|crece|encoge" y "sale": igual. Duran medio segundo ("entra_dura", "sale_dura").
- A medida: "anima": [{"prop": "x|y|escala|giro|opacidad", "de": 0, "a": 1, "desde": 0.2, "hasta": 0.9, "curva": "suave|lineal|entra|sale|rebote"}].
- "trazo": {"desde": 0.5, "hasta": 2}: el texto se va escribiendo, o la línea o el trazado se van dibujando.
- Zoom lento sobre una imagen: anima su escala de 1 a 1.15 durante toda la escena.
- Rótulos al ritmo de una voz: una capa de texto por frase, con "desde" y "hasta" tomados de los tiempos que te da `narrar`.

Oficio: deja márgenes (nada pegado al borde), pocas cosas por escena, tipografía grande (títulos de 80 a 130, cuerpo de 44 a 60), dos o tres colores con buen contraste, y que todo lo importante quepa en la zona central. Calcula las posiciones: lo que dibujas es exactamente lo que se ve.
""".trim()
}
