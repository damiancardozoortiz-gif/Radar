import com.radar.jarvis.core.*;
import java.io.File;
import java.util.*;

/** Voz: detección de fin de frase (nueva vs la anterior, con señales SINTÉTICAS) y medición de tiempos. Arg: carpeta temporal. */
public class VoiceTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static Random rnd = new Random(42);

    /** Señal: ms de ruido, ms de voz, ms de ruido final. Amplitudes por trama de 50 ms. */
    static int[] signal(int noise, int voice, int leadMs, int speakMs, int tailMs) {
        int n = (leadMs + speakMs + tailMs) / 50; int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            int t = i * 50;
            boolean v = t >= leadMs && t < leadMs + speakMs;
            int base = v ? voice : noise;
            s[i] = Math.max(0, (int) (base * (0.75 + rnd.nextDouble() * 0.5)));
            if (v && i % 9 == 4) s[i] = (int) (noise * 0.9);  // pausas breves entre palabras
        }
        return s;
    }

    /** Algoritmo ANTERIOR (umbral fijo 2200, 0,9 s de silencio) para comparar. Devuelve ms hasta cortar o -1 si no detectó voz. */
    static int oldAlgo(int[] s) {
        long t = 0, lastVoice = 0, voiceMs = 0; boolean heard = false;
        for (int a : s) {
            t += 50;
            if (a > 2200) { if (heard) voiceMs += 50; heard = true; lastVoice = t; }
            if (heard && t - lastVoice > 900) return voiceMs < 500 ? -1 : (int) t;
            if (!heard && t > 8000) return -1;
            if (t > 15000) return 15000;
        }
        return heard ? 15000 : -1;
    }

    static int[] newAlgo(int[] s) {  // {ms hasta cortar, 1 si oyó frase}
        Vad v = new Vad(); int t = 0;
        for (int a : s) { t += 50; Vad.State st = v.feed(a); if (st == Vad.State.FIN || st == Vad.State.SIN_VOZ || st == Vad.State.MAXIMO) return new int[]{t, v.heard() ? 1 : 0}; }
        return new int[]{t, v.heard() ? 1 : 0};
    }

    static java.io.InputStream open(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("xi-api-key", "clave-prueba");
        c.getOutputStream().write("{\"text\":\"hola\"}".getBytes("UTF-8"));
        return c.getInputStream();
    }

    public static void main(String[] a) throws Exception {
        // 1) sala silenciosa, voz normal: los dos funcionan; el nuevo corta 100 ms antes (0,8 s de silencio)
        int[] quiet = signal(250, 6000, 400, 2500, 4000);
        int o1 = oldAlgo(quiet); int[] n1 = newAlgo(quiet);
        check(n1[1] == 1 && n1[0] <= 400 + 2500 + 900, "silencio + voz normal: fin de frase a " + n1[0] + " ms (antes " + o1 + " ms)");
        // 2) sala RUIDOSA (ruido ~2600): el anterior no ve el final y graba hasta el tope de 15 s
        int[] noisy = signal(2600, 9000, 400, 2500, 14000);
        int o2 = oldAlgo(noisy); int[] n2 = newAlgo(noisy);
        check(o2 >= 15000 && n2[1] == 1 && n2[0] < 4500, "ruido de fondo: antes grababa " + o2 + " ms (tope); ahora corta a " + n2[0] + " ms");
        // 3) voz BAJA en silencio (~1500): el anterior no la oye
        int[] soft = signal(150, 1500, 400, 2000, 3000);
        int o3 = oldAlgo(soft); int[] n3 = newAlgo(soft);
        check(o3 == -1 && n3[1] == 1, "voz baja: antes «no te he oído»; ahora detectada (fin a " + n3[0] + " ms)");
        // 4) golpe corto (100 ms) sin voz: no es una frase
        int[] knock = signal(200, 200, 0, 0, 9000); knock[20] = 9000; knock[21] = 9000;
        check(newAlgo(knock)[1] == 0, "un golpe de 100 ms no cuenta como voz");
        // 5) nadie habla: termina en 8 s sin voz
        int[] none = signal(300, 300, 0, 0, 9000);
        int[] n5 = newAlgo(none);
        check(n5[1] == 0 && n5[0] == 8000, "sin voz: deja de escuchar a los 8 s");
        // 6) el ruido cambia (se enciende un ventilador) antes de hablar: el umbral lo sigue
        Vad v = new Vad();
        for (int i = 0; i < 20; i++) v.feed(200);
        int thrQuiet = v.threshold();
        for (int i = 0; i < 60; i++) v.feed(2000);
        check(v.threshold() > thrQuiet * 2 && v.state() != Vad.State.HABLANDO, "el umbral sigue al ruido (" + thrQuiet + " → " + v.threshold() + ") y el ventilador no se toma por voz");

        // 7) robustez: 50 variaciones aleatorias por escenario (no se ajusta a una sola señal)
        int[][] sc = {{250, 6000, 1}, {2600, 9000, 1}, {150, 1500, 1}, {1200, 4500, 1}, {300, 300, 0}};
        String[] names = {"silencio", "ruido fuerte", "voz baja", "ruido medio", "sin voz"};
        for (int k = 0; k < sc.length; k++) {
            int good = 0;
            for (int seed = 1; seed <= 50; seed++) {
                rnd = new Random(seed * 7919L);
                int[] sg = signal(sc[k][0], sc[k][1], 400, sc[k][2] == 1 ? 2500 : 0, 9000);
                int[] r2 = newAlgo(sg);
                boolean right = sc[k][2] == 1 ? r2[1] == 1 && r2[0] <= 400 + 2500 + 1600 : r2[1] == 0;
                if (right) good++;
            }
            check(good >= 48, "robustez «" + names[k] + "»: " + good + "/50 variaciones correctas");
        }

        // ---- medición de tiempos
        File dir = new File(a[0]); dir.mkdirs();
        VoiceMetrics m = new VoiceMetrics(dir);
        m.start("voz", 1000); m.mark("transcrito", 2200); m.mark("respuesta", 4700); m.mark("primer_audio", 6900);
        Map<String, Object> r = m.finish(9000, "elevenlabs-mp3", 380, 41, null);
        check(Long.valueOf(1200).equals(r.get("reconocimiento_ms")) && Long.valueOf(2500).equals(r.get("interpretacion_ms"))
                && Long.valueOf(2200).equals(r.get("sintesis_hasta_sonido_ms")) && Long.valueOf(5900).equals(r.get("hasta_primera_palabra_ms")),
                "etapas medidas: reconocimiento 1,2 s · Claude 2,5 s · síntesis 2,2 s · primera palabra 5,9 s");
        m.start("voz", 0); m.mark("transcrito", 1100); m.mark("respuesta", 3500); m.mark("primer_audio", 4100); m.finish(6000, "elevenlabs-stream", 300, 40, null);
        m.start("voz", 0); m.mark("transcrito", 1000); m.mark("respuesta", 3300); m.mark("primer_audio", 3800); m.finish(5000, "elevenlabs-stream", 310, 40, null);
        m.start("texto", 0); m.mark("respuesta", 2000); m.finish(2500, "android", 100, 39, "ElevenLabs 401");
        Map<String, Object> cmp = m.compare();
        check(Long.valueOf(2).equals(Json.obj(cmp.get("elevenlabs-stream")).get("turnos")) && Long.valueOf(550).equals(Json.obj(cmp.get("elevenlabs-stream")).get("sintesis_hasta_sonido_ms")),
                "comparación por motor (medianas): stream " + cmp.get("elevenlabs-stream"));
        check(Long.valueOf(1).equals(Json.obj(cmp.get("android")).get("errores")), "los errores de voz se cuentan por motor");
        check(m.report().contains("primera palabra") && new VoiceMetrics(dir).all().size() == 4, "informe con datos guardados en el teléfono (persisten)");
        m.mark("primer_audio", 5);  // sin turno abierto no hace nada
        check(m.finish(1, "x", 0, 0, null) == null, "sin turno abierto no se registra nada inventado");
        // ---- voz en streaming contra un ElevenLabs SIMULADO (args: carpeta, puerto)
        if (a.length > 1) {
            String base = "http://127.0.0.1:" + a[1] + "/v1/text-to-speech/voz";
            final long[] written = {0};
            final List<Float> levels = new ArrayList<Float>();
            PcmStream.Sink sink = new PcmStream.Sink() {
                public void write(byte[] b, int off, int len) { written[0] += len; if (len % 2 != 0) throw new RuntimeException("muestra partida"); }
                public boolean stopped() { return false; }
                public void level(float l, long ms) { levels.add(l); }
            };
            long t0 = System.currentTimeMillis();
            Map<String, Object> st = PcmStream.pump(open(base + "/stream?output_format=pcm_22050"), sink, t0);
            long streamFirst = ((Number) st.get("primer_byte_ms")).longValue();
            long t1 = System.currentTimeMillis();
            Map<String, Object> whole = PcmStream.pump(open(base + "?output_format=pcm_22050"), new PcmStream.Sink() {
                public void write(byte[] b, int off, int len) {} public boolean stopped() { return false; } public void level(float l, long ms) {} }, t1);
            long wholeFirst = ((Number) whole.get("primer_byte_ms")).longValue();
            check(written[0] == 44100 && ((Number) st.get("trozos")).intValue() >= 10, "streaming: llegó todo el audio (" + written[0] + " bytes en " + st.get("trozos") + " trozos), sin partir muestras");
            check(streamFirst < wholeFirst / 2, "SIMULADO: primer sonido a " + streamFirst + " ms con streaming vs " + wholeFirst + " ms esperando el audio completo");
            float max = 0; for (float l : levels) max = Math.max(max, l);
            check(max > 0.5f, "volumen real calculado del audio (máx " + String.format(Locale.US, "%.2f", max) + ") para el holograma");
            final int[] count = {0};
            Map<String, Object> cut = PcmStream.pump(open(base + "/stream?output_format=pcm_22050"), new PcmStream.Sink() {
                public void write(byte[] b, int off, int len) { count[0]++; } public boolean stopped() { return count[0] >= 2; } public void level(float l, long ms) {} }, System.currentTimeMillis());
            check(Boolean.TRUE.equals(cut.get("cortado")) && count[0] == 2, "interrupción: deja de reproducir en cuanto usted habla");
        }
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
