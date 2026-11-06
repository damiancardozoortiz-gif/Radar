package com.radar.jarvis.core.video;

import com.radar.jarvis.core.Json;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * LÍNEA DE TIEMPO del editor de vídeo (lista de edición): clips en orden, cada uno es un tramo [inicio, fin) de un vídeo
 * fuente. Operaciones: añadir (unir), recortar, dividir, mover, borrar y deshacer. No toca los archivos: la exportación
 * (Android: MediaExtractor + MediaMuxer) lee esta lista. Tiempos en milisegundos.
 */
public final class Timeline {
    public static final class Clip {
        public final String source; public final long in, out;
        public Clip(String source, long in, long out) { this.source = source; this.in = in; this.out = out; }
        public long length() { return out - in; }
        Map<String, Object> toMap() { return Json.map("fuente", source, "inicio_ms", in, "fin_ms", out); }
    }

    public static final long MIN_CLIP_MS = 200;
    private final List<Clip> clips = new ArrayList<Clip>();
    private final LinkedList<List<Clip>> undo = new LinkedList<List<Clip>>();

    public List<Clip> clips() { return new ArrayList<Clip>(clips); }
    public int size() { return clips.size(); }

    public long duration() { long d = 0; for (Clip c : clips) d += c.length(); return d; }

    private void save() { undo.add(new ArrayList<Clip>(clips)); if (undo.size() > 50) undo.removeFirst(); }

    public boolean undo() { if (undo.isEmpty()) return false; clips.clear(); clips.addAll(undo.removeLast()); return true; }

    /** Añade un vídeo entero (o un tramo) al final: así se UNEN clips. */
    public void add(String source, long in, long out) {
        check(in, out);
        save();
        clips.add(new Clip(source, in, out));
    }

    /** ¿Qué clip y qué instante de la fuente corresponde al tiempo t de la salida? {índice, ms en la fuente} o null. */
    public long[] locate(long t) {
        long acc = 0;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            if (t < acc + c.length() || i == clips.size() - 1 && t == acc + c.length()) return new long[]{i, c.in + (t - acc)};
            acc += c.length();
        }
        return null;
    }

    /** Divide en el instante t de la salida (dos clips). */
    public boolean split(long t) {
        long[] p = locate(t);
        if (p == null) return false;
        Clip c = clips.get((int) p[0]);
        if (p[1] - c.in < MIN_CLIP_MS || c.out - p[1] < MIN_CLIP_MS) return false;
        save();
        clips.set((int) p[0], new Clip(c.source, c.in, p[1]));
        clips.add((int) p[0] + 1, new Clip(c.source, p[1], c.out));
        return true;
    }

    /** Recorta un clip a [in, out) dentro de su fuente original. */
    public void trim(int i, long in, long out) {
        check(in, out);
        save();
        Clip c = clips.get(i);
        clips.set(i, new Clip(c.source, in, out));
    }

    /** Quita el principio (hasta t) o el final (desde t) del clip que hay en el instante t de la salida. */
    public boolean trimAt(long t, boolean keepAfter) {
        long[] p = locate(t);
        if (p == null) return false;
        Clip c = clips.get((int) p[0]);
        long in = keepAfter ? p[1] : c.in, out = keepAfter ? c.out : p[1];
        if (out - in < MIN_CLIP_MS) return false;
        save();
        clips.set((int) p[0], new Clip(c.source, in, out));
        return true;
    }

    public void remove(int i) { save(); clips.remove(i); }

    public void move(int from, int to) { save(); Clip c = clips.remove(from); clips.add(Math.max(0, Math.min(to, clips.size())), c); }

    /** Sustituye toda la lista (p. ej. el plan de un Short), guardando la anterior para deshacer. */
    public void replace(List<Clip> nw) { save(); clips.clear(); clips.addAll(nw); }

    /** Tiempo en la SALIDA de un instante de la fuente (para subtítulos). -1 si ese instante quedó fuera del montaje. */
    public long outputTime(String source, long srcMs) {
        long acc = 0;
        for (Clip c : clips) {
            if (c.source.equals(source) && srcMs >= c.in && srcMs < c.out) return acc + (srcMs - c.in);
            acc += c.length();
        }
        return -1;
    }

    public List<Object> toList() { List<Object> l = new ArrayList<Object>(); for (Clip c : clips) l.add(c.toMap()); return l; }

    public static Timeline fromList(List<Object> l) {
        Timeline t = new Timeline();
        if (l != null) for (Object o : l) {
            Map<String, Object> m = Json.obj(o);
            t.clips.add(new Clip(String.valueOf(m.get("fuente")), ((Number) m.get("inicio_ms")).longValue(), ((Number) m.get("fin_ms")).longValue()));
        }
        return t;
    }

    private static void check(long in, long out) {
        if (in < 0 || out - in < MIN_CLIP_MS) throw new IllegalArgumentException("tramo no válido: " + in + "–" + out + " ms");
    }

    /**
     * Sin recodificar, un corte solo puede EMPEZAR en un fotograma clave. Devuelve el clave anterior o igual a t
     * (keyframes ordenados, ms). Así se sabe de antemano cuánto antes empezará realmente cada clip.
     */
    public static long snapToKeyframe(long t, long[] keyframes) {
        long best = 0;
        for (long k : keyframes) { if (k <= t) best = k; else break; }
        return best;
    }
}
