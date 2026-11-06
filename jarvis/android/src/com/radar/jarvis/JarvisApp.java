package com.radar.jarvis;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import com.radar.jarvis.core.Brain;
import com.radar.jarvis.core.Evolution;
import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.Lab;
import com.radar.jarvis.core.Vault;
import com.radar.jarvis.core.Vulkan;
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
    // ================================================================== FÉNIX 80 (motor de evolución) + VULKAN
    private static Evolution evo;
    private static Vulkan vulkan;
    private static Thread runner;
    public interface EvoListener { void step(String result); }
    public static volatile EvoListener evoListener;

    /** Motor de evolución (necesita el cerebro ya creado). */
    public static synchronized Evolution evolution(Context c) {
        if (evo == null && brain != null) {
            final Context app = c.getApplicationContext();
            evo = new Evolution(app.getFilesDir(), brain.experience);
            java.util.List<java.util.Map<String, Object>> known = new java.util.ArrayList<java.util.Map<String, Object>>();
            try { for (Object o : Json.arr(Json.parse(brain.assetText("pendientes.json")))) known.add(Json.obj(o)); } catch (Exception ignored) {}
            vulkan = new Vulkan(brain, new LabSandbox(app), new Vulkan.Probe() { public java.util.List<java.util.Map<String, Object>> run() { return probes(app); } }, known, evo.dir());
        }
        return evo;
    }

    public static synchronized Vulkan vulkan(Context c) { evolution(c); return vulkan; }

    /** Comprobaciones reales del teléfono, sin red ni coste: claves, permisos, almacenamiento y aislamiento del laboratorio. */
    public static java.util.List<java.util.Map<String, Object>> probes(Context c) {
        java.util.List<java.util.Map<String, Object>> l = new java.util.ArrayList<java.util.Map<String, Object>>();
        l.add(Json.map("modulo", "claude", "ok", brain != null && !brain.apiKey.isEmpty(), "detalle", brain != null && !brain.apiKey.isEmpty() ? "clave guardada" : "falta la clave de Claude", "tipo", "humano"));
        l.add(Json.map("modulo", "elevenlabs", "ok", voice(c).usesElevenLabs(), "detalle", voice(c).usesElevenLabs() ? "clave guardada" : "falta la clave de ElevenLabs (uso la voz del teléfono)", "tipo", "humano"));
        boolean mic = Perms.granted(c, "android.permission.RECORD_AUDIO");
        l.add(Json.map("modulo", "micrófono", "ok", mic, "detalle", mic ? "permiso concedido" : "sin permiso de micrófono", "tipo", "humano"));
        java.io.File f = new java.io.File(c.getFilesDir(), "estado");
        l.add(Json.map("modulo", "almacenamiento", "ok", f.isDirectory() && f.canWrite(), "detalle", f.canWrite() ? "se puede escribir" : "no se puede escribir en la memoria de la app", "tipo", "nativo"));
        String sb;
        try {
            java.util.Map<String, Object> r = Json.obj(Json.parse(new LabSandbox(c).run(Lab.single("function run(e){ return e + 1; }", 1L), Lab.TIMEOUT_MS)));
            sb = r != null && Boolean.TRUE.equals(r.get("ok")) && Long.valueOf(2).equals(r.get("salida")) ? null : "respuesta inesperada " + r;
        } catch (Exception e) { sb = e.getMessage(); }
        l.add(Json.map("modulo", "laboratorio", "ok", sb == null, "detalle", sb == null ? "aislamiento WebView funciona" : "el aislamiento falla: " + sb, "tipo", "nativo"));
        return l;
    }

    /** «JARVIS, trabaja en ti mismo»: inicia la misión y el bucle en segundo plano (con intervalos y límites). */
    public static synchronized void startMission(Context c, boolean web) throws java.io.IOException {
        evolution(c).start(System.currentTimeMillis(), web);
        ensureRunner();
    }

    public static synchronized void ensureRunner() {
        if (evo == null || (runner != null && runner.isAlive())) return;
        runner = new Thread(new Runnable() { public void run() {
            while (true) {
                long now = System.currentTimeMillis(), w = evo.waitMs(now);
                if (w < 0) break;  // misión pausada, detenida o terminada: el hilo se va
                if (w > 0) { try { Thread.sleep(Math.min(w, 3000)); } catch (InterruptedException e) { break; } continue; }
                final String r = evo.tick(now, vulkan);
                final EvoListener l = evoListener;
                if (l != null) MAIN.post(new Runnable() { public void run() { l.step(r); } });
            }
            final EvoListener l = evoListener;
            if (l != null) MAIN.post(new Runnable() { public void run() { l.step("fin: misión " + evo.missionState().toLowerCase(java.util.Locale.ROOT)); } });
        }}, "fenix80");
        runner.setPriority(Thread.MIN_PRIORITY);
        runner.start();
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
