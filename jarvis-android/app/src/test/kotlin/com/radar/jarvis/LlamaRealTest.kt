package com.radar.jarvis

import com.radar.jarvis.nucleo.CerebroLocal
import com.radar.jarvis.nucleo.LocalAIClient
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.RespuestaLocal
import com.radar.jarvis.nucleo.TipoFallo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Contra un llama-server DE VERDAD (llama.cpp). Solo corre si se le dice dónde está:
 *   LLAMA_SERVER = ruta del ejecutable llama-server
 *   LLAMA_MODELO = ruta de un modelo .gguf
 * La prueba arranca el servidor, le pregunta, lo apaga, comprueba el fallo y lo vuelve a arrancar.
 */
class LlamaRealTest {
    private val bin = System.getenv("LLAMA_SERVER").orEmpty()
    private val modelo = System.getenv("LLAMA_MODELO").orEmpty()

    private fun arrancar(puerto: Int): Process {
        val p = ProcessBuilder(bin, "-m", modelo, "--host", "127.0.0.1", "--port", "$puerto", "-c", "2048", "-np", "1")
            .redirectErrorStream(true).redirectOutput(File.createTempFile("llama", ".log")).start()
        val c = LocalAIClient("http://127.0.0.1:$puerto")
        val hasta = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < hasta) {
            if (runBlocking { c.salud() } is RespuestaLocal.Ok) return p
            Thread.sleep(300)
        }
        p.destroy(); error("llama-server no arrancó")
    }

    @Test fun conversaConLlamaServerRealSeCaeYSeRecupera() = runBlocking {
        assumeTrue("define LLAMA_SERVER y LLAMA_MODELO para esta prueba", File(bin).canExecute() && File(modelo).isFile)
        val puerto = java.net.ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$puerto"
        var servidor = arrancar(puerto)
        try {
            val cerebro = CerebroLocal(LocalAIClient(url), Mente(CajaFalsa()), "Se llama Rafael", maxTokens = 24)
            val (r1, f1) = cerebro.responder("Hola, ¿quién eres?")
            println("llama-server real respondió (${r1.texto.length} caracteres): ${r1.texto.take(80)}")
            assertEquals(null, f1); assertEquals("terminada", r1.estado); assertTrue(r1.texto.isNotBlank())
            val r2 = LocalAIClient(url).chat(listOf(com.radar.jarvis.nucleo.MensajeIA("user", "Di hola")), 8) as RespuestaLocal.Ok
            assertTrue("el modelo que dice el servidor: ${r2.modelo}", r2.modelo.endsWith(".gguf"))
            assertTrue(r2.tokensSalida in 1..8)

            servidor.destroy(); servidor.waitFor()
            val (r3, f3) = cerebro.responder("¿Sigues ahí?")
            assertEquals(TipoFallo.APAGADO, f3!!.tipo); assertEquals("fallo", r3.estado)
            println("con el servidor apagado: ${r3.texto}")

            servidor = arrancar(puerto)
            val (r4, f4) = cerebro.responder("¿Y ahora?")
            assertEquals(null, f4); assertTrue(r4.texto.isNotBlank())
            println("recuperado tras volver a arrancar")
        } finally { servidor.destroy() }
    }
}
