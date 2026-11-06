package com.radar.jarvis.core.fin;

import com.radar.jarvis.core.Json;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * JARVIS FINANCIAL INTELLIGENCE · los analistas (técnico, tendencias, liquidez) como cálculo determinista.
 * Nada se inventa: todo sale de las velas que se le dan (importadas de MT5 en CSV, o del conector cuando exista).
 */
public final class Market {
    private Market() {}

    // ================================================================== velas
    public static final class Candle {
        public final long t; public final double o, h, l, c, v;
        public Candle(long t, double o, double h, double l, double c, double v) { this.t = t; this.o = o; this.h = h; this.l = l; this.c = c; this.v = v; }
    }

    public static final String[] TF = {"M1", "M5", "M15", "M30", "H1", "H4", "D1"};
    public static final long[] TF_SEC = {60, 300, 900, 1800, 3600, 14400, 86400};

    /** Lee un CSV exportado de MetaTrader 5 (<DATE> <TIME> <OPEN> <HIGH> <LOW> <CLOSE> <TICKVOL> …) o genérico
     *  (time,open,high,low,close[,volume]; time en ISO o epoch). Devuelve las velas en orden. */
    public static List<Candle> parseCsv(String text) {
        List<Candle> out = new ArrayList<Candle>();
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("<") || Character.isLetter(line.charAt(0))) continue;  // cabecera
            String[] f = line.split(line.contains("\t") ? "\t" : line.contains(";") ? ";" : ",");
            try {
                long t; int i;
                if (f[0].matches("\\d{4}[.\\-/]\\d{2}[.\\-/]\\d{2}") && f.length > 5 && f[1].contains(":")) { t = time(f[0] + " " + f[1]); i = 2; }
                else { t = time(f[0]); i = 1; }
                double o = Double.parseDouble(f[i]), h = Double.parseDouble(f[i + 1]), l = Double.parseDouble(f[i + 2]), c = Double.parseDouble(f[i + 3]);
                double v = f.length > i + 4 ? Double.parseDouble(f[i + 4]) : 0;
                if (h < Math.max(o, c) || l > Math.min(o, c)) continue;  // vela incoherente: se descarta
                out.add(new Candle(t, o, h, l, c, v));
            } catch (Exception ignored) {}
        }
        Collections.sort(out, new Comparator<Candle>() { public int compare(Candle a, Candle b) { return Long.compare(a.t, b.t); } });
        return out;
    }

    static long time(String s) throws Exception {
        s = s.trim();
        if (s.matches("\\d{9,11}")) return Long.parseLong(s);
        if (s.matches("\\d{12,14}")) return Long.parseLong(s) / 1000;
        s = s.replace('T', ' ').replace('.', '-').replace('/', '-').replace("Z", "");
        String fmt = s.length() > 16 ? "yyyy-MM-dd HH:mm:ss" : s.length() > 10 ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd";
        SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s).getTime() / 1000;
    }

    /** Temporalidad de las velas (la separación más frecuente). */
    public static int baseTf(List<Candle> cs) {
        Map<Long, Integer> count = new LinkedHashMap<Long, Integer>();
        for (int i = 1; i < cs.size(); i++) { long d = cs.get(i).t - cs.get(i - 1).t; Integer n = count.get(d); count.put(d, n == null ? 1 : n + 1); }
        long best = 0; int bn = -1;
        for (Map.Entry<Long, Integer> e : count.entrySet()) if (e.getValue() > bn) { bn = e.getValue(); best = e.getKey(); }
        for (int i = 0; i < TF_SEC.length; i++) if (TF_SEC[i] == best) return i;
        return -1;
    }

    /** Agrupa velas de una temporalidad menor en una mayor (M1 → M5 … D1). */
    public static List<Candle> resample(List<Candle> cs, long sec) {
        List<Candle> out = new ArrayList<Candle>();
        long bucket = -1; double o = 0, h = 0, l = 0, c = 0, v = 0;
        for (Candle k : cs) {
            long b = k.t - (k.t % sec);
            if (b != bucket) {
                if (bucket >= 0) out.add(new Candle(bucket, o, h, l, c, v));
                bucket = b; o = k.o; h = k.h; l = k.l; c = k.c; v = k.v;
            } else { h = Math.max(h, k.h); l = Math.min(l, k.l); c = k.c; v += k.v; }
        }
        if (bucket >= 0) out.add(new Candle(bucket, o, h, l, c, v));
        return out;
    }

    // ================================================================== indicadores
    static double[] closes(List<Candle> cs) { double[] r = new double[cs.size()]; for (int i = 0; i < r.length; i++) r[i] = cs.get(i).c; return r; }

    public static double[] sma(double[] v, int n) {
        double[] r = new double[v.length]; double s = 0;
        for (int i = 0; i < v.length; i++) { s += v[i]; if (i >= n) s -= v[i - n]; r[i] = i >= n - 1 ? s / n : Double.NaN; }
        return r;
    }

    public static double[] ema(double[] v, int n) {
        double[] r = new double[v.length]; double k = 2.0 / (n + 1);
        for (int i = 0; i < v.length; i++) {
            if (i < n - 1) r[i] = Double.NaN;
            else if (i == n - 1) { double s = 0; for (int j = 0; j < n; j++) s += v[j]; r[i] = s / n; }
            else r[i] = v[i] * k + r[i - 1] * (1 - k);
        }
        return r;
    }

    /** RSI de Wilder. */
    public static double[] rsi(double[] c, int n) {
        double[] r = new double[c.length];
        java.util.Arrays.fill(r, Double.NaN);
        if (c.length <= n) return r;
        double g = 0, l = 0;
        for (int i = 1; i <= n; i++) { double d = c[i] - c[i - 1]; if (d > 0) g += d; else l -= d; }
        g /= n; l /= n;
        r[n] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            g = (g * (n - 1) + Math.max(d, 0)) / n;
            l = (l * (n - 1) + Math.max(-d, 0)) / n;
            r[i] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        }
        return r;
    }

    /** MACD(12,26,9): {macd, señal, histograma}. */
    public static double[][] macd(double[] c) {
        double[] f = ema(c, 12), s = ema(c, 26), m = new double[c.length];
        for (int i = 0; i < c.length; i++) m[i] = f[i] - s[i];
        int first = 25;
        double[] tail = new double[Math.max(0, c.length - first)];
        for (int i = first; i < c.length; i++) tail[i - first] = m[i];
        double[] sigT = ema(tail, 9), sig = new double[c.length], hist = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            sig[i] = i >= first ? sigT[i - first] : Double.NaN;
            hist[i] = m[i] - sig[i];
        }
        return new double[][]{m, sig, hist};
    }

    /** ATR de Wilder. */
    public static double[] atr(List<Candle> cs, int n) {
        double[] r = new double[cs.size()];
        java.util.Arrays.fill(r, Double.NaN);
        if (cs.size() <= n) return r;
        double s = 0;
        for (int i = 1; i <= n; i++) s += tr(cs, i);
        r[n] = s / n;
        for (int i = n + 1; i < cs.size(); i++) r[i] = (r[i - 1] * (n - 1) + tr(cs, i)) / n;
        return r;
    }

    static double tr(List<Candle> cs, int i) {
        Candle k = cs.get(i), p = cs.get(i - 1);
        return Math.max(k.h - k.l, Math.max(Math.abs(k.h - p.c), Math.abs(k.l - p.c)));
    }

    /** Niveles de Fibonacci de un tramo (del mínimo al máximo si la tendencia es alcista). */
    public static Map<String, Object> fibonacci(double low, double high, boolean up) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        double[] lv = {0.236, 0.382, 0.5, 0.618, 0.786};
        for (double f : lv) m.put(String.valueOf(f), round(up ? high - (high - low) * f : low + (high - low) * f));
        return m;
    }

    // ================================================================== estructura, niveles y liquidez
    /** Máximos/mínimos de giro (fractal de 2 velas a cada lado): {índice, precio, +1 máximo / -1 mínimo}. */
    public static List<double[]> swings(List<Candle> cs) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 2; i < cs.size() - 2; i++) {
            Candle k = cs.get(i);
            if (k.h > cs.get(i - 1).h && k.h > cs.get(i - 2).h && k.h >= cs.get(i + 1).h && k.h >= cs.get(i + 2).h) out.add(new double[]{i, k.h, 1});
            if (k.l < cs.get(i - 1).l && k.l < cs.get(i - 2).l && k.l <= cs.get(i + 1).l && k.l <= cs.get(i + 2).l) out.add(new double[]{i, k.l, -1});
        }
        return out;
    }

    /** Análisis completo de una temporalidad. */
    public static Map<String, Object> analyze(List<Candle> cs, String tf) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("temporalidad", tf);
        r.put("velas", (long) cs.size());
        if (cs.size() < 60) { r.put("aviso", "pocas velas (" + cs.size() + "): hacen falta al menos 60"); return r; }
        double[] c = closes(cs);
        int last = cs.size() - 1;
        Candle k = cs.get(last);
        double price = k.c, a = atr(cs, 14)[last];
        r.put("precio", round(price));
        r.put("atr14", round(a));
        double e20 = ema(c, 20)[last], e50 = ema(c, 50)[last], s200 = cs.size() >= 200 ? sma(c, 200)[last] : Double.NaN;
        r.put("ema20", round(e20)); r.put("ema50", round(e50)); r.put("sma200", Double.isNaN(s200) ? null : round(s200));
        double rs = rsi(c, 14)[last];
        r.put("rsi14", round2(rs));
        r.put("rsi_lectura", rs >= 70 ? "sobrecompra" : rs <= 30 ? "sobreventa" : "neutral");
        double[][] m = macd(c);
        r.put("macd", round(m[0][last])); r.put("macd_senal", round(m[1][last])); r.put("macd_hist", round(m[2][last]));
        r.put("macd_cruce", m[2][last] > 0 && m[2][last - 1] <= 0 ? "alcista reciente" : m[2][last] < 0 && m[2][last - 1] >= 0 ? "bajista reciente" : "sin cruce");

        // estructura de mercado (máximos y mínimos crecientes o decrecientes)
        List<double[]> sw = swings(cs);
        List<double[]> highs = new ArrayList<double[]>(), lows = new ArrayList<double[]>();
        for (double[] s : sw) (s[2] > 0 ? highs : lows).add(s);
        String estructura = "lateral";
        if (highs.size() >= 2 && lows.size() >= 2) {
            double h1 = highs.get(highs.size() - 2)[1], h2 = highs.get(highs.size() - 1)[1];
            double l1 = lows.get(lows.size() - 2)[1], l2 = lows.get(lows.size() - 1)[1];
            if (h2 > h1 && l2 > l1) estructura = "alcista (máximos y mínimos crecientes)";
            else if (h2 < h1 && l2 < l1) estructura = "bajista (máximos y mínimos decrecientes)";
        }
        r.put("estructura", estructura);
        String tendencia = price > e50 && e20 > e50 ? "alcista" : price < e50 && e20 < e50 ? "bajista" : "indefinida";
        if (!Double.isNaN(s200)) tendencia += price > s200 ? " (sobre la SMA200)" : " (bajo la SMA200)";
        r.put("tendencia", tendencia);

        // soportes y resistencias: agrupa giros cercanos (0,3·ATR)
        List<double[]> levels = new ArrayList<double[]>();  // {precio, toques}
        for (double[] s : sw) {
            boolean merged = false;
            for (double[] lv : levels) if (Math.abs(lv[0] - s[1]) <= 0.3 * a) { lv[0] = (lv[0] * lv[1] + s[1]) / (lv[1] + 1); lv[1]++; merged = true; break; }
            if (!merged) levels.add(new double[]{s[1], 1});
        }
        double sup = Double.NaN, res = Double.NaN; int supT = 0, resT = 0;
        for (double[] lv : levels) {
            if (lv[0] < price && (Double.isNaN(sup) || lv[0] > sup)) { sup = lv[0]; supT = (int) lv[1]; }
            if (lv[0] > price && (Double.isNaN(res) || lv[0] < res)) { res = lv[0]; resT = (int) lv[1]; }
        }
        r.put("soporte", Double.isNaN(sup) ? null : Json.map("precio", round(sup), "toques", (long) supT));
        r.put("resistencia", Double.isNaN(res) ? null : Json.map("precio", round(res), "toques", (long) resT));

        // liquidez: máximos/mínimos iguales (0,1·ATR) = zonas donde suele haber órdenes; barridos de esas zonas
        List<Object> pools = new ArrayList<Object>();
        for (int i = 0; i < sw.size(); i++) for (int j = i + 1; j < sw.size(); j++) {
            double[] x = sw.get(i), y = sw.get(j);
            if (x[2] == y[2] && Math.abs(x[1] - y[1]) <= 0.1 * a && y[0] - x[0] >= 3)
                pools.add(Json.map("tipo", x[2] > 0 ? "máximos iguales (liquidez arriba)" : "mínimos iguales (liquidez abajo)", "precio", round((x[1] + y[1]) / 2)));
        }
        r.put("liquidez", pools.size() > 4 ? new ArrayList<Object>(pools.subList(pools.size() - 4, pools.size())) : pools);

        // rupturas falsas en las últimas 20 velas: cierra fuera de un nivel y vuelve dentro en ≤ 2 velas
        List<Object> falsas = new ArrayList<Object>();
        for (double[] lv : levels) {
            if (lv[1] < 2) continue;
            for (int i = Math.max(1, last - 20); i < last - 1; i++) {
                Candle p = cs.get(i - 1), x = cs.get(i);
                boolean up = p.c <= lv[0] && x.c > lv[0], dn = p.c >= lv[0] && x.c < lv[0];
                if (!up && !dn) continue;
                for (int j = i + 1; j <= Math.min(last, i + 2); j++) {
                    if (up && cs.get(j).c < lv[0] || dn && cs.get(j).c > lv[0]) {
                        falsas.add(Json.map("nivel", round(lv[0]), "direccion", up ? "ruptura falsa al alza" : "ruptura falsa a la baja", "velas_atras", (long) (last - i)));
                        break;
                    }
                }
            }
        }
        r.put("rupturas_falsas", falsas);

        // velas japonesas: patrón de la última vela
        double body = Math.abs(k.c - k.o), range = k.h - k.l, upW = k.h - Math.max(k.o, k.c), dnW = Math.min(k.o, k.c) - k.l;
        Candle p = cs.get(last - 1);
        String vela = "normal";
        if (range > 0 && body <= 0.1 * range) vela = "doji (indecisión)";
        else if (dnW >= 2 * body && upW <= 0.3 * body) vela = "martillo / pin bar alcista";
        else if (upW >= 2 * body && dnW <= 0.3 * body) vela = "estrella fugaz / pin bar bajista";
        else if (k.c > k.o && p.c < p.o && k.c >= p.o && k.o <= p.c) vela = "envolvente alcista";
        else if (k.c < k.o && p.c > p.o && k.o >= p.c && k.c <= p.o) vela = "envolvente bajista";
        r.put("vela", vela);

        // volumen relativo
        double vs = 0; int vn = 0;
        for (int i = Math.max(0, last - 20); i < last; i++) { vs += cs.get(i).v; vn++; }
        r.put("volumen_relativo", vs > 0 ? round2(k.v / (vs / vn)) : null);

        // Fibonacci del último tramo entre giros
        if (!highs.isEmpty() && !lows.isEmpty()) {
            double[] hh = highs.get(highs.size() - 1), ll = lows.get(lows.size() - 1);
            boolean up = ll[0] < hh[0];
            r.put("fibonacci", Json.map("tramo", up ? "alcista" : "bajista", "minimo", round(ll[1]), "maximo", round(hh[1]), "retrocesos", fibonacci(ll[1], hh[1], up)));
        }
        return r;
    }

    /** Análisis multitemporal: desde la temporalidad de las velas hasta D1. */
    public static Map<String, Object> multi(List<Candle> base) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        int b = baseTf(base);
        if (b < 0) { out.put("aviso", "no reconozco la temporalidad de las velas (M1, M5, M15, M30, H1, H4 o D1)"); return out; }
        out.put("temporalidad_base", TF[b]);
        out.put("desde", fmt(base.get(0).t));
        out.put("hasta", fmt(base.get(base.size() - 1).t));
        List<Object> tfs = new ArrayList<Object>();
        for (int i = b; i < TF.length; i++) tfs.add(analyze(i == b ? base : resample(base, TF_SEC[i]), TF[i]));
        out.put("analisis", tfs);
        return out;
    }

    /** Análisis multitemporal con velas propias de cada temporalidad (p. ej. leídas de MT5): {"M15": velas, "H1": velas…}. */
    public static Map<String, Object> multiTf(Map<String, List<Candle>> byTf) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Object> tfs = new ArrayList<Object>();
        List<Candle> first = null;
        for (String tf : TF) {
            List<Candle> cs = byTf.get(tf);
            if (cs == null) continue;
            if (first == null && !cs.isEmpty()) { first = cs; out.put("temporalidad_base", tf); }
            tfs.add(analyze(cs, tf));
        }
        if (first == null) { out.put("aviso", "no llegaron velas"); return out; }
        out.put("desde", fmt(first.get(0).t));
        out.put("hasta", fmt(first.get(first.size() - 1).t));
        out.put("fuente", "MT5 vía MetaApi");
        out.put("analisis", tfs);
        return out;
    }

    static String fmt(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(t * 1000) + " UTC";
    }

    static Double round(double x) { return Double.isNaN(x) ? null : Math.round(x * 100000.0) / 100000.0; }
    static Double round2(double x) { return Double.isNaN(x) ? null : Math.round(x * 100.0) / 100.0; }
}
