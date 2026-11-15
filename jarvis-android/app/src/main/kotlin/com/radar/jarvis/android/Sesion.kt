package com.radar.jarvis.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.radar.jarvis.nucleo.Agente
import com.radar.jarvis.nucleo.Aprendido
import com.radar.jarvis.nucleo.Evaluacion
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.Momento
import com.radar.jarvis.nucleo.Peticion
import com.radar.jarvis.nucleo.Reflexion
import com.radar.jarvis.nucleo.Respuesta
import com.radar.jarvis.nucleo.Rutina
import com.radar.jarvis.nucleo.Vivido
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArraySet

enum class Estado(val etiqueta: String) {
    ESPERA("en espera"), ESCUCHANDO("escuchando"), PENSANDO("pensando"), ACTUANDO("actuando"),
    ESPERA_SI("espera tu sí"), HABLANDO("hablando"), PAUSA("en pausa")
}

data class Paso(val n: Int, val texto: String, val estado: String, val detalle: String)

/**
 * Lo que JARVIS está haciendo ahora mismo. Una sola instancia para toda la app: la pantalla principal y el orbe
 * flotante la observan y pintan lo mismo. Todo lo que cambia aquí se avisa en el hilo principal.
 *
 * También lleva el pulso de la iniciativa: cada minuto mira si alguna rutina debe saltar.
 */
object Sesion {
    private val principal = Handler(Looper.getMainLooper())
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val oyentes = CopyOnWriteArraySet<() -> Unit>()
    private var trabajo: Job? = null
    private var agente: Agente? = null
    private var herramientas: Herramientas? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private lateinit var ctx: Context

    lateinit var almacen: Almacen
        private set
    lateinit var voz: Voz
        private set
    lateinit var estudio: EstudioAndroid
        private set
    lateinit var fuentes: FuentesAndroid
        private set
    val mente: Mente get() = almacen.mente

    @Volatile var estado = Estado.ESPERA; private set
    @Volatile var subtitulo = ""; private set
    @Volatile var detalle = ""; private set
    @Volatile var peticion: Peticion? = null; private set
    @Volatile var pausa = false; private set
    @Volatile var enPrimerPlano = false
    /** La fecha de la última tarea evaluada que aún puedes votar, o 0. */
    @Volatile var porVotar = 0L; private set
    val pasos = java.util.Collections.synchronizedList(mutableListOf<Paso>())

    val ocupado get() = trabajo?.isActive == true

    fun iniciar(contexto: Context) {
        ctx = contexto.applicationContext
        almacen = Almacen(ctx)
        voz = Voz(ctx)
        estudio = EstudioAndroid(ctx, almacen)
        fuentes = FuentesAndroid(ctx, almacen)
        principal.postDelayed(pulso, 20_000)
        principal.postDelayed({ estudiar() }, 6_000)        // si un estudio quedó a medias al cerrarse la app, sigue por donde iba
    }

    // ------------------------------------------------------------ estudiar un tomo en segundo plano
    private var trabajoEstudio: Job? = null
    val estudiando get() = trabajoEstudio?.isActive == true

    /** Lo llama el agente al empezar o detener un estudio. */
    private fun alCambiarEstudio() {
        if (almacen.estudios.actual()?.estado == "en_curso") estudiar() else { trabajoEstudio?.cancel(); avisar() }
    }

    /**
     * Arranca o continúa el estudio que haya en marcha. Va aparte de la conversación: puedes seguir usando a JARVIS
     * mientras escribe. No toca el teléfono: solo busca, lee y escribe en la biblioteca.
     */
    fun estudiar() {
        if (estudiando || pausa || !almacen.hayClave || !almacen.estudios.toca()) return
        trabajoEstudio = ambito.launch {
            val fin = com.radar.jarvis.nucleo.Estudiante(almacen.estudios, ::escribirCapitulo) { p ->
                almacen.apuntar("ESTUDIO ${p.resumen()}"); avisar()
            }.correr() ?: return@launch
            avisar()
            if (fin.estado == "terminado") {
                almacen.apuntar("ESTUDIO terminado: ${fin.resumen()}")
                val pendientes = almacen.biblioteca.pendientes(fin.tomo).size
                Avisador.avisar(ctx, "JARVIS terminó de estudiar", fin.resumen() + if (pendientes > 0) ". Quedaron $pendientes datos por contrastar." else "")
            }
        }
    }

    /** Un capítulo lo escribe un agente nuevo, con la cabeza limpia y su propio cupo de búsquedas. Devuelve si quedó guardado. */
    private suspend fun escribirCapitulo(p: com.radar.jarvis.nucleo.PlanEstudio, c: com.radar.jarvis.nucleo.CapituloPlan): Boolean {
        val fecha = System.currentTimeMillis()
        val manos = Herramientas(ServicioAccesibilidad.manos, mente, { false }, { _, texto, est, det -> if (est != "en curso") almacen.apuntar("ESTUDIO cap. ${c.numero}: $texto $det".take(200)) },
            biblioteca = almacen.biblioteca, soloEstas = com.radar.jarvis.nucleo.Estudios.HERRAMIENTAS)
        val escritor = Agente(almacen.ajustes().copy(busquedas = BUSQUEDAS_POR_CAPITULO, lecturas = LECTURAS_POR_CAPITULO, quien = ""), manos, mente,
            extraSistema = com.radar.jarvis.nucleo.Estudios.OFICIO)
        val r = escritor.correr(almacen.estudios.encargo(p, c))
        val escrito = almacen.biblioteca.tomo(p.tomo)?.capitulos?.any { it.numero == c.numero } == true
        mente.evaluar(Evaluacion(fecha, "Estudiar «${p.tomo}», capítulo ${c.numero}: ${c.titulo}", escrito, 3, r.pasos, System.currentTimeMillis() - fecha, r.coste))
        almacen.apuntar("ESTUDIO cap. ${c.numero} ${if (escrito) "escrito" else "NO escrito (${r.texto.take(80)})"} · ${r.busquedas} búsquedas, ${r.lecturas} lecturas, ~${"%.3f".format(java.util.Locale.US, r.coste)} $")
        return escrito
    }

    fun observar(o: () -> Unit) { oyentes += o }
    fun dejarDeObservar(o: () -> Unit) { oyentes -= o }
    fun avisar() { principal.post { oyentes.forEach { it() } } }

    private fun poner(e: Estado, texto: String? = null) {
        estado = if (pausa) Estado.PAUSA else e
        if (texto != null) subtitulo = texto
        avisar()
    }

    /** Los ajustes cambiaron: el próximo mensaje usa un agente nuevo (y una conversación nueva). */
    fun reconfigurar() { agente = null; herramientas = null }

    fun nuevaConversacion() {
        agente?.olvidarConversacion()
        pasos.clear(); detalle = ""; porVotar = 0
        poner(Estado.ESPERA, "Conversación nueva.")
    }

    private fun agente(): Agente = agente ?: run {
        val h = Herramientas(ServicioAccesibilidad.manos, mente, ::pedirPermiso, ::alPaso,
            alCrearRutina = { r -> almacen.apuntar("RUTINA creada: ${r.nombre} (${r.disparador.frase()})"); programarDespertador() },
            estudio = estudio, fuentes = fuentes, biblioteca = almacen.biblioteca, puentes = com.radar.jarvis.nucleo.Puentes(almacen.puentes),
            estudios = almacen.estudios, alEstudiar = { alCambiarEstudio() })
        herramientas = h
        Agente(almacen.ajustes(), h, mente, { nota -> pasos += Paso(0, nota, "nota", ""); avisar() }, almacen.biblioteca).also { agente = it }
    }

    private fun alPaso(n: Int, texto: String, est: String, det: String) {
        synchronized(pasos) {
            val i = pasos.indexOfFirst { it.n == n }
            if (i >= 0) pasos[i] = Paso(n, texto, est, det) else pasos += Paso(n, texto, est, det)
        }
        if (est != "en curso") almacen.apuntar("${if (est == "hecho") "hecho" else "FALLO"}  $texto ${det.take(80)}")
        if (est == "en curso" && peticion == null) poner(Estado.ACTUANDO, "$texto…") else avisar()
    }

    /** Lo llama el freno: se enseña la tarjeta y la tarea se queda esperando tu toque (diez minutos como mucho). */
    private suspend fun pedirPermiso(p: Peticion): Boolean {
        val d = CompletableDeferred<Boolean>()
        decision = d
        peticion = p
        almacen.apuntar("PERMISO pedido: ${p.accion} en ${p.donde} (${p.motivo})")
        poner(Estado.ESPERA_SI, "Necesito tu sí: ${p.accion}")
        if (!enPrimerPlano) Avisador.avisar(ctx, "JARVIS necesita tu sí", p.accion)
        if (almacen.voz) principal.post { voz.decir("Necesito tu sí en pantalla.") }
        val ok = try { withTimeoutOrNull(ESPERA_MAX) { d.await() } ?: false } finally { peticion = null; decision = null }
        almacen.apuntar("PERMISO ${if (ok) "APROBADO" else "rechazado"}: ${p.accion}")
        poner(Estado.ACTUANDO)
        return ok
    }

    /** Tu respuesta a la tarjeta de permiso. Solo vale tocar el botón. */
    fun decidir(aprobar: Boolean) { decision?.complete(aprobar) }

    /** Lo que le dices o le escribes. */
    fun enviar(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        if (pausa) return poner(Estado.PAUSA, "Estoy en pausa. Quítala para seguir.")
        if (ocupado) return poner(estado, "Estoy con otra cosa. Espera o pulsa Detener.")
        lanzar(t, null, null)
    }

    private fun lanzar(t: String, rutina: Rutina?, motivo: String?) {
        voz.callar()
        pasos.clear(); detalle = ""; porVotar = 0
        almacen.apuntar(if (rutina != null) "RUTINA «${rutina.nombre}» salta: $motivo" else "TAREA: $t")
        poner(Estado.PENSANDO, if (rutina != null) "Rutina «${rutina.nombre}»: $motivo" else "«$t»")
        trabajo = ambito.launch {
            val fecha = System.currentTimeMillis()
            val a = agente()
            herramientas?.nivel = rutina?.nivel ?: "hacer"
            val r = try {
                a.correr(t, rutina?.let { "«${it.nombre}» (nivel ${it.nivel}), porque $motivo" })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Respuesta("Algo falló dentro de JARVIS: ${e.javaClass.simpleName}. ${e.message.orEmpty().take(120)}", "fallo", 0)
            } finally { herramientas?.nivel = "hacer" }
            almacen.apuntar("FIN (${r.estado}, ${r.pasos} pasos, ${r.busquedas} búsquedas, ${r.lecturas} lecturas, ~${"%.3f".format(java.util.Locale.US, r.coste)} $): ${r.texto.take(160)}")
            val partes = r.texto.trim().split(Regex("\n\\s*\n"), limit = 2)
            detalle = partes.getOrElse(1) { "" }.trim()
            val decir = partes[0].trim()

            // se apunta cómo fue; la revisión posterior lo afina y aprende de ello
            val hechos = synchronized(pasos) { pasos.filter { it.n > 0 }.map { "${it.texto} (${it.estado}${if (it.detalle.isNotEmpty()) ": " + it.detalle else ""})" } }
            mente.evaluar(Evaluacion(fecha, t, r.estado == "terminada", 3, r.pasos, System.currentTimeMillis() - fecha, r.coste, sola = rutina != null))
            if (r.estado != "fallo" || r.pasos > 0) porVotar = fecha
            if (almacen.memoriaSola && almacen.hayClave) ambito.launch {
                revisar(a, Vivido(fecha, t, r.texto, hechos, r.estado == "terminada", rutina != null, r.habilidades, almacen.rutinasSolas))
            }

            if (rutina != null) Avisador.avisar(ctx, "JARVIS · ${rutina.nombre}", decir)
            if (almacen.voz && !pausa) {
                poner(Estado.HABLANDO, decir)
                principal.post { voz.decir(limpiar(decir)) { if (estado == Estado.HABLANDO) poner(Estado.ESPERA) } }
            } else poner(Estado.ESPERA, decir)
        }
    }

    /** Después de cada turno, sin que nadie lo pida: qué recordar, cómo salió, qué aprender. */
    private suspend fun revisar(a: Agente, v: Vivido) {
        val ap: Aprendido = try { Reflexion.revisar(mente, a, v) } catch (e: CancellationException) { throw e } catch (e: Exception) { Aprendido() }
        val extra = a.cobrarRevision()
        if (extra > 0) mente.corregir(v.fecha) { it.copy(coste = it.coste + extra) }
        val notas = buildList {
            if (ap.recuerdos > 0) add("Recordé ${ap.recuerdos} ${if (ap.recuerdos == 1) "cosa nueva" else "cosas nuevas"}.")
            ap.leccion?.let { add("Lección: $it") }
            ap.habilidad?.let { add("Aprendí la habilidad «$it».") }
            ap.rutina?.let { add("Creé la rutina «${it.nombre}»: ${it.disparador.frase()}. Puedes verla o borrarla en «mente».") }
        }
        if (notas.isEmpty()) return
        notas.forEach { almacen.apuntar("APRENDIDO: $it"); pasos += Paso(0, it, "nota", "") }
        ap.rutina?.let { programarDespertador(); Avisador.avisar(ctx, "JARVIS creó una rutina", "${it.nombre}: ${it.disparador.frase()}") }
        avisar()
    }

    /** Tu voto sobre la última respuesta: pesa más que su propia nota. */
    fun votar(gusto: Boolean) {
        val fecha = porVotar.takeIf { it > 0 } ?: return
        porVotar = 0
        mente.corregir(fecha) { it.copy(voto = if (gusto) 1 else -1, logrado = if (gusto) it.logrado else false) }
        almacen.apuntar("VOTO: ${if (gusto) "bien" else "mal"}")
        poner(estado, if (gusto) "Anotado, gracias." else "Anotado. Dime qué salió mal y lo aprendo.")
    }

    /** Para lo que esté haciendo: rechaza lo que esperaba permiso y corta la tarea. */
    fun detener(motivo: String = "Detenido.") {
        decision?.complete(false)
        trabajo?.cancel()
        voz.callar(); voz.dejarDeEscuchar()
        poner(Estado.ESPERA, motivo)
    }

    /** Pausa de emergencia: corta todo, tampoco saltan las rutinas, y no acepta nada hasta que la quites. */
    fun ponerPausa(on: Boolean) {
        pausa = on
        if (on) { trabajoEstudio?.cancel(); detener("En pausa. No hago nada hasta que la quites."); almacen.apuntar("PAUSA activada") }
        else { estado = Estado.ESPERA; subtitulo = "Listo."; avisar(); estudiar() }
    }

    /** Los videos o audios que eliges con el botón de adjuntar. */
    fun adjuntar(uris: List<android.net.Uri>) {
        ambito.launch {
            val n = estudio.adjuntar(uris)
            poner(estado, if (n == 0) "No pude leer esos archivos: tienen que ser videos, audios o imágenes."
                else "$n ${if (n == 1) "archivo adjunto" else "archivos adjuntos"}. Dime qué hago con ${if (n == 1) "él" else "ellos"}.")
            if (n == 0) return@launch
        }
    }

    /** Escucha una frase por el micrófono y la manda. Desde el hilo principal. */
    fun escucharVoz() {
        if (pausa || ocupado) return
        if (estado == Estado.ESCUCHANDO) { voz.dejarDeEscuchar(); return poner(Estado.ESPERA, "") }
        poner(Estado.ESCUCHANDO, "Te escucho…")
        voz.escuchar({ enviar(it) }, { poner(Estado.ESPERA, it) })
    }

    // ------------------------------------------------------------ iniciativa
    private val pulso = object : Runnable {
        override fun run() { latido(); principal.postDelayed(this, 60_000) }
    }
    private val enCola = mutableListOf<Triple<Rutina, String, Long>>()      // rutinas que saltaron mientras estaba ocupado
    @Volatile private var fusibleAvisado = ""

    /** Llegó una notificación: si alguna rutina las espera, se mira enseguida en vez de esperar al minuto. */
    fun alLlegarAviso() {
        if (mente.rutinas().any { it.activa && it.disparador.tipo == "notificacion" }) principal.postDelayed({ latido() }, 2_000)
    }

    fun programarDespertador() = Despertador.programar(ctx, mente.proximaHora(System.currentTimeMillis()))

    /** El pulso de la iniciativa: ¿toca alguna rutina? Se llama cada minuto, al llegar una notificación y al sonar el despertador. */
    fun latido() {
        estudiar()                                           // si un estudio se cortó (sin red, app cerrada), aquí se retoma
        ambito.launch {
            try {
                val rutinas = mente.rutinas().filter { it.activa }
                if (rutinas.isEmpty()) return@launch
                val ahora = System.currentTimeMillis()
                val tipos = rutinas.map { it.disparador.tipo }.toSet()
                val m = Momento(ahora,
                    avisos = if ("notificacion" in tipos) ServicioNotificaciones.nuevas() else emptyList(),
                    posicion = if ("lugar" in tipos) fuentes.posicion(conNombre = false) else null,
                    citas = if ("calendario" in tipos) fuentes.citas(1).orEmpty() else emptyList())
                synchronized(enCola) {
                    mente.debidas(m).forEach { enCola += Triple(it.first, it.second, ahora) }
                    enCola.removeAll { ahora - it.third > COLA_MAX }                 // lo que lleva media hora esperando, ya no viene a cuento
                }
                programarDespertador()
                if (pausa || ocupado) return@launch
                val siguiente = synchronized(enCola) { enCola.removeFirstOrNull() } ?: return@launch
                val gastado = mente.gastoSoloHoy(ahora)
                if (gastado >= almacen.fusible) {                                     // el fusible: hoy ya no hace nada más por su cuenta
                    val hoy = java.time.LocalDate.now().toString()
                    if (fusibleAvisado != hoy) {
                        fusibleAvisado = hoy
                        almacen.apuntar("FUSIBLE: hoy lleva ~${"%.2f".format(java.util.Locale.US, gastado)} $ por iniciativa; rutinas detenidas hasta mañana")
                        Avisador.avisar(ctx, "JARVIS detuvo sus rutinas por hoy",
                            "Hoy ha gastado unos ${"%.2f".format(gastado)} dólares por iniciativa, más que el límite de ${"%.2f".format(almacen.fusible)}. Mañana siguen. Puedes cambiar el límite en Ajustes.")
                    }
                    synchronized(enCola) { enCola.clear() }
                    return@launch
                }
                if (!almacen.hayClave) return@launch
                principal.post { if (!ocupado && !pausa) lanzar(siguiente.first.encargo, siguiente.first, siguiente.second) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { almacen.apuntar("FALLO en el pulso de rutinas: ${e.javaClass.simpleName}") }
        }
    }

    /** Cuánto debe latir el orbe ahora: tu voz al escuchar, un pulso al hablar. */
    fun nivelVoz(): Float = when (estado) {
        Estado.ESCUCHANDO -> voz.nivel
        Estado.HABLANDO -> (0.35 + 0.3 * kotlin.math.sin(System.nanoTime() / 9e7)).toFloat()
        else -> 0f
    }

    private fun limpiar(s: String) = s.replace(Regex("https?://\\S+"), "").replace(Regex("[*_#`>|\\[\\]]"), "").trim()

    private const val ESPERA_MAX = 10 * 60 * 1000L
    private const val COLA_MAX = 30 * 60 * 1000L
    /** El cupo de internet de CADA capítulo al estudiar: empieza de cero en cada uno. */
    private const val BUSQUEDAS_POR_CAPITULO = 4
    private const val LECTURAS_POR_CAPITULO = 3
}
