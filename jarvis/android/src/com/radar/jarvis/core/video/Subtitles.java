package com.radar.jarvis.core.video;

import com.radar.jarvis.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SUBTÍTULOS a partir de la transcripción con tiempos por palabra (ElevenLabs Scribe: words[{text, start, end, type}]).
 * Agrupa en líneas legibles (máx. 42 caracteres y 3,5 s; corta en pausas y signos), las traslada a los tiempos del montaje
 * (quita lo que se cortó) y genera SRT. No inventa texto: solo usa las palabras transcritas.
 */
public final class Subtitles {
    private Subtitles() {}

    public static final int MAX_CHARS = 42;
    public static final long MAX_MS = 3500, PAUSE_MS = 450;

    /** Palabra con tiempos en ms de la fuente. */
    public static final class Word {
        public final String text; public final long start, end;
        public Word(String text, long start, long end) { this.text = text; this.start = start; this.end = end; }
    }

    public static final class Cue {
        public final long start, end; public final String text;
        Cue(long s, long e, String t) { start = s; end = e; text = t; }
    }

    /** Lee las palabras de la respuesta de Scribe (segundos → ms; ignora espacios y eventos de audio). */
    public static List<Word> fromScribe(Map<String, Object> resp) {
        List<Word> out = new ArrayList<Word>();
        List<Object> ws = resp == null ? null : Json.arr(resp.get("words"));
        if (ws != null) for (Object o : ws) {
            Map<String, Object> w = Json.obj(o);
            if (w == null || (w.get("type") != null && !"word".equals(w.get("type")))) continue;
            String t = String.valueOf(w.get("text")).trim();
            if (t.isEmpty() || !(w.get("start") instanceof Number) || !(w.get("end") instanceof Number)) continue;
            out.add(new Word(t, Math.round(((Number) w.get("start")).doubleValue() * 1000), Math.round(((Number) w.get("end")).doubleValue() * 1000)));
        }
        return out;
    }

    /** Palabras de la fuente → palabras en tiempos del montaje (las cortadas desaparecen). */
    public static List<Word> remap(List<Word> words, String source, Timeline tl) {
        List<Word> out = new ArrayList<Word>();
        for (Word w : words) {
            long s = tl.outputTime(source, w.start), e = tl.outputTime(source, Math.max(w.start, w.end - 1));
            if (s < 0 || e < 0 || e < s) continue;  // la palabra (o parte) quedó fuera: no se subtitula a medias
            out.add(new Word(w.text, s, e + 1));
        }
        return out;
    }

    /** Agrupa en líneas. */
    public static List<Cue> cues(List<Word> words) {
        List<Cue> out = new ArrayList<Cue>();
        StringBuilder line = new StringBuilder();
        long start = -1, end = -1;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            boolean gap = end >= 0 && w.start - end > PAUSE_MS;
            boolean full = line.length() + 1 + w.text.length() > MAX_CHARS || start >= 0 && w.end - start > MAX_MS;
            if (start >= 0 && (gap || full)) { out.add(new Cue(start, end, line.toString())); line.setLength(0); start = -1; }
            if (start < 0) start = w.start;
            if (line.length() > 0) line.append(' ');
            line.append(w.text);
            end = w.end;
            if (w.text.matches(".*[.!?…]$")) { out.add(new Cue(start, end, line.toString())); line.setLength(0); start = -1; }
        }
        if (start >= 0) out.add(new Cue(start, end, line.toString()));
        return out;
    }

    public static String srt(List<Cue> cues) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            b.append(i + 1).append('\n').append(ts(c.start)).append(" --> ").append(ts(Math.max(c.end, c.start + 300))).append('\n').append(c.text).append("\n\n");
        }
        return b.toString();
    }

    static String ts(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** Texto plano con marcas de tiempo (para que Claude proponga cortes sobre lo que realmente se dice). */
    public static String transcriptWithTimes(List<Word> words, int maxChars) {
        StringBuilder b = new StringBuilder();
        for (Cue c : cues(words)) {
            String l = String.format(Locale.US, "[%.1f-%.1f] %s\n", c.start / 1000.0, c.end / 1000.0, c.text);
            if (b.length() + l.length() > maxChars) { b.append("[…transcripción recortada…]\n"); break; }
            b.append(l);
        }
        return b.toString();
    }
}
