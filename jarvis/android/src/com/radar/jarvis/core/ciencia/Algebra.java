package com.radar.jarvis.core.ciencia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * ÁLGEBRA EXACTA: ecuaciones polinómicas (raíces racionales exactas, cuadráticas con radicales, el resto numérico con
 * pulido de Newton), factorización sobre los racionales, inecuaciones lineales, sistemas lineales (Gauss exacto) y
 * álgebra lineal (determinante, inversa, rango, polinomio característico y valores propios). Cada resultado se VERIFICA
 * sustituyendo en el enunciado original.
 */
public final class Algebra {
    private Algebra() {}

    // ================================================================== polinomios
    static Q horner(Q[] c, Q x) { Q r = Q.ZERO; for (int i = c.length - 1; i >= 0; i--) r = r.mul(x).add(c[i]); return r; }
    static double horner(Q[] c, double x) { double r = 0; for (int i = c.length - 1; i >= 0; i--) r = r * x + c[i].toDouble(); return r; }
    /** Divide por (x - r) (división sintética exacta). */
    static Q[] deflate(Q[] c, Q r) {
        Q[] o = new Q[c.length - 1];
        Q acc = Q.ZERO;
        for (int i = c.length - 1; i >= 1; i--) { acc = acc.mul(r).add(c[i]); o[i - 1] = acc; }
        return o;
    }
    static List<BigInteger> divisors(BigInteger n) {
        n = n.abs();
        List<BigInteger> o = new ArrayList<BigInteger>();
        if (n.bitLength() > 62) return o;  // demasiado grande para probar divisores: se resuelve numéricamente
        long v = n.longValue();
        for (long i = 1; i * i <= v && i < 3_000_000L; i++) if (v % i == 0) { o.add(BigInteger.valueOf(i)); if (i != v / i) o.add(BigInteger.valueOf(v / i)); }
        return o;
    }

    public static final class Roots {
        public final List<String> exact = new ArrayList<String>();     // texto exacto (racional o con radical)
        public final List<Double> real = new ArrayList<Double>();      // todas las raíces reales (con multiplicidad)
        public final List<double[]> complex = new ArrayList<double[]>(); // pares (re, im) con im > 0
        public String factored, method;
        public double maxResidual;
    }

    /** Raíces de c0 + c1 x + … (coeficientes exactos). */
    public static Roots roots(Q[] c) {
        Roots r = new Roots();
        List<String> methods = new ArrayList<String>();
        if (c.length < 2) throw new IllegalArgumentException("no hay incógnita: es una constante");
        Q lead = c[c.length - 1];
        List<Q> rat = new ArrayList<Q>();
        Q[] p = c.clone();
        // 1) raíces racionales (teorema de la raíz racional) — exactas
        boolean changed = true;
        while (p.length > 1 && changed) {
            changed = false;
            if (p[0].isZero()) { rat.add(Q.ZERO); p = deflate(p, Q.ZERO); changed = true; continue; }
            BigInteger den = BigInteger.ONE;
            for (Q q : p) den = den.divide(den.gcd(q.d)).multiply(q.d);
            BigInteger a0 = p[0].mul(Q.of(den)).n, an = p[p.length - 1].mul(Q.of(den)).n;
            if (p.length <= 3) break;  // grado ≤ 2: fórmula exacta abajo
            for (BigInteger num : divisors(a0)) {
                for (BigInteger dd : divisors(an)) {
                    for (int sg = 1; sg >= -1 && !changed; sg -= 2) {
                        Q cand = new Q(num.multiply(BigInteger.valueOf(sg)), dd);
                        if (horner(p, cand).isZero()) { rat.add(cand); p = deflate(p, cand); changed = true; }
                    }
                    if (changed) break;
                }
                if (changed) break;
            }
        }
        java.util.Collections.sort(rat);
        if (!rat.isEmpty()) methods.add("raíces racionales exactas");
        for (Q q : rat) { r.exact.add(q.toString()); r.real.add(q.toDouble()); }
        StringBuilder fac = new StringBuilder();
        if (!lead.equals(Q.ONE)) fac.append(lead.isInt() ? lead.toString() : "(" + lead + ")");
        for (Q q : rat) fac.append(q.isZero() ? "x" : "(x " + (q.signum() < 0 ? "+ " + q.neg() : "- " + q) + ")");
        // 2) lo que queda
        if (p.length == 2) {
            Q x = p[0].neg().div(p[1]);
            r.exact.add(x.toString()); r.real.add(x.toDouble());
            fac.append(x.isZero() ? "x" : "(x " + (x.signum() < 0 ? "+ " + x.neg() : "- " + x) + ")");
            methods.add("despeje exacto");
        } else if (p.length == 3) {
            methods.add("fórmula cuadrática exacta");
            Q a = p[2], b = p[1], cc = p[0];
            Q disc = b.mul(b).sub(Q.of(4).mul(a).mul(cc));
            Q mid = b.neg().div(Q.of(2).mul(a));
            Q sq = disc.sqrtExact();
            if (sq != null) {
                Q x1 = mid.add(sq.div(Q.of(2).mul(a))), x2 = mid.sub(sq.div(Q.of(2).mul(a)));
                if (x1.compareTo(x2) > 0) { Q tq = x1; x1 = x2; x2 = tq; }
                for (Q x : x1.equals(x2) ? new Q[]{x1} : new Q[]{x1, x2}) { r.exact.add(x.toString()); r.real.add(x.toDouble()); fac.append("(x " + (x.signum() < 0 ? "+ " + x.neg() : "- " + x) + ")"); }
            } else {
                // √disc = k√m / den  →  x = mid ± (k / (den·2a)) √m
                Q[] rad = radical(disc.abs());
                Q coef = rad[0].div(Q.of(2).mul(a)).abs();
                String m = rad[1].toString();
                String term = (coef.equals(Q.ONE) ? "" : coef.toString()) + "√" + m;
                if (disc.signum() > 0) {
                    double sd = Math.sqrt(disc.toDouble()) / (2 * a.toDouble());
                    r.exact.add((mid.isZero() ? "" : mid + " + ") + term); r.exact.add((mid.isZero() ? "-" : mid + " - ") + term);
                    r.real.add(mid.toDouble() + Math.abs(sd)); r.real.add(mid.toDouble() - Math.abs(sd));
                } else {
                    double im = Math.sqrt(-disc.toDouble()) / Math.abs(2 * a.toDouble());
                    r.exact.add((mid.isZero() ? "" : mid + " + ") + term + "·i"); r.exact.add((mid.isZero() ? "-" : mid + " - ") + term + "·i");
                    r.complex.add(new double[]{mid.toDouble(), im});
                }
                fac.append("(" + Expr.fromPoly(new Q[]{p[0].div(a), p[1].div(a), Q.ONE}, "x") + ")");
            }
        } else if (p.length > 3) {
            methods.add("Durand-Kerner + Newton (numérico) para grado " + (p.length - 1));
            List<double[]> z = durandKerner(p);
            for (double[] w : z) {
                if (Math.abs(w[1]) < 1e-9 * Math.max(1, Math.abs(w[0]))) r.real.add(newton(p, w[0]));
                else if (w[1] > 0) r.complex.add(w);
            }
            fac.append("(" + Expr.fromPoly(monic(p), "x") + ")");
        }
        r.factored = fac.length() == 0 ? Expr.fromPoly(c, "x").toString() : fac.toString();
        java.util.Collections.sort(r.real);
        for (double x : r.real) r.maxResidual = Math.max(r.maxResidual, Math.abs(horner(c, x)));
        r.method = join(methods, " + ");
        return r;
    }
    static Q[] monic(Q[] p) { Q[] o = new Q[p.length]; for (int i = 0; i < p.length; i++) o[i] = p[i].div(p[p.length - 1]); return o; }
    /** q (≥0) = (k/den)·√m con m entero libre de cuadrados: devuelve {k/den, m}. */
    static Q[] radical(Q q) {
        BigInteger nd = q.n.multiply(q.d);  // √(n/d) = √(n·d)/d
        BigInteger k = BigInteger.ONE, m = nd;
        for (long f = 2; f * f <= 1_000_000_000L && BigInteger.valueOf(f * f).compareTo(m) <= 0; f++) {
            BigInteger ff = BigInteger.valueOf(f * f);
            while (m.mod(ff).signum() == 0) { m = m.divide(ff); k = k.multiply(BigInteger.valueOf(f)); }
        }
        return new Q[]{new Q(k, q.d), Q.of(m)};
    }
    static List<double[]> durandKerner(Q[] p) {
        int n = p.length - 1;
        double[] c = new double[p.length];
        for (int i = 0; i < p.length; i++) c[i] = p[i].toDouble() / p[n].toDouble();
        double[][] z = new double[n][2];
        double R = 1; for (int i = 0; i < n; i++) R = Math.max(R, 1 + Math.abs(c[i]));
        for (int i = 0; i < n; i++) { double ang = 2 * Math.PI * i / n + 0.4; z[i][0] = R * 0.5 * Math.cos(ang); z[i][1] = R * 0.5 * Math.sin(ang); }
        for (int it = 0; it < 2000; it++) {
            double delta = 0;
            for (int i = 0; i < n; i++) {
                double[] num = {1, 0};  // evaluar el polinomio mónico en z_i
                double[] acc = {0, 0};
                for (int k = n; k >= 0; k--) { acc = cmul(acc, z[i]); acc[0] += k == n ? 1 : c[k]; }
                num = acc;
                double[] den = {1, 0};
                for (int j = 0; j < n; j++) if (j != i) den = cmul(den, new double[]{z[i][0] - z[j][0], z[i][1] - z[j][1]});
                double[] q = cdiv(num, den);
                z[i][0] -= q[0]; z[i][1] -= q[1];
                delta = Math.max(delta, Math.hypot(q[0], q[1]));
            }
            if (delta < 1e-14) break;
        }
        List<double[]> o = new ArrayList<double[]>();
        for (double[] w : z) o.add(w);
        return o;
    }
    static double[] cmul(double[] a, double[] b) { return new double[]{a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]}; }
    static double[] cdiv(double[] a, double[] b) { double d = b[0] * b[0] + b[1] * b[1]; return new double[]{(a[0] * b[0] + a[1] * b[1]) / d, (a[1] * b[0] - a[0] * b[1]) / d}; }
    static double newton(Q[] p, double x) {
        Q[] d = derivP(p);
        for (int i = 0; i < 50; i++) { double f = horner(p, x), g = horner(d, x); if (g == 0) break; double nx = x - f / g; if (Math.abs(nx - x) < 1e-15 * Math.max(1, Math.abs(x))) { x = nx; break; } x = nx; }
        return x;
    }
    public static Q[] derivP(Q[] p) { if (p.length <= 1) return new Q[]{Q.ZERO}; Q[] o = new Q[p.length - 1]; for (int i = 1; i < p.length; i++) o[i - 1] = p[i].mul(Q.of(i)); return o; }

    // ================================================================== ecuaciones e inecuaciones
    /** Resuelve "2x^2 - 3x = 5", "x^3 = 8", "3(x+1) = 2x - 4" o una inecuación lineal "2x + 3 > 7". */
    public static Map<String, Object> solve(String text) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        String t = text.replace("≤", "<=").replace("≥", ">=").replace("==", "=");
        String rel = null;
        for (String op : new String[]{"<=", ">=", "<", ">", "="}) if (t.contains(op)) { rel = op; break; }
        String[] sides = rel == null ? new String[]{t, "0"} : t.split(java.util.regex.Pattern.quote(rel), 2);
        if (rel == null) rel = "=";
        Expr l = Expr.parse(sides[0]), rr = Expr.parse(sides[1]);
        Expr diff = Expr.op(Expr.SUB, l, rr);
        TreeSet<String> vs = diff.vars();
        if (vs.size() != 1) throw new IllegalArgumentException(vs.isEmpty() ? "no hay incógnita" : "hay varias incógnitas " + vs + ": usá un sistema de ecuaciones");
        String v = vs.first();
        out.put("incognita", v);
        Q[] p = diff.poly(v);
        if (!"=".equals(rel)) {
            if (p == null || p.length > 2) throw new IllegalArgumentException("por ahora resuelvo inecuaciones lineales");
            Q a = p.length > 1 ? p[1] : Q.ZERO, b = p[0];
            if (a.isZero()) { boolean holds = cmp(b.signum(), rel); out.put("solucion", holds ? "todos los números reales" : "ninguna solución"); out.put("metodo", "inecuación sin incógnita efectiva"); return out; }
            Q x = b.neg().div(a);
            String r2 = a.signum() < 0 ? flip(rel) : rel;  // dividir por negativo invierte el sentido
            out.put("solucion", v + " " + r2 + " " + x);
            out.put("metodo", "inecuación lineal exacta" + (a.signum() < 0 ? " (se invirtió el sentido al dividir por " + a + ")" : ""));
            // verificación: un punto a cada lado
            double inside = x.toDouble() + (r2.startsWith(">") ? 1 : -1), outside = x.toDouble() + (r2.startsWith(">") ? -1 : 1);
            boolean ok = cmp(Math.signum(diff.eval(v, inside)), rel) && !cmp(Math.signum(diff.eval(v, outside)), rel);
            out.put("verificado", ok);
            return out;
        }
        if (p != null && p.length >= 2) {
            Roots r = roots(p);
            out.put("grado", p.length - 1);
            out.put("raices_exactas", r.exact);
            List<String> reals = new ArrayList<String>(); for (double x : r.real) reals.add(Num.fmt(Num.sig(x, 12)));
            out.put("raices_reales", reals);
            List<String> cx = new ArrayList<String>(); for (double[] z : r.complex) { cx.add(Num.fmt(Num.sig(z[0], 10)) + " ± " + Num.fmt(Num.sig(z[1], 10)) + "i"); }
            if (!cx.isEmpty()) out.put("raices_complejas", cx);
            out.put("factorizacion", r.factored);
            out.put("metodo", r.method);
            double tol = 1e-9 * Math.max(1, maxAbs(p));
            double res = 0; for (double x : r.real) res = Math.max(res, Math.abs(diff.eval(v, x)));
            out.put("residuo_maximo", res);
            out.put("verificado", res <= tol);
            return out;
        }
        if (p != null) { out.put("solucion", p[0].isZero() ? "identidad: vale para todo " + v : "no tiene solución (" + p[0] + " ≠ 0)"); out.put("verificado", true); return out; }
        // no polinómica: búsqueda numérica de cambios de signo
        List<Double> sol = Calculo.scanRoots(diff, v, -100, 100, 4000);
        List<String> s = new ArrayList<String>(); for (double x : sol) s.add(Num.fmt(Num.sig(x, 12)));
        out.put("raices_reales", s);
        out.put("metodo", "búsqueda numérica en [-100, 100] (bisección + Newton); puede haber raíces fuera del intervalo");
        double res = 0; for (double x : sol) res = Math.max(res, Math.abs(diff.eval(v, x)));
        out.put("residuo_maximo", res);
        out.put("verificado", !sol.isEmpty() && res < 1e-8);
        return out;
    }
    static double maxAbs(Q[] p) { double m = 0; for (Q q : p) m = Math.max(m, Math.abs(q.toDouble())); return m; }
    static boolean cmp(double sg, String rel) {
        if ("<".equals(rel)) return sg < 0; if (">".equals(rel)) return sg > 0;
        if ("<=".equals(rel)) return sg <= 0; if (">=".equals(rel)) return sg >= 0; return sg == 0;
    }
    static String flip(String r) { return "<".equals(r) ? ">" : ">".equals(r) ? "<" : "<=".equals(r) ? ">=" : "<="; }

    /** Sistema lineal: ecuaciones separadas por ';' o saltos de línea. Gauss-Jordan exacto. */
    public static Map<String, Object> system(String text) {
        String[] eqs = text.split("[;\\n]+");
        List<Map<String, Q>> rows = new ArrayList<Map<String, Q>>();
        TreeSet<String> vars = new TreeSet<String>();
        for (String e : eqs) {
            if (e.trim().isEmpty()) continue;
            String[] s = e.split("=", 2);
            if (s.length < 2) throw new IllegalArgumentException("cada ecuación necesita '=': " + e.trim());
            Map<String, Q> m = Expr.op(Expr.SUB, Expr.parse(s[0]), Expr.parse(s[1])).linear();
            if (m == null) throw new IllegalArgumentException("no es lineal: " + e.trim());
            rows.add(m);
            for (String k : m.keySet()) if (!k.isEmpty()) vars.add(k);
        }
        List<String> vl = new ArrayList<String>(vars);
        int n = rows.size(), mcols = vl.size();
        Q[][] a = new Q[n][mcols + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < mcols; j++) { Q c = rows.get(i).get(vl.get(j)); a[i][j] = c == null ? Q.ZERO : c; }
            Q c0 = rows.get(i).get(""); a[i][mcols] = c0 == null ? Q.ZERO : c0.neg();
        }
        int rank = rref(a, mcols);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("incognitas", vl);
        for (int i = rank; i < n; i++) if (!a[i][mcols].isZero()) { out.put("solucion", "incompatible: no tiene solución"); out.put("rango", rank); out.put("verificado", true); return out; }
        if (rank < mcols) { out.put("solucion", "compatible indeterminado: infinitas soluciones (" + (mcols - rank) + " grado(s) de libertad)"); out.put("rango", rank); out.put("verificado", true); return out; }
        Map<String, String> sol = new LinkedHashMap<String, String>();
        Map<String, Q> exact = new LinkedHashMap<String, Q>();
        for (int j = 0; j < mcols; j++) { sol.put(vl.get(j), a[j][mcols].toString()); exact.put(vl.get(j), a[j][mcols]); }
        out.put("solucion", sol);
        // verificación EXACTA en las ecuaciones originales
        boolean ok = true;
        for (Map<String, Q> row : rows) {
            Q acc = row.containsKey("") ? row.get("") : Q.ZERO;
            for (Map.Entry<String, Q> e : row.entrySet()) if (!e.getKey().isEmpty()) acc = acc.add(e.getValue().mul(exact.get(e.getKey())));
            if (!acc.isZero()) ok = false;
        }
        out.put("metodo", "Gauss-Jordan con fracciones exactas");
        out.put("verificado", ok);
        return out;
    }

    /** Forma escalonada reducida (in situ) sobre las primeras cols columnas; devuelve el rango. */
    static int rref(Q[][] a, int cols) {
        int r = 0;
        for (int c = 0; c < cols && r < a.length; c++) {
            int piv = -1;
            for (int i = r; i < a.length; i++) if (!a[i][c].isZero()) { piv = i; break; }
            if (piv < 0) continue;
            Q[] t = a[piv]; a[piv] = a[r]; a[r] = t;
            Q inv = Q.ONE.div(a[r][c]);
            for (int j = 0; j < a[r].length; j++) a[r][j] = a[r][j].mul(inv);
            for (int i = 0; i < a.length; i++) if (i != r && !a[i][c].isZero()) {
                Q f = a[i][c];
                for (int j = 0; j < a[i].length; j++) a[i][j] = a[i][j].sub(f.mul(a[r][j]));
            }
            r++;
        }
        return r;
    }

    // ================================================================== matrices
    /** "[[1,2],[3,4]]" o "1 2; 3 4" → matriz exacta. */
    public static Q[][] matrix(String s) {
        String t = s.trim();
        List<String> rows = new ArrayList<String>();
        if (t.startsWith("[[")) {
            t = t.substring(1, t.length() - 1);
            int depth = 0, st = -1;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '[') { if (depth++ == 0) st = i + 1; }
                else if (c == ']') { if (--depth == 0) rows.add(t.substring(st, i)); }
            }
        } else for (String r : t.split(";")) rows.add(r);
        Q[][] m = new Q[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            String[] cells = rows.get(i).trim().split("[,\\s]+");
            m[i] = new Q[cells.length];
            for (int j = 0; j < cells.length; j++) {
                Q v = Expr.parse(cells[j]).exact();
                if (v == null) throw new IllegalArgumentException("valor no exacto en la matriz: " + cells[j]);
                m[i][j] = v;
            }
            if (m[i].length != m[0].length) throw new IllegalArgumentException("las filas tienen distinta cantidad de columnas");
        }
        return m;
    }
    static Q[][] copy(Q[][] a) { Q[][] o = new Q[a.length][]; for (int i = 0; i < a.length; i++) o[i] = a[i].clone(); return o; }
    static void square(Q[][] a) { for (Q[] r : a) if (r.length != a.length) throw new IllegalArgumentException("la matriz debe ser cuadrada"); }

    public static Q det(Q[][] m) {
        square(m);
        Q[][] a = copy(m);
        int n = a.length; Q d = Q.ONE;
        for (int c = 0; c < n; c++) {
            int piv = -1;
            for (int i = c; i < n; i++) if (!a[i][c].isZero()) { piv = i; break; }
            if (piv < 0) return Q.ZERO;
            if (piv != c) { Q[] t = a[piv]; a[piv] = a[c]; a[c] = t; d = d.neg(); }
            d = d.mul(a[c][c]);
            for (int i = c + 1; i < n; i++) {
                Q f = a[i][c].div(a[c][c]);
                for (int j = c; j < n; j++) a[i][j] = a[i][j].sub(f.mul(a[c][j]));
            }
        }
        return d;
    }
    public static int rank(Q[][] m) { return rref(copy(m), m[0].length); }
    public static Q[][] inverse(Q[][] m) {
        square(m);
        int n = m.length;
        Q[][] a = new Q[n][2 * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < 2 * n; j++) a[i][j] = j < n ? m[i][j] : (j - n == i ? Q.ONE : Q.ZERO);
        if (rref(a, n) < n) throw new ArithmeticException("la matriz no es invertible (determinante 0)");
        Q[][] o = new Q[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) o[i][j] = a[i][n + j];
        return o;
    }
    public static Q[][] mul(Q[][] a, Q[][] b) {
        if (a[0].length != b.length) throw new IllegalArgumentException("dimensiones incompatibles para multiplicar");
        Q[][] o = new Q[a.length][b[0].length];
        for (int i = 0; i < a.length; i++) for (int j = 0; j < b[0].length; j++) { Q s = Q.ZERO; for (int k = 0; k < b.length; k++) s = s.add(a[i][k].mul(b[k][j])); o[i][j] = s; }
        return o;
    }
    public static Q[][] transpose(Q[][] a) { Q[][] o = new Q[a[0].length][a.length]; for (int i = 0; i < a.length; i++) for (int j = 0; j < a[0].length; j++) o[j][i] = a[i][j]; return o; }
    public static boolean isIdentity(Q[][] a) { for (int i = 0; i < a.length; i++) for (int j = 0; j < a.length; j++) if (!a[i][j].equals(i == j ? Q.ONE : Q.ZERO)) return false; return true; }

    /** Polinomio característico det(λI − A) por Faddeev–LeVerrier (exacto). Coeficientes [c0..cn], cn = 1. */
    public static Q[] charPoly(Q[][] a) {
        square(a);
        int n = a.length;
        Q[] c = new Q[n + 1];
        c[n] = Q.ONE;
        Q[][] mk = new Q[n][n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) mk[i][j] = Q.ZERO;
        for (int k = 1; k <= n; k++) {
            Q[][] am = mul(a, mk);
            for (int i = 0; i < n; i++) am[i][i] = am[i][i].add(c[n - k + 1]);  // M_k = A·M_{k-1} + c_{n-k+1} I
            mk = am;
            Q[][] amk = mul(a, mk);
            Q tr = Q.ZERO; for (int i = 0; i < n; i++) tr = tr.add(amk[i][i]);
            c[n - k] = tr.neg().div(Q.of(k));
        }
        return c;
    }
    public static String show(Q[][] m) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < m.length; i++) {
            if (i > 0) b.append(", ");
            b.append('[');
            for (int j = 0; j < m[i].length; j++) { if (j > 0) b.append(", "); b.append(m[i][j]); }
            b.append(']');
        }
        return b.append(']').toString();
    }
    static String join(List<String> l, String sep) { StringBuilder b = new StringBuilder(); for (String s : l) { if (b.length() > 0) b.append(sep); b.append(s); } return b.toString(); }
}
