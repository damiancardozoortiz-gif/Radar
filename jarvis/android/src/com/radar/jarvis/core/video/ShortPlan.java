package com.radar.jarvis.core.video;

import com.radar.jarvis.core.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PLAN DE UN SHORT («convertí este vídeo en un Short de 30 segundos»).
 * - Con IA: Claude propone segmentos sobre la transcripción con tiempos; aquí se VALIDAN contra la realidad: se ordenan,
 *   se recortan a la duración del vídeo, se funden los solapados, no se corta a mitad de palabra y el total no pasa del
 *   objetivo (+10 %). Lo que no cumple se descarta y se dice.
 * - Sin IA (o si la IA falla): se eligen los tramos con más habla por segundo hasta llenar el objetivo.
 * El resultado es una Timeline que se previsualiza y solo se exporta con la aprobación del usuario.
 */
public final class ShortPlan {
    private ShortPlan() {}

    public static final class Result {
        public final Timeline timeline; public final List<String> notes; public final String source;
        Result(Timeline t, List<String> n, String s) { timeline = t; notes = n; source = s; }
    }

    /** segments: [{inicio_s, fin_s, motivo}] (lo que propone Claude). */
    public static Result fromAi(List<Object> segments, String video, long durationMs, long targetMs, List<Subtitles.Word> words) {
        List<String> notes = new ArrayList<String>();
        List<long[]> segs = new ArrayList<long[]>();
        if (segments != null) for (Object o : segments) {
            Map<String, Object> s = Json.obj(o);
            if (s == null || !(s.get("inicio_s") instanceof Number) || !(s.get("fin_s") instanceof Number)) { notes.add("segmento sin tiempos: descartado"); continue; }
            long a = Math.round(((Number) s.get("inicio_s")).doubleValue() * 1000), b = Math.round(((Number) s.get("fin_s")).doubleValue() * 1000);
            if (a >= durationMs) { notes.add("segmento fuera del vídeo (" + a / 1000.0 + " s): descartado"); continue; }
            a = Math.max(0, a); b = Math.min(durationMs, b);
            if (b - a < Timeline.MIN_CLIP_MS) { notes.add("segmento demasiado corto: descartado"); continue; }
            segs.add(new long[]{a, b});
        }
        return build(segs, video, durationMs, targetMs, words, notes, "IA (validada)");
    }

    /** Sin IA: ventanas de ~5 s con más palabras por segundo, sin solaparse, hasta llenar el objetivo. */
    public static Result heuristic(String video, long durationMs, long targetMs, List<Subtitles.Word> words) {
        List<String> notes = new ArrayList<String>();
        long win = 5000;
        List<long[]> cand = new ArrayList<long[]>();
        for (long s = 0; s + 1000 <= durationMs; s += 1000) {
            long e = Math.min(durationMs, s + win), n = 0;
            for (Subtitles.Word w : words) if (w.start >= s && w.end <= e) n++;
            cand.add(new long[]{s, e, n});
        }
        Collections.sort(cand, new Comparator<long[]>() { public int compare(long[] x, long[] y) { return x[2] != y[2] ? Long.compare(y[2], x[2]) : Long.compare(x[0], y[0]); } });
        List<long[]> pick = new ArrayList<long[]>();
        long total = 0;
        for (long[] c : cand) {
            if (total >= targetMs) break;
            boolean overlap = false;
            for (long[] p : pick) if (c[0] < p[1] && p[0] < c[1]) overlap = true;
            if (overlap) continue;
            pick.add(new long[]{c[0], c[1]});
            total += c[1] - c[0];
        }
        if (words.isEmpty()) notes.add("sin transcripción: se tomaron los primeros segundos");
        return build(pick, video, durationMs, targetMs, words, notes, "automático (más habla por segundo)");
    }

    private static Result build(List<long[]> segs, String video, long durationMs, long targetMs, List<Subtitles.Word> words, List<String> notes, String src) {
        Collections.sort(segs, new Comparator<long[]>() { public int compare(long[] x, long[] y) { return Long.compare(x[0], y[0]); } });
        // no cortar palabras: el inicio retrocede al comienzo de la palabra; el final avanza a su fin
        for (long[] s : segs) for (Subtitles.Word w : words) {
            if (w.start < s[0] && w.end > s[0]) s[0] = w.start;
            if (w.start < s[1] && w.end > s[1]) s[1] = Math.min(durationMs, w.end);
        }
        List<long[]> merged = new ArrayList<long[]>();
        for (long[] s : segs) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) { long[] m = merged.get(merged.size() - 1); m[1] = Math.max(m[1], s[1]); notes.add("segmentos solapados: unidos"); }
            else merged.add(new long[]{s[0], s[1]});
        }
        Timeline t = new Timeline();
        long total = 0, max = targetMs + targetMs / 10;
        for (long[] s : merged) {
            if (total >= max) { notes.add("segmento descartado: ya se llegó a la duración objetivo"); continue; }
            long b = Math.min(s[1], s[0] + (max - total));
            if (b < s[1]) { // si hay que acortar, que termine al final de una palabra
                long lastEnd = -1;  // termina al final de la última palabra completa que cabe
                for (Subtitles.Word w : words) if (w.end <= b && w.start >= s[0] && w.end > lastEnd) lastEnd = w.end;
                if (lastEnd - s[0] >= Timeline.MIN_CLIP_MS) b = lastEnd;
                notes.add("último segmento acortado para no pasar de " + targetMs / 1000 + " s");
            }
            if (b - s[0] < Timeline.MIN_CLIP_MS) continue;
            t.add(video, s[0], b);
            total += b - s[0];
        }
        if (t.size() == 0 && durationMs >= Timeline.MIN_CLIP_MS) { t.add(video, 0, Math.min(durationMs, targetMs)); notes.add("ningún segmento válido: se tomó el comienzo"); }
        return new Result(t, notes, src);
    }
}
