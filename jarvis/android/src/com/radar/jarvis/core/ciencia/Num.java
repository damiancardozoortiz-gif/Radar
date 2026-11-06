package com.radar.jarvis.core.ciencia;

import java.util.Locale;

/** Formato de números para resultados (sin ruido de coma flotante). */
public final class Num {
    private Num() {}
    public static String fmt(double x) {
        if (Double.isNaN(x)) return "indefinido";
        if (Double.isInfinite(x)) return x > 0 ? "∞" : "-∞";
        if (x == Math.rint(x) && Math.abs(x) < 1e15) return String.valueOf((long) x);
        double a = Math.abs(x);
        if (a >= 1e-4 && a < 1e15) return new java.math.BigDecimal(Double.toString(x)).stripTrailingZeros().toPlainString();  // representación más corta exacta
        String s = String.format(Locale.US, "%.11e", x);
        String[] p = s.split("e"); return trim(p[0]) + "e" + Integer.parseInt(p[1]);
    }
    static String trim(String s) { if (!s.contains(".")) return s; s = s.replaceAll("0+$", ""); return s.endsWith(".") ? s.substring(0, s.length() - 1) : s; }
    /** Redondeo a cifras significativas. */
    public static double sig(double x, int n) { if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) return x; return new java.math.BigDecimal(x).round(new java.math.MathContext(n)).doubleValue(); }
    public static boolean close(double a, double b, double rel) { return Math.abs(a - b) <= rel * Math.max(1e-12, Math.max(Math.abs(a), Math.abs(b))); }
}
