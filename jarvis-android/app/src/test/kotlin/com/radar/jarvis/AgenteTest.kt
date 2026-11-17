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
        assertEquals("la búsqueda directa, sin Python de por medio", "web_search_20250305", tools.getJSONObject(nombres.indexOf("web_search")).getString("type"))
        assertEquals("web_fetch_20250910", tools.getJSONObject(nombres.indexOf("web_fetch")).getString("type"))
        assertFalse("no se ofrece ejecución de código: no hay nada que parsear con json.loads", nombres.any { it.contains("code") })
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

    @Test fun haikuVaSinRespaldo() = runBlocking {
        respuestas += mensaje(dice("Hola."), "end_turn")
        agente(TelefonoFalso(), mutableListOf(), true, "claude-haiku-5-5").correr("Hola")
        val p = peticiones[0]
        assertFalse(p.has("fallbacks"))
    }

    private fun conUso(contenido: String, fin: String, busquedas: Int, lecturas: Int = 0) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$contenido],"stop_reason":"$fin","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5,"server_tool_use":{"web_search_requests":$busquedas,"web_fetch_requests":$lecturas}}}"""
    private fun herramienta(p: JSONObject, nombre: String): JSONObject? =
        p.getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it) }.firstOrNull { it.getString("name") == nombre } }

    @Test fun elCupoDeBusquedasLoLlevaLaAppYElModeloLoVe() = runBlocking {
        // capítulo con cupo de 4 búsquedas y 3 lecturas: gasta 3 búsquedas y 1 lectura, luego 1 búsqueda más, y se queda sin búsquedas
        respuestas += conUso(usa("t1", "biblioteca_indice", "{}"), "tool_use", 3, 1)
        respuestas += conUso(usa("t2", "biblioteca_indice", "{}"), "tool_use", 1, 0)
        respuestas += conUso(dice("hecho"), "end_turn", 0, 0)
        val mente = Mente(CajaFalsa()); val b = com.radar.jarvis.nucleo.Biblioteca(CajaFalsa())
        val a = Agente(Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:$puerto", busquedas = 4, lecturas = 3),
            Herramientas(TelefonoFalso(), mente, { true }, pausaTrasActuar = 0, biblioteca = b, soloEstas = com.radar.jarvis.nucleo.Estudios.HERRAMIENTAS), mente,
            extraSistema = com.radar.jarvis.nucleo.Estudios.OFICIO)
        val r = a.correr("Escribe el capítulo 1")
        assertEquals(4, r.busquedas); assertEquals(1, r.lecturas)

        assertEquals("empieza con el cupo entero", 4, herramienta(peticiones[0], "web_search")!!.getInt("max_uses"))
        assertEquals(3, herramienta(peticiones[0], "web_fetch")!!.getInt("max_uses"))
        assertTrue(peticiones[0].getJSONArray("messages").getJSONObject(0).get("content").toString().contains("te quedan 4 búsquedas y 3 lecturas"))
        assertEquals("tras gastar 3, la siguiente petición solo admite 1", 1, herramienta(peticiones[1], "web_search")!!.getInt("max_uses"))
        assertEquals(2, herramienta(peticiones[1], "web_fetch")!!.getInt("max_uses"))
        val m1 = peticiones[1].getJSONArray("messages"); val ultimo1 = m1.getJSONObject(m1.length() - 1).getJSONArray("content")
        assertEquals("tool_result", ultimo1.getJSONObject(0).getString("type"))
        assertTrue("el modelo ve cuánto le queda después de cada paso", ultimo1.getJSONObject(1).getString("text").contains("te quedan 1 búsquedas y 2 lecturas"))
        assertEquals("con el cupo a cero la búsqueda ni se ofrece: no puede bloquearse a mitad", null, herramienta(peticiones[2], "web_search"))
        assertEquals(2, herramienta(peticiones[2], "web_fetch")!!.getInt("max_uses"))

        // el que escribe un capítulo solo tiene la biblioteca y la web: nada del teléfono
        val nombres = peticiones[0].getJSONArray("tools").let { t -> (0 until t.length()).map { t.getJSONObject(it).getString("name") } }
        assertEquals(com.radar.jarvis.nucleo.Estudios.HERRAMIENTAS + setOf("web_search", "web_fetch"), nombres.toSet())
        assertTrue(peticiones[0].getString("system").contains("escribe UN capítulo"))
    }

    @Test fun sinBusquedaEnAjustesNoHayWebNiCupo() = runBlocking {
        respuestas += mensaje(dice("Hola."), "end_turn")
        Agente(Ajustes("sk-ant-api03-prueba", buscarEnWeb = false, baseUrl = "http://127.0.0.1:$puerto"),
            Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { true }, pausaTrasActuar = 0), Mente(CajaFalsa())).correr("Hola")
        assertEquals(null, herramienta(peticiones[0], "web_search")); assertEquals(null, herramienta(peticiones[0], "web_fetch"))
        assertFalse(peticiones[0].getJSONArray("messages").getJSONObject(0).get("content").toString().contains("Cupo"))
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

    @Test fun laVozCortadaYLaTareaAbiertaViajanEnElMensaje() = runBlocking {
        respuestas += mensaje(dice("¿Qué quieres que te escriba?"), "end_turn")
        val caja = CajaFalsa()
        val tareas = com.radar.jarvis.nucleo.Tareas(caja)
        tareas.empezar("Ordenar los álbumes de fotos", listOf("Abrir la galería", "Crear álbumes"), com.radar.jarvis.nucleo.Comprobacion("manual", "Los álbumes salen en la galería"))
        val mente = Mente(caja)
        val a = Agente(Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:$puerto"), Herramientas(TelefonoFalso(), mente, { true }, pausaTrasActuar = 0), mente,
            contextoExtra = { tareas.contexto() })
        a.correr("Escríbeme", canal = "Entró por voz, y la grabación terminó de golpe: la frase puede estar a medias")
        val primero = peticiones[0].getJSONArray("messages").getJSONObject(0).get("content").toString()
        assertTrue(primero.contains("grabación terminó de golpe"))
        assertTrue(primero.contains("Tarea abierta") && primero.contains("Ordenar los álbumes"))
        assertTrue(peticiones[0].getString("system").contains("pregunta antes de actuar"))
    }

    @Test fun lasPaginasDeUnPdfViajanComoImagenes() = runBlocking {
        respuestas += mensaje(usa("t1", "leer_documento", """{"id":"d1"}"""), "tool_use")
        respuestas += mensaje(dice("Es un informe."), "end_turn")
        val docs = object : com.radar.jarvis.nucleo.Documentos {
            override fun lista() = listOf(com.radar.jarvis.nucleo.Documento("d1", "Informe.pdf", "pdf", 3, "adjunto"))
            override fun texto(id: String): String? = null
            override fun paginas(id: String, desde: Int, hasta: Int) = (desde..hasta).map { byteArrayOf(it.toByte()) }
        }
        val mente = Mente(CajaFalsa())
        Agente(Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:$puerto"), Herramientas(TelefonoFalso(), mente, { true }, pausaTrasActuar = 0, documentos = docs), mente)
            .correr("Resume el informe")
        val m = peticiones[1].getJSONArray("messages")
        val resultado = m.getJSONObject(m.length() - 1).getJSONArray("content").getJSONObject(0)
        val bloques = resultado.getJSONArray("content")
        assertEquals(4, bloques.length())                     // el texto y las tres páginas
        assertEquals("image", bloques.getJSONObject(3).getString("type"))
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
