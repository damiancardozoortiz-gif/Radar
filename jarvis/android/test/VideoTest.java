import com.radar.jarvis.core.*;
import com.radar.jarvis.core.video.*;
import java.io.File;
import java.util.*;

/** Editor de vídeo (núcleo, sin Android): línea de tiempo, fotogramas clave, subtítulos, plan de Short (IA validada).
 *  Args: carpeta temporal, assets, puerto del Claude SIMULADO. */
public class VideoTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }

    static List<Subtitles.Word> speech() {  // 60 s de vídeo: habla de 1,5 a 15 s y de 32 a 50 s
        List<Subtitles.Word> w = new ArrayList<Subtitles.Word>();
        String[] a = "Hoy les cuento tres claves del seguro de hogar. La primera es revisar la suma asegurada cada año. La segunda es".split(" ");
        long t = 1500; for (String x : a) { w.add(new Subtitles.Word(x, t, t + 450)); t += 600; }
        String[] b = "Y la tercera, la más importante, es leer las exclusiones antes de firmar. Así evitan sorpresas. Gracias por mirar.".split(" ");
        t = 32000; for (String x : b) { w.add(new Subtitles.Word(x, t, t + 500)); t += 650; }
        return w;
    }

    public static void main(String[] a) throws Exception {
        // ---- línea de tiempo
        Timeline tl = new Timeline();
        tl.add("A", 0, 10000); tl.add("B", 0, 5000);
        check(tl.duration() == 15000 && tl.size() == 2, "unir dos vídeos: 2 clips, 15 s");
        check(tl.split(4000) && tl.size() == 3 && tl.clips().get(0).out == 4000 && tl.clips().get(1).in == 4000, "dividir a los 4 s");
        check(tl.trimAt(12000, true) && tl.duration() == 13000 && tl.clips().get(2).in == 2000, "recortar el comienzo del clip de B hasta el cursor");
        tl.remove(0);
        check(tl.duration() == 9000 && "A".equals(tl.clips().get(0).source) && tl.clips().get(0).in == 4000, "borrar un clip");
        tl.move(1, 0);
        check("B".equals(tl.clips().get(0).source), "mover un clip");
        check(tl.undo() && "A".equals(tl.clips().get(0).source) && tl.undo() && tl.duration() == 13000, "deshacer dos veces");
        check(tl.outputTime("A", 1000) == 1000 && tl.outputTime("A", 4500) == 4500 && tl.outputTime("B", 1000) == -1 && tl.outputTime("B", 3000) == 11000,
                "tiempo de la fuente → tiempo del montaje (lo cortado no existe)");
        check(!tl.split(100), "no se crean clips de menos de 0,2 s");
        try { tl.add("C", 500, 600); check(false, "tramo inválido aceptado"); } catch (IllegalArgumentException e) { check(true, "tramo de 0,1 s: rechazado"); }
        Timeline back = Timeline.fromList(tl.toList());
        check(back.duration() == tl.duration() && back.size() == tl.size(), "la edición se guarda y se recupera (JSON)");
        check(Timeline.snapToKeyframe(4300, new long[]{0, 2000, 4000, 6000}) == 4000 && Timeline.snapToKeyframe(1999, new long[]{0, 2000}) == 0,
                "sin recodificar, el corte empieza en el fotograma clave anterior (4,3 s → 4,0 s)");

        // ---- subtítulos
        Map<String, Object> scribe = Json.obj(Json.parse("{\"text\":\"Hola, señor.\",\"words\":[{\"text\":\"Hola,\",\"start\":0.12,\"end\":0.5,\"type\":\"word\"},{\"text\":\" \",\"start\":0.5,\"end\":0.55,\"type\":\"spacing\"},{\"text\":\"señor.\",\"start\":0.55,\"end\":1.0,\"type\":\"word\"},{\"text\":\"(risas)\",\"start\":1.1,\"end\":1.5,\"type\":\"audio_event\"}]}"));
        List<Subtitles.Word> sw = Subtitles.fromScribe(scribe);
        check(sw.size() == 2 && sw.get(0).start == 120 && sw.get(1).end == 1000, "palabras de Scribe con tiempos (sin espacios ni eventos de audio)");
        List<Subtitles.Word> words = speech();
        List<Subtitles.Cue> cues = Subtitles.cues(words);
        boolean maxOk = true; for (Subtitles.Cue c : cues) if (c.text.length() > Subtitles.MAX_CHARS || c.end - c.start > Subtitles.MAX_MS + 600) maxOk = false;
        check(cues.size() >= 5 && maxOk, "líneas legibles: " + cues.size() + " subtítulos, ninguno de más de 42 caracteres ni 3,5 s");
        String srt = Subtitles.srt(cues);
        check(srt.startsWith("1\n00:00:01,500 --> ") && srt.contains("\n\n2\n"), "formato SRT válido");
        Timeline cut = new Timeline(); cut.add("V", 1500, 10000); cut.add("V", 32000, 45000);
        List<Subtitles.Word> rw = Subtitles.remap(words, "V", cut);
        String joined = ""; for (Subtitles.Word x : rw) joined += x.text + " ";
        check(!joined.contains("segunda") && joined.contains("tercera") && rw.get(0).start == 0, "subtítulos sincronizados con el montaje: lo cortado no aparece y todo se desplaza");

        // ---- plan de Short: propuesta de IA validada contra el vídeo real
        List<Object> ai = Json.arr(Json.parse("[{\"inicio_s\":1.7,\"fin_s\":9.0},{\"inicio_s\":8.0,\"fin_s\":14.2},{\"inicio_s\":99,\"fin_s\":120},{\"inicio_s\":30,\"fin_s\":55},{\"foo\":1}]"));
        ShortPlan.Result r = ShortPlan.fromAi(ai, "V", 60000, 30000, words);
        long total = r.timeline.duration();
        check(total <= 33000 && total >= 25000, "Short de " + total / 1000.0 + " s (objetivo 30 s, máx. +10 %)");
        check(r.notes.toString().contains("fuera del vídeo") && r.notes.toString().contains("sin tiempos") && r.notes.toString().contains("solapados"),
                "segmentos inventados, sin tiempos o solapados: se descartan o unen y se informa → " + r.notes);
        Timeline.Clip first = r.timeline.clips().get(0);
        check(first.in == 1500, "no se corta a mitad de palabra: el inicio 1,7 s retrocede a 1,5 s (comienzo de «Hoy»)");
        boolean endsOnWord = false; long lastOut = r.timeline.clips().get(r.timeline.size() - 1).out;
        for (Subtitles.Word w : words) if (w.end == lastOut) endsOnWord = true;
        check(endsOnWord, "el último segmento, acortado, termina al final de una palabra (" + lastOut + " ms)");
        ShortPlan.Result h = ShortPlan.heuristic("V", 60000, 30000, words);
        boolean inSpeech = true;
        for (Timeline.Clip c : h.timeline.clips()) if (c.out <= 1500 || (c.in >= 15000 && c.out <= 32000)) inSpeech = false;
        check(h.timeline.duration() <= 33000 && inSpeech, "sin IA: elige tramos con habla (" + h.timeline.duration() / 1000.0 + " s), no silencios");
        ShortPlan.Result none = ShortPlan.fromAi(Json.arr(Json.parse("[{\"inicio_s\":200,\"fin_s\":210}]")), "V", 60000, 30000, words);
        check(none.timeline.size() == 1 && none.notes.toString().contains("ningún segmento válido"), "si la IA no propone nada válido, se dice y se toma el comienzo");

        // ---- Claude SIMULADO propone; el núcleo valida
        File data = new File(a[0]); data.mkdirs();
        final File assets = new File(a[1]);
        Brain b = new Brain(data, new Vault.Source() {
            public String read(String p) throws java.io.IOException { return new String(java.nio.file.Files.readAllBytes(new File(assets, p).toPath()), "UTF-8"); }
            public String[] list(String d) { String[] l = new File(assets, d).list(); return l == null ? new String[0] : l; }
        });
        b.apiKey = "sk-ant-prueba"; b.baseUrl = "http://127.0.0.1:" + a[2];
        Map<String, Object> p = b.videoPlan("convertí este vídeo en un Short de 30 segundos", Subtitles.transcriptWithTimes(words, 8000), 60000, 30000);
        ShortPlan.Result pr = ShortPlan.fromAi(Json.arr(p.get("segments")), "V", 60000, 30000, words);
        check("claude".equals(p.get("brain")) && p.get("title") != null && pr.timeline.duration() <= 33000 && pr.notes.toString().contains("fuera del vídeo"),
                "Claude propone (título «" + p.get("title") + "») y el segmento inventado se descarta");
        b.setPause(true, "prueba");
        check(Boolean.TRUE.equals(b.videoPlan("x", "", 1000, 1000).get("paused")), "en PAUSA no se planifica nada");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
