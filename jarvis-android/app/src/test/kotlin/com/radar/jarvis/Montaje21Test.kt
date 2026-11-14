package com.radar.jarvis

import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Medio
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.Montaje
import com.radar.jarvis.nucleo.Palabra
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Lo nuevo de la 2.1: imágenes fijas con movimiento, velocidad, fundidos, volumen por partes y subtítulos con la voz. */
class Montaje21Test {
    private val medios = EstudioFalso().medios() + Medio("a9", "Narración", "audio", 6_000, origen = "narración")
    private val voz = listOf(Palabra("Hola,", 0, 400), Palabra("esto", 450, 700), Palabra("es", 720, 800), Palabra("una", 820, 950),
        Palabra("prueba.", 1000, 1500), Palabra("Segunda", 2600, 3000), Palabra("frase", 3050, 3400), Palabra("larguísima", 3450, 4200),
        Palabra("que", 4250, 4350), Palabra("sigue", 4400, 4700), Palabra("y", 4720, 4760), Palabra("sigue", 4800, 5100))

    private fun plan(a: Map<String, Any?>) = Montaje.plan(a, medios) { if (it == "a9") voz else null }
    private fun falla(a: Map<String, Any?>, contiene: String) {
        try { plan(a); fail("debía fallar: $contiene") } catch (e: ErrorManos) { assertTrue(e.message, e.message!!.contains(contiene)) }
    }

    @Test fun laVelocidadCambiaLoQueDuraElTrozo() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 10, "velocidad" to 2),
            mapOf("id" to "v2", "desde" to 0, "hasta" to 4, "velocidad" to 0.5))))
        assertEquals(5_000, p.cortes[0].duracionMs); assertEquals(8_000, p.cortes[1].duracionMs); assertEquals(13_000, p.duracionMs)
        falla(mapOf("clips" to listOf(mapOf("id" to "v1", "velocidad" to 9))), "velocidad")
        falla(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 1, "velocidad" to 4))), "demasiado corto")
    }

    @Test fun elLimiteDeDiezMinutosCuentaLaVelocidad() {
        // 11 minutos de material a doble velocidad son 5 y medio: cabe
        assertEquals(330_000, plan(mapOf("clips" to List(11) { mapOf("id" to "v1", "velocidad" to 2) })).duracionMs)
    }

    @Test fun unaImagenEsUnTrozoConDuracionYMovimiento() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "i1", "duracion" to 3, "movimiento" to "acercar", "fundido" to "ambos"), mapOf("id" to "i1")),
            "formato" to "vertical"))
        assertTrue(p.cortes[0].imagen); assertTrue(p.cortes[0].sinSonido)
        assertEquals(3_000, p.cortes[0].duracionMs); assertEquals(4_000, p.cortes[1].duracionMs)     // por defecto, 4 segundos
        assertEquals("acercar", p.cortes[0].movimiento); assertEquals("ambos", p.cortes[0].fundido)
        falla(mapOf("clips" to listOf(mapOf("id" to "i1", "duracion" to 0.1))), "imagen")
        falla(mapOf("clips" to listOf(mapOf("id" to "i1", "movimiento" to "girar"))), "movimiento")
        falla(mapOf("clips" to listOf(mapOf("id" to "v1", "fundido" to "cortina"))), "fundido")
    }

    @Test fun elFormatoOriginalDeUnaImagenNoPasaDe1920() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "i1"))))
        assertEquals(1920, p.ancho); assertEquals(1440, p.alto)
    }

    @Test fun volumenPorTrozo() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "v1", "volumen" to 0.3), mapOf("id" to "v2", "volumen" to 0))))
        assertEquals(0.3f, p.cortes[0].volumen, 0.001f); assertFalse(p.cortes[0].sinSonido)
        assertTrue(p.cortes[1].sinSonido)                                                            // volumen 0 es quitar el sonido
        falla(mapOf("clips" to listOf(mapOf("id" to "v1", "volumen" to 5))), "volumen")
    }

    @Test fun laMusicaCambiaDeVolumenPorTramos() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 20)), "musica" to "a1", "volumen_musica" to 0.5,
            "volumen_musica_tramos" to listOf(mapOf("desde" to 5, "hasta" to 10, "volumen" to 0.1), mapOf("desde" to 15, "hasta" to 99, "volumen" to 1))))
        assertEquals(0.5f, p.volumenMusicaEn(0), 0.001f); assertEquals(0.1f, p.volumenMusicaEn(5_000), 0.001f)
        assertEquals(0.1f, p.volumenMusicaEn(9_999), 0.001f); assertEquals(0.5f, p.volumenMusicaEn(10_000), 0.001f)
        assertEquals(1f, p.volumenMusicaEn(19_000), 0.001f); assertEquals(20_000, p.tramosMusica[1].hastaMs)   // el final se ajusta al video
        falla(mapOf("clips" to listOf(mapOf("id" to "v1")), "volumen_musica_tramos" to listOf(mapOf("desde" to 1, "hasta" to 2, "volumen" to 0.1))), "no hay música")
        falla(mapOf("clips" to listOf(mapOf("id" to "v1")), "musica" to "a1", "volumen_musica_tramos" to listOf(mapOf("desde" to 8, "hasta" to 2, "volumen" to 0.1))), "antes de empezar")
        falla(mapOf("clips" to listOf(mapOf("id" to "v1")), "musica" to "a1", "volumen_musica_tramos" to listOf(mapOf("desde" to 1, "hasta" to 2))), "volumen")
    }

    @Test fun lasLetrasConTiempoSeJuntanEnPalabras() {
        val letras = "Hola  ya".map { it.toString() }
        val p = Montaje.palabras(letras, List(8) { it * 0.1 }, List(8) { it * 0.1 + 0.1 })
        assertEquals(listOf(Palabra("Hola", 0, 400), Palabra("ya", 600, 800)), p)
        assertTrue(Montaje.palabras(emptyList(), emptyList(), emptyList()).isEmpty())
        assertEquals(1, Montaje.palabras(listOf("a", "b", "c"), listOf(0.0, 0.1), listOf(0.1)).size)   // listas desiguales no rompen
    }

    @Test fun losRotulosSiguenALaVoz() {
        val r = Montaje.rotulos(voz)
        assertEquals("Hola, esto es una prueba.", r[0].texto)
        assertEquals(0, r[0].desdeMs); assertEquals(1900, r[0].hastaMs)                 // 400 ms después de terminar de decirlo; luego hay silencio
        assertEquals("Segunda frase larguísima que", r[1].texto); assertEquals(2600, r[1].desdeMs)
        assertEquals(4400, r[1].hastaMs)                                                // se queda hasta que entra el siguiente
        assertEquals("sigue y sigue", r[2].texto); assertEquals(5500, r[2].hastaMs)
        assertTrue(r.all { it.texto.length <= 28 && it.hastaMs > it.desdeMs })
        assertTrue(r.zipWithNext().all { (a, b) -> a.hastaMs <= b.desdeMs })            // nunca dos a la vez
    }

    @Test fun losSubtitulosSeRepartenPorTrozos() {
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 3), mapOf("id" to "v2", "desde" to 0, "hasta" to 4)),
            "narracion" to "a9", "subtitulos" to true))
        assertEquals(3, p.subtitulos.size)
        val uno = p.subtitulosDe(0); val dos = p.subtitulosDe(1)
        assertEquals(2, uno.size); assertEquals(3_000, uno[1].hastaMs)                  // el segundo rótulo se corta donde acaba el trozo
        assertEquals(0, dos[0].desdeMs); assertEquals("Segunda frase larguísima que", dos[0].texto)   // y sigue en el siguiente, desde su principio
        assertEquals(1_400, dos[0].hastaMs); assertEquals("sigue y sigue", dos[1].texto)
    }

    @Test fun losSubtitulosCuentanLaVelocidad() {
        // el primer trozo va al doble: 6 s de material ocupan 3 s, así que el segundo trozo empieza en el segundo 3
        val p = plan(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 6, "velocidad" to 2), mapOf("id" to "v2", "desde" to 0, "hasta" to 4)),
            "narracion" to "a9", "subtitulos" to true))
        assertEquals("Segunda frase larguísima que", p.subtitulosDe(1)[0].texto); assertEquals(0, p.subtitulosDe(1)[0].desdeMs)
    }

    @Test fun subtitulosMalPedidos() {
        val clips = listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 10))
        falla(mapOf("clips" to clips, "subtitulos" to true), "narración")
        falla(mapOf("clips" to clips, "narracion" to "a1", "subtitulos" to true), "narración")          // a1 es música: dura más que el video
        falla(mapOf("clips" to listOf(mapOf("id" to "v1", "desde" to 0, "hasta" to 10, "texto" to "Título")), "narracion" to "a9", "subtitulos" to true), "a la vez")
        val sinTiempos = Montaje.plan(mapOf("clips" to clips, "narracion" to "a9"), medios)             // sin pedirlos, no hacen falta tiempos
        assertTrue(sinTiempos.subtitulos.isEmpty())
        try { Montaje.plan(mapOf("clips" to clips, "narracion" to "a9", "subtitulos" to true), medios); fail() }
        catch (e: ErrorManos) { assertTrue(e.message!!.contains("tiempos")) }
    }

    @Test fun elFundidoVaDeNegroAEnteroYVuelve() {
        assertEquals(0f, Montaje.brillo("entrada", 0, 4000), 0.001f); assertEquals(0.5f, Montaje.brillo("entrada", 250, 4000), 0.001f)
        assertEquals(1f, Montaje.brillo("entrada", 3999, 4000), 0.001f)
        assertEquals(1f, Montaje.brillo("salida", 0, 4000), 0.001f); assertEquals(0f, Montaje.brillo("salida", 4000, 4000), 0.001f)
        assertEquals(1f, Montaje.brillo("ambos", 2000, 4000), 0.001f); assertEquals(1f, Montaje.brillo("", 0, 4000), 0.001f)
        assertEquals(1f, Montaje.brillo("ambos", 200, 400), 0.001f)                      // trozo muy corto: el fundido se acorta
        for (ms in -500L..5000L step 37) assertTrue(Montaje.brillo("ambos", ms, 4000) in 0f..1f)
    }

    @Test fun elMovimientoNuncaDejaVerElBorde() {
        for (m in Montaje.MOVIMIENTOS) for (ms in -100L..4100L step 50) {
            val (escala, dx) = Montaje.encuadre(m, ms, 4000)
            assertTrue("$m $ms", escala >= 1f && Math.abs(dx) <= escala - 1f + 0.0001f)   // en coordenadas de -1 a 1, el borde queda fuera del cuadro
        }
        assertEquals(1f, Montaje.encuadre("acercar", 0, 4000).first, 0.001f); assertEquals(1.18f, Montaje.encuadre("acercar", 4000, 4000).first, 0.001f)
        assertEquals(1f to 0f, Montaje.encuadre("", 2000, 4000))
    }

    @Test fun elAgentePuedeMontarConTodoLoNuevo() = runBlocking {
        val e = EstudioFalso()
        val h = Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { true }, pausaTrasActuar = 0, estudio = e)
        assertTrue(h.ejecutar("medios", emptyMap()).texto.contains("i1 · imagen · 4000×3000"))
        assertFalse(h.ejecutar("ver_video", mapOf("id" to "i1")).error)
        assertFalse(h.ejecutar("narrar", mapOf("texto" to "Hola, esto es una prueba. " + "Sigue. ".repeat(12))).error)
        e.voces["a9"] = voz
        val r = h.ejecutar("montar_video", mapOf("clips" to listOf(mapOf("id" to "i1", "duracion" to 5, "movimiento" to "alejar"),
            mapOf("id" to "v1", "desde" to 0, "hasta" to 6, "velocidad" to 1.5, "fundido" to "salida")), "formato" to "vertical",
            "narracion" to "a9", "subtitulos" to true, "musica" to "a1", "volumen_musica_tramos" to listOf(mapOf("desde" to 0, "hasta" to 5, "volumen" to 0.05))))
        assertFalse(r.texto, r.error)
        assertEquals(9_000, e.planes[0].duracionMs); assertEquals(3, e.planes[0].subtitulos.size)
        val sinTiempos = h.ejecutar("montar_video", mapOf("clips" to listOf(mapOf("id" to "v1")), "narracion" to "a1", "subtitulos" to true))
        assertTrue(sinTiempos.error)
    }
}
