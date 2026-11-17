package com.radar.jarvis

import com.radar.jarvis.nucleo.Biblioteca
import com.radar.jarvis.nucleo.Bitacora
import com.radar.jarvis.nucleo.Comprobacion
import com.radar.jarvis.nucleo.Documento
import com.radar.jarvis.nucleo.Documentos
import com.radar.jarvis.nucleo.Freno
import com.radar.jarvis.nucleo.Herramientas
import com.radar.jarvis.nucleo.Mente
import com.radar.jarvis.nucleo.PermisoFijo
import com.radar.jarvis.nucleo.Permisos
import com.radar.jarvis.nucleo.Peticion
import com.radar.jarvis.nucleo.Proyectos
import com.radar.jarvis.nucleo.Tarea
import com.radar.jarvis.nucleo.Tareas
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los diez criterios del informe de JARVIS para la 2.3, uno por prueba (o más). */
class Version23Test {
    private class Montaje(val caja: CajaFalsa = CajaFalsa(), val tel: TelefonoFalso = TelefonoFalso()) {
        val mente = Mente(caja)
        val tareas = Tareas(caja)
        val proyectos = Proyectos(caja)
        val permisos = Permisos(caja)
        val bitacora = Bitacora(caja)
        val biblioteca = Biblioteca(caja)
        val pedidos = mutableListOf<Peticion>()
        val avisos = mutableListOf<Pair<String, String>>()
        val cerradas = mutableListOf<Tarea>()
        var aprobar = false
        var docs: Documentos? = null
        fun herramientas() = Herramientas(tel, mente, { pedidos += it; aprobar }, pausaTrasActuar = 0, biblioteca = biblioteca,
            tareas = tareas, proyectos = proyectos, documentos = docs, permisos = permisos, bitacora = bitacora,
            avisarPersona = { t, x -> avisos += t to x }, alCerrarTarea = { cerradas += it })
    }

    private fun Herramientas.run(nombre: String, a: Map<String, Any?> = emptyMap()) = runBlocking { ejecutar(nombre, a) }

    // ---------------------------------------------------------------- 1. tareas largas que se retoman
    @Test fun unaTareaDe15PasosCortadaEnEl7SigueDesdeEl8() {
        val caja = CajaFalsa()
        val m = Montaje(caja)
        val h = m.herramientas()
        val r = h.run("tarea_empezar", mapOf("objetivo" to "Ordenar las 15 fotos del viaje en álbumes", "pasos" to (1..15).map { "Paso número $it" },
            "comprobar" to "manual", "comprobar_texto" to "Los 15 álbumes aparecen en la galería"))
        assertFalse(r.texto, r.error)
        for (p in 1..7) assertFalse(h.run("tarea_avanzar", mapOf("paso" to p, "resultado" to "hecho $p")).error)
        // se «cierra la app»: el turno no termina; todo lo que queda es lo que hay en disco
        val despues = Tareas(caja)
        val t = despues.paraRetomar()
        assertNotNull(t)
        assertEquals(8, t!!.actual)
        val encargo = despues.retomar(t.id)
        assertTrue(encargo, encargo.contains("sigue desde el paso 8"))
        assertTrue(encargo.contains("1. ✓ Paso número 1") && encargo.contains("8. → Paso número 8"))
        assertEquals(1, despues.abierta()!!.reanudaciones)
    }

    @Test fun unaTareaQueParaParaPreguntarNoSeRetomaSola() {
        val m = Montaje()
        m.tareas.empezar("Mandar el resumen a Ana por correo", listOf("Escribir", "Enviar"), Comprobacion("manual", "El correo sale en Enviados"))
        m.tareas.alTerminarTurno("terminado")
        assertEquals("esperando", m.tareas.abierta()!!.estado)
        assertNull(m.tareas.paraRetomar())
        assertTrue(m.tareas.contexto().contains("esperando a la persona"))
    }

    @Test fun unaTareaSeRetomaComoMuchoTresVeces() {
        val m = Montaje()
        val t = m.tareas.empezar("Algo que se corta siempre", listOf("Uno", "Dos"), Comprobacion("manual", "Se ve el resultado final"))
        repeat(3) { assertNotNull(m.tareas.paraRetomar()); m.tareas.retomar(t.id) }
        assertNull(m.tareas.paraRetomar())
    }

    // ---------------------------------------------------------------- 2. avisar a la persona
    @Test fun avisaALaPersonaYAlCerrarUnaTarea() {
        val m = Montaje()
        val h = m.herramientas()
        assertFalse(h.run("avisar_persona", mapOf("titulo" to "Estudio terminado", "texto" to "El tomo de computadoras tiene 10 capítulos.")).error)
        assertEquals("Estudio terminado", m.avisos.single().first)
        repeat(2) { h.run("avisar_persona", mapOf("titulo" to "Otro", "texto" to "aviso")) }
        assertTrue("como mucho tres por turno", h.run("avisar_persona", mapOf("titulo" to "Cuarto", "texto" to "de más")).error)
        h.run("tarea_empezar", mapOf("objetivo" to "Revisar el calendario de la semana", "pasos" to listOf("Abrir", "Leer"), "comprobar" to "manual", "comprobar_texto" to "Se leyeron los siete días"))
        h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Leído", "evidencia" to "Vi los eventos del lunes al domingo en la agenda"))
        assertEquals("terminada", m.cerradas.single().estado)
    }

    // ---------------------------------------------------------------- 3. comprobar antes de dar algo por hecho
    @Test fun noDaPorHechoLoQueNoSeVeEnPantalla() {
        val m = Montaje()
        val h = m.herramientas()
        h.run("tarea_empezar", mapOf("objetivo" to "Contestar a Ana que mañana voy", "pasos" to listOf("Abrir el chat", "Escribir", "Enviar"),
            "comprobar" to "pantalla", "comprobar_texto" to "Sí, mañana voy"))
        // no envió nada (p. ej. sin conexión), pero dice que sí
        val r1 = h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Enviado"))
        assertTrue(r1.texto, r1.error && r1.texto.startsWith("COMPROBACIÓN FALLIDA"))
        assertEquals("en_curso", m.tareas.abierta()!!.estado)
        val r2 = h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Enviado"))
        assertTrue(r2.error)
        assertNull(m.tareas.abierta())
        assertEquals("fallida", m.tareas.todas().last().estado)
        assertEquals("fallida", m.cerradas.single().estado)
    }

    @Test fun loQueSeVeEnPantallaPasaLaComprobacion() {
        val m = Montaje(tel = TelefonoFalso().apply { foco = true })
        val h = m.herramientas()
        h.run("tarea_empezar", mapOf("objetivo" to "Escribir a Ana que mañana voy", "pasos" to listOf("Escribir", "Comprobar"),
            "comprobar" to "pantalla", "comprobar_texto" to "Sí, mañana voy"))
        h.run("escribir", mapOf("texto" to "Sí, mañana voy"))
        assertFalse(h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Escrito")).error)
        assertEquals("terminada", m.tareas.todas().last().estado)
    }

    @Test fun unCapituloSinSeccionesOSinFuentesNoPasa() {
        val m = Montaje()
        val h = m.herramientas()
        m.biblioteca.escribir("Computadoras", "Cómo funcionan", 1, "Historia", "Texto corrido sin secciones. ".repeat(40), emptyList())
        h.run("tarea_empezar", mapOf("objetivo" to "Escribir el capítulo 1 de Computadoras", "pasos" to listOf("Investigar", "Escribir"),
            "comprobar" to "capitulo", "comprobar_texto" to "Computadoras|1"))
        val r = h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Escrito"))
        assertTrue(r.texto, r.error && r.texto.contains("secciones"))
        m.biblioteca.escribir("Computadoras", "", 1, "Historia", "## Orígenes\n" + "Texto. ".repeat(60) + "\n## Hoy\n" + "Más texto. ".repeat(60), listOf("Fuente A https://a.example"))
        assertFalse(h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Escrito")).error)
    }

    @Test fun laComprobacionManualPideEvidencia() {
        val m = Montaje()
        val h = m.herramientas()
        h.run("tarea_empezar", mapOf("objetivo" to "Poner la alarma de las siete", "pasos" to listOf("Abrir el reloj", "Crear"), "comprobar" to "manual", "comprobar_texto" to "La alarma de las 7:00 sale activada"))
        assertTrue(h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Hecho")).error)
        assertFalse(h.run("tarea_terminar", mapOf("logrado" to true, "resultado" to "Hecho", "evidencia" to "En la lista de alarmas aparece 7:00 con el interruptor encendido")).error)
    }

    // ---------------------------------------------------------------- 4. bucles
    @Test fun laMismaPantallaTresVecesAvisaYALaCuartaPara() {
        val m = Montaje()
        val h = m.herramientas()
        assertFalse(h.run("deslizar", mapOf("direccion" to "arriba")).texto.contains("AVISO"))
        assertFalse(h.run("deslizar", mapOf("direccion" to "arriba")).texto.contains("AVISO"))
        val tercera = h.run("deslizar", mapOf("direccion" to "arriba"))
        assertTrue(tercera.texto, !tercera.error && tercera.texto.contains("AVISO"))
        val cuarta = h.run("deslizar", mapOf("direccion" to "arriba"))
        assertTrue(cuarta.texto, cuarta.error && cuarta.texto.contains("BUCLE"))
        val despues = h.run("tocar", mapOf("ref" to 3))
        assertTrue("las manos quedan quietas", despues.error && despues.texto.contains("bucle"))
        assertTrue("puede seguir hablando y avisando", !h.run("avisar_persona", mapOf("titulo" to "Atasco", "texto" to "No encuentro el ajuste")).error)
        h.nuevaTarea()
        assertFalse("en el turno siguiente vuelve a poder", h.run("tocar", mapOf("ref" to 3)).error)
    }

    // ---------------------------------------------------------------- 5. permisos fijos
    @Test fun mandarseUnMensajeASiMismoNoPreguntaPeroAOtroSi() {
        val m = Montaje(tel = TelefonoFalso().apply { foco = true })      // el chat de arriba se llama «Ana López»
        m.aprobar = false
        m.permisos.chatPropio = "Rafael (tú)"
        val h = m.herramientas()
        h.run("escribir", mapOf("texto" to "Recordatorio"))
        assertTrue("es el chat de otra persona: pregunta", h.run("tocar", mapOf("ref" to 4)).error)
        assertEquals(1, m.pedidos.size)
        assertNull("escribir a otros nunca se ofrece como permiso fijo", m.pedidos[0].fijable)

        val m2 = Montaje(tel = TelefonoFalso().apply { foco = true })
        m2.permisos.chatPropio = "Ana López"                               // aquí ese ES su chat consigo misma
        val h2 = m2.herramientas()
        h2.run("escribir", mapOf("texto" to "Recordatorio"))
        val r = h2.run("tocar", mapOf("ref" to 4))
        assertFalse(r.texto, r.error)
        assertTrue(r.texto.contains("permiso fijo"))
        assertTrue(m2.pedidos.isEmpty())
        assertEquals(listOf("Recordatorio"), m2.tel.enviados)
    }

    @Test fun unPermisoFijoDeAppCubreConfirmarPeroNuncaBorrarNiPagar() {
        val cal = "com.google.android.calendar"
        val m = Montaje(tel = TelefonoFalso().apply { app = cal })
        val h = m.herramientas()
        // sin permiso: pregunta, y ofrece dejarlo fijo
        assertTrue(h.run("tocar", mapOf("ref" to 2, "irreversible" to true)).error)
        val propuesta = m.pedidos.single().fijable
        assertNotNull(propuesta)
        assertEquals("app", propuesta!!.tipo); assertEquals(cal, propuesta.donde)
        // la persona pulsa «Siempre»
        m.permisos.conceder(propuesta)
        m.pedidos.clear()
        assertFalse(h.run("tocar", mapOf("ref" to 2, "irreversible" to true)).error)
        assertTrue(m.pedidos.isEmpty())
        // con el permiso puesto, borrar, pagar y enviar siguen preguntando
        for (e in listOf("Eliminar evento", "Pagar", "Confirmar pago", "Confirmar pedido", "Enviar invitación")) {
            assertNull(e, m.permisos.cubre(e, cal, false, false, emptyList()))
            assertNull(e, Permisos.propuesta(e, cal, false, false))
        }
        assertNotNull(m.permisos.cubre("Guardar", cal, true, false, emptyList()))
        // y nunca en un banco
        assertNull(Permisos.propuesta("Confirmar", "com.bancodemo.app", true, false))
    }

    @Test fun losPuentesQueEnvianOBorranNoSePuedenDejarFijos() {
        for (n in listOf("p_zapier__gmail_send_email", "p_x__deleteFile", "p_x__slack_post_message", "p_x__stripe_create_charge", "p_x__share_document"))
            assertFalse(n, Freno.puenteFijable(n))
        for (n in listOf("p_zapier__google_calendar_create_event", "p_x__notion_create_page", "p_x__todoist_add_task"))
            assertTrue(n, Freno.puenteFijable(n))
        val m = Montaje()
        m.permisos.conceder(PermisoFijo("", "puente", "p_zapier__gmail_send_email", "enviar correos", 0))
        assertNull("aunque alguien lo hubiera guardado, enviar correos no se cubre", m.permisos.cubrePuente("p_zapier__gmail_send_email"))
    }

    @Test fun enUnaRutinaDeNivelPrepararElPermisoFijoNoDaElUltimoPaso() {
        val m = Montaje(tel = TelefonoFalso().apply { foco = true })
        m.permisos.chatPropio = "Ana López"
        val h = m.herramientas().apply { nivel = "preparar" }
        h.run("escribir", mapOf("texto" to "Hola"))
        assertTrue(h.run("tocar", mapOf("ref" to 4)).error)
        assertTrue(m.tel.enviados.isEmpty())
    }

    // ---------------------------------------------------------------- 6. ordenar la memoria
    @Test fun elRepasoDetectaLeccionesQueSeContradicen() {
        val mente = Mente(CajaFalsa())
        mente.recordar("leccion", "En WhatsApp pulsa Intro para enviar el mensaje", "autoevaluación")
        mente.recordar("leccion", "En WhatsApp no pulses Intro para enviar el mensaje", "autoevaluación")
        mente.recordar("leccion", "Antes de tocar mira la pantalla con ver_pantalla", "autoevaluación")
        val r = mente.repasar()
        assertEquals(1, r.contradicciones.size)
        assertEquals(1, mente.dudas().size)
        assertTrue(mente.contexto("cómo envío en WhatsApp con Intro").texto.contains("se contradicen"))
    }

    @Test fun lasLeccionesCuentanSusConfirmacionesYSeJuntanLasRepetidas() {
        val mente = Mente(CajaFalsa())
        val l = mente.recordar("leccion", "En Gmail el botón Enviar está arriba a la derecha", "autoevaluación")!!
        assertNull(mente.recordar("leccion", "En Gmail el botón Enviar está arriba a la derecha", "autoevaluación"))
        assertEquals(2, mente.recuerdo(l.id)!!.veces)
        assertEquals("confirmada 2 veces", mente.recuerdo(l.id)!!.firmeza())
        mente.recordar("leccion", "En Gmail el botón Enviar está arriba a la derecha del correo nuevo", "autoevaluación")
        val r = mente.repasar()
        assertEquals(1, r.fusionados)
        assertEquals(1, mente.recuerdos().count { it.capa == "leccion" })
        assertEquals(3, mente.recuerdos().single { it.capa == "leccion" }.veces)
    }

    @Test fun jarvisPuedeBuscarCorregirYOlvidarRecuerdos() {
        val m = Montaje()
        val h = m.herramientas()
        val r = m.mente.recordar("hecho", "El dentista de Rafael es el doctor Pérez", "conversación")!!
        assertTrue(h.run("memoria_buscar", mapOf("consulta" to "dentista")).texto.contains(r.id))
        assertFalse(h.run("memoria_corregir", mapOf("id" to r.id, "texto" to "El dentista de Rafael es la doctora Gómez")).error)
        assertTrue(m.mente.recuerdo(r.id)!!.texto.contains("Gómez"))
        assertFalse(h.run("memoria_olvidar", mapOf("id" to r.id, "motivo" to "cambió de dentista")).error)
        assertNull(m.mente.recuerdo(r.id))
    }

    // ---------------------------------------------------------------- 7. proyectos
    @Test fun preguntarComoVaUnProyectoDaElSiguientePaso() {
        val m = Montaje()
        val h = m.herramientas()
        assertFalse(h.run("proyecto_guardar", mapOf("nombre" to "Videos explicativos animados", "objetivo" to "Videos al estilo Stupy Doo para explicar temas",
            "siguiente" to "Dibujar el personaje principal con crear_imagen", "pendientes" to listOf("Elegir la voz", "Guion del primer video"))).error)
        val ctx = m.proyectos.contexto("¿cómo va lo de los videos?")
        assertTrue(ctx, ctx.contains("Dibujar el personaje principal"))
        assertEquals("", m.proyectos.contexto("pon una alarma a las siete"))
        h.run("proyecto_guardar", mapOf("nombre" to "videos explicativos animados", "hecho" to "elegir la voz", "siguiente" to "Escribir el guion del primer video"))
        val p = m.proyectos.lista().single()
        assertEquals(listOf("Guion del primer video"), p.pendientes)
        assertEquals("Escribir el guion del primer video", p.siguiente)
    }

    // ---------------------------------------------------------------- 8. documentos
    private class DocumentosFalsos : Documentos {
        val texto = "Capítulo uno. ".repeat(2000)
        override fun lista() = listOf(Documento("d1", "Informe.pdf", "pdf", 9, "carpeta"), Documento("d2", "Notas.txt", "texto", 0, "adjunto"))
        override fun texto(id: String) = if (id == "d2") texto else null
        override fun paginas(id: String, desde: Int, hasta: Int) = (desde..hasta).map { byteArrayOf(it.toByte()) }
    }

    @Test fun leeUnPdfPorPaginasYUnTextoPorPartes() {
        val m = Montaje().apply { docs = DocumentosFalsos() }
        val h = m.herramientas()
        assertTrue(h.run("documentos").texto.contains("Informe.pdf"))
        val p = h.run("leer_documento", mapOf("id" to "d1"))
        assertFalse(p.texto, p.error)
        assertEquals(1, p.imagen!![0].toInt()); assertEquals(listOf(2, 3, 4), p.extra.map { it[0].toInt() })
        assertTrue(p.texto.contains("desde=5"))
        val ultimo = h.run("leer_documento", mapOf("id" to "d1", "desde" to 9))
        assertTrue(ultimo.texto.contains("final"))
        val t = h.run("leer_documento", mapOf("id" to "d2"))
        assertTrue(t.texto.contains("parte 1 de 3") && t.texto.contains("datos_no_confiables"))
    }

    // ---------------------------------------------------------------- 10. registro por turno
    @Test fun elRegistroCoincideConLoQueHizo() {
        val m = Montaje(tel = TelefonoFalso().apply { foco = true })
        val h = m.herramientas()
        m.bitacora.empezar(1L, "Escríbele a Ana que voy", "Entró por voz")
        h.run("ver_pantalla")
        h.run("escribir", mapOf("texto" to "Voy", "proposito" to "Escribir la respuesta"))
        h.run("tocar", mapOf("ref" to 4))                     // Enviar sin aprobar: queda como error
        m.bitacora.cerrar("Lo dejé escrito; falta tu sí.", "terminada", 0.01)
        val t = Bitacora(m.caja).turnos().single()            // releído de disco
        assertEquals(listOf("ver_pantalla", "escribir", "tocar"), t.usos.map { it.herramienta })
        assertEquals(listOf(false, false, true), t.usos.map { it.error })
        assertTrue(t.usos[1].datos.contains("texto=Voy") && !t.usos[1].datos.contains("proposito"))
        assertTrue(t.usos[2].resultado.startsWith("NO APROBADO"))
        assertEquals("Entró por voz", t.canal)
        assertEquals("Lo dejé escrito; falta tu sí.", t.respuesta)
    }

    @Test fun unTurnoCortadoQuedaApuntadoHastaDondeLlego() {
        val caja = CajaFalsa()
        val b = Bitacora(caja)
        b.empezar(5L, "Tarea larga", "escrito")
        b.anotar("ver_pantalla", emptyMap(), com.radar.jarvis.nucleo.Resultado("Pantalla"), 10)
        val t = Bitacora(caja).turnos().single()
        assertEquals("en curso", t.estado); assertEquals(1, t.usos.size)
    }
}
