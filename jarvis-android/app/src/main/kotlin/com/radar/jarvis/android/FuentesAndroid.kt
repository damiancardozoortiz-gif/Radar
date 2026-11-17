package com.radar.jarvis.android

import android.Manifest
import android.app.Notification
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.radar.jarvis.nucleo.Aviso
import com.radar.jarvis.nucleo.Cita
import com.radar.jarvis.nucleo.Fuentes
import com.radar.jarvis.nucleo.Posicion
import java.util.Locale

/**
 * El oído de JARVIS para las notificaciones. Lo activas tú en Ajustes → Acceso a notificaciones.
 * Guarda las últimas que llegaron (solo en memoria) y avisa a la sesión por si alguna rutina las esperaba.
 */
class ServicioNotificaciones : NotificationListenerService() {
    override fun onListenerConnected() { instancia = this; Sesion.avisar() }
    override fun onListenerDisconnected() { if (instancia === this) instancia = null; Sesion.avisar() }

    private fun aviso(n: StatusBarNotification): Aviso? {
        if (n.packageName == packageName || n.isOngoing) return null
        val e = n.notification.extras
        val titulo = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val texto = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (titulo.isBlank() && texto.isBlank()) return null
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString() }.getOrDefault(n.packageName)
        return Aviso(app, titulo.take(120), texto.replace(Regex("\\s+"), " ").take(400), n.postTime)
    }

    override fun onNotificationPosted(n: StatusBarNotification) {
        if (!Sesion.almacen.fuenteAvisos) return
        val a = aviso(n) ?: return
        synchronized(recientes) {
            if (recientes.none { it.clave == a.clave }) { recientes.addLast(a); while (recientes.size > 60) recientes.removeFirst() }
        }
        Sesion.alLlegarAviso()
    }

    /** Las que hay ahora mismo en la barra, la más reciente primero. */
    fun activas(): List<Aviso> = try { activeNotifications.mapNotNull { aviso(it) }.sortedByDescending { it.hora } } catch (e: Exception) { emptyList() }

    companion object {
        @Volatile var instancia: ServicioNotificaciones? = null
            private set
        private val recientes = ArrayDeque<Aviso>()

        /** Las que llegaron desde la última vez que se preguntó: para las rutinas. */
        fun nuevas(): List<Aviso> = synchronized(recientes) { recientes.toList().also { recientes.clear() } }
    }
}

/** Notificaciones, agenda, contactos y ubicación. Cada una necesita su interruptor encendido Y el permiso de Android. */
class FuentesAndroid(private val ctx: Context, private val almacen: Almacen) : Fuentes {
    private fun tiene(permiso: String) = ctx.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED
    fun puedeAgenda() = tiene(Manifest.permission.READ_CALENDAR)
    fun puedeContactos() = tiene(Manifest.permission.READ_CONTACTS)
    fun puedeUbicacion() = tiene(Manifest.permission.ACCESS_FINE_LOCATION) || tiene(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun puedeAvisos() = ServicioNotificaciones.instancia != null

    override fun avisos(): List<Aviso>? = if (almacen.fuenteAvisos) ServicioNotificaciones.instancia?.activas() else null

    override fun citas(dias: Int): List<Cita>? {
        if (!almacen.fuenteAgenda || !puedeAgenda()) return null
        val ahora = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { ContentUris.appendId(it, ahora); ContentUris.appendId(it, ahora + dias * 86_400_000L); it.build() }
        val salida = mutableListOf<Cita>()
        return try {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && salida.size < 80) salida += Cita(c.getString(0).orEmpty().ifEmpty { "(sin título)" }, c.getLong(1), c.getLong(2), c.getString(3).orEmpty())
            }
            salida
        } catch (e: Exception) { null }
    }

    override fun contactos(buscar: String): List<Pair<String, String>>? {
        if (!almacen.fuenteContactos || !puedeContactos()) return null
        val salida = mutableListOf<Pair<String, String>>()
        return try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$buscar%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                while (c.moveToNext() && salida.size < 30) (c.getString(0).orEmpty() to c.getString(1).orEmpty()).let { if (it !in salida) salida += it }
            }
            salida
        } catch (e: Exception) { null }
    }

    override fun posicion(): Posicion? = posicion(conNombre = true)

    /** La última posición que conoce el teléfono. No enciende el GPS: usa lo que ya haya. */
    fun posicion(conNombre: Boolean): Posicion? {
        if (!almacen.fuenteUbicacion || !puedeUbicacion()) return null
        return try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            @Suppress("MissingPermission")
            val l = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: return null
            val nombre = if (!conNombre) "" else runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("es")).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let { a ->
                    listOfNotNull(a.thoroughfare, a.subLocality, a.locality).distinct().joinToString(", ")
                }
            }.getOrNull().orEmpty()
            Posicion(l.latitude, l.longitude, nombre)
        } catch (e: Exception) { null }
    }

    override fun porQueNo(fuente: String): String = when (fuente) {
        "notificaciones" -> if (!almacen.fuenteAvisos) "la persona no ha encendido «Notificaciones» en Ajustes de JARVIS" else "falta dar a JARVIS el acceso a notificaciones en los ajustes de Android"
        "agenda" -> if (!almacen.fuenteAgenda) "la persona no ha encendido «Agenda» en Ajustes de JARVIS" else "falta el permiso de calendario"
        "contactos" -> if (!almacen.fuenteContactos) "la persona no ha encendido «Contactos» en Ajustes de JARVIS" else "falta el permiso de contactos"
        "ubicacion" -> if (!almacen.fuenteUbicacion) "la persona no ha encendido «Ubicación» en Ajustes de JARVIS"
            else if (!puedeUbicacion()) "falta el permiso de ubicación" else "el teléfono no tiene ninguna posición reciente (¿la ubicación está apagada?)"
        else -> "esa fuente no existe"
    }
}
