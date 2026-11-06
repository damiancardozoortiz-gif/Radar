package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * APRENDIZAJE POR EXPERIENCIA. Cada tarea queda registrada: qué se pidió, con qué herramienta, si salió bien y,
 * si falló, el error. Los aprendizajes (de investigaciones, operaciones y fallos) se buscan antes de cada tarea
 * parecida y se le dan a Claude como contexto, para no repetir errores conocidos.
 */
public final class Experience {
    private final File dir;

    public Experience(File dataDir) { this.dir = new File(dataDir, "aprendizaje"); dir.mkdirs(); }

    /** Registra el resultado de una tarea. */
    public synchronized void record(String task, String tool, boolean ok, String error, long ms) {
        append("experiencia.jsonl", Json.map("fecha", now(), "tarea", cut(task, 200), "herramienta", tool, "ok", ok,
                "error", error == null ? null : cut(error, 300), "ms", ms));
    }

    /** Guarda un aprendizaje reutilizable (qué funcionó y por qué, o qué evitar). */
    public synchronized void learn(String area, String task, String lesson) {
        if (lesson == null || lesson.trim().isEmpty()) return;
        append(area + ".jsonl", Json.map("fecha", now(), "tarea", cut(task, 200), "aprendizaje", cut(lesson.trim(), 500)));
    }

    /** Aprendizajes y fallos previos parecidos a esta tarea (por palabras en común), los más útiles primero. */
    public synchronized List<String> relevant(String task, int n) {
        Set<String> q = new HashSet<String>(Vault.tokens(task));
        List<Object[]> scored = new ArrayList<Object[]>();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (!f.getName().endsWith(".jsonl")) continue;
            boolean exp = f.getName().equals("experiencia.jsonl");
            for (String line : lines(f)) {
                Map<String, Object> m;
                try { m = Json.obj(Json.parse(line)); } catch (Exception e) { continue; }
                if (m == null) continue;
                String text;
                if (exp) {
                    if (Boolean.TRUE.equals(m.get("ok")) || m.get("error") == null) continue;  // de la experiencia solo interesan los fallos
                    text = "Fallo anterior en «" + m.get("tarea") + "» (" + m.get("herramienta") + "): " + m.get("error");
                } else text = String.valueOf(m.get("aprendizaje"));
                int common = 0;
                for (String t : Vault.tokens(String.valueOf(m.get("tarea")) + " " + text)) if (q.contains(t)) common++;
                if (common >= 2) scored.add(new Object[]{common, text});
            }
        }
        java.util.Collections.sort(scored, new java.util.Comparator<Object[]>() { public int compare(Object[] a, Object[] b) { return (Integer) b[0] - (Integer) a[0]; } });
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (Object[] s : scored) { if (out.size() >= n) break; if (seen.add((String) s[1])) out.add((String) s[1]); }
        return out;
    }

    /** Resumen: tareas, aciertos, fallos por herramienta y errores repetidos (para el laboratorio). */
    public synchronized Map<String, Object> stats() {
        int total = 0, ok = 0;
        Map<String, Integer> failsByTool = new LinkedHashMap<String, Integer>(), errors = new LinkedHashMap<String, Integer>();
        for (String line : lines(new File(dir, "experiencia.jsonl"))) {
            Map<String, Object> m;
            try { m = Json.obj(Json.parse(line)); } catch (Exception e) { continue; }
            total++;
            if (Boolean.TRUE.equals(m.get("ok"))) { ok++; continue; }
            String t = String.valueOf(m.get("herramienta"));
            failsByTool.put(t, failsByTool.containsKey(t) ? failsByTool.get(t) + 1 : 1);
            String e = String.valueOf(m.get("error")).replaceAll("\\d+", "#");
            errors.put(e, errors.containsKey(e) ? errors.get(e) + 1 : 1);
        }
        List<Object> repeated = new ArrayList<Object>();
        for (Map.Entry<String, Integer> e : errors.entrySet()) if (e.getValue() >= 2) repeated.add(e.getKey() + " (×" + e.getValue() + ")");
        int lessons = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jsonl") && !f.getName().equals("experiencia.jsonl")) lessons += lines(f).size();
        Map<String, Object> fails = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Integer> e : failsByTool.entrySet()) fails.put(e.getKey(), (long) e.getValue());
        return Json.map("tareas", (long) total, "bien", (long) ok, "fallos", (long) (total - ok),
                "acierto_pct", total == 0 ? null : Math.round(ok * 1000.0 / total) / 10.0, "fallos_por_herramienta", fails,
                "errores_repetidos", repeated, "aprendizajes", (long) lessons);
    }

    private void append(String file, Map<String, Object> m) {
        try {
            FileOutputStream o = new FileOutputStream(new File(dir, file), true);
            o.write((Json.write(m) + "\n").getBytes("UTF-8"));
            o.close();
        } catch (IOException ignored) {}
    }

    private static List<String> lines(File f) {
        List<String> out = new ArrayList<String>();
        String s = Lab.read(f);
        if (s != null) for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l);
        return out;
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()); }
}
