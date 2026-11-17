package com.radar.jarvis

import com.radar.jarvis.nucleo.ClienteMcp
import com.radar.jarvis.nucleo.Cerebro
import com.radar.jarvis.nucleo.Enrutador
import com.radar.jarvis.nucleo.Freno
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.Peticion
import com.radar.jarvis.nucleo.PuenteConfig
import com.radar.jarvis.nucleo.Puentes
import com.radar.jarvis.nucleo.Preferencias
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class TallerTest {
    @Test fun httpSinCifrarSoloConElPropioTelefono() {
        assertTrue(ClienteMcp.esLocal("http://127.0.0.1:8090/mcp"))
        assertTrue(ClienteMcp.esLocal("http://localhost:8090/mcp"))
        assertFalse(ClienteMcp.esLocal("http://192.168.1.4:8090/mcp"))
        assertFalse(ClienteMcp.esLocal("http://127.0.0.1.evil.com/mcp"))
        assertFalse(ClienteMcp.esLocal("https://127.0.0.1/mcp"))
    }

    @Test fun pedirlePorVozQueTrabajeEnSuCodigoVaAClaude() {
        val auto = Preferencias("auto", claudeAutorizado = true, hayClave = true)
        for (m in listOf("Mejora tu código para que el orbe brille más", "Compila y pasa las pruebas", "Arregla el bug del dictado", "Haz commit de los cambios"))
            assertEquals(m, Cerebro.CLAUDE, Enrutador.decidir(auto, m).cerebro)
    }

    @Test fun subirYDeshacerNuncaSeDejanFijos() {
        assertFalse(Freno.puenteFijable("p_taller__taller_subir"))
        assertFalse(Freno.puenteFijable("p_taller__taller_deshacer"))
        assertTrue(Freno.puenteFijable("p_taller__taller_compilar"))
    }

    /**
     * Contra un taller DE VERDAD (taller/taller.py sobre un clon del repositorio). Se activa con
     * TALLER_URL y TALLER_TOKEN. Comprueba que JARVIS ve sus herramientas, que leer no pide permiso,
     * que cambiar código sí, y que si la persona dice que no, el archivo no cambia.
     */
    @Test fun jarvisUsaElTallerRealPasandoPorElFreno() = runBlocking {
        val url = System.getenv("TALLER_URL").orEmpty(); val token = System.getenv("TALLER_TOKEN").orEmpty()
        val repo = System.getenv("TALLER_REPO").orEmpty()
        assumeTrue("define TALLER_URL, TALLER_TOKEN y TALLER_REPO", url.isNotEmpty() && token.isNotEmpty() && repo.isNotEmpty())
        val pedidos = mutableListOf<Peticion>()
        var aprobar = false
        fun herramientas() = Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { pedidos += it; aprobar }, pausaTrasActuar = 0,
            puentes = Puentes(listOf(PuenteConfig("taller", url, token))))
        val h = herramientas()
        val nombres = h.catalogo().map { it.nombre }
        assertTrue(nombres.toString(), "p_taller__taller_leer" in nombres && "p_taller__taller_reemplazar" in nombres)

        val leido = h.ejecutar("p_taller__taller_leer", mapOf("ruta" to "jarvis-android/app/build.gradle.kts", "desde" to 1, "hasta" to 3))
        assertFalse(leido.texto, leido.error); assertTrue(leido.texto.contains("plugins {")); assertTrue(pedidos.isEmpty())

        // crear la rama de trabajo: es un cambio, pide permiso
        aprobar = true
        assertFalse(h.ejecutar("p_taller__taller_rama", mapOf("nombre" to "prueba-freno")).error)
        assertEquals(1, pedidos.size)

        // la persona dice que no: el archivo no cambia
        aprobar = false
        val archivo = File(repo, "jarvis-android/README.md"); val antes = archivo.readText()
        val r = h.ejecutar("p_taller__taller_reemplazar", mapOf("ruta" to "jarvis-android/README.md", "buscar" to "# JARVIS para Android", "reemplazar" to "# JARVIS"))
        assertTrue(r.error); assertTrue(r.texto.startsWith("NO APROBADO"))
        assertEquals(antes, archivo.readText())
        assertNotNull("cambiar código se puede dejar fijo si la persona quiere", pedidos.last().fijable)

        // dice que sí: cambia
        aprobar = true
        val r2 = h.ejecutar("p_taller__taller_reemplazar", mapOf("ruta" to "jarvis-android/README.md", "buscar" to "# JARVIS para Android", "reemplazar" to "# JARVIS para Android (taller)"))
        assertFalse(r2.texto, r2.error); assertTrue(archivo.readText().startsWith("# JARVIS para Android (taller)"))

        // subir nunca se ofrece como permiso fijo
        aprobar = false
        h.ejecutar("p_taller__taller_subir", emptyMap())
        assertNull(pedidos.last().fijable)
        // deja la copia limpia: guarda lo cambiado
        aprobar = true
        assertFalse(h.ejecutar("p_taller__taller_commit", mapOf("mensaje" to "Prueba del freno en el taller")).error)
    }

    /**
     * La cadena entera, como cuando se lo pides por voz: el agente de JARVIS (con un Claude simulado que decide los pasos)
     * usa el taller REAL para crear la rama, cambiar un archivo y hacer commit, con la persona aprobando cada cambio.
     */
    @Test fun elAgenteTrabajaEnSuCodigoDePrincipioAFin() = runBlocking {
        val url = System.getenv("TALLER_URL").orEmpty(); val token = System.getenv("TALLER_TOKEN").orEmpty()
        val repo = System.getenv("TALLER_REPO").orEmpty()
        assumeTrue("define TALLER_URL, TALLER_TOKEN y TALLER_REPO", url.isNotEmpty() && token.isNotEmpty() && repo.isNotEmpty())
        val guion = ArrayDeque(listOf(
            """{"type":"tool_use","id":"a","name":"p_taller__taller_rama","input":{"nombre":"voz-orbe"}}""",
            """{"type":"tool_use","id":"b","name":"p_taller__taller_reemplazar","input":{"ruta":"jarvis-android/app/build.gradle.kts","buscar":"versionName = \"2.5\"","reemplazar":"versionName = \"2.5.1\""}}""",
            """{"type":"tool_use","id":"c","name":"p_taller__taller_commit","input":{"mensaje":"Sube la versión a 2.5.1 (prueba del taller)"}}""",
            """{"type":"text","text":"Listo: commit hecho en la rama jarvis/voz-orbe."}""",
        ))
        val claude = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!claude.isClosed) {
                val s = try { claude.accept() } catch (e: Exception) { break }
                s.use {
                    val e = it.getInputStream(); val cab = StringBuilder()
                    while (!cab.endsWith("\r\n\r\n")) { val b = e.read(); if (b < 0) break; cab.append(b.toChar()) }
                    val largo = cab.lines().first { l -> l.lowercase().startsWith("content-length:") }.substringAfter(":").trim().toInt()
                    var n = 0; val c = ByteArray(largo); while (n < largo) n += e.read(c, n, largo - n)
                    val bloque = guion.removeFirst()
                    val fin = if (bloque.contains("tool_use")) "tool_use" else "end_turn"
                    val r = """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$bloque],"stop_reason":"$fin","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""".toByteArray()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray() + r)
                }
            }
        }
        // la versión de la copia debe ser la 2.5 para que el fragmento exista
        val gradle = File(repo, "jarvis-android/app/build.gradle.kts")
        assumeTrue("la copia del taller debe estar en la 2.5", gradle.readText().contains("versionName = \"2.5\""))
        val pedidos = mutableListOf<Peticion>()
        val mente = Mente(CajaFalsa())
        val h = Herramientas(TelefonoFalso(), mente, { pedidos += it; true }, pausaTrasActuar = 0, puentes = Puentes(listOf(PuenteConfig("taller", url, token))))
        val r = com.radar.jarvis.nucleo.Agente(com.radar.jarvis.nucleo.Ajustes("sk-ant-api03-prueba", baseUrl = "http://127.0.0.1:${claude.localPort}", buscarEnWeb = false), h, mente)
            .correr("Sube tu versión a 2.5.1", canal = "Entró por voz")
        claude.close()
        assertEquals("terminada", r.estado)
        assertEquals("cada cambio pidió el sí de la persona: rama, archivo y commit", 3, pedidos.size)
        assertTrue(gradle.readText().contains("versionName = \"2.5.1\""))
        val log = ProcessBuilder("git", "-C", repo, "log", "-1", "--format=%s|%D").start().inputStream.bufferedReader().readText()
        assertTrue(log, log.startsWith("Sube la versión a 2.5.1") && log.contains("jarvis/voz-orbe"))
    }
}
