package com.radar.jarvis.nucleo

enum class Cerebro { LOCAL, CLAUDE }

/** Qué cerebro atiende un mensaje y por qué. cerebro = null: ninguno puede atenderlo (se explica en `motivo`). */
data class Ruta(val cerebro: Cerebro?, val motivo: String)

/** Cómo quiere la persona que se elija el cerebro. Se guarda en Ajustes. */
data class Preferencias(
    /** auto (local primero), local (solo local) o claude (solo Claude). */
    val proveedor: String = "auto",
    /** ¿Puede JARVIS usar Claude (de pago) cuando el local no basta? */
    val claudeAutorizado: Boolean = true,
    /** Si el local no responde (apagado, cargando), ¿se reintenta UNA vez con Claude? */
    val respaldoClaude: Boolean = false,
    val hayClave: Boolean = false,
) {
    /** Claude se puede usar: hay clave, la persona lo autorizó y no eligió «solo local». */
    val puedeClaude get() = hayClave && claudeAutorizado && proveedor != "local"
}

/**
 * El enrutador: decide qué cerebro atiende cada mensaje.
 *
 * El cerebro local (un modelo pequeño en Termux) sirve para conversar y responder lo que ya sabe, pero no tiene manos:
 * no maneja el teléfono, no busca en internet, no edita video ni usa herramientas. Eso necesita a Claude.
 * En modo automático el local va primero y Claude solo entra cuando el mensaje necesita herramientas y está autorizado.
 * Cada mensaje va a UN cerebro; Claude como respaldo, como mucho una vez y solo si el local no llegó a contestar.
 */
object Enrutador {
    val PROVEEDORES = listOf(
        "auto" to "Automático: local primero, Claude solo si hace falta",
        "local" to "Solo el cerebro local (Qwen en Termux)",
        "claude" to "Solo Claude (como hasta ahora)",
    )

    // Lo que solo se puede hacer con herramientas: manejar el teléfono, internet, video, estudio, memoria explícita…
    private val ACCION = Regex(
        "\\b(" + listOf(
            "abre\\w*", "abrir", "envia\\w*", "enviar", "manda(r|le|lo|la|me|selo)?", "escribe(le|me)?", "escribir(le)?", "responde(le)? a", "contesta(le)? a",
            "llama a", "llamar a", "llamale", "llamalo", "llamala", "marca a", "toca", "tocar", "pulsa", "desliza", "instala\\w*", "desinstala\\w*",
            "activa(r|lo|la|me)?", "desactiva(r|lo|la|me)?", "apaga(r|lo|la)?", "enciende\\w*", "sube el volumen", "baja el volumen",
            "pon(me)? (una |la |el )?(alarma|recordatorio|temporizador|musica|cancion)", "alarma\\w*", "temporizador", "recordatorio\\w*",
            "busca(r|me|lo|la)?", "buscame", "googlea\\w*", "investiga(r|lo|la)?", "navega(r)?", "entra en", "descarga(r|lo|la|me)?",
            "whatsapp", "telegram", "instagram", "tiktok", "youtube", "facebook", "gmail", "correos?", "email", "mail", "sms",
            "notificacion(es)?", "agenda", "calendario", "eventos?", "contactos?", "ubicacion", "donde estoy", "pantalla",
            "videos?", "montaje", "monta(r|lo|la|me)?", "edita(r|lo|la|me)?", "narra(r|lo|la|cion)?", "dibuja\\w*", "imagen(es)?", "fotos?", "anima(r|lo|la|cion)",
            "estudia(r|lo|la|te)?", "tomos?", "biblioteca", "capitulos?", "rutinas?", "habilidad(es)?", "aprende a",
            "recuerda(me|lo|la)?", "recordar", "acuerdate", "apunta(r|lo|la|me)?", "anota(r|lo|la|me)?", "olvida(r|lo|la|te)?",
            "documentos?", "pdf", "archivos?", "proyectos?", "tareas?", "comparte\\w*", "compartir", "publica(r|lo|la)?",
            "noticias", "clima", "que tiempo hace", "precios?", "cotizacion", "dolar", "partido de hoy", "ultima hora",
        ).joinToString("|") + ")\\b"
    )

    /** Por qué este mensaje necesita herramientas (y por tanto a Claude), o null si basta con conversar. */
    fun necesitaHerramientas(mensaje: String, hayTareaAbierta: Boolean = false, hayAdjuntos: Boolean = false): String? {
        if (hayTareaAbierta) return "hay una tarea abierta que sigue con herramientas"
        if (hayAdjuntos) return "hay archivos adjuntos que mirar"
        val m = ACCION.find(Freno.norm(mensaje)) ?: return null
        return "pide hacer algo («${m.value}») que necesita herramientas"
    }

    /** El cerebro para un mensaje de la persona. */
    fun decidir(p: Preferencias, mensaje: String, hayTareaAbierta: Boolean = false, hayAdjuntos: Boolean = false): Ruta = when (p.proveedor) {
        "claude" -> if (p.hayClave) Ruta(Cerebro.CLAUDE, "elegiste solo Claude")
            else Ruta(null, "Elegiste solo Claude pero no hay clave de la API. Ponla en Ajustes o cambia de cerebro.")
        "local" -> Ruta(Cerebro.LOCAL, "elegiste solo el cerebro local")
        else -> {
            val porQue = necesitaHerramientas(mensaje, hayTareaAbierta, hayAdjuntos)
            when {
                porQue == null -> Ruta(Cerebro.LOCAL, "es conversación: la atiende el cerebro local")
                p.puedeClaude -> Ruta(Cerebro.CLAUDE, porQue)
                else -> Ruta(Cerebro.LOCAL, "$porQue, pero Claude no está ${if (p.hayClave) "autorizado" else "configurado"}: contesta el local, sin manos")
            }
        }
    }

    /** Si el local falló sin contestar: ¿se pasa a Claude? Solo en automático, con respaldo encendido y Claude autorizado. Una vez. */
    fun respaldo(p: Preferencias, fallo: RespuestaLocal.Fallo): Ruta? =
        if (p.proveedor == "auto" && p.respaldoClaude && p.puedeClaude && fallo.tipo != TipoFallo.DIRECCION)
            Ruta(Cerebro.CLAUDE, "el cerebro local no respondió (${fallo.tipo.name.lowercase()}): respaldo con Claude") else null

    /** Cómo se atendió un mensaje. */
    data class Atendido(val respuesta: Respuesta, val cerebro: Cerebro, val falloLocal: RespuestaLocal.Fallo? = null)

    /**
     * Atiende un mensaje con la ruta decidida. Garantías: Claude se llama como mucho UNA vez por mensaje; con ruta local,
     * solo si el local falló sin contestar y el respaldo está permitido. Si el local contestó, Claude no se llama.
     * @param nota avisa a la pantalla de lo que pasa (p. ej. «respaldo con Claude»).
     */
    suspend fun atender(
        ruta: Ruta, p: Preferencias,
        local: suspend () -> Pair<Respuesta, RespuestaLocal.Fallo?>,
        claude: suspend () -> Respuesta,
        nota: (String) -> Unit = {},
    ): Atendido {
        if (ruta.cerebro == Cerebro.CLAUDE) return Atendido(claude(), Cerebro.CLAUDE)
        val (r, fallo) = local()
        val otra = fallo?.let { respaldo(p, it) } ?: return Atendido(r, Cerebro.LOCAL, fallo)
        nota(otra.motivo)
        return Atendido(claude(), Cerebro.CLAUDE, fallo)
    }

    /** Las rutinas, las tareas retomadas y los estudios siempre necesitan herramientas: solo con Claude autorizado. */
    fun puedeTrabajarSolo(p: Preferencias) = p.puedeClaude
}
