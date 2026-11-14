package com.radar.jarvis

import com.radar.jarvis.nucleo.Agente
import com.radar.jarvis.nucleo.Ajustes
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.Peticion
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** El bucle del agente contra una API de Claude de mentira: se comprueba lo que la app manda de verdad por la red. */
class AgenteTest {
    private lateinit var servidor: ServerSocket
    private val peticiones = mutableListOf<JSONObject>()
    private val cabeceras = mutableListOf<Map<String, String>>()
    private val respuestas = ArrayDeque<String>()
    private var codigo = 200
    private val puerto get() = servidor.localPort

    /** Un servidor HTTP mínimo: lee una petición, la apunta y responde con lo siguiente del guion. */
    @Before fun arrancar() {
        servidor = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!servidor.isClosed) {
                val s = try { servidor.accept() } catch (e: Exception) { break }
                s.use { atender(it) }
            }
        }
    }

    private fun atender(s: java.net.Socket) {
        val entrada = s.getInputStream()
        val cab = StringBuilder()
        while (!cab.endsWith("\r\n\r\n")) { val b = entrada.read(); if (b < 0) return; cab.append(b.toChar()) }
        val lineas = cab.lines().drop(1).filter { ":" in it }
        val mapa = lineas.associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
        val cuerpo = ByteArray(mapa["content-length"]?.toInt() ?: 0)
        var leido = 0
        while (leido < cuerpo.size) { val n = entrada.read(cuerpo, leido, cuerpo.size - leido); if (n < 0) break; leido += n }
        peticiones += JSONObject(cuerpo.decodeToString()); cabeceras += mapa
        val r = (if (codigo == 200) respuestas.removeFirst()
                 else """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 $codigo X\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(r); flush()
        }
    }

    @After fun parar() = servidor.close()

    private fun mensaje(contenido: String, fin: String) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$contenido],"stop_reason":"$fin","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
    private fun usa(id: String, nombre: String, entrada: String) = """{"type":"tool_use","id":"$id","name":"$nombre","input":$entrada}"""
    private fun dice(t: String) = """{"type":"text","text":"$t"}"""

    private fun agente(tel: TelefonoFalso, pedidos: MutableList<Peticion>, aprobar: Boolean, modelo: String = "claude-opus-5-5") =
        Agente(Ajustes("sk-ant-api03-prueba", modelo = modelo, baseUrl = "http://127.0.0.1:$puerto"),
            Herramientas(tel, Mente(CajaFalsa()), { pedidos += it; aprobar }, pausaTrasActuar = 0), Mente(CajaFalsa()))

    private fun guion() {
        respuestas += mensaje(usa("t1", "tocar", """{"ref":3,"proposito":"Enfocar el campo"}"""), "tool_use")
        respuestas += mensaje(usa("t2", "escribir", """{"texto":"Mañana llego a las nueve","proposito":"Escribir"}"""), "tool_use")
        respuestas += mensaje(dice("Ya está escrito, lo envío.") + "," + usa("t3", "tocar", """{"ref":4,"irreversible":true}"""), "tool_use")
        respuestas += mensaje(dice("Listo."), "end_turn")
    }

    @Test fun tareaCompletaConAprobacion() = runBlocking {
        guion()
        val tel = TelefonoFalso(); val pedidos = mutableListOf<Peticion>()
        val r = agente(tel, pedidos, true).correr("Dile a Ana que mañana llego a las nueve")
        assertEquals("terminada", r.estado); assertEquals("Listo.", r.texto); assertEquals(3, r.pasos)
        assertEquals(listOf("Mañana llego a las nueve"), tel.enviados)
        assertEquals(1, pedidos.size)

        val p = peticiones[0]
        assertEquals("claude-opus-5-5", p.getString("model"))
        assertEquals("default", p.getString("fallbacks"))
        assertEquals("medium", p.getJSONObject("output_config").getString("effort"))
        assertEquals("ephemeral", p.getJSONObject("cache_control").getString("type"))
        assertFalse("no se manda thinking: en Opus 5.5 va siempre activo", p.has("thinking"))
        assertFalse(p.has("tool_choice")); assertFalse(p.has("temperature"))
        assertTrue(cabeceras[0]["anthropic-beta"]!!.contains("server-side-fallback-2026-07-01"))
        // la clave viaja en la cabecera que elija la librería oficial (x-api-key o Authorization), y en ninguna otra parte
        assertTrue(cabeceras[0]["x-api-key"] == "sk-ant-api03-prueba" || cabeceras[0]["authorization"] == "Bearer sk-ant-api03-prueba")
        assertFalse(p.toString().contains("sk-ant-api03-prueba"))
        val tools = p.getJSONArray("tools")
        val nombres = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(nombres.containsAll(listOf("ver_pantalla", "tocar", "escribir", "abrir_app", "web_search", "medios", "ver_video", "montar_video")))
        val montar = tools.getJSONObject(nombres.indexOf("montar_video")).getJSONObject("input_schema")
        assertEquals("array", montar.getJSONObject("properties").getJSONObject("clips").getString("type"))
        assertEquals("id", montar.getJSONObject("properties").getJSONObject("clips").getJSONObject("items").getJSONArray("required").getString(0))
        assertEquals("web_search_20260209", tools.getJSONObject(nombres.indexOf("web_search")).getString("type"))
        assertTrue(p.getString("system").contains("JARVIS"))

        // el resultado de la herramienta vuelve en UN mensaje de usuario, tras el mensaje entero del asistente
        val m = peticiones[1].getJSONArray("messages")
        assertEquals(3, m.length())
        assertEquals("assistant", m.getJSONObject(1).getString("role"))
        val res = m.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", res.getString("type")); assertEquals("t1", res.getString("tool_use_id"))
        assertTrue(res.getString("content").contains("datos_no_confiables"))
    }

    @Test fun siRechazasNoSeEnviaYElModeloSeEntera() = runBlocking {
        guion()
        val tel = TelefonoFalso(); val pedidos = mutableListOf<Peticion>()
        val r = agente(tel, pedidos, false).correr("Dile a Ana que mañana llego a las nueve")
        assertEquals("terminada", r.estado)
        assertTrue(tel.enviados.isEmpty())
        val m = peticiones[3].getJSONArray("messages")
        val res = m.getJSONObject(m.length() - 1).getJSONArray("content").getJSONObject(0)
        assertTrue(res.getBoolean("is_error")); assertTrue(res.getString("content").startsWith("NO APROBADO"))
    }

    @Test fun laCapturaViajaComoImagen() = runBlocking {
        respuestas += mensaje(usa("t1", "ver_pantalla", """{"captura":true}"""), "tool_use")
        respuestas += mensaje(dice("Veo el chat."), "end_turn")
        agente(TelefonoFalso(), mutableListOf(), true).correr("¿Qué hay en pantalla?")
        val m = peticiones[1].getJSONArray("messages")
        val bloques = m.getJSONObject(2).getJSONArray("content").getJSONObject(0).getJSONArray("content")
        assertEquals("text", bloques.getJSONObject(0).getString("type"))
        assertEquals("image", bloques.getJSONObject(1).getString("type"))
        assertEquals("image/jpeg", bloques.getJSONObject(1).getJSONObject("source").getString("media_type"))
    }

    @Test fun haikuUsaLaBusquedaBasicaYSinRespaldo() = runBlocking {
        respuestas += mensaje(dice("Hola."), "end_turn")
        agente(TelefonoFalso(), mutableListOf(), true, "claude-haiku-5-5").correr("Hola")
        val p = peticiones[0]
        assertFalse(p.has("fallbacks"))
        val tools = p.getJSONArray("tools")
        assertEquals("web_search_20250305", tools.getJSONObject(tools.length() - 1).getString("type"))
    }

    @Test fun laMemoriaViajaEnElMensajeYNoEnElSistema() = runBlocking {
        respuestas += mensaje(dice("Hola, Damián."), "end_turn")
        val mente = Mente(CajaFalsa()).apply { recordar("perfil", "Se llama Damián y vive en Buenos Aires", "p"); recordar("hecho", "El wifi de la oficina va lento", "p") }
        val a = Agente(Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:$puerto"), Herramientas(TelefonoFalso(), mente, { true }, pausaTrasActuar = 0), mente)
        val r = a.correr("Hola", rutina = "«Buenos días» (nivel avisar), porque son las 8:00")
        val p = peticiones[0]
        val primero = p.getJSONArray("messages").getJSONObject(0).get("content").toString()
        assertTrue(primero.contains("Se llama Damián")); assertTrue(primero.contains("NO lo ha pedido la persona")); assertTrue(primero.contains("Buenos días"))
        assertFalse("lo que no viene al caso no se manda", primero.contains("wifi"))
        assertFalse("el sistema no cambia con la memoria: así se cobra menos", p.getString("system").contains("Damián"))
        assertTrue(r.coste > 0.0)
    }

    @Test fun laRevisionEsUnaLlamadaBarataYSinHerramientas() = runBlocking {
        respuestas += mensaje(dice("{}"), "end_turn")
        val a = agente(TelefonoFalso(), mutableListOf(), true)
        assertEquals("{}", a.pensar("Eres la revisión.", "Qué pasó."))
        val p = peticiones[0]
        assertEquals("claude-haiku-5-5", p.getString("model")); assertFalse(p.has("tools")); assertFalse(p.has("fallbacks"))
        assertTrue(a.cobrarRevision() > 0.0); assertEquals(0.0, a.cobrarRevision(), 0.0)
    }

    @Test fun laConversacionSeRecuerda() = runBlocking {
        respuestas += mensaje(dice("Hola."), "end_turn"); respuestas += mensaje(dice("Bien."), "end_turn")
        val a = agente(TelefonoFalso(), mutableListOf(), true)
        a.correr("Hola"); a.correr("¿Qué tal?")
        assertEquals(3, peticiones[1].getJSONArray("messages").length())
    }

    @Test fun unRechazoDelModeloSeCuenta() = runBlocking {
        respuestas += """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"No puedo con esto."},"usage":{"input_tokens":1,"output_tokens":1}}"""
        val r = agente(TelefonoFalso(), mutableListOf(), true).correr("algo")
        assertEquals("rechazada", r.estado)
    }

    @Test fun claveMalaSeExplica() = runBlocking {
        codigo = 401
        val r = agente(TelefonoFalso(), mutableListOf(), true).correr("Hola")
        assertEquals("fallo", r.estado); assertTrue(r.texto.contains("clave"))
    }

    @Test fun sinClaveNoLlama() = runBlocking {
        val r = Agente(Ajustes(""), Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { true }), Mente(CajaFalsa())).correr("Hola")
        assertEquals("fallo", r.estado); assertTrue(peticiones.isEmpty())
    }
}
