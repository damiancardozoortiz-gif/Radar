package com.radar.jarvis.nucleo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownServiceException

/** Un mensaje de la conversación con el cerebro local. rol: system, user o assistant. */
data class MensajeIA(val rol: String, val texto: String)

enum class TipoFallo {
    /** No hay nada escuchando en esa dirección: llama-server no está en marcha. */
    APAGADO,
    /** Tardó más de la cuenta en conectar o en contestar. */
    TIEMPO,
    /** El servidor está, pero aún carga el modelo (llama-server responde 503). */
    CARGANDO,
    /** El servidor respondió con un error HTTP. */
    HTTP,
    /** La respuesta no es el JSON esperado, o vino vacía. */
    FORMATO,
    /** La dirección no es válida o Android no deja usar http sin cifrar con ella. */
    DIRECCION,
}

sealed class RespuestaLocal {
    data class Ok(
        val texto: String, val modelo: String, val tokensEntrada: Int, val tokensSalida: Int, val ms: Long,
        /** true si se cortó por el límite de tokens (finish_reason = length). */
        val cortada: Boolean,
    ) : RespuestaLocal()

    data class Fallo(val tipo: TipoFallo, val detalle: String) : RespuestaLocal() {
        /** La explicación para la persona, en una frase. */
        fun frase(base: String): String = when (tipo) {
            TipoFallo.APAGADO -> "No hay ningún cerebro local escuchando en $base. Abre Termux y arranca llama-server."
            TipoFallo.TIEMPO -> "El cerebro local no contestó a tiempo ($detalle)."
            TipoFallo.CARGANDO -> "El cerebro local todavía está cargando el modelo. Prueba en unos segundos."
            TipoFallo.HTTP -> "El cerebro local respondió con un error: $detalle."
            TipoFallo.FORMATO -> "El cerebro local devolvió algo que no entiendo: $detalle."
            TipoFallo.DIRECCION -> "La dirección del cerebro local no sirve: $detalle."
        }
    }
}

/**
 * Cliente del cerebro local: un llama-server (llama.cpp) en el propio teléfono, normalmente en Termux,
 * con su API compatible con OpenAI en POST /v1/chat/completions.
 *
 * - Todo se hace en Dispatchers.IO: nunca bloquea el hilo principal.
 * - Si la corrutina se cancela (Detener), se corta la conexión en el acto.
 * - Nunca lanza por un fallo de red o del servidor: devuelve RespuestaLocal.Fallo con el tipo.
 */
class LocalAIClient(
    base: String = POR_DEFECTO,
    private val conectarMs: Int = 3_000,
    private val leerMs: Int = 180_000,
) {
    val base: String = base.trim().trimEnd('/')

    /** null si la dirección vale; si no, por qué. */
    fun problemaDireccion(): String? {
        val u = runCatching { URL(base) }.getOrNull() ?: return "«$base» no es una dirección"
        if (u.protocol != "http" && u.protocol != "https") return "tiene que empezar por http:// o https://"
        if (u.protocol == "http" && u.host !in LOCALES) return "con http sin cifrar solo se permite 127.0.0.1 o localhost (el propio teléfono)"
        return null
    }

    /** ¿Está el servidor en marcha y con el modelo cargado? Usa GET /health de llama-server. */
    suspend fun salud(esperaMs: Int = 2_000): RespuestaLocal = withContext(Dispatchers.IO) {
        problemaDireccion()?.let { return@withContext RespuestaLocal.Fallo(TipoFallo.DIRECCION, it) }
        val t0 = System.currentTimeMillis()
        pedir("GET", "/health", null, esperaMs, esperaMs) { codigo, cuerpo ->
            when (codigo) {
                200 -> RespuestaLocal.Ok("ok", "", 0, 0, System.currentTimeMillis() - t0, false)
                503 -> RespuestaLocal.Fallo(TipoFallo.CARGANDO, mensajeError(cuerpo) ?: "cargando el modelo")
                else -> RespuestaLocal.Fallo(TipoFallo.HTTP, "código $codigo" + (mensajeError(cuerpo)?.let { ": $it" } ?: ""))
            }
        }
    }

    /** Manda la conversación y devuelve lo que contesta el modelo. */
    suspend fun chat(mensajes: List<MensajeIA>, maxTokens: Int = 400, temperatura: Double = 0.6): RespuestaLocal = withContext(Dispatchers.IO) {
        problemaDireccion()?.let { return@withContext RespuestaLocal.Fallo(TipoFallo.DIRECCION, it) }
        val cuerpo = JSONObject()
            .put("messages", JSONArray(mensajes.map { JSONObject().put("role", it.rol).put("content", it.texto) }))
            .put("max_tokens", maxTokens)
            .put("temperature", temperatura)
            .put("stream", false)
        val t0 = System.currentTimeMillis()
        pedir("POST", "/v1/chat/completions", cuerpo.toString(), conectarMs, leerMs) { codigo, texto ->
            when (codigo) {
                200 -> leerRespuesta(texto, System.currentTimeMillis() - t0)
                503 -> RespuestaLocal.Fallo(TipoFallo.CARGANDO, mensajeError(texto) ?: "cargando el modelo")
                else -> RespuestaLocal.Fallo(TipoFallo.HTTP, "código $codigo" + (mensajeError(texto)?.let { ": $it" } ?: ""))
            }
        }
    }

    private suspend fun pedir(
        metodo: String, ruta: String, cuerpo: String?, conectar: Int, leer: Int,
        alResponder: (Int, String) -> RespuestaLocal,
    ): RespuestaLocal {
        val c = try { URL(base + ruta).openConnection() as HttpURLConnection } catch (e: Exception) {
            return RespuestaLocal.Fallo(TipoFallo.DIRECCION, e.message ?: "dirección no válida")
        }
        // si cancelan la tarea (Detener, pausa), un vigilante corta la conexión y la lectura bloqueada termina en el acto
        return coroutineScope {
            val vigia = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { c.disconnect() } } }
            try { leer(c, metodo, cuerpo, conectar, leer, alResponder) } finally { vigia.cancel() }
        }
    }

    private fun leer(c: HttpURLConnection, metodo: String, cuerpo: String?, conectar: Int, leer: Int, alResponder: (Int, String) -> RespuestaLocal): RespuestaLocal =
        try {
            c.requestMethod = metodo
            c.connectTimeout = conectar
            c.readTimeout = leer
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val texto = (if (codigo in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            alResponder(codigo, texto)
        } catch (e: ConnectException) {
            RespuestaLocal.Fallo(TipoFallo.APAGADO, e.message ?: "conexión rechazada")
        } catch (e: SocketTimeoutException) {
            RespuestaLocal.Fallo(TipoFallo.TIEMPO, if ((e.message ?: "").contains("connect", true)) "no se pudo conectar en ${conectar / 1000} s" else "sin respuesta en ${leer / 1000} s")
        } catch (e: UnknownServiceException) {
            // Android: «CLEARTEXT communication to … not permitted by network security policy»
            RespuestaLocal.Fallo(TipoFallo.DIRECCION, "Android no permite http sin cifrar con esa dirección (${e.message?.take(120)})")
        } catch (e: java.io.IOException) {
            // conexión cortada a medias (el servidor se cerró mientras respondía) o red rota
            RespuestaLocal.Fallo(TipoFallo.APAGADO, "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        } finally {
            runCatching { c.disconnect() }
        }

    companion object {
        const val POR_DEFECTO = "http://127.0.0.1:8080"
        val LOCALES = setOf("127.0.0.1", "localhost")

        fun mensajeError(cuerpo: String): String? = runCatching {
            val o = JSONObject(cuerpo)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()?.take(200)

        /** Lee la respuesta de /v1/chat/completions (formato OpenAI, tal como la da llama-server). */
        fun leerRespuesta(texto: String, ms: Long): RespuestaLocal = try {
            val o = JSONObject(texto)
            val eleccion = o.getJSONArray("choices").getJSONObject(0)
            val msg = eleccion.getJSONObject("message")
            val contenido = if (msg.isNull("content")) "" else msg.optString("content")
            val limpio = contenido.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            if (limpio.isEmpty()) RespuestaLocal.Fallo(TipoFallo.FORMATO, "respuesta vacía")
            else {
                val uso = o.optJSONObject("usage")
                RespuestaLocal.Ok(limpio, o.optString("model"), uso?.optInt("prompt_tokens") ?: 0, uso?.optInt("completion_tokens") ?: 0, ms,
                    eleccion.optString("finish_reason") == "length")
            }
        } catch (e: Exception) {
            RespuestaLocal.Fallo(TipoFallo.FORMATO, "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }
}
