package com.radar.jarvis.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.radar.jarvis.R
import com.radar.jarvis.ui.MainActivity

/** Las notificaciones que pone JARVIS para contarte lo que hizo por iniciativa. */
object Avisador {
    private const val CANAL = "jarvis"

    fun puede(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun avisar(ctx: Context, titulo: String, texto: String) {
        if (!puede(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Lo que hace JARVIS por iniciativa", NotificationManager.IMPORTANCE_DEFAULT))
        val abrir = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify((System.currentTimeMillis() % 100_000).toInt(), Notification.Builder(ctx, CANAL).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(titulo.take(60)).setContentText(texto.take(120)).setStyle(Notification.BigTextStyle().bigText(texto.take(700)))
                .setContentIntent(abrir).setAutoCancel(true).build())
        }
    }
}

/**
 * El despertador de las rutinas de reloj: pide a Android que despierte a JARVIS cuando toque la próxima,
 * aunque el teléfono esté dormido. Android puede retrasarlo unos minutos para ahorrar batería.
 */
class Despertador : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Sesion.latido()

    companion object {
        fun programar(ctx: Context, cuando: Long?) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Despertador::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            runCatching { if (cuando == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando + 5_000, pi) }
        }
    }
}
