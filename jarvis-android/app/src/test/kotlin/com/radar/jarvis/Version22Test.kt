package com.radar.jarvis

import com.radar.jarvis.nucleo.Biblioteca
import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Estudiante
import com.radar.jarvis.nucleo.Estudios
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Mente
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Los criterios del dueño para dar por buena la versión 2.2. */
class Version22Test {
    private fun falla(contiene: String, hacer: () -> Unit) = try { hacer(); fail("debía fallar: $contiene") } catch (e: ErrorManos) { assertTrue(e.message, e.message!!.contains(contiene)) }
    private fun capitulo(tema: String) = "## Qué es\n\n" + "El $tema es un concepto central de la física cuántica que conviene entender despacio, con sus palabras clave y su fórmula. ".repeat(5) +
        "\n\n## Un ejemplo\n\n" + "Un electrón en una caja ilustra el $tema: al medir cambia lo que se puede saber del sistema. ".repeat(4) +
        "\n\n## Errores típicos\n\n" + "Confundir el $tema con una limitación de los aparatos al medir es el error más común. ".repeat(4)
    private val indice = listOf("Dualidad onda-partícula", "Principio de incertidumbre", "Función de onda", "Ecuación de Schrödinger", "Superposición",
        "Entrelazamiento", "Efecto túnel", "Medición y colapso", "Cómo se aplica").map { it to "Explicar $it con definiciones, fórmula y un ejemplo." }

    // ---------- criterio: una consulta con 3 pasajes muestra las fuentes una sola vez
    @Test fun tresPasajesYLasFuentesUnaSolaVez() {
        val b = Biblioteca(CajaFalsa())
        b.escribir("Física cuántica", "Fundamentos", 2, "Principio de incertidumbre", capitulo("principio de incertidumbre"),
            listOf("Feynman, Lectures on Physics III — https://www.feynmanlectures.caltech.edu/III_toc.html", "MIT OCW 8.04 — https://ocw.mit.edu/courses/8-04"))
        val l = b.consultar("principio de incertidumbre del electrón al medir", 3)
        assertEquals(3, l.size)
        val r = b.respuesta(l)
        assertEquals("la lista de fuentes aparece una sola vez", 1, Regex("feynmanlectures").findAll(r).count())
        assertEquals(1, Regex("Fuentes:").findAll(r).count())
        assertTrue("las fuentes van al final", r.indexOf("Fuentes:") > r.lastIndexOf("[3]"))
        assertEquals("cada pasaje con su tomo, capítulo y sección", setOf("Qué es", "Un ejemplo", "Errores típicos"), l.map { it.seccion }.toSet())
        for (i in 1..3) assertTrue(r.contains("[$i] «Física cuántica», capítulo 2: Principio de incertidumbre · sección «"))
        assertFalse("los títulos de sección no se repiten dentro del texto del pasaje", l.any { it.texto.startsWith("##") })
    }

    @Test fun dosCapitulosCitadosDosLineasDeFuentes() {
        val b = Biblioteca(CajaFalsa())
        b.escribir("Física cuántica", "", 1, "Dualidad", capitulo("comportamiento ondulatorio"), listOf("fuente uno"))
        b.escribir("Física cuántica", "", 2, "Incertidumbre", capitulo("principio de incertidumbre"), emptyList())
        val r = b.respuesta(b.consultar("comportamiento ondulatorio y principio de incertidumbre", 4))
        assertEquals(1, Regex("fuente uno").findAll(r).count()); assertTrue(r.contains("capítulo 2: (sin fuentes apuntadas)"))
    }

    // ---------- criterio: un dato marcado como pendiente aparece en la lista y desaparece al verificarlo
    @Test fun unDatoPendienteApareceYDesapareceAlVerificarlo() = runBlocking {
        val b = Biblioteca(CajaFalsa()); val h = Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { true }, pausaTrasActuar = 0, biblioteca = b)
        b.escribir("Física cuántica", "", 2, "Incertidumbre", capitulo("principio de incertidumbre") + "\n\nHeisenberg lo publicó en 1925.", listOf("f"))
        assertTrue(h.ejecutar("biblioteca_pendientes", emptyMap()).texto.contains("No hay ningún dato"))
        val marca = h.ejecutar("biblioteca_pendiente", mapOf("tomo" to "Física cuántica", "capitulo" to 2, "dato" to "Heisenberg lo publicó en 1925", "motivo" to "otras fuentes dicen 1927"))
        assertFalse(marca.error); assertTrue(marca.texto.contains("d1"))
        val lista = h.ejecutar("biblioteca_pendientes", emptyMap()).texto
        assertTrue("aparece en la lista", lista.contains("d1") && lista.contains("1925") && lista.contains("capítulo 2") && lista.contains("1927"))
        assertEquals("apuntarlo dos veces no lo duplica", 1, b.also { it.marcarPendiente("Física cuántica", 2, "Heisenberg lo publicó en 1925", "") }.pendientes().size)
        assertTrue("al consultar ese capítulo, avisa de que hay un dato dudoso", b.respuesta(b.consultar("principio de incertidumbre", 1)).contains("d1"))

        assertFalse(h.ejecutar("biblioteca_corregir", mapOf("tomo" to "Física cuántica", "capitulo" to 2, "buscar" to "lo publicó en 1925", "reemplazar" to "lo publicó en 1927", "fuentes" to listOf("Zeitschrift für Physik 43"))).error)
        assertFalse(h.ejecutar("biblioteca_verificar", mapOf("id" to "d1", "resultado" to "corregido")).error)
        assertTrue("y desaparece al verificarlo", h.ejecutar("biblioteca_pendientes", emptyMap()).texto.contains("No hay ningún dato"))
        assertTrue(h.ejecutar("biblioteca_verificar", mapOf("id" to "d1", "resultado" to "confirmado")).error)
        assertTrue(h.ejecutar("biblioteca_verificar", mapOf("id" to "d9", "resultado" to "quizá")).error)
        assertTrue(h.ejecutar("biblioteca_pendiente", mapOf("tomo" to "Física cuántica", "capitulo" to 7, "dato" to "algo dudoso")).error)
    }

    // ---------- mejora 6: corregir una parte sin reescribir el capítulo
    @Test fun corregirCambiaSoloEseFragmento() {
        val b = Biblioteca(CajaFalsa())
        val original = capitulo("efecto túnel") + "\n\nLa barrera mide 5 nanómetros."
        b.escribir("Física cuántica", "", 7, "Efecto túnel", original, listOf("fuente vieja"))
        val t = b.corregir("fisica cuantica", 7, "La barrera mide 5 nanómetros.", "La barrera mide 1 nanómetro.", listOf("fuente nueva", "fuente vieja"))
        val nuevo = b.leer(t, 7)!!
        assertTrue(nuevo.endsWith("La barrera mide 1 nanómetro.")); assertEquals("lo demás queda idéntico", original.trim().removeSuffix("La barrera mide 5 nanómetros."), nuevo.removeSuffix("La barrera mide 1 nanómetro."))
        assertEquals("las fuentes se suman, sin repetir", listOf("fuente vieja", "fuente nueva"), t.capitulos.single().fuentes)
        assertEquals(nuevo.length, t.capitulos.single().letras)
        assertEquals("la búsqueda ve el cambio", 1, b.consultar("barrera nanómetro").count { it.texto.contains("1 nanómetro") })
        falla("no está en el capítulo") { b.corregir("Física cuántica", 7, "La barrera mide 9 nanómetros.", "x") }
        falla("aparece") { b.corregir("Física cuántica", 7, "error más común", "fallo habitual") }
        falla("demasiado corto") { b.corregir("Física cuántica", 7, "túnel", "x") }
        falla("no tiene capítulo") { b.corregir("Física cuántica", 3, "La barrera mide", "x") }
        assertEquals("un intento fallido no cambia nada", nuevo, b.leer(b.tomo("Física cuántica")!!, 7))
    }

    // ---------- criterio: si el estudio se corta en el capítulo 4, se reanuda en el 5 sin repetir lo hecho
    @Test fun siSeCortaEnElCuatroSigueEnElCincoSinRepetir() = runBlocking {
        val caja = CajaFalsa(); val b = Biblioteca(caja); val estudios = Estudios(caja, b)
        estudios.empezar("Física cuántica", "Fundamentos", indice)
        val escritos = mutableListOf<Int>()
        // primera sesión: escribe hasta el capítulo 4 y, justo después de guardarlo, se corta (se cierra la app)
        try {
            Estudiante(estudios, { p, c ->
                b.escribir(p.tomo, p.descripcion, c.numero, c.titulo, capitulo(c.titulo), listOf("f")); escritos += c.numero
                if (c.numero == 4) throw kotlinx.coroutines.CancellationException("se cerró la app")
                true
            }).correr()
            fail("debía cortarse")
        } catch (e: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf(1, 2, 3, 4), escritos)
        assertEquals("el corte llegó antes de apuntar el 4: en el plan aún figura pendiente", "pendiente", estudios.actual()!!.capitulos[3].estado)

        // segunda sesión, con todo recién leído del disco: sigue en el 5
        val b2 = Biblioteca(caja); val estudios2 = Estudios(caja, b2)
        assertTrue(estudios2.toca())
        assertEquals("se reanuda en el capítulo 5", 5, estudios2.siguiente()!!.numero)
        val fin = Estudiante(estudios2, { p, c -> b2.escribir(p.tomo, p.descripcion, c.numero, c.titulo, capitulo(c.titulo), listOf("f")); escritos += c.numero; true }).correr()!!
        assertEquals("sin repetir lo hecho", listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), escritos)
        assertEquals("terminado", fin.estado); assertEquals(9, fin.hechos); assertEquals(9, b2.tomo("Física cuántica")!!.capitulos.size)
        assertFalse(estudios2.toca()); assertNull(estudios2.siguiente())
    }

    // ---------- criterio: un tomo de 9 capítulos, con 3 búsquedas por capítulo, sin bloquearse
    @Test fun nueveCapitulosConTresBusquedasCadaUnoSinBloquearse() = runBlocking {
        val caja = CajaFalsa(); val b = Biblioteca(caja); val estudios = Estudios(caja, b)
        estudios.empezar("Física cuántica", "Fundamentos", indice)
        var buscadas = 0; var bloqueos = 0
        val fin = Estudiante(estudios, { p, c ->
            var cupo = 4                                        // el cupo empieza de cero en cada capítulo: lo que gastó el anterior no cuenta
            repeat(3) { if (cupo > 0) { cupo--; buscadas++ } else bloqueos++ }
            b.escribir(p.tomo, p.descripcion, c.numero, c.titulo, capitulo(c.titulo), listOf("f")); true
        }).correr()!!
        assertEquals(27, buscadas); assertEquals(0, bloqueos); assertEquals(9, fin.hechos); assertEquals("terminado", fin.estado)
    }

    @Test fun unCapituloQueFallaNoTumbaElTomoNiEntraEnBucle() = runBlocking {
        val caja = CajaFalsa(); val b = Biblioteca(caja); val estudios = Estudios(caja, b)
        estudios.empezar("Física cuántica", "", indice.take(3))
        var intentos = 0
        val escribir: suspend (com.radar.jarvis.nucleo.PlanEstudio, com.radar.jarvis.nucleo.CapituloPlan) -> Boolean = { p, c ->
            intentos++
            if (c.numero == 2) false else { b.escribir(p.tomo, "", c.numero, c.titulo, capitulo(c.titulo), listOf("f")); true }
        }
        var p = Estudiante(estudios, escribir).correr()!!
        assertEquals("tras un fallo se para y espera, no insiste en bucle", 2, intentos)
        assertEquals("en_curso", p.estado); assertFalse("y no se reintenta hasta pasados unos minutos", estudios.toca())
        assertTrue(estudios.toca(System.currentTimeMillis() + Estudios.ESPERA_TRAS_FALLO + 1))
        p = Estudiante(estudios, escribir).correr()!!; p = Estudiante(estudios, escribir).correr()!!
        assertEquals("al tercer fallo lo da por imposible y sigue con el siguiente", "fallido", p.capitulos[1].estado)
        assertEquals("hecho", p.capitulos[2].estado); assertEquals("terminado", p.estado); assertTrue(p.resumen().contains("sin poder escribirse"))
    }

    @Test fun elEstudioSeLanzaSeConsultaYSeDetieneDesdeElAgente() = runBlocking {
        val caja = CajaFalsa(); val b = Biblioteca(caja); val estudios = Estudios(caja, b); var avisos = 0
        val h = Herramientas(TelefonoFalso(), Mente(CajaFalsa()), { true }, pausaTrasActuar = 0, biblioteca = b, estudios = estudios, alEstudiar = { avisos++ })
        assertTrue(h.ejecutar("estudio_estado", emptyMap()).texto.contains("No hay ningún estudio"))
        val caps = indice.map { mapOf("titulo" to it.first, "alcance" to it.second) }
        assertTrue(h.ejecutar("estudio_empezar", mapOf("tomo" to "Física cuántica", "capitulos" to caps.take(1))).error)
        assertTrue(h.ejecutar("estudio_empezar", mapOf("tomo" to "Física cuántica", "capitulos" to listOf(mapOf("titulo" to "Uno", "alcance" to "x"), mapOf("titulo" to "Dos", "alcance" to "y")))).error)
        val r = h.ejecutar("estudio_empezar", mapOf("tomo" to "Física cuántica", "descripcion" to "Fundamentos", "capitulos" to caps))
        assertFalse(r.texto, r.error); assertTrue(r.texto.contains("9 capítulos")); assertEquals(1, avisos)
        assertTrue("no puede haber dos a la vez", h.ejecutar("estudio_empezar", mapOf("tomo" to "Otro tema", "capitulos" to caps)).error)
        assertTrue(h.ejecutar("estudio_estado", emptyMap()).texto.contains("0 de 9"))
        val encargo = estudios.encargo(estudios.actual()!!, estudios.siguiente()!!)
        assertTrue(encargo.contains("capítulo 1") && encargo.contains("Dualidad onda-partícula") && encargo.contains("9. Cómo se aplica") && encargo.contains("← este"))
        assertFalse(h.ejecutar("estudio_detener", emptyMap()).error); assertEquals("detenido", estudios.actual()!!.estado); assertNull(estudios.siguiente())
        assertFalse("detenido el anterior, se puede empezar otro", h.ejecutar("estudio_empezar", mapOf("tomo" to "Otro tema", "capitulos" to caps)).error)
    }

    @Test fun elQueEscribeUnCapituloNoPuedeTocarElTelefono() = runBlocking {
        val tel = TelefonoFalso().apply { campo = "hola"; foco = true }
        val h = Herramientas(tel, Mente(CajaFalsa()), { true }, pausaTrasActuar = 0, biblioteca = Biblioteca(CajaFalsa()), soloEstas = Estudios.HERRAMIENTAS)
        assertEquals(Estudios.HERRAMIENTAS, h.catalogo().map { it.nombre }.toSet())
        for (t in listOf("tocar", "abrir_app", "estudio_empezar", "rutina_crear", "recordar")) assertTrue(t, h.ejecutar(t, mapOf("ref" to 4)).error)
        assertTrue(tel.enviados.isEmpty()); assertFalse(h.ejecutar("biblioteca_indice", emptyMap()).error)
    }
}
