package com.radar.jarvis;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Handler;
import android.os.Looper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Voz de JARVIS con ElevenLabs (la integración que ya tienes; la clave nunca sale del teléfono salvo hacia ElevenLabs).
 * - Escuchar: graba tu orden, fin de turno tras ~0,9 s de silencio, y la transcribe con Scribe (scribe_v1, es).
 * - Hablar: una voz FIJA (masculina, adulta, grave si tu cuenta la tiene), modelo natural por defecto,
 *   ritmo tranquilo, volumen ajustable, margen al final para no cortar frases.
 * - Interrupción: mientras habla escucha con cancelación de eco; si hablas ~0,3 s, se calla.
 * - Sin clave de ElevenLabs, o si falla, habla con la voz de Android y lo indica.
 */
public final class Voice {
    public static final int SILENCE_MS = 900, MIN_SPEECH_MS = 500, MAX_TURN_MS = 15000, NO_SPEECH_MS = 8000;
    public static final int LEVEL_THRESHOLD = 2200;  // de 32767 (getMaxAmplitude)

    public volatile String elevenKey = "", voiceId = "", voiceName = "", state = "en espera", lastError = "";
    /** eleven_multilingual_v2 = la más natural (por defecto); eleven_flash_v2_5 = la más rápida. */
    public volatile String model = "eleven_multilingual_v2";
    public volatile float volume = 1f;
    public volatile boolean enabled = true, bargeIn = true;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder rec;
    private MediaPlayer player;
    private TextToSpeech tts;
    private boolean ttsReady;
    private volatile boolean recording, cancelled, bargeStop, simple;
    private volatile Runnable onInterrupt;

    public interface Level { void level(float l, long ms); }
    public interface Recorded { void done(File audio, String err); }

    Voice(Context c) {
        ctx = c;
        tts = new TextToSpeech(c, new TextToSpeech.OnInitListener() {
            public void onInit(int s) { ttsReady = s == TextToSpeech.SUCCESS; if (ttsReady) tts.setLanguage(new Locale("es", "ES")); }
        });
    }

    public boolean usesElevenLabs() { return elevenKey != null && !elevenKey.isEmpty(); }

    // ================================================================== escuchar
    /** Graba hasta que te callas ~0,9 s. Menos de medio segundo de voz = ruido. Llamar fuera del hilo principal. */
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

    /** ElevenLabs Scribe (scribe_v1, es). */
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

    // ================================================================== voces
    /** Voces de tu cuenta (id, nombre, etiquetas). También comprueba que la clave funciona. */
    public List<Map<String, Object>> voices() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/voices").openConnection();
        c.setRequestProperty("xi-api-key", elevenKey);
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        int code = c.getResponseCode();
        if (code >= 400) { c.disconnect(); throw new IOException(code == 401 ? "clave de ElevenLabs no válida" : "ElevenLabs respondió " + code); }
        List<Object> v = Json.arr(Json.obj(Json.parse(read(c.getInputStream()))).get("voices"));
        c.disconnect();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (v != null) for (Object o : v) out.add(Json.obj(o));
        return out;
    }

    /** Puntuación para «voz masculina adulta, grave, profunda y elegante, en español». */
    public static int score(Map<String, Object> v) {
        Map<String, Object> l = Json.obj(v.get("labels"));
        String labels = l == null ? "" : Json.write(l).toLowerCase();
        String all = (v.get("name") + " " + v.get("description") + " " + labels).toLowerCase();
        int s = 0;
        if (all.contains("female") || all.contains("mujer") || all.contains("femenin")) s -= 10;
        else if (labels.contains("\"male\"") || all.contains(" male") || all.contains("mascul") || all.contains("hombre")) s += 5;
        for (String w : new String[]{"deep", "grave", "profund", "baritone", "authoritative", "elegant", "elegante", "calm", "narrat", "mature"}) if (all.contains(w)) s += 2;
        if (all.contains("middle") || all.contains("\"old\"")) s += 2;
        if (all.contains("young")) s -= 1;
        for (String w : new String[]{"spanish", "español", "espanol", "latin", "castil", "mexic", "argent"}) if (all.contains(w)) s += 3;
        return s;
    }

    /** Elige y FIJA la voz (todas las respuestas con la misma). Si ya hay una guardada, la respeta. */
    public String chooseVoice() throws IOException {
        if (voiceId != null && !voiceId.isEmpty()) return voiceId;
        List<Map<String, Object>> vs = voices();
        if (vs.isEmpty()) throw new IOException("tu cuenta de ElevenLabs no tiene voces");
        Map<String, Object> best = vs.get(0);
        for (Map<String, Object> v : vs) if (score(v) > score(best)) best = v;
        setVoice(Json.str(best.get("voice_id")), String.valueOf(best.get("name")));
        return voiceId;
    }

    public void setVoice(String id, String name) {
        voiceId = id; voiceName = name;
        JarvisApp.store(ctx).put("voice_id", id);
        JarvisApp.store(ctx).put("voice_name", name);
    }

    // ================================================================== hablar
    /** Habla y avisa al terminar (en el hilo principal). Mientras habla, la app no graba órdenes. */
    public void speak(final String text, final Runnable done) {
        stopSpeaking();
        if (!enabled || text == null || text.trim().isEmpty()) { if (done != null) main.post(done); return; }
        state = "hablando";
        new Thread(new Runnable() { public void run() {
            if (usesElevenLabs()) {
                try {
                    final File mp3 = tts11(text);
                    main.post(new Runnable() { public void run() { play(mp3, done); } });
                    lastError = "";
                    return;
                } catch (Exception e) {
                    lastError = "ElevenLabs: " + e.getMessage() + " (uso la voz de Android)";
                }
            }
            main.post(new Runnable() { public void run() { androidTts(text, done); } });
        }}).start();
    }

    private File tts11(String text) throws IOException {
        String vid = chooseVoice();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/text-to-speech/" + vid + "?output_format=mp3_44100_128").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("xi-api-key", elevenKey);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "audio/mpeg");
        Map<String, Object> body = Json.map("text", text, "model_id", model);
        if (!simple) {  // voz grave y serena: estable, poca exageración, algo más pausada
            body.put("voice_settings", Json.map("stability", 0.55, "similarity_boost", 0.8, "style", 0.1, "use_speaker_boost", true, "speed", 0.94));
            if (model.endsWith("v2_5")) body.put("language_code", "es");  // solo los modelos v2.5 aceptan language_code
        }
        OutputStream o = c.getOutputStream();
        o.write(Json.write(body).getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        if ((code == 400 || code == 422) && !simple) {  // si tu cuenta no acepta algún ajuste, se repite con los básicos
            c.disconnect();
            simple = true;
            try { return tts11(text); } finally { simple = false; }
        }
        if (code >= 400) { c.disconnect(); throw new IOException("código " + code); }
        File mp3 = new File(ctx.getCacheDir(), "voz.mp3");
        InputStream in = c.getInputStream();
        FileOutputStream f = new FileOutputStream(mp3);
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) f.write(buf, 0, n);
        f.close(); in.close(); c.disconnect();
        return mp3;
    }

    private void play(File mp3, final Runnable done) {
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(mp3.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {  // margen para no cortar el final de la frase
                    main.postDelayed(new Runnable() { public void run() { stopSpeaking(); if (done != null) done.run(); } }, 250);
                }
            });
            player.setVolume(volume, volume);
            player.prepare();
            player.start();
            startBargeIn();
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
            public void onDone(String id) { state = "en espera"; if (done != null) main.post(done); }
            public void onError(String id) { state = "en espera"; if (done != null) main.post(done); }
        });
        HashMap<String, String> p = new HashMap<String, String>();
        p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "jarvis");
        p.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, String.valueOf(volume));
        tts.setPitch(0.85f);       // algo más grave
        tts.setSpeechRate(0.95f);  // ritmo tranquilo
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, p);
    }

    public void stopSpeaking() {
        bargeStop = true;
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} player.release(); player = null; }
        if (tts != null) tts.stop();
        if (!recording) state = "en espera";
    }

    public boolean isSpeaking() { return "hablando".equals(state); }

    /** Qué hacer si empiezas a hablar mientras JARVIS habla. */
    public void setOnInterrupt(Runnable r) { onInterrupt = r; }

    /** Mientras habla: escucha con cancelación de eco; si hablas ~0,3 s seguidos, JARVIS se calla. */
    private void startBargeIn() {
        if (!bargeIn || onInterrupt == null || !Perms.granted(ctx, "android.permission.RECORD_AUDIO")) return;
        bargeStop = false;
        new Thread(new Runnable() { public void run() {
            int rate = 16000, frame = 320;
            AudioRecord ar = null;
            AcousticEchoCanceler aec = null;
            try {
                int size = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frame * 4);
                ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(ar.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
                ar.startRecording();
                short[] buf = new short[frame];
                int loudFrames = 0;
                long start = System.currentTimeMillis();
                while (!bargeStop) {
                    int n = ar.read(buf, 0, frame);
                    if (n <= 0) continue;
                    long sum = 0;
                    for (int k = 0; k < n; k++) sum += Math.abs(buf[k]);
                    boolean loud = sum / n > 2600 && System.currentTimeMillis() - start > 600;
                    loudFrames = loud ? loudFrames + 1 : Math.max(0, loudFrames - 1);
                    if (loudFrames >= 15) {
                        final Runnable r = onInterrupt;
                        main.post(new Runnable() { public void run() { stopSpeaking(); if (r != null) r.run(); } });
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (ar != null) { try { ar.stop(); } catch (Exception ignored) {} ar.release(); }
                if (aec != null) aec.release();
            }
        }}).start();
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }
}
