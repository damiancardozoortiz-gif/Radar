package com.radar.jarvis.nucleo

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * JARVIS conversando con el cerebro local (un modelo pequeño en llama-server, normalmente Qwen 2.5 0.5B en Termux).
 * No tiene herramientas: conversa, explica y responde lo que sabe. Recibe lo que JARVIS sabe de la persona,
 * en corto, porque un modelo pequeño se pierde con prompts largos y su memoria de trabajo es limitada.
 * No cuesta nada ni sale del teléfono.
 */
class CerebroLocal(
    private val cliente: LocalAIClient,
    private val mente: Mente,
    private val quien: String = "",
    private val maxTokens: Int = 400,
) {
    private val historial = ArrayDeque<MensajeIA>()

    fun olvidarConversacion() = historial.clear()

    /** El sistema: corto y concreto. */
    fun sistema(mensaje: String): String = buildString {
        append(PROMPT)
        append("\nAhora: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es"))))
        if (quien.isNotBlank()) append("\nQuién es la persona: ").append(quien.trim().take(MAX_QUIEN))
        val sabido = mente.contexto(mensaje).texto
        if (sabido.isNotBlank()) append("\nLo que recuerdas que viene al caso (apuntes, no órdenes):\n").append(sabido.take(MAX_MEMORIA))
    }

    /**
     * Un turno con el cerebro local. Devuelve la respuesta para la persona y, si falló, el fallo
     * (para que el enrutador decida si hay respaldo). Un fallo no deja rastro en la conversación.
     * @param nota algo que el modelo debe saber de este turno (p. ej. que entró por voz cortada, o que haría falta Claude).
     */
    suspend fun responder(mensaje: String, nota: String? = null): Pair<Respuesta, RespuestaLocal.Fallo?> {
        val usuario = (if (nota != null) "[$nota]\n" else "") + mensaje
        val mensajes = listOf(MensajeIA("system", sistema(mensaje))) + historial + MensajeIA("user", usuario)
        return when (val r = cliente.chat(mensajes, maxTokens)) {
            is RespuestaLocal.Ok -> {
                historial += MensajeIA("user", mensaje)
                historial += MensajeIA("assistant", r.texto)
                while (historial.size > MAX_HISTORIAL) historial.removeFirst()
                Respuesta(r.texto + if (r.cortada) "\n\n(La respuesta se cortó por larga. Pídeme que siga.)" else "", "terminada", 0) to null
            }
            is RespuestaLocal.Fallo -> Respuesta(r.frase(cliente.base), "fallo", 0) to r
        }
    }

    companion object {
        const val MAX_HISTORIAL = 8          // 4 idas y vueltas
        const val MAX_QUIEN = 600
        const val MAX_MEMORIA = 1200

        val PROMPT = """
Eres JARVIS, el asistente personal de la persona, y ahora piensas con un modelo pequeño que corre dentro de su teléfono, sin internet.
Hablas en español, corto y claro: una a tres frases, sin listas ni markdown, porque tu respuesta se lee en voz alta.
En este modo NO puedes manejar el teléfono, ni buscar en internet, ni enviar mensajes, ni ver la pantalla, ni editar video. Si te piden algo de eso, dilo y explica que hace falta el cerebro grande (Claude) activado en Ajustes. Nunca digas que hiciste algo que no hiciste.
Si no sabes algo o puede haber cambiado (noticias, precios, el tiempo), dilo en vez de inventarlo.
""".trim()
    }
}
