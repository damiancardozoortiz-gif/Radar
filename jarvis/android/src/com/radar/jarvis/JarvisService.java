package com.radar.jarvis;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;

import com.radar.jarvis.core.Brain;

import java.io.File;
import java.util.Map;

/**
 * Servicio en primer plano (notificación visible siempre mientras está activo, con botones PAUSA y Detener).
 * Escucha DOS PALMADAS solo si lo activas: dos picos cortos de volumen separados 150–800 ms.
 * Tras las palmadas graba tu orden, la manda al cerebro y responde en voz alta y en la notificación.
 * Límites honestos: Android puede detener el servicio para ahorrar batería; al volver, respeta la PAUSA.
 */
public class JarvisService extends Service {
    public static final String ACTION_START = "jarvis.START", ACTION_STOP = "jarvis.STOP", ACTION_PAUSE = "jarvis.PAUSE";
    public static volatile boolean running, clapOn;
    private static final int NOTIF = 7;
    private volatile Thread clapThread;
    private volatile boolean stopClap;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? ACTION_START : i.getAction();  // i == null: Android lo relanzó tras cerrarlo
        Store s = JarvisApp.store(this);
        if (ACTION_STOP.equals(a)) { stopEverything(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(a)) {
            stopClap();
            JarvisApp.voice(this).stopSpeaking();
            JarvisApp.voice(this).cancelRecording();
            JarvisApp.brain(this, new JarvisApp.Ready() { public void ready(Brain b) { if (!b.isPaused()) b.setPause(true, "notificación"); notifyState("EN PAUSA · abre JARVIS para quitarla"); } });
            if (!running) { startForeground(NOTIF, build("EN PAUSA")); running = true; }
            return START_STICKY;
        }
        if (i == null && !s.flag("service_on")) { stopSelf(); return START_NOT_STICKY; }
        boolean clap = i != null ? i.getBooleanExtra("clap", s.flag("clap_on")) : s.flag("clap_on");
        startForeground(NOTIF, build("Activo"));
        running = true;
        final boolean wantClap = clap;
        JarvisApp.brain(this, new JarvisApp.Ready() { public void ready(Brain b) {
            JarvisApp.applySettings(JarvisService.this, b);
            if (b.isPaused()) { notifyState("EN PAUSA · abre JARVIS para quitarla"); return; }
            if (wantClap) startClap(); else notifyState("Activo · abre JARVIS para hablar");
            b.log("servicio", "iniciado" + (wantClap ? " con escucha por palmadas" : ""));
        }});
        return START_STICKY;
    }

    // ------------------------------------------------------------------ notificación
    private Notification build(String text) {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            try {  // canal de notificaciones (API 26) por reflexión: se compila contra API 23
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class).newInstance("jarvis", "JARVIS", 2);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(getSystemService(NotificationManager.class), channel);
                nb = Notification.Builder.class.getConstructor(android.content.Context.class, String.class).newInstance(this, "jarvis");
            } catch (Exception e) { nb = new Notification.Builder(this); }
        } else nb = new Notification.Builder(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, JarvisService.class).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, JarvisService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return nb.setSmallIcon(R.drawable.ic_launcher).setContentTitle("JARVIS" + (clapOn ? " · escuchando palmadas" : ""))
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setContentIntent(open)
                .addAction(0, "PAUSA", pause).addAction(0, "Detener", stop).build();
    }

    private void notifyState(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, build(text));
    }

    // ------------------------------------------------------------------ dos palmadas
    private void startClap() {
        stopClap();
        if (!Perms.granted(this, "android.permission.RECORD_AUDIO")) { notifyState("Sin permiso de micrófono: no puedo escuchar palmadas."); return; }
        stopClap = false;
        clapOn = true;
        notifyState("Dos palmadas y te escucho.");
        clapThread = new Thread(new Runnable() { public void run() { clapLoop(); } });
        clapThread.start();
    }

    private void stopClap() {
        stopClap = true;
        clapOn = false;
        Thread t = clapThread;
        if (t != null) try { t.join(1000); } catch (InterruptedException ignored) {}
        clapThread = null;
    }

    private void clapLoop() {
        int rate = 16000, frame = rate / 50;  // 20 ms
        int bufSize = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4);
        AudioRecord ar;
        try { ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize); ar.startRecording(); }
        catch (Exception e) { notifyState("No pude usar el micrófono para las palmadas."); return; }
        short[] buf = new short[frame];
        double noise = 500;
        long lastClap = 0, loudSince = 0;
        boolean trigger = false;
        while (!stopClap) {
            int n = ar.read(buf, 0, frame);
            if (n <= 0) continue;
            int peak = 0; double sum = 0;
            for (int k = 0; k < n; k++) { int v = Math.abs(buf[k]); if (v > peak) peak = v; sum += v; }
            double avg = sum / n;
            long now = System.currentTimeMillis();
            boolean loud = peak > 20000 && peak > noise * 8;
            if (loud && loudSince == 0) loudSince = now;
            if (!loud && loudSince != 0) {
                long dur = now - loudSince;
                loudSince = 0;
                if (dur <= 120) {  // un golpe corto: palmada
                    if (lastClap != 0 && now - lastClap >= 150 && now - lastClap <= 800) { trigger = true; }
                    lastClap = now;
                }
            }
            if (!loud) noise = noise * 0.95 + avg * 0.05;
            if (lastClap != 0 && now - lastClap > 800) lastClap = 0;
            if (trigger) break;
        }
        ar.stop();
        ar.release();
        if (trigger && !stopClap) handleCommand();
    }

    private void handleCommand() {
        final Voice v = JarvisApp.voice(this);
        final Brain b = JarvisApp.brainOrNull();
        if (b == null || b.isPaused()) return;
        b.log("microfono", "dos palmadas detectadas");
        notifyState("Te escucho…");
        if (!v.usesElevenLabs()) {  // sin ElevenLabs, el reconocimiento de Android necesita la pantalla
            notifyState("Te he oído. Pulsa aquí para hablar (sin ElevenLabs no puedo transcribir en segundo plano).");
            if (!stopClap) startClap();
            return;
        }
        v.record(null, new Voice.Recorded() { public void done(File audio, String err) {
            String reply = err != null ? "No te he oído." : null;
            if (audio != null) {
                try {
                    String text = v.transcribe(audio);
                    Map<String, Object> r = b.answer(text);
                    reply = String.valueOf(r.get("say"));
                    notifyState("Tú: " + text + "\nJARVIS: " + reply);
                } catch (Exception e) { reply = "No pude entenderte."; b.error("palmadas: " + e.getMessage()); }
            }
            final String say = reply;
            JarvisApp.ui(new Runnable() { public void run() {
                if (b.isPaused()) return;
                v.speak(say, new Runnable() { public void run() { if (!b.isPaused() && running) startClap(); } });
            }});
        }});
    }

    private void stopEverything() {
        stopClap();
        running = false;
        if (JarvisApp.brainOrNull() != null) JarvisApp.brainOrNull().log("servicio", "detenido");
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { stopClap(); running = false; super.onDestroy(); }
}
