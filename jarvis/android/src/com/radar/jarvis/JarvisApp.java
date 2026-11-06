package com.radar.jarvis;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import com.radar.jarvis.core.Brain;
import com.radar.jarvis.core.Vault;
import com.radar.jarvis.core.fin.Discipline;
import com.radar.jarvis.core.fin.MetaApi;
import com.radar.jarvis.core.fin.Mt5;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Arranque único de JARVIS dentro de la app: cerebro, claves, voz y registro de fallos. */
public class JarvisApp extends Application {
    public static final int SETUP_VERSION = 1;  // al subirlo, el asistente vuelve a pedir los permisos
    private static Brain brain;
    private static Store store;
    private static Voice voice;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** El saludo suena UNA vez por arranque de la app (no al volver a la pantalla ni al refrescarla). */
    public static volatile boolean greeted;

    public interface Ready { void ready(Brain b); }

    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {  // se registra el fallo; los datos (memoria, borradores, pausa) ya están en disco
                    File f = new File(getFilesDir(), "estado/errores.log");
                    f.getParentFile().mkdirs();
                    FileOutputStream o = new FileOutputStream(f, true);
                    o.write((new java.util.Date() + " CIERRE INESPERADO: " + e + "\n").getBytes("UTF-8"));
                    o.close();
                } catch (Exception ignored) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    public static Store store(Context c) {
        if (store == null) store = new Store(c.getApplicationContext());
        return store;
    }

    public static Voice voice(Context c) {
        if (voice == null) voice = new Voice(c.getApplicationContext());
        return voice;
    }

    public static Brain brainOrNull() { return brain; }

    private static Mt5 mt5;
    /** Conector MT5: MetaApi (solo lectura) si hay token y cuenta guardados; si no, sin conexión. */
    public static synchronized Mt5 mt5(Context c) {
        if (mt5 == null) {
            Store s = store(c);
            String tok = s.secret("metaapi_token"), acc = s.get("metaapi_account", "");
            mt5 = tok.isEmpty() || acc.isEmpty() ? new Mt5.NotConnected() : new MetaApi(tok, acc);
        }
        return mt5;
    }
    private static Discipline discipline;
    /** Regla de autodisciplina financiera (estado en disco: sobrevive a reinicios). */
    public static synchronized Discipline discipline(Context c) {
        if (discipline == null) discipline = new Discipline(c.getApplicationContext().getFilesDir());
        return discipline;
    }

    /** Tras cambiar el token o la cuenta. */
    public static synchronized void resetMt5() { mt5 = null; }

    /** Crea el cerebro en segundo plano (lee las notas de la app) y avisa en el hilo principal. */
    public static void brain(final Context c, final Ready r) {
        if (brain != null) { r.ready(brain); return; }
        new Thread(new Runnable() { public void run() {
            final Context app = c.getApplicationContext();
            final AssetManager am = app.getAssets();
            Brain b = new Brain(app.getFilesDir(), new Vault.Source() {
                public String read(String p) throws IOException { return readAll(am.open(p)); }
                public String[] list(String d) throws IOException { return am.list(d); }
            });
            applySettings(app, b);
            synchronized (JarvisApp.class) { if (brain == null) brain = b; }
            MAIN.post(new Runnable() { public void run() { r.ready(brain); } });
        }}).start();
    }

    public static void applySettings(Context c, Brain b) {
        Store s = store(c);
        b.apiKey = s.secret("anthropic");
        String m = s.get("model", Brain.DEFAULT_MODEL);
        b.model = m.isEmpty() ? Brain.DEFAULT_MODEL : m;
        Voice v = voice(c);
        v.elevenKey = s.secret("elevenlabs");
        v.voiceId = s.get("voice_id", "");
        v.voiceName = s.get("voice_name", "");
        v.model = s.get("voice_model", "eleven_multilingual_v2");
        v.volume = s.num("volume_set") == 1 ? s.num("volume") / 100f : 1f;
        v.enabled = !s.flag("voice_off");
        v.bargeIn = !s.flag("bargein_off");
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    public static void ui(Runnable r) { MAIN.post(r); }
}
