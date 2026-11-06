package com.radar.jarvis;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Gestor de permisos de JARVIS. Muestra el estado REAL que da Android; nunca finge un permiso. */
public final class Perms {
    public static final class P {
        public final String id, name, manifest, why, when;
        public final boolean optional;
        P(String id, String name, String manifest, String why, String when, boolean optional) {
            this.id = id; this.name = name; this.manifest = manifest; this.why = why; this.when = when; this.optional = optional;
        }
    }

    public static final P[] ALL = {
        new P("mic", "Micrófono", "android.permission.RECORD_AUDIO",
              "Escuchar tus órdenes y las dos palmadas.", "Solo cuando pulsas el micrófono o activas la escucha por palmadas (con notificación visible).", false),
        new P("camera", "Cámara", "android.permission.CAMERA",
              "Ver lo que le enseñes y sacar fotos para que JARVIS las describa.", "Solo con la pantalla de cámara abierta. Se apaga al salir.", false),
        new P("notif", "Notificaciones", null,
              "Mostrar la notificación del servicio y sus botones de PAUSA.", "Mientras el servicio está activo.", false),
        new P("location", "Ubicación", "android.permission.ACCESS_FINE_LOCATION",
              "Responder «¿dónde estoy?» y abrir rutas desde aquí.", "Una lectura cada vez que lo pides; no se envía a Claude.", true),
    };

    private Perms() {}

    /** "concedido" | "denegado" | "no pedido" | "bloqueado (ajustes)" */
    public static String state(Activity a, P p) {
        if (p.manifest == null) {  // notificaciones: en Android 13+ las controla el sistema por app
            NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
            try {  // areNotificationsEnabled (API 24) por reflexión
                return (Boolean) NotificationManager.class.getMethod("areNotificationsEnabled").invoke(nm) ? "concedido" : "denegado";
            } catch (Exception e) {
                return "no verificable (Android 6)";
            }
        }
        if (a.checkSelfPermission(p.manifest) == PackageManager.PERMISSION_GRANTED) return "concedido";
        boolean asked = JarvisApp.store(a).flag("asked_" + p.id);
        if (!asked) return "no pedido";
        return a.shouldShowRequestPermissionRationale(p.manifest) ? "denegado" : "bloqueado (ajustes)";
    }

    public static boolean granted(Context c, String manifest) {
        return c.checkSelfPermission(manifest) == PackageManager.PERMISSION_GRANTED;
    }

    /** Pide UN permiso con el diálogo oficial de Android. Si está bloqueado, abre los Ajustes de la app. */
    public static void request(Activity a, P p, int code) {
        String st = state(a, p);
        if (p.manifest == null) { openNotificationSettings(a); return; }
        if ("bloqueado (ajustes)".equals(st)) { openAppSettings(a); return; }
        JarvisApp.store(a).flag("asked_" + p.id, true);
        a.requestPermissions(new String[]{p.manifest}, code);
    }

    public static void openAppSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void openNotificationSettings(Context c) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", c.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static String howTo(P p) {
        if (p.manifest == null) return "Ajustes → Aplicaciones → JARVIS → Notificaciones → activar.";
        return "Ajustes → Aplicaciones → JARVIS → Permisos → " + p.name + " → Permitir.";
    }
}
