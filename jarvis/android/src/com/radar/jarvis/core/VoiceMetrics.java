package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MEDICIÓN REAL DE LA VOZ. Cada turno de conversación marca sus etapas con la hora real:
 *   fin_habla (usted dejó de hablar) → transcrito → respuesta (Claude) → primer_audio (primera muestra que suena) → fin_audio.
 * Con eso se calculan: reconocimiento, interpretación, síntesis hasta el primer sonido, y TIEMPO HASTA LA PRIMERA PALABRA.
 * Cada turno guarda también el motor de voz usado (p. ej. «elevenlabs-mp3» o «elevenlabs-stream»), la CPU y la RAM de la app,
 * para comparar antes y después de cada optimización con datos del propio teléfono. Archivo: files/voz/metricas.jsonl.
 */
public final class VoiceMetrics {
    public static final String[] STAGES = {"fin_habla", "transcrito", "respuesta", "primer_audio", "fin_audio"};

    private final File file;
    private Map<String, Object> turn;

    public VoiceMetrics(File dataDir) {
        File d = new File(dataDir, "voz");
        d.mkdirs();
        file = new File(d, "metricas.jsonl");
    }

    /** Empieza un turno (al terminar usted de hablar, o al escribir). */
    public synchronized void start(String origin, long nowMs) {
        turn = Json.map("fecha", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(nowMs)), "origen", origin, "t0", nowMs);
        turn.put("fin_habla", nowMs);
    }

    public synchronized boolean active() { return turn != null; }

    /** Marca una etapa (la primera vez cuenta; las siguientes se ignoran). */
    public synchronized void mark(String stage, long nowMs) {
        if (turn == null || turn.containsKey(stage)) return;
        turn.put(stage, nowMs);
    }

    public synchronized void note(String key, Object value) { if (turn != null) turn.put(key, value); }

    /** Cierra el turno, calcula las duraciones y lo guarda. Devuelve el registro (o null si no había turno). */
    public synchronized Map<String, Object> finish(long nowMs, String engine, long cpuMs, long ramMb, String error) {
        if (turn == null) return null;
        mark("fin_audio", nowMs);
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("fecha", turn.get("fecha"));
        r.put("origen", turn.get("origen"));
        r.put("motor", engine);
        r.put("reconocimiento_ms", d("fin_habla", "transcrito"));
        r.put("interpretacion_ms", d("transcrito", "respuesta"));
        r.put("sintesis_hasta_sonido_ms", d("respuesta", "primer_audio"));
        r.put("hasta_primera_palabra_ms", d("fin_habla", "primer_audio"));
        r.put("total_ms", d("fin_habla", "fin_audio"));
        r.put("cpu_ms", cpuMs);
        r.put("ram_mb", ramMb);
        if (error != null) r.put("error", error);
        for (String k : new String[]{"transcripcion", "palabras"}) if (turn.containsKey(k)) r.put(k, turn.get(k));
        turn = null;
        try { FileOutputStream o = new FileOutputStream(file, true); o.write((Json.write(r) + "\n").getBytes("UTF-8")); o.close(); } catch (IOException ignored) {}
        return r;
    }

    private Object d(String a, String b) {
        Object x = turn.get(a), y = turn.get(b);
        return x instanceof Number && y instanceof Number ? (Object) Math.max(0L, ((Number) y).longValue() - ((Number) x).longValue()) : null;
    }

    public synchronized List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String s = Lab.read(file);
        if (s != null) for (String l : s.split("\n")) { try { Map<String, Object> m = Json.obj(Json.parse(l)); if (m != null) out.add(m); } catch (Exception ignored) {} }
        return out;
    }

    /** Medianas por motor de voz: la comparación «antes / después» con los turnos reales. */
    public synchronized Map<String, Object> compare() {
        Map<String, List<Map<String, Object>>> by = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> m : all()) {
            String k = String.valueOf(m.get("motor"));
            if (!by.containsKey(k)) by.put(k, new ArrayList<Map<String, Object>>());
            by.get(k).add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<Map<String, Object>>> e : by.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("turnos", (long) e.getValue().size());
            for (String f : new String[]{"reconocimiento_ms", "interpretacion_ms", "sintesis_hasta_sonido_ms", "hasta_primera_palabra_ms", "cpu_ms", "ram_mb"})
                s.put(f, median(e.getValue(), f));
            long errors = 0;
            for (Map<String, Object> m : e.getValue()) if (m.get("error") != null) errors++;
            s.put("errores", errors);
            out.put(e.getKey(), s);
        }
        return out;
    }

    static Long median(List<Map<String, Object>> l, String f) {
        List<Long> v = new ArrayList<Long>();
        for (Map<String, Object> m : l) if (m.get(f) instanceof Number) v.add(((Number) m.get(f)).longValue());
        if (v.isEmpty()) return null;
        Collections.sort(v);
        return v.size() % 2 == 1 ? v.get(v.size() / 2) : (v.get(v.size() / 2 - 1) + v.get(v.size() / 2)) / 2;
    }

    /** Resumen hablado/escrito con datos reales. */
    public synchronized String report() {
        Map<String, Object> c = compare();
        if (c.isEmpty()) return "Todavía no tengo mediciones de voz: hable conmigo y las registro.";
        StringBuilder b = new StringBuilder("Mediciones reales de voz (medianas):");
        for (Map.Entry<String, Object> e : c.entrySet()) {
            Map<String, Object> s = Json.obj(e.getValue());
            b.append("\n• ").append(e.getKey()).append(" (").append(s.get("turnos")).append(" turnos): primera palabra ").append(ms(s.get("hasta_primera_palabra_ms")))
             .append(" · reconocimiento ").append(ms(s.get("reconocimiento_ms"))).append(" · Claude ").append(ms(s.get("interpretacion_ms")))
             .append(" · síntesis ").append(ms(s.get("sintesis_hasta_sonido_ms"))).append(" · CPU ").append(s.get("cpu_ms") == null ? "?" : s.get("cpu_ms") + " ms")
             .append(" · RAM ").append(s.get("ram_mb") == null ? "?" : s.get("ram_mb") + " MB").append(" · errores ").append(s.get("errores"));
        }
        return b.toString();
    }

    private static String ms(Object o) { return o == null ? "?" : String.format(Locale.US, "%.1f s", ((Number) o).longValue() / 1000.0); }
}
