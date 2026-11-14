package com.radar.jarvis.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.radar.jarvis.nucleo.Agente
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Peticion
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
 */
object Sesion {
    private val principal = Handler(Looper.getMainLooper())
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val oyentes = CopyOnWriteArraySet<() -> Unit>()
    private var trabajo: Job? = null
    private var agente: Agente? = null
    private var decision: CompletableDeferred<Boolean>? = null

    lateinit var almacen: Almacen
        private set
    lateinit var voz: Voz
        private set
    lateinit var estudio: EstudioAndroid
        private set

    @Volatile var estado = Estado.ESPERA; private set
    @Volatile var subtitulo = ""; private set
    @Volatile var detalle = ""; private set
    @Volatile var peticion: Peticion? = null; private set
    @Volatile var pausa = false; private set
    @Volatile var enPrimerPlano = false
    val pasos = java.util.Collections.synchronizedList(mutableListOf<Paso>())

    val ocupado get() = trabajo?.isActive == true

    fun iniciar(ctx: Context) {
        almacen = Almacen(ctx.applicationContext)
        voz = Voz(ctx.applicationContext)
        estudio = EstudioAndroid(ctx.applicationContext, almacen)
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
    fun reconfigurar() { agente = null }

    fun nuevaConversacion() {
        agente?.olvidarConversacion()
        pasos.clear(); detalle = ""
        poner(Estado.ESPERA, "Conversación nueva.")
    }

    private fun agente(): Agente = agente ?: Agente(
        almacen.ajustes(),
        Herramientas(ServicioAccesibilidad.manos, almacen, ::pedirPermiso, ::alPaso, estudio = estudio),
        almacen,
    ) { nota -> pasos += Paso(0, nota, "nota", ""); avisar() }.also { agente = it }

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
        if (almacen.voz) principal.post { voz.decir("Necesito tu sí en pantalla.") }
        val ok = try { withTimeoutOrNull(ESPERA_MAX) { d.await() } ?: false } finally { peticion = null; decision = null }
        almacen.apuntar("PERMISO ${if (ok) "APROBADO" else "rechazado"}: ${p.accion}")
        poner(Estado.ACTUANDO)
        return ok
    }

    /** Tu respuesta a la tarjeta de permiso. Solo vale tocar el botón. */
    fun decidir(aprobar: Boolean) { decision?.complete(aprobar) }

    fun enviar(texto: String) {
        val t = texto.trim()
        if (t.isEmpty()) return
        if (pausa) return poner(Estado.PAUSA, "Estoy en pausa. Quítala para seguir.")
        if (ocupado) return poner(estado, "Estoy con otra cosa. Espera o pulsa Detener.")
        voz.callar()
        pasos.clear(); detalle = ""
        almacen.apuntar("TAREA: $t")
        poner(Estado.PENSANDO, "«$t»")
        trabajo = ambito.launch {
            val r = try {
                agente().correr(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.radar.jarvis.nucleo.Respuesta("Algo falló dentro de JARVIS: ${e.javaClass.simpleName}. ${e.message.orEmpty().take(120)}", "fallo", 0)
            }
            almacen.apuntar("FIN (${r.estado}, ${r.pasos} pasos): ${r.texto.take(160)}")
            val partes = r.texto.trim().split(Regex("\n\\s*\n"), limit = 2)
            detalle = partes.getOrElse(1) { "" }.trim()
            val decir = partes[0].trim()
            if (almacen.voz && !pausa) {
                poner(Estado.HABLANDO, decir)
                principal.post { voz.decir(limpiar(decir)) { if (estado == Estado.HABLANDO) poner(Estado.ESPERA) } }
            } else poner(Estado.ESPERA, decir)
        }
    }

    /** Los videos o audios que eliges con el botón de adjuntar. */
    fun adjuntar(uris: List<android.net.Uri>) {
        ambito.launch {
            val n = estudio.adjuntar(uris)
            poner(estado, if (n == 0) "No pude leer esos archivos: tienen que ser videos o audios."
                else "$n ${if (n == 1) "archivo adjunto" else "archivos adjuntos"}. Dime qué hago con ${if (n == 1) "él" else "ellos"}.")
        }
    }

    /** Para lo que esté haciendo: rechaza lo que esperaba permiso y corta la tarea. */
    fun detener(motivo: String = "Detenido.") {
        decision?.complete(false)
        trabajo?.cancel()
        voz.callar(); voz.dejarDeEscuchar()
        poner(Estado.ESPERA, motivo)
    }

    /** Pausa de emergencia: corta todo y no acepta nada hasta que la quites. */
    fun ponerPausa(on: Boolean) {
        pausa = on
        if (on) { detener("En pausa. No hago nada hasta que la quites."); almacen.apuntar("PAUSA activada") }
        else { estado = Estado.ESPERA; subtitulo = "Listo."; avisar() }
    }

    /** Escucha una frase por el micrófono y la manda. Desde el hilo principal. */
    fun escucharVoz() {
        if (pausa || ocupado) return
        if (estado == Estado.ESCUCHANDO) { voz.dejarDeEscuchar(); return poner(Estado.ESPERA, "") }
        poner(Estado.ESCUCHANDO, "Te escucho…")
        voz.escuchar({ enviar(it) }, { poner(Estado.ESPERA, it) })
    }

    /** Cuánto debe latir el orbe ahora: tu voz al escuchar, un pulso al hablar. */
    fun nivelVoz(): Float = when (estado) {
        Estado.ESCUCHANDO -> voz.nivel
        Estado.HABLANDO -> (0.35 + 0.3 * kotlin.math.sin(System.nanoTime() / 9e7)).toFloat()
        else -> 0f
    }

    private fun limpiar(s: String) = s.replace(Regex("https?://\\S+"), "").replace(Regex("[*_#`>|\\[\\]]"), "").trim()

    private const val ESPERA_MAX = 10 * 60 * 1000L
}
