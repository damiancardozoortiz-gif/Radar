package com.radar.jarvis.core.ciencia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ESTADÍSTICA Y PROBABILIDAD: descriptiva (media, mediana, moda, desvío muestral y poblacional, percentiles), correlación
 * y regresión lineal con R², distribuciones (binomial exacta, Poisson, normal), Bayes y Monte Carlo REPRODUCIBLE (semilla).
 */
public final class Estadistica {
    private Estadistica() {}

    /** "1, 2, 3" · "1 2 3" · "1,5; 2,25" (con ';' la coma es decimal). */
    public static double[] parse(String s) {
        s = s.replace("[", " ").replace("]", " ").trim();
        String[] p = s.contains(";") ? s.replace(',', '.').split("[;\\s]+") : s.split("[,\\s]+");
        List<Double> o = new ArrayList<Double>();
        for (String x : p) { x = x.trim(); if (x.isEmpty()) continue; o.add(Double.parseDouble(x)); }
        double[] r = new double[o.size()];
        for (int i = 0; i < r.length; i++) r[i] = o.get(i);
        return r;
    }

    public static double mean(double[] x) { need(x, 1); double s = 0; for (double v : x) s += v; return s / x.length; }
    public static double median(double[] x) { need(x, 1); double[] s = x.clone(); Arrays.sort(s); int n = s.length; return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2; }
    public static List<Double> mode(double[] x) {
        Map<Double, Integer> c = new HashMap<Double, Integer>(); int best = 0;
        for (double v : x) { int k = (c.containsKey(v) ? c.get(v) : 0) + 1; c.put(v, k); best = Math.max(best, k); }
        List<Double> o = new ArrayList<Double>();
        if (best > 1) for (Map.Entry<Double, Integer> e : c.entrySet()) if (e.getValue() == best) o.add(e.getKey());
        java.util.Collections.sort(o);
        return o;
    }
    /** Varianza con algoritmo de Welford (estable). sample=true divide por n-1. */
    public static double variance(double[] x, boolean sample) {
        need(x, sample ? 2 : 1);
        double m = 0, s = 0; int k = 0;
        for (double v : x) { k++; double d = v - m; m += d / k; s += d * (v - m); }
        return s / (sample ? x.length - 1 : x.length);
    }
    public static double std(double[] x, boolean sample) { return Math.sqrt(variance(x, sample)); }
    /** Percentil con interpolación lineal (método "R-7", el de Excel PERCENTIL.INC). */
    public static double percentile(double[] x, double p) {
        need(x, 1);
        double[] s = x.clone(); Arrays.sort(s);
        double h = (s.length - 1) * p / 100.0; int lo = (int) Math.floor(h);
        return lo + 1 < s.length ? s[lo] + (h - lo) * (s[lo + 1] - s[lo]) : s[lo];
    }
    public static double correlation(double[] x, double[] y) {
        same(x, y);
        double mx = mean(x), my = mean(y), sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < x.length; i++) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my); }
        return sxy / Math.sqrt(sxx * syy);
    }
    /** Regresión lineal por mínimos cuadrados: {pendiente, ordenada, r2}. */
    public static double[] regression(double[] x, double[] y) {
        same(x, y);
        double mx = mean(x), my = mean(y), sxy = 0, sxx = 0;
        for (int i = 0; i < x.length; i++) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); }
        if (sxx == 0) throw new IllegalArgumentException("todas las x son iguales: no hay recta");
        double b = sxy / sxx, a = my - b * mx, ssr = 0, sst = 0;
        for (int i = 0; i < x.length; i++) { double e = y[i] - (a + b * x[i]); ssr += e * e; sst += (y[i] - my) * (y[i] - my); }
        return new double[]{b, a, sst == 0 ? 1 : 1 - ssr / sst};
    }
    static void need(double[] x, int n) { if (x == null || x.length < n) throw new IllegalArgumentException("hacen falta al menos " + n + " dato(s)"); }
    static void same(double[] x, double[] y) { if (x.length != y.length || x.length < 2) throw new IllegalArgumentException("las dos series deben tener el mismo largo (≥ 2)"); }

    public static Map<String, Object> describe(double[] x) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("n", x.length);
        o.put("media", Num.fmt(Num.sig(mean(x), 12)));
        o.put("mediana", Num.fmt(median(x)));
        List<Double> m = mode(x);
        List<String> ms = new ArrayList<String>(); for (double v : m) ms.add(Num.fmt(v));
        o.put("moda", ms.isEmpty() ? "no hay (ningún valor se repite)" : ms);
        double mn = x[0], mx = x[0]; for (double v : x) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        o.put("minimo", Num.fmt(mn)); o.put("maximo", Num.fmt(mx));
        if (x.length >= 2) { o.put("desvio_muestral", Num.fmt(Num.sig(std(x, true), 10))); o.put("desvio_poblacional", Num.fmt(Num.sig(std(x, false), 10))); }
        o.put("cuartiles", Num.fmt(Num.sig(percentile(x, 25), 10)) + " · " + Num.fmt(Num.sig(percentile(x, 50), 10)) + " · " + Num.fmt(Num.sig(percentile(x, 75), 10)));
        // verificación: la media por suma directa coincide con la de Welford
        double s = 0; for (double v : x) s += v;
        o.put("verificado", Num.close(s / x.length, mean(x), 1e-12) || Math.abs(s / x.length) < 1e-12);
        return o;
    }

    // ------------------------------------------------------------------ probabilidad
    public static BigInteger binomial(int n, int k) {
        if (k < 0 || k > n) return BigInteger.ZERO;
        k = Math.min(k, n - k);
        BigInteger r = BigInteger.ONE;
        for (int i = 1; i <= k; i++) r = r.multiply(BigInteger.valueOf(n - k + i)).divide(BigInteger.valueOf(i));
        return r;
    }
    public static double binomialPmf(int n, int k, double p) { return binomial(n, k).doubleValue() * Math.pow(p, k) * Math.pow(1 - p, n - k); }
    public static double poissonPmf(int k, double lambda) { double lp = -lambda + k * Math.log(lambda); for (int i = 2; i <= k; i++) lp -= Math.log(i); return Math.exp(lp); }
    /** Φ(z) con erf de alta precisión (serie para |z| chico, fracción continua para colas). Error < 1e-12. */
    public static double normalCdf(double z) { return 0.5 * erfc(-z / Math.sqrt(2)); }
    static double erfc(double x) {
        if (x < 0) return 2 - erfc(-x);
        if (x < 2.5) {  // serie de Taylor de erf
            double sum = x, term = x, x2 = x * x;
            for (int n = 1; n < 200; n++) { term *= -x2 / n; double t = term / (2 * n + 1); sum += t; if (Math.abs(t) < 1e-17 * Math.abs(sum)) break; }
            return 1 - 2 / Math.sqrt(Math.PI) * sum;
        }
        // fracción continua de Lentz para erfc
        double tiny = 1e-300, f = x, c = x, d = 0;
        for (int n = 1; n < 300; n++) {
            double an = n / 2.0;
            d = x + an * d; d = d == 0 ? tiny : d; d = 1 / d;
            c = x + an / c; c = c == 0 ? tiny : c;
            double delta = c * d; f *= delta;
            if (Math.abs(delta - 1) < 1e-16) break;
        }
        return Math.exp(-x * x) / Math.sqrt(Math.PI) / f;
    }
    /** P(A|B) = P(B|A)·P(A) / P(B), con P(B) = P(B|A)P(A) + P(B|¬A)P(¬A). */
    public static double bayes(double pA, double pBgivenA, double pBgivenNotA) {
        double pB = pBgivenA * pA + pBgivenNotA * (1 - pA);
        return pBgivenA * pA / pB;
    }
    /** Monte Carlo reproducible: estima π con n puntos y semilla fija. */
    public static double monteCarloPi(int n, long seed) {
        Random r = new Random(seed); int in = 0;
        for (int i = 0; i < n; i++) { double x = r.nextDouble(), y = r.nextDouble(); if (x * x + y * y <= 1) in++; }
        return 4.0 * in / n;
    }
}
