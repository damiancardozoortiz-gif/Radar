package com.radar.jarvis;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.radar.jarvis.core.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Voz de JARVIS: graba tu orden (fin de turno por silencio), la transcribe con ElevenLabs Scribe y habla con
 *  ElevenLabs. Sin clave de ElevenLabs habla con la voz del propio Android (y lo indica en el estado). */
public final class Voice {
    public static final int SILENCE_MS = 900, MIN_SPEECH_MS = 500, MAX_TURN_MS = 15000, NO_SPEECH_MS = 8000;
    public static final int LEVEL_THRESHOLD = 2200;  // de 32767 (getMaxAmplitude)

    public volatile String elevenKey = "", voiceId = "", state = "en espera", lastError = "";
    private final Context ctx;
    private MediaRecorder rec;
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady;
    private volatile boolean recording, cancelled;

    public interface Level { void level(float l, long ms); }
    public interface Recorded { void done(File audio, String err); }
    public interface Text { void done(String text, String err); }

    Voice(Context c) {
        ctx = c;
        tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
            public void onInit(int s) { ttsReady = s == TextToSpeech.SUCCESS; if (ttsReady) tts.setLanguage(new Locale("es", "ES")); }
        });
    }

    public boolean usesElevenLabs() { return elevenKey != null && !elevenKey.isEmpty(); }

    // ------------------------------------------------------------------ escuchar
    /** Graba hasta que te callas ~0,9 s. Menos de medio segundo de voz = ruido (no se manda). Llamar fuera del hilo principal. */
    public void record(Level levelCb, Recorded done) {
        File out = new File(ctx.getCacheDir(), "orden.m4a");
        cancelled = false;
        try {
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(48000);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            release();
            done.done(null, "no pude usar el micrófono (" + e.getClass().getSimpleName() + ")");
            return;
        }
        recording = true; state = "escuchando";
        long t0 = System.currentTimeMillis(), lastVoice = 0, voiceMs = 0;
        boolean heard = false;
        try {
            while (!cancelled) {
                Thread.sleep(50);
                long now = System.currentTimeMillis();
                int amp = rec.getMaxAmplitude();
                if (levelCb != null) levelCb.level(Math.min(1f, amp / 12000f), now - t0);
                if (amp > LEVEL_THRESHOLD) { if (heard) voiceMs += 50; heard = true; lastVoice = now; }
                if (heard && now - lastVoice > SILENCE_MS) break;
                if (!heard && now - t0 > NO_SPEECH_MS) break;
                if (now - t0 > MAX_TURN_MS) break;
            }
        } catch (InterruptedException ignored) {}
        release();
        if (cancelled) { done.done(null, "cancelado"); return; }
        if (!heard || voiceMs < MIN_SPEECH_MS) { done.done(null, "no te he oído"); return; }
        done.done(out, null);
    }

    public void cancelRecording() { cancelled = true; }

    private void release() {
        recording = false; state = "en espera";
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); rec = null; }
    }

    /** ElevenLabs Scribe (scribe_v1, es). La clave nunca sale de este teléfono salvo hacia ElevenLabs. */
    public String transcribe(File audio) throws IOException {
        if (!usesElevenLabs()) throw new IOException("falta la clave de ElevenLabs");
        String boundary = "jarvis" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("xi-api-key", elevenKey);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        OutputStream o = c.getOutputStream();
        for (String[] f : new String[][]{{"model_id", "scribe_v1"}, {"language_code", "es"}, {"tag_audio_events", "false"}})
            o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n" + f[1] + "\r\n").getBytes("UTF-8"));
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"orden.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes("UTF-8"));
        FileInputStream in = new FileInputStream(audio);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        o.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new IOException("ElevenLabs " + code);
        String t = Json.str(Json.obj(Json.parse(body)).get("text"));
        return t == null ? "" : t.trim();
    }

    // ------------------------------------------------------------------ hablar
    /** Habla y avisa al terminar (en el hilo principal). Mientras habla, la app no graba. */
    public void speak(final String text, final Runnable done) {
        stopSpeaking();
        if (text == null || text.trim().isEmpty()) { if (done != null) JarvisApp.ui(done); return; }
        state = "hablando";
        new Thread(new Runnable() { public void run() {
            if (usesElevenLabs()) {
                try {
                    final File mp3 = tts11(text);
                    JarvisApp.ui(new Runnable() { public void run() { play(mp3, done); } });
                    lastError = "";
                    return;
                } catch (Exception e) {
                    lastError = "ElevenLabs: " + e.getMessage() + " (uso la voz de Android)";
                }
            }
            JarvisApp.ui(new Runnable() { public void run() { androidTts(text, done); } });
        }}).start();
    }

    private File tts11(String text) throws IOException {
        String vid = voiceId;
        if (vid == null || vid.isEmpty()) vid = firstVoice();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/text-to-speech/" + vid + "?output_format=mp3_44100_128").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("xi-api-key", elevenKey);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "audio/mpeg");
        OutputStream o = c.getOutputStream();
        o.write(Json.write(Json.map("text", text, "model_id", "eleven_flash_v2_5", "language_code", "es")).getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        if (code >= 400) { c.disconnect(); throw new IOException("código " + code); }
        File mp3 = new File(ctx.getCacheDir(), "voz.mp3");
        InputStream in = c.getInputStream();
        FileOutputStream f = new FileOutputStream(mp3);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) f.write(buf, 0, n);
        f.close(); in.close(); c.disconnect();
        return mp3;
    }

    private String firstVoice() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/voices").openConnection();
        c.setRequestProperty("xi-api-key", elevenKey);
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        if (c.getResponseCode() >= 400) throw new IOException("no pude leer tus voces (" + c.getResponseCode() + ")");
        List<Object> v = Json.arr(Json.obj(Json.parse(read(c.getInputStream()))).get("voices"));
        c.disconnect();
        if (v == null || v.isEmpty()) throw new IOException("tu cuenta no tiene voces");
        voiceId = Json.str(Json.obj(v.get(0)).get("voice_id"));
        return voiceId;
    }

    private void play(File mp3, final Runnable done) {
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(mp3.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { stopSpeaking(); if (done != null) done.run(); }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            lastError = "no pude reproducir el audio";
            stopSpeaking();
            if (done != null) done.run();
        }
    }

    private void androidTts(String text, final Runnable done) {
        if (!ttsReady) { state = "en espera"; lastError = "la voz de Android no está lista"; if (done != null) done.run(); return; }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) {}
            public void onDone(String id) { state = "en espera"; if (done != null) JarvisApp.ui(done); }
            public void onError(String id) { state = "en espera"; if (done != null) JarvisApp.ui(done); }
        });
        HashMap<String, String> p = new HashMap<String, String>();
        p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p);
    }

    public void stopSpeaking() {
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (tts != null) tts.stop();
        if (!recording) state = "en espera";
    }

    public boolean isSpeaking() { return "hablando".equals(state); }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }
}
