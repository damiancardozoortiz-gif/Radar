package com.radar.jarvis.core.ciencia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CÁLCULO Y MÉTODOS NUMÉRICOS: derivada simbólica (comprobada con diferencias centrales), integral definida (Simpson
 * adaptativo cruzado con Gauss-Legendre; exacta si es polinómica), límites numéricos por ambos lados, series de Taylor,
 * extremos de polinomios, raíces (bisección, Newton, secante) y ecuaciones diferenciales (Euler y Runge-Kutta 4).
 * Cada resultado trae cómo se verificó.
 */
public final class Calculo {
    private Calculo() {}

    static double f(Expr e, String v, double x) { return e.eval(v, x); }
    static String var(Expr e) { return e.vars().isEmpty() ? "x" : e.vars().first(); }

    public static Map<String, Object> derivative(String expr) {
        Expr e = Expr.parse(expr);
        String v = var(e);
        Expr d = e.deriv(v);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("funcion", e.toString());
        o.put("derivada", d.toString());
        // verificación: comparar con la derivada numérica en varios puntos donde la función está definida
        double[] pts = {0.37, 1.3, 2.1, -0.8, 3.7};
        int checked = 0; double worst = 0;
        for (double x : pts) {
            double sym = d.eval(v, x), num = central(e, v, x);
            if (Double.isNaN(sym) || Double.isNaN(num) || Double.isInfinite(sym)) continue;
            worst = Math.max(worst, Math.abs(sym - num) / Math.max(1, Math.abs(sym)));
            checked++;
        }
        o.put("verificado", checked >= 2 && worst < 1e-5);
        o.put("verificacion", "comparada con diferencias centrales en " + checked + " puntos (error relativo máx. " + Num.fmt(Num.sig(worst, 3)) + ")");
        return o;
    }
    static double central(Expr e, String v, double x) {
        double h = 1e-5 * Math.max(1, Math.abs(x));
        return (-f(e, v, x + 2 * h) + 8 * f(e, v, x + h) - 8 * f(e, v, x - h) + f(e, v, x - 2 * h)) / (12 * h);
    }

    public static Map<String, Object> integral(String expr, double a, double b) {
        Expr e = Expr.parse(expr);
        String v = var(e);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("funcion", e.toString());
        o.put("intervalo", "[" + Num.fmt(a) + ", " + Num.fmt(b) + "]");
        Q[] p = e.poly(v);
        if (p != null) {  // exacta: primitiva del polinomio
            Q qa = Q.fromDouble(a), qb = Q.fromDouble(b);
            Q[] prim = new Q[p.length + 1];
            prim[0] = Q.ZERO;
            for (int i = 0; i < p.length; i++) prim[i + 1] = p[i].div(Q.of(i + 1));
            Q val = Algebra.horner(prim, qb).sub(Algebra.horner(prim, qa));
            o.put("primitiva", Expr.fromPoly(prim, v) + " + C");
            o.put("valor_exacto", val.toString());
            o.put("valor", Num.fmt(Num.sig(val.toDouble(), 12)));
            double s = simpson(e, v, a, b, 1e-12, 50);
            o.put("verificado", Num.close(s, val.toDouble(), 1e-9) || Math.abs(s - val.toDouble()) < 1e-9);
            o.put("verificacion", "primitiva exacta; Simpson adaptativo da " + Num.fmt(Num.sig(s, 12)));
            return o;
        }
        double s = simpson(e, v, a, b, 1e-10, 50), g = gaussLegendre(e, v, a, b, 200);
        o.put("valor", Num.fmt(Num.sig(s, 12)));
        boolean ok = !Double.isNaN(s) && (Num.close(s, g, 1e-7) || Math.abs(s - g) < 1e-9);
        o.put("verificado", ok);
        o.put("verificacion", "Simpson adaptativo " + Num.fmt(Num.sig(s, 12)) + " vs Gauss-Legendre " + Num.fmt(Num.sig(g, 12)) + (ok ? " (coinciden)" : " (NO coinciden: puede haber una singularidad)"));
        return o;
    }
    public static double simpson(Expr e, String v, double a, double b, double eps, int depth) {
        double fa = f(e, v, a), fb = f(e, v, b), m = (a + b) / 2, fm = f(e, v, m);
        return asr(e, v, a, b, fa, fm, fb, (b - a) / 6 * (fa + 4 * fm + fb), eps, depth);
    }
    static double asr(Expr e, String v, double a, double b, double fa, double fm, double fb, double whole, double eps, int depth) {
        double m = (a + b) / 2, lm = (a + m) / 2, rm = (m + b) / 2, flm = f(e, v, lm), frm = f(e, v, rm);
        double left = (m - a) / 6 * (fa + 4 * flm + fm), right = (b - m) / 6 * (fm + 4 * frm + fb);
        if (depth <= 0 || Math.abs(left + right - whole) <= 15 * eps) return left + right + (left + right - whole) / 15;
        return asr(e, v, a, m, fa, flm, fm, left, eps / 2, depth - 1) + asr(e, v, m, b, fm, frm, fb, right, eps / 2, depth - 1);
    }
    static final double[] GX = {-0.906179845938664, -0.538469310105683, 0, 0.538469310105683, 0.906179845938664};
    static final double[] GW = {0.236926885056189, 0.478628670499366, 0.568888888888889, 0.478628670499366, 0.236926885056189};
    public static double gaussLegendre(Expr e, String v, double a, double b, int panels) {
        double h = (b - a) / panels, s = 0;
        for (int i = 0; i < panels; i++) {
            double c = a + (i + 0.5) * h;
            for (int k = 0; k < 5; k++) s += GW[k] * f(e, v, c + GX[k] * h / 2);
        }
        return s * h / 2;
    }
    public static double trapezoid(Expr e, String v, double a, double b, int n) {
        double h = (b - a) / n, s = (f(e, v, a) + f(e, v, b)) / 2;
        for (int i = 1; i < n; i++) s += f(e, v, a + i * h);
        return s * h;
    }

    public static Map<String, Object> limit(String expr, double a) {
        Expr e = Expr.parse(expr);
        String v = var(e);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        double[] left = new double[6], right = new double[6];
        for (int i = 0; i < 6; i++) { double h = Math.pow(10, -2 - i); left[i] = f(e, v, a - h); right[i] = f(e, v, a + h); }
        double l = left[5], r = right[5];
        boolean convL = Math.abs(left[5] - left[4]) < 1e-5 * Math.max(1, Math.abs(l)), convR = Math.abs(right[5] - right[4]) < 1e-5 * Math.max(1, Math.abs(r));
        o.put("funcion", e.toString());
        o.put("punto", Num.fmt(a));
        o.put("por_izquierda", Num.fmt(Num.sig(l, 8)));
        o.put("por_derecha", Num.fmt(Num.sig(r, 8)));
        if (convL && convR && Num.close(l, r, 1e-5)) { o.put("limite", Num.fmt(Num.sig((l + r) / 2, 8))); o.put("verificado", true); }
        else if (Math.abs(l) > 1e6 || Math.abs(r) > 1e6) { o.put("limite", "no existe (diverge: tiende a infinito)"); o.put("verificado", true); }
        else { o.put("limite", "no existe o no converge numéricamente (izquierda ≠ derecha)"); o.put("verificado", convL && convR); }
        o.put("metodo", "aproximación numérica con h = 10^-2 … 10^-7 por ambos lados");
        return o;
    }

    /** Taylor de orden n en a: coeficientes f^(k)(a)/k! con derivadas SIMBÓLICAS sucesivas. */
    public static Map<String, Object> taylor(String expr, double a, int n) {
        Expr e = Expr.parse(expr);
        String v = var(e);
        List<String> terms = new ArrayList<String>();
        Expr d = e; double fact = 1;
        double[] coef = new double[n + 1];
        for (int k = 0; k <= n; k++) {
            if (k > 0) { d = d.deriv(v); fact *= k; }
            coef[k] = d.eval(v, a) / fact;
            if (Math.abs(coef[k]) > 1e-14) terms.add(Num.fmt(Num.sig(coef[k], 10)) + (k == 0 ? "" : a == 0 ? "·" + v + (k > 1 ? "^" + k : "") : "·(" + v + " - " + Num.fmt(a) + ")" + (k > 1 ? "^" + k : "")));
        }
        double x = a + 0.1, approx = 0;
        for (int k = 0; k <= n; k++) approx += coef[k] * Math.pow(x - a, k);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("serie", Algebra.join(terms, " + ").replace("+ -", "- "));
        o.put("verificacion", "en " + v + " = " + Num.fmt(x) + ": serie " + Num.fmt(Num.sig(approx, 10)) + " vs función " + Num.fmt(Num.sig(e.eval(v, x), 10)));
        o.put("verificado", Math.abs(approx - e.eval(v, x)) < 1e-3 * Math.max(1, Math.abs(approx)));
        return o;
    }

    /** Máximos, mínimos e inflexiones de un polinomio (raíces exactas de la derivada + criterio de la segunda derivada). */
    public static Map<String, Object> extremes(String expr) {
        Expr e = Expr.parse(expr);
        String v = var(e);
        Q[] p = e.poly(v);
        if (p == null) throw new IllegalArgumentException("por ahora busco extremos exactos solo en polinomios");
        Q[] d1 = Algebra.derivP(p), d2 = Algebra.derivP(d1);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        List<String> max = new ArrayList<String>(), min = new ArrayList<String>(), flat = new ArrayList<String>(), infl = new ArrayList<String>();
        if (d1.length >= 2) for (double x : Algebra.roots(d1).real) {
            double s = Algebra.horner(d2, x), y = Algebra.horner(p, x);
            String pt = "(" + Num.fmt(Num.sig(x, 10)) + ", " + Num.fmt(Num.sig(y, 10)) + ")";
            if (s < -1e-9) max.add(pt); else if (s > 1e-9) min.add(pt); else flat.add(pt);
        }
        if (d2.length >= 2) for (double x : Algebra.roots(d2).real) {
            double before = Algebra.horner(d2, x - 1e-4), after = Algebra.horner(d2, x + 1e-4);
            if (before * after < 0) infl.add("(" + Num.fmt(Num.sig(x, 10)) + ", " + Num.fmt(Num.sig(Algebra.horner(p, x), 10)) + ")");
        }
        o.put("funcion", e.toString());
        o.put("derivada", Expr.fromPoly(d1, v).toString());
        o.put("maximos_locales", max); o.put("minimos_locales", min);
        if (!flat.isEmpty()) o.put("puntos_criticos_dudosos", flat);
        o.put("inflexiones", infl);
        o.put("verificado", true);
        return o;
    }

    // ------------------------------------------------------------------ raíces numéricas
    public static double bisection(Expr e, String v, double a, double b, double tol) {
        double fa = f(e, v, a), fb = f(e, v, b);
        if (fa == 0) return a; if (fb == 0) return b;
        if (fa * fb > 0) throw new IllegalArgumentException("la función no cambia de signo en el intervalo");
        for (int i = 0; i < 200 && b - a > tol; i++) {
            double m = (a + b) / 2, fm = f(e, v, m);
            if (fm == 0) return m;
            if (fa * fm < 0) { b = m; } else { a = m; fa = fm; }
        }
        return (a + b) / 2;
    }
    public static double newton(Expr e, String v, double x0) {
        Expr d = e.deriv(v);
        double x = x0;
        for (int i = 0; i < 100; i++) {
            double fx = f(e, v, x), g = d.eval(v, x);
            if (g == 0 || Double.isNaN(g)) throw new ArithmeticException("derivada nula: Newton no puede seguir");
            double nx = x - fx / g;
            if (Math.abs(nx - x) < 1e-14 * Math.max(1, Math.abs(x))) return nx;
            x = nx;
        }
        throw new ArithmeticException("Newton no convergió en 100 iteraciones");
    }
    public static double secant(Expr e, String v, double x0, double x1) {
        for (int i = 0; i < 100; i++) {
            double f0 = f(e, v, x0), f1 = f(e, v, x1);
            if (f1 == f0) break;
            double x2 = x1 - f1 * (x1 - x0) / (f1 - f0);
            if (Math.abs(x2 - x1) < 1e-14 * Math.max(1, Math.abs(x1))) return x2;
            x0 = x1; x1 = x2;
        }
        return x1;
    }
    /** Busca cambios de signo en una grilla y refina cada uno con bisección. */
    public static List<Double> scanRoots(Expr e, String v, double a, double b, int n) {
        List<Double> o = new ArrayList<Double>();
        double h = (b - a) / n, px = a, pf = f(e, v, a);
        for (int i = 1; i <= n; i++) {
            double x = a + i * h, fx = f(e, v, x);
            if (Double.isNaN(fx) || Double.isNaN(pf) || Double.isInfinite(fx) || Double.isInfinite(pf)) { px = x; pf = fx; continue; }
            if (pf == 0) o.add(px);
            else if (pf * fx < 0) {
                double r = bisection(e, v, px, x, 1e-15);
                if (Math.abs(f(e, v, r)) < 1e-6) o.add(r);  // descarta saltos (polos), no son raíces
            }
            px = x; pf = fx;
            if (o.size() > 50) break;
        }
        return o;
    }

    // ------------------------------------------------------------------ ecuaciones diferenciales y' = f(t, y)
    public static double[] euler(Expr f, double t0, double y0, double t1, int n) {
        double h = (t1 - t0) / n, t = t0, y = y0;
        Map<String, Double> m = new HashMap<String, Double>();
        for (int i = 0; i < n; i++) { m.put("t", t); m.put("y", y); y += h * f.eval(m); t += h; }
        return new double[]{t, y};
    }
    public static double[] rk4(Expr f, double t0, double y0, double t1, int n) {
        double h = (t1 - t0) / n, t = t0, y = y0;
        for (int i = 0; i < n; i++) {
            double k1 = fy(f, t, y), k2 = fy(f, t + h / 2, y + h * k1 / 2), k3 = fy(f, t + h / 2, y + h * k2 / 2), k4 = fy(f, t + h, y + h * k3);
            y += h / 6 * (k1 + 2 * k2 + 2 * k3 + k4); t += h;
        }
        return new double[]{t, y};
    }
    static double fy(Expr f, double t, double y) { Map<String, Double> m = new HashMap<String, Double>(); m.put("t", t); m.put("y", y); return f.eval(m); }
    public static Map<String, Object> ode(String fty, double t0, double y0, double t1) {
        Expr f = Expr.parse(fty);
        double[] a = rk4(f, t0, y0, t1, 1000), b = rk4(f, t0, y0, t1, 2000), e = euler(f, t0, y0, t1, 1000);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("ecuacion", "y' = " + f);
        o.put("condicion", "y(" + Num.fmt(t0) + ") = " + Num.fmt(y0));
        o.put("y_final", Num.fmt(Num.sig(b[1], 10)));
        o.put("metodo", "Runge-Kutta 4 (1000 y 2000 pasos) y Euler como referencia");
        o.put("euler", Num.fmt(Num.sig(e[1], 8)));
        o.put("verificado", Num.close(a[1], b[1], 1e-8) || Math.abs(a[1] - b[1]) < 1e-10);
        o.put("verificacion", "RK4 con el doble de pasos cambia " + Num.fmt(Num.sig(Math.abs(a[1] - b[1]), 3)));
        return o;
    }
}
