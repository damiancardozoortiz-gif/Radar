package com.radar.jarvis

import com.radar.jarvis.nucleo.Agente
import com.radar.jarvis.nucleo.Ajustes
import com.radar.jarvis.nucleo.Cerebro
import com.radar.jarvis.nucleo.CerebroLocal
import com.radar.jarvis.nucleo.Enrutador
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.LocalAIClient
import com.radar.jarvis.nucleo.MensajeIA
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.Preferencias
import com.radar.jarvis.nucleo.Respuesta
import com.radar.jarvis.nucleo.RespuestaLocal
import com.radar.jarvis.nucleo.TipoFallo
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Un llama-server de mentira: responde como /health y /v1/chat/completions de llama.cpp, o falla como se le diga. */
private class LlamaFalso(puerto: Int = 0) {
    val socket = ServerSocket(puerto, 50, InetAddress.getByName("127.0.0.1"))
    val puerto get() = socket.localPort
    val url get() = "http://127.0.0.1:$puerto"
    val peticiones = mutableListOf<Pair<String, String>>()       // (ruta, cuerpo)
    @Volatile var codigo = 200
    @Volatile var cuerpo = respuesta("Hola, soy JARVIS.")
    @Volatile var esperaMs = 0L

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val s = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { s.use { atender(it) } } }
            }
        }
    }

    private fun atender(s: java.net.Socket) {
        val e = s.getInputStream()
        val cab = StringBuilder()
        while (!cab.endsWith("\r\n\r\n")) { val b = e.read(); if (b < 0) return; cab.append(b.toChar()) }
        val ruta = cab.lineSequence().first().split(" ")[1]
        val largo = cab.lines().firstOrNull { it.lowercase().startsWith("content-length:") }?.substringAfter(":")?.trim()?.toInt() ?: 0
        val c = ByteArray(largo); var n = 0
        while (n < largo) { val k = e.read(c, n, largo - n); if (k < 0) break; n += k }
        synchronized(peticiones) { peticiones += ruta to c.decodeToString() }
        if (esperaMs > 0) Thread.sleep(esperaMs)
        val (cod, txt) = if (ruta == "/health") (if (codigo == 503) 503 to """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}""" else 200 to """{"status":"ok"}""") else codigo to cuerpo
        val b = txt.toByteArray()
        s.getOutputStream().apply { write("HTTP/1.1 $cod X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
    }

    fun cerrar() = socket.close()

    companion object {
        fun respuesta(texto: String, fin: String = "stop") = JSONObject().put("choices", listOf(JSONObject().put("index", 0).put("finish_reason", fin)
            .put("message", JSONObject().put("role", "assistant").put("content", texto))))
            .put("model", "qwen2.5-0.5b-instruct-q4_k_m.gguf").put("object", "chat.completion")
            .put("usage", JSONObject().put("prompt_tokens", 40).put("completion_tokens", 8)).toString()
    }
}

class CerebroLocalTest {
    private val servidores = mutableListOf<LlamaFalso>()
    private fun llama() = LlamaFalso().also { servidores += it }
    @After fun cerrar() = servidores.forEach { it.cerrar() }

    private fun puertoLibre() = ServerSocket(0).use { it.localPort }

    // ---------------------------------------------------------------- 1-3. conexión, envío y recepción
    @Test fun mandaLaPreguntaYLeeLaRespuesta() = runBlocking {
        val s = llama()
        val c = LocalAIClient(s.url)
        assertTrue(c.salud() is RespuestaLocal.Ok)
        val r = c.chat(listOf(MensajeIA("system", "Eres JARVIS"), MensajeIA("user", "¿Qué hora es en Tokio?")), maxTokens = 99)
        assertTrue(r.toString(), r is RespuestaLocal.Ok)
        r as RespuestaLocal.Ok
        assertEquals("Hola, soy JARVIS.", r.texto)
        assertEquals(40, r.tokensEntrada); assertEquals(8, r.tokensSalida); assertFalse(r.cortada)
        val (ruta, cuerpo) = s.peticiones.last()
        assertEquals("/v1/chat/completions", ruta)
        val o = JSONObject(cuerpo)
        assertEquals(99, o.getInt("max_tokens")); assertFalse(o.getBoolean("stream"))
        assertEquals("system", o.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("¿Qué hora es en Tokio?", o.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun respuestaCortadaPorLargoSeMarca() = runBlocking {
        val s = llama().apply { cuerpo = LlamaFalso.respuesta("Una respuesta muy lar", "length") }
        val r = LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Ok
        assertTrue(r.cortada)
    }

    // ---------------------------------------------------------------- 4. servidor apagado y otros fallos
    @Test fun servidorApagadoDaApagadoSinLanzar() = runBlocking {
        val url = "http://127.0.0.1:${puertoLibre()}"
        val r = LocalAIClient(url).chat(listOf(MensajeIA("user", "hola")))
        assertEquals(TipoFallo.APAGADO, (r as RespuestaLocal.Fallo).tipo)
        assertTrue(r.frase(url).contains("Termux"))
        assertEquals(TipoFallo.APAGADO, (LocalAIClient(url).salud() as RespuestaLocal.Fallo).tipo)
    }

    @Test fun cargandoElModeloDa503() = runBlocking {
        val s = llama().apply { codigo = 503; cuerpo = """{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}""" }
        assertEquals(TipoFallo.CARGANDO, (LocalAIClient(s.url).salud() as RespuestaLocal.Fallo).tipo)
        assertEquals(TipoFallo.CARGANDO, (LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Fallo).tipo)
    }

    @Test fun errorDelServidorYRespuestasRaras() = runBlocking {
        val s = llama().apply { codigo = 400; cuerpo = """{"error":{"code":400,"message":"the request exceeds the available context size"}}""" }
        val r = LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Fallo
        assertEquals(TipoFallo.HTTP, r.tipo); assertTrue(r.detalle.contains("context size"))
        s.codigo = 200; s.cuerpo = "<html>no soy json</html>"
        assertEquals(TipoFallo.FORMATO, (LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Fallo).tipo)
        s.cuerpo = LlamaFalso.respuesta("   ")
        assertEquals(TipoFallo.FORMATO, (LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Fallo).tipo)
    }

    @Test fun siTardaDemasiadoDaTiempo() = runBlocking {
        val s = llama().apply { esperaMs = 3_000 }
        val r = LocalAIClient(s.url, leerMs = 500).chat(listOf(MensajeIA("user", "x")))
        assertEquals(TipoFallo.TIEMPO, (r as RespuestaLocal.Fallo).tipo)
    }

    @Test fun soloPermiteHttpSinCifrarConElPropioTelefono() = runBlocking {
        assertNull(LocalAIClient("http://127.0.0.1:8080").problemaDireccion())
        assertNull(LocalAIClient("http://localhost:8080/").problemaDireccion())
        assertNotNull(LocalAIClient("http://192.168.1.20:8080").problemaDireccion())
        assertNotNull(LocalAIClient("ftp://127.0.0.1").problemaDireccion())
        assertEquals(TipoFallo.DIRECCION, (LocalAIClient("http://10.0.0.5:8080").chat(listOf(MensajeIA("user", "x"))) as RespuestaLocal.Fallo).tipo)
    }

    // ---------------------------------------------------------------- 5. recuperación
    @Test fun cuandoElServidorVuelveSeRecupera() = runBlocking {
        val puerto = puertoLibre()
        val c = LocalAIClient("http://127.0.0.1:$puerto")
        assertEquals(TipoFallo.APAGADO, (c.chat(listOf(MensajeIA("user", "hola"))) as RespuestaLocal.Fallo).tipo)
        val s = LlamaFalso(puerto).also { servidores += it }            // arrancan llama-server en Termux
        assertTrue(c.chat(listOf(MensajeIA("user", "hola"))) is RespuestaLocal.Ok)
        s.cerrar()                                                      // lo cierran otra vez
        assertEquals(TipoFallo.APAGADO, (c.chat(listOf(MensajeIA("user", "hola"))) as RespuestaLocal.Fallo).tipo)
    }

    @Test fun unFalloNoEnsuciaLaConversacionDelCerebroLocal() = runBlocking {
        val s = llama()
        val cerebro = CerebroLocal(LocalAIClient(s.url), Mente(CajaFalsa()), "Se llama Rafael")
        assertEquals("terminada", cerebro.responder("Hola").first.estado)
        s.codigo = 503
        val (r, fallo) = cerebro.responder("¿Sigues ahí?")
        assertEquals("fallo", r.estado); assertEquals(TipoFallo.CARGANDO, fallo!!.tipo)
        s.codigo = 200
        cerebro.responder("Ahora sí")
        val msgs = JSONObject(s.peticiones.last().second).getJSONArray("messages")
        // sistema + (hola, respuesta) + el mensaje nuevo: el que falló no quedó en el historial
        assertEquals(4, msgs.length())
        assertTrue(msgs.getJSONObject(0).getString("content").contains("Se llama Rafael"))
        assertEquals("Ahora sí", msgs.getJSONObject(3).getString("content"))
    }

    // ---------------------------------------------------------------- no bloquea ni se cuelga
    @Test fun noBloqueaElHiloDeQuienLlama() = runBlocking {
        val s = llama().apply { esperaMs = 1_500 }
        val unHilo = Executors.newSingleThreadExecutor().asCoroutineDispatcher()     // como el hilo principal: uno solo
        withContext(unHilo) {
            val pregunta = async { LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) }
            var latidos = 0
            repeat(5) { delay(100); latidos++ }                          // el mismo hilo sigue libre mientras espera la respuesta
            assertEquals(5, latidos)
            assertFalse(pregunta.isCompleted)
            assertTrue(pregunta.await() is RespuestaLocal.Ok)
        }
        unHilo.close()
    }

    @Test fun detenerCortaLaEsperaEnElActo() = runBlocking {
        val s = llama().apply { esperaMs = 10_000 }
        val t0 = System.currentTimeMillis()
        val trabajo = async(kotlinx.coroutines.Dispatchers.Default) { LocalAIClient(s.url).chat(listOf(MensajeIA("user", "x"))) }
        delay(300); trabajo.cancel()
        withTimeout(3_000) { runCatching { trabajo.await() } }
        assertTrue("canceló en ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
    }

    // ---------------------------------------------------------------- el enrutador
    private val auto = Preferencias("auto", claudeAutorizado = true, respaldoClaude = false, hayClave = true)

    @Test fun conversacionAlLocalYAccionesAClaude() {
        for (m in listOf("Hola, ¿cómo estás?", "¿Cómo se llama la capital de Francia?", "Explícame la fotosíntesis", "¿Qué pasó en abril de 1810?",
            "Cuéntame un chiste de animales", "¿Qué es una montaña?", "Dime algo bonito, preciosa")) {
            assertEquals(m, Cerebro.LOCAL, Enrutador.decidir(auto, m).cerebro)
        }
        for (m in listOf("Abre WhatsApp", "Mándale a Ana que llego tarde", "Pon una alarma a las 7", "Busca el precio del dólar", "Monta un video con mis fotos",
            "Estudia física cuántica", "Llama a mamá", "Recuérdame comprar pan", "¿Qué notificaciones tengo?", "Lee el PDF")) {
            assertEquals(m, Cerebro.CLAUDE, Enrutador.decidir(auto, m).cerebro)
        }
        assertEquals("con una tarea abierta sigue Claude", Cerebro.CLAUDE, Enrutador.decidir(auto, "sí, sigue", hayTareaAbierta = true).cerebro)
    }

    @Test fun claudeSoloSiEstaAutorizadoYHayClave() {
        assertEquals(Cerebro.LOCAL, Enrutador.decidir(auto.copy(claudeAutorizado = false), "Abre WhatsApp").cerebro)
        assertEquals(Cerebro.LOCAL, Enrutador.decidir(auto.copy(hayClave = false), "Abre WhatsApp").cerebro)
        assertTrue(Enrutador.decidir(auto.copy(claudeAutorizado = false), "Abre WhatsApp").motivo.contains("Claude no está"))
        assertEquals(Cerebro.LOCAL, Enrutador.decidir(auto.copy(proveedor = "local"), "Abre WhatsApp").cerebro)
        assertEquals(Cerebro.CLAUDE, Enrutador.decidir(auto.copy(proveedor = "claude"), "Hola").cerebro)
        assertNull(Enrutador.decidir(Preferencias("claude", hayClave = false), "Hola").cerebro)
        assertFalse(Enrutador.puedeTrabajarSolo(auto.copy(proveedor = "local")))
        assertFalse(Enrutador.puedeTrabajarSolo(auto.copy(claudeAutorizado = false)))
        assertTrue(Enrutador.puedeTrabajarSolo(auto))
    }

    @Test fun claudeSeLlamaComoMuchoUnaVezPorMensaje() = runBlocking {
        var llamadas = 0
        val claude: suspend () -> Respuesta = { llamadas++; Respuesta("Hecho con Claude", "terminada", 3) }
        val bien: suspend () -> Pair<Respuesta, RespuestaLocal.Fallo?> = { Respuesta("Hola", "terminada", 0) to null }
        val apagado: suspend () -> Pair<Respuesta, RespuestaLocal.Fallo?> = { Respuesta("apagado", "fallo", 0) to RespuestaLocal.Fallo(TipoFallo.APAGADO, "") }
        val local = Enrutador.decidir(auto, "Hola")
        assertEquals(Cerebro.LOCAL, Enrutador.atender(local, auto, bien, claude).cerebro); assertEquals(0, llamadas)
        // apagado sin respaldo: se dice, sin gastar
        val sin = Enrutador.atender(local, auto, apagado, claude)
        assertEquals(Cerebro.LOCAL, sin.cerebro); assertEquals("fallo", sin.respuesta.estado); assertEquals(0, llamadas)
        // apagado con respaldo: una sola llamada a Claude
        val con = Enrutador.atender(local, auto.copy(respaldoClaude = true), apagado, claude)
        assertEquals(Cerebro.CLAUDE, con.cerebro); assertEquals(TipoFallo.APAGADO, con.falloLocal!!.tipo); assertEquals(1, llamadas)
        // respaldo encendido pero sin autorización: nada
        Enrutador.atender(local, auto.copy(respaldoClaude = true, claudeAutorizado = false), apagado, claude); assertEquals(1, llamadas)
        // ruta Claude: una llamada, y el local ni se toca
        var tocoLocal = false
        Enrutador.atender(Enrutador.decidir(auto, "Abre WhatsApp"), auto, { tocoLocal = true; bien() }, claude)
        assertEquals(2, llamadas); assertFalse(tocoLocal)
    }

    // ---------------------------------------------------------------- 6. compatibilidad con Claude (API simulada)
    @Test fun elCaminoDeClaudeSigueFuncionandoYCuentaUnaPeticion() = runBlocking {
        val claudeApi = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        var peticiones = 0
        thread(isDaemon = true) {
            while (!claudeApi.isClosed) {
                val s = try { claudeApi.accept() } catch (e: Exception) { break }
                s.use {
                    val e = it.getInputStream(); val cab = StringBuilder()
                    while (!cab.endsWith("\r\n\r\n")) { val b = e.read(); if (b < 0) break; cab.append(b.toChar()) }
                    val largo = cab.lines().first { l -> l.lowercase().startsWith("content-length:") }.substringAfter(":").trim().toInt()
                    var n = 0; val c = ByteArray(largo); while (n < largo) n += e.read(c, n, largo - n)
                    peticiones++
                    val r = """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"Abierto."}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""".toByteArray()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray() + r)
                }
            }
        }
        val llamaApagado = "http://127.0.0.1:${puertoLibre()}"
        val mente = Mente(CajaFalsa())
        val agente = Agente(Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:${claudeApi.localPort}"), Herramientas(TelefonoFalso(), mente, { true }, pausaTrasActuar = 0), mente)
        val cerebro = CerebroLocal(LocalAIClient(llamaApagado), mente)
        val p = auto.copy(respaldoClaude = true)
        // una acción: va a Claude, una petición
        val a = Enrutador.atender(Enrutador.decidir(p, "Abre WhatsApp"), p, { cerebro.responder("Abre WhatsApp") }, { agente.correr("Abre WhatsApp") })
        assertEquals("Abierto.", a.respuesta.texto); assertEquals(1, peticiones)
        // conversación con el local apagado y respaldo: exactamente una petición más, no dos
        val b = Enrutador.atender(Enrutador.decidir(p, "Hola"), p, { cerebro.responder("Hola") }, { agente.correr("Hola") })
        assertEquals(Cerebro.CLAUDE, b.cerebro); assertEquals(2, peticiones)
        claudeApi.close()
    }
}
