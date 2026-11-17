package com.radar.jarvis.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** La voz de JARVIS en el teléfono: el dictado y la síntesis de voz del propio Android. No cuestan nada. */
class Voz(private val ctx: Context) {
    private val principal = Handler(Looper.getMainLooper())
    private var listo = false
    private var alAcabar: (() -> Unit)? = null
    private var reconocedor: SpeechRecognizer? = null
    private val idioma: Locale = Locale.getDefault().takeIf { it.language == "es" } ?: Locale("es", "ES")

    private val tts: TextToSpeech = TextToSpeech(ctx) { estado ->
        listo = estado == TextToSpeech.SUCCESS
        if (listo) configurar()
    }

    private fun configurar() {
        tts.language = idioma
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = acabar()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = acabar()
            override fun onStop(id: String?, interrumpido: Boolean) = acabar()
        })
    }

    private fun acabar() {
        principal.post { val f = alAcabar; alAcabar = null; f?.invoke() }
    }

    /** Dice el texto en voz alta. `fin` se llama siempre, también si no hay voz o se interrumpe. */
    fun decir(texto: String, fin: () -> Unit = {}) {
        if (!listo || texto.isBlank()) { fin(); return }
        alAcabar = fin
        if (tts.speak(texto.take(900), TextToSpeech.QUEUE_FLUSH, null, "jarvis") != TextToSpeech.SUCCESS) acabar()
    }

    fun callar() { if (listo) tts.stop() }

    fun tienePermiso() = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var parcial = ""
    private var cortadaPorTi = false

    /**
     * Escucha una frase. Hay que llamarlo desde el hilo principal.
     * alOir recibe el texto y si la grabación terminó de golpe (la paraste tú a mitad, o el dictado se cortó y solo quedó lo parcial).
     */
    fun escuchar(alOir: (String, Boolean) -> Unit, alFallar: (String) -> Unit) {
        if (!tienePermiso()) return alFallar("Falta el permiso del micrófono. Dámelo en Ajustes.")
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return alFallar("Este teléfono no tiene dictado por voz. Escríbemelo.")
        callar()
        dejarDeEscuchar()
        parcial = ""; cortadaPorTi = false
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        reconocedor = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                val cortada = cortadaPorTi
                val p = parcial
                dejarDeEscuchar()
                if (t.isNotEmpty()) alOir(t, cortada) else if (p.isNotEmpty()) alOir(p, true) else alFallar("No te oí.")
            }
            override fun onError(codigo: Int) {
                val p = parcial
                dejarDeEscuchar()
                // se cortó, pero algo se entendió: va, marcado como cortado, para que JARVIS pregunte si hace falta
                if (p.isNotEmpty() && codigo != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return alOir(p, true)
                alFallar(when (codigo) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te oí."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta el permiso del micrófono."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El dictado necesita conexión y no la hay."
                    else -> "El dictado falló (código $codigo). Escríbemelo."
                })
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(db: Float) { nivel = ((db + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { parcial = it }
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }

    /** La persona tocó para parar mientras hablaba: se usa lo que se oyó, marcado como cortado. */
    fun terminar() {
        val r = reconocedor ?: return
        cortadaPorTi = true
        runCatching { r.stopListening() }
    }

    fun dejarDeEscuchar() {
        nivel = 0f
        reconocedor?.let { runCatching { it.cancel(); it.destroy() } }
        reconocedor = null
    }

    /** Volumen del micrófono mientras escucha, de 0 a 1: hace latir el orbe. */
    @Volatile var nivel = 0f
        private set
}
