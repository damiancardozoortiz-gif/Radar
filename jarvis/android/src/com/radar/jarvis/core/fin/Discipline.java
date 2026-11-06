package com.radar.jarvis.core.fin;

import com.radar.jarvis.core.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REGLA DE AUTODISCIPLINA (JARVIS 3.0). Se aplica ANTES de cualquier orden, además de Risk.check.
 *
 * Ciclo de operaciones = las operaciones desde la última autorización expresa del señor Rafael.
 *  - Ganancias: hasta 3 operaciones por ciclo; una 4.ª solo si esas 3 cerraron con resultado neto positivo,
 *    y pasando otra vez todos los controles de riesgo. Tras la 4.ª (o tras 3 que no fueron todas ganadoras) el ciclo
 *    termina y hace falta una nueva autorización.
 *  - Pérdidas: con 2 operaciones perdedoras en el ciclo, BLOQUEO inmediato (demo y real), informe de pérdidas y
 *    nada se reactiva solo: hace falta autorización expresa tras revisar el riesgo.
 *  - Volumen: nunca más que el lote que fija el riesgo por operación; tras una pérdida, nunca más volumen que la anterior.
 *  - PAUSA bloquea antes que todo. Los resultados DEMO y REALES se registran y se muestran por separado.
 * Ni JARVIS ni Claude pueden reactivar, cambiar estas reglas ni los límites: solo el usuario desde el panel.
 */
public final class Discipline {
    public static final int WINS_FOR_FOURTH = 3, MAX_CYCLE = 4, MAX_LOSSES = 2;

    /** Operación cerrada con todo lo que pide el registro de aprendizaje. */
    public static final class Trade {
        public String id, account = "demo", symbol, tf, side, strategy = "", entryReason = "", exitReason = "", conditions = "";
        public long openMs, closeMs, recordedMs;
        public double entry, exit, lots, sl, tp, net, commission, spread;

        public Map<String, Object> toMap() {
            return Json.map("id", id, "cuenta", account, "instrumento", symbol, "temporalidad", tf, "direccion", side,
                    "apertura", fmt(openMs), "cierre", fmt(closeMs), "apertura_ms", openMs, "cierre_ms", closeMs,
                    "entrada", entry, "salida", exit, "volumen", lots, "stop_loss", sl, "take_profit", tp,
                    "resultado_neto", net, "registrado_ms", recordedMs, "comisiones", commission, "spread", spread, "condiciones_mercado", conditions,
                    "estrategia", strategy, "motivo_entrada", entryReason, "motivo_salida", exitReason);
        }

        public static Trade fromMap(Map<String, Object> m) {
            Trade t = new Trade();
            t.id = Json.str(m.get("id")); t.account = str(m, "cuenta", "demo"); t.symbol = Json.str(m.get("instrumento"));
            t.tf = Json.str(m.get("temporalidad")); t.side = Json.str(m.get("direccion"));
            t.openMs = lng(m, "apertura_ms"); t.closeMs = lng(m, "cierre_ms"); t.recordedMs = lng(m, "registrado_ms");
            t.entry = num(m, "entrada"); t.exit = num(m, "salida"); t.lots = num(m, "volumen"); t.sl = num(m, "stop_loss");
            t.tp = num(m, "take_profit"); t.net = num(m, "resultado_neto"); t.commission = num(m, "comisiones"); t.spread = num(m, "spread");
            t.conditions = str(m, "condiciones_mercado", ""); t.strategy = str(m, "estrategia", "");
            t.entryReason = str(m, "motivo_entrada", ""); t.exitReason = str(m, "motivo_salida", "");
            return t;
        }

        /** Lo que falta para que el registro sea válido (vacío = válido). */
        public List<String> missing() {
            List<String> f = new ArrayList<String>();
            if (empty(symbol)) f.add("instrumento");
            if (empty(tf)) f.add("temporalidad");
            if (!"buy".equals(side) && !"sell".equals(side)) f.add("dirección");
            if (!"demo".equals(account) && !"real".equals(account)) f.add("tipo de cuenta");
            if (entry <= 0) f.add("precio de entrada");
            if (exit <= 0) f.add("precio de salida");
            if (lots <= 0) f.add("volumen");
            if (sl <= 0) f.add("stop loss");
            if (closeMs <= 0) f.add("fecha de cierre");
            if (empty(strategy)) f.add("estrategia");
            if (empty(entryReason)) f.add("motivo de entrada");
            if (empty(exitReason)) f.add("motivo de salida");
            return f;
        }
    }

    private final File dir;
    private Map<String, Object> state;

    public Discipline(File dataDir) {
        this.dir = new File(dataDir, "finanzas");
        dir.mkdirs();
        state = Json.obj(readJson(new File(dir, "disciplina.json")));
        if (state == null) state = new LinkedHashMap<String, Object>();
    }

    // ------------------------------------------------------------------ registro
    public synchronized List<Trade> trades() {
        List<Trade> out = new ArrayList<Trade>();
        for (String line : readLines(new File(dir, "operaciones.jsonl"))) {
            try { out.add(Trade.fromMap(Json.obj(Json.parse(line)))); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Registra una operación cerrada. Si con ella se llega a 2 pérdidas en el ciclo, bloquea y genera el informe. */
    public synchronized Map<String, Object> record(Trade t) throws IOException {
        List<String> miss = t.missing();
        if (!miss.isEmpty()) throw new IOException("faltan datos: " + join(miss));
        t.recordedMs = System.currentTimeMillis();  // el ciclo cuenta por fecha de registro: una fecha de cierre antigua no esquiva la regla
        if (t.id == null || t.id.isEmpty()) t.id = "op-" + t.recordedMs + "-" + Long.toHexString(System.nanoTime() & 0xffff);
        append(new File(dir, "operaciones.jsonl"), Json.write(t.toMap()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Trade> cyc = cycle();
        int losses = 0;
        for (Trade x : cyc) if (x.net < 0) losses++;
        if (losses >= MAX_LOSSES && !blocked()) {
            String report = lossReport(cyc);
            state.put("bloqueado", true);
            state.put("motivo_bloqueo", MAX_LOSSES + " operaciones perdedoras en el ciclo");
            state.put("bloqueado_en", fmt(System.currentTimeMillis()));
            state.put("informe", report);
            save();
            out.put("bloqueo", state.get("motivo_bloqueo"));
            out.put("informe", report);
        }
        out.put("id", t.id);
        return out;
    }

    /** Operaciones del ciclo actual (registradas después de la última autorización). */
    public synchronized List<Trade> cycle() {
        long since = authorizedMs();
        List<Trade> out = new ArrayList<Trade>();
        for (Trade t : trades()) if (since > 0 && t.recordedMs >= since) out.add(t);
        return out;
    }

    // ------------------------------------------------------------------ autorización (solo el usuario)
    public synchronized boolean authorized() { return authorizedMs() > 0; }
    public synchronized boolean blocked() { return Boolean.TRUE.equals(state.get("bloqueado")); }
    long authorizedMs() { Object v = state.get("autorizado_ms"); return v instanceof Number ? ((Number) v).longValue() : 0; }

    /**
     * Autorización expresa del usuario para abrir un ciclo nuevo, tras revisar el riesgo.
     * reviewed: el usuario confirmó que revisó límites e informe. Llamar SOLO desde una acción del usuario en el panel.
     */
    public synchronized void authorize(boolean reviewed, String note, long nowMs) throws IOException {
        if (!reviewed) throw new IOException("la reactivación exige revisar el riesgo y confirmarlo");
        state.put("autorizado_ms", nowMs);
        state.put("autorizado_en", fmt(nowMs));
        state.put("nota_autorizacion", note == null ? "" : note);
        state.put("bloqueado", false);
        state.remove("motivo_bloqueo");
        save();
        append(new File(dir, "autorizaciones.jsonl"), Json.write(Json.map("fecha", fmt(nowMs), "nota", note)));
    }

    // ------------------------------------------------------------------ control ANTES de la orden
    /**
     * Motivos por los que NO se puede enviar la orden (vacío = la regla de disciplina lo permite; Risk.check va aparte).
     * openInCycle: órdenes ya enviadas en este ciclo que siguen abiertas. maxLotsByRisk: el lote que fija Risk.lots.
     */
    public synchronized List<String> check(Risk.Order o, boolean paused, int openInCycle, double maxLotsByRisk) {
        List<String> no = new ArrayList<String>();
        if (paused) no.add("PAUSA activa (bloqueo prioritario)");
        if (blocked()) no.add("BLOQUEO por " + state.get("motivo_bloqueo") + ": hace falta su autorización expresa tras revisar el riesgo");
        else if (!authorized()) no.add("no hay un ciclo autorizado: autorice antes, tras revisar el riesgo");
        List<Trade> cyc = cycle();
        int n = cyc.size() + openInCycle, wins = 0, losses = 0;
        for (Trade t : cyc) { if (t.net > 0) wins++; if (t.net < 0) losses++; }
        if (!blocked() && authorized()) {
            if (losses >= MAX_LOSSES) no.add(MAX_LOSSES + " operaciones perdedoras: no se abren nuevas entradas");
            if (n >= MAX_CYCLE) no.add("ciclo completo (" + MAX_CYCLE + " operaciones): hace falta una nueva autorización");
            else if (n == WINS_FOR_FOURTH) {
                if (openInCycle > 0) no.add("la 4.ª operación solo se permite cuando las 3 anteriores estén cerradas");
                else if (wins < WINS_FOR_FOURTH) no.add("la 4.ª operación solo se permite tras 3 ganadoras netas (" + wins + " de 3): el ciclo terminó");
            }
        }
        if (maxLotsByRisk > 0 && o.lots > maxLotsByRisk + 1e-9)
            no.add(String.format(Locale.US, "volumen %.2f mayor que el que fija el riesgo (%.2f): no se aumenta por ganar ni para recuperar", o.lots, maxLotsByRisk));
        Trade last = cyc.isEmpty() ? lastTrade() : cyc.get(cyc.size() - 1);
        if (last != null && last.net < 0 && o.lots > last.lots + 1e-9)
            no.add(String.format(Locale.US, "tras una pérdida el volumen no puede subir (anterior %.2f, ahora %.2f)", last.lots, o.lots));
        return no;
    }

    private Trade lastTrade() { List<Trade> all = trades(); return all.isEmpty() ? null : all.get(all.size() - 1); }

    // ------------------------------------------------------------------ panel
    /** Resumen para el panel: acumulados por cuenta, estado, próxima operación permitida. */
    public synchronized Map<String, Object> panel() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String acc : new String[]{"demo", "real"}) {
            double gains = 0, losses = 0; int w = 0, l = 0, n = 0;
            for (Trade t : trades()) {
                if (!acc.equals(t.account)) continue;
                n++;
                if (t.net > 0) { gains += t.net; w++; }
                else if (t.net < 0) { losses += -t.net; l++; }
            }
            out.put(acc, Json.map("operaciones", (long) n, "ganadoras", (long) w, "perdedoras", (long) l,
                    "ganancias_acumuladas", r2(gains), "perdidas_acumuladas", r2(losses), "neto", r2(gains - losses)));
        }
        List<Trade> cyc = cycle();
        int w = 0, l = 0;
        for (Trade t : cyc) { if (t.net > 0) w++; if (t.net < 0) l++; }
        out.put("autorizacion", authorized() ? "ciclo autorizado el " + state.get("autorizado_en") : "sin autorizar");
        out.put("bloqueo", blocked() ? "BLOQUEADO: " + state.get("motivo_bloqueo") + " (" + state.get("bloqueado_en") + ")" : "sin bloqueo");
        out.put("ciclo", Json.map("operaciones", (long) cyc.size(), "ganadoras", (long) w, "perdedoras", (long) l));
        out.put("proxima", next(cyc.size(), w, l));
        out.put("aprendizajes", (long) readLines(new File(dir, "aprendizajes.jsonl")).size());
        if (state.get("informe") != null) out.put("informe_perdidas", state.get("informe"));
        return out;
    }

    private String next(int n, int w, int l) {
        if (blocked()) return "ninguna: bloqueo activo hasta su autorización";
        if (!authorized()) return "ninguna: falta autorizar un ciclo";
        if (l >= MAX_LOSSES) return "ninguna";
        if (n < WINS_FOR_FOURTH) return "operación " + (n + 1) + " de " + WINS_FOR_FOURTH + " (con todos los controles)";
        if (n == WINS_FOR_FOURTH && w == WINS_FOR_FOURTH) return "4.ª operación permitida (3 ganadoras netas), con todos los controles";
        return "ninguna: ciclo terminado, hace falta una nueva autorización";
    }

    // ------------------------------------------------------------------ aprendizaje
    public synchronized void saveLesson(String tradeId, String account, String lesson) throws IOException {
        append(new File(dir, "aprendizajes.jsonl"), Json.write(Json.map("fecha", fmt(System.currentTimeMillis()), "operacion", tradeId,
                "cuenta", account, "aprendizaje", lesson)));
    }

    /** Últimos aprendizajes (para dárselos al estratega). */
    public synchronized List<String> lessons(int n) {
        List<String> l = readLines(new File(dir, "aprendizajes.jsonl")), out = new ArrayList<String>();
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) {
            try { Map<String, Object> m = Json.obj(Json.parse(l.get(i))); out.add("[" + m.get("cuenta") + "] " + m.get("aprendizaje")); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Informe determinista de las pérdidas del ciclo (lo que pasó, sin opiniones). */
    String lossReport(List<Trade> cyc) {
        StringBuilder b = new StringBuilder("# Informe de pérdidas · ").append(fmt(System.currentTimeMillis())).append("\n\n");
        b.append("Se registraron ").append(MAX_LOSSES).append(" operaciones perdedoras en el ciclo. Nuevas entradas BLOQUEADAS (demo y real) ")
         .append("hasta su autorización expresa tras revisar el riesgo.\n\n");
        double total = 0;
        for (Trade t : cyc) {
            b.append("- ").append(t.net < 0 ? "PÉRDIDA" : t.net > 0 ? "ganancia" : "neutra").append(" · [").append(t.account).append("] ")
             .append(t.side).append(' ').append(t.symbol).append(' ').append(t.tf).append(" · entrada ").append(t.entry).append(" → salida ").append(t.exit)
             .append(" · volumen ").append(t.lots).append(" · SL ").append(t.sl).append(" · neto ").append(r2(t.net))
             .append("\n  estrategia: ").append(t.strategy).append(" · entrada: ").append(t.entryReason).append(" · salida: ").append(t.exitReason).append('\n');
            total += t.net;
        }
        b.append("\nResultado neto del ciclo: ").append(r2(total)).append('\n');
        String name = "perdidas-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".md";
        try { File f = new File(dir, name); write(f, b.toString()); return f.getAbsolutePath(); }
        catch (IOException e) { return b.toString(); }
    }

    // ------------------------------------------------------------------ archivos
    private void save() throws IOException { write(new File(dir, "disciplina.json"), Json.write(state)); }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write(s.getBytes("UTF-8")); o.close();
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("no pude guardar " + f.getName()); }
    }

    private static void append(File f, String line) throws IOException {
        FileOutputStream o = new FileOutputStream(f, true);
        o.write((line + "\n").getBytes("UTF-8")); o.close();
    }

    private static String read(File f) {
        try {
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()]; int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (IOException e) { return null; }
    }

    private static Object readJson(File f) { String s = read(f); try { return s == null ? null : Json.parse(s); } catch (Exception e) { return null; } }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        String s = read(f);
        if (s != null) for (String l : s.split("\n")) if (!l.trim().isEmpty()) out.add(l);
        return out;
    }

    static String fmt(long ms) { return ms <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms)); }
    static double r2(double x) { return Math.round(x * 100.0) / 100.0; }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private static String str(Map<String, Object> m, String k, String d) { String s = Json.str(m.get(k)); return s == null ? d : s; }
    private static double num(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).doubleValue() : 0; }
    private static long lng(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : 0; }
    private static String join(List<String> l) { StringBuilder b = new StringBuilder(); for (String s : l) b.append(b.length() == 0 ? "" : ", ").append(s); return b.toString(); }
}
