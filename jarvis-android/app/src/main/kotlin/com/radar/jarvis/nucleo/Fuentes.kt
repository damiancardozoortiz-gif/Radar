package com.radar.jarvis.nucleo

/**
 * Lo que JARVIS puede saber de tu día además de la pantalla: notificaciones, agenda, contactos y dónde estás.
 * Cada fuente está apagada hasta que tú la enciendes en Ajustes y das el permiso de Android.
 * Cada función devuelve null si su fuente no está disponible; `porQueNo` explica el motivo.
 */
interface Fuentes {
    fun avisos(): List<Aviso>?
    fun citas(dias: Int): List<Cita>?
    fun contactos(buscar: String): List<Pair<String, String>>?
    fun posicion(): Posicion?
    /** fuente: notificaciones, agenda, contactos o ubicacion. */
    fun porQueNo(fuente: String): String
}
