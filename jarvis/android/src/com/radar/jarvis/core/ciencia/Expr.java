package com.radar.jarvis.core.ciencia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * EXPRESIÓN MATEMÁTICA: analizador (precedencia, paréntesis, multiplicación implícita "2x", "3(x+1)"), evaluación
 * numérica, valor EXACTO racional cuando existe, extracción de polinomios y de formas lineales, DERIVADA simbólica y
 * simplificación básica. Funciones: sqrt raiz sin cos tan asin acos atan ln log exp abs; constantes pi π e.
 */
public final class Expr {
    public static final int NUM = 0, VAR = 1, CONST = 2, ADD = 3, SUB = 4, MUL = 5, DIV = 6, POW = 7, NEG = 8, FN = 9;
    public final int k; public final Expr a, b; public final Q q; public final String s;

    private Expr(int k, Expr a, Expr b, Q q, String s) { this.k = k; this.a = a; this.b = b; this.q = q; this.s = s; }
    public static Expr num(Q q) { return new Expr(NUM, null, null, q, null); }
    public static Expr num(long v) { return num(Q.of(v)); }
    public static Expr var(String v) { return new Expr(VAR, null, null, null, v); }
    static Expr op(int k, Expr a, Expr b) { return new Expr(k, a, b, null, null); }
    static Expr fn(String f, Expr a) { return new Expr(FN, a, null, null, f); }

    static final String[] FNS = {"sqrt", "raiz", "sin", "sen", "cos", "tan", "asin", "acos", "atan", "ln", "log", "exp", "abs"};

    // ------------------------------------------------------------------ analizador
    public static Expr parse(String src) {
        P p = new P(normalize(src));
        Expr e = p.sum();
        p.ws();
        if (p.i < p.t.length()) throw new IllegalArgumentException("no entiendo '" + p.t.substring(p.i) + "' en la expresión");
        return e;
    }
    static String normalize(String s) {
        s = s.replace('×', '*').replace('·', '*').replace('÷', '/').replace('−', '-').replace("**", "^").replace('²', '\u0001').replace('³', '\u0002');
        s = s.replace("\u0001", "^2").replace("\u0002", "^3").replace("√", "sqrt");
        s = s.replaceAll("(\\d),(\\d)", "$1.$2");  // coma decimal
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    private static final class P {
        final String t; int i;
        P(String t) { this.t = t; }
        void ws() { while (i < t.length() && t.charAt(i) == ' ') i++; }
        boolean eat(char c) { ws(); if (i < t.length() && t.charAt(i) == c) { i++; return true; } return false; }
        Expr sum() {
            Expr e = term();
            while (true) {
                if (eat('+')) e = op(ADD, e, term());
                else if (eat('-')) e = op(SUB, e, term());
                else return e;
            }
        }
        Expr term() {
            Expr e = unary();
            while (true) {
                if (eat('*')) e = op(MUL, e, unary());
                else if (eat('/')) e = op(DIV, e, unary());
                else if (startsPrimary()) e = op(MUL, e, power());  // multiplicación implícita
                else return e;
            }
        }
        boolean startsPrimary() {
            ws();
            if (i >= t.length()) return false;
            char c = t.charAt(i);
            return Character.isLetterOrDigit(c) || c == '(' || c == 'π' || c == '.';
        }
        Expr unary() {
            if (eat('-')) return op(NEG, unary(), null);
            if (eat('+')) return unary();
            return power();
        }
        Expr power() {
            Expr base = primary();
            if (eat('^')) return op(POW, base, unary());  // asociativa por la derecha: 2^3^2 = 2^9
            return base;
        }
        Expr primary() {
            ws();
            if (i >= t.length()) throw new IllegalArgumentException("la expresión está incompleta");
            char c = t.charAt(i);
            if (c == '(') { i++; Expr e = sum(); if (!eat(')')) throw new IllegalArgumentException("falta cerrar un paréntesis"); return e; }
            if (Character.isDigit(c) || c == '.') {
                int st = i;
                while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.')) i++;
                if (i < t.length() && t.charAt(i) == 'e' && i + 1 < t.length() && (Character.isDigit(t.charAt(i + 1)) || t.charAt(i + 1) == '-' && i + 2 < t.length() && Character.isDigit(t.charAt(i + 2)))) {
                    i += 2; while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
                }
                return num(Q.parse(t.substring(st, i)));
            }
            if (c == 'π') { i++; return new Expr(CONST, null, null, null, "pi"); }
            if (Character.isLetter(c)) {
                for (String f : FNS) if (t.startsWith(f, i) && after(i + f.length()) == '(') {
                    i += f.length(); i++;
                    Expr arg = sum();
                    if (!eat(')')) throw new IllegalArgumentException("falta cerrar el paréntesis de " + f);
                    return fn(canon(f), arg);
                }
                if (t.startsWith("pi", i)) { i += 2; return new Expr(CONST, null, null, null, "pi"); }
                i++;
                if (c == 'e') return new Expr(CONST, null, null, null, "e");
                return var(String.valueOf(c));  // variables de una letra: "xy" = x·y
            }
            throw new IllegalArgumentException("símbolo inesperado '" + c + "'");
        }
        char after(int j) { while (j < t.length() && t.charAt(j) == ' ') j++; return j < t.length() ? t.charAt(j) : 0; }
    }
    static String canon(String f) { return "raiz".equals(f) ? "sqrt" : "sen".equals(f) ? "sin" : f; }

    // ------------------------------------------------------------------ evaluación
    public double eval(Map<String, Double> v) {
        switch (k) {
            case NUM: return q.toDouble();
            case CONST: return "pi".equals(s) ? Math.PI : Math.E;
            case VAR: { Double x = v == null ? null : v.get(s); if (x == null) throw new IllegalArgumentException("falta el valor de " + s); return x; }
            case ADD: return a.eval(v) + b.eval(v);
            case SUB: return a.eval(v) - b.eval(v);
            case MUL: return a.eval(v) * b.eval(v);
            case DIV: return a.eval(v) / b.eval(v);
            case POW: return Math.pow(a.eval(v), b.eval(v));
            case NEG: return -a.eval(v);
            default: return fnv(s, a.eval(v));
        }
    }
    public double eval(String var, double x) { Map<String, Double> m = new HashMap<String, Double>(); m.put(var, x); return eval(m); }
    static double fnv(String f, double x) {
        if ("sqrt".equals(f)) return Math.sqrt(x);
        if ("sin".equals(f)) return Math.sin(x);
        if ("cos".equals(f)) return Math.cos(x);
        if ("tan".equals(f)) return Math.tan(x);
        if ("asin".equals(f)) return Math.asin(x);
        if ("acos".equals(f)) return Math.acos(x);
        if ("atan".equals(f)) return Math.atan(x);
        if ("ln".equals(f)) return Math.log(x);
        if ("log".equals(f)) return Math.log10(x);
        if ("exp".equals(f)) return Math.exp(x);
        if ("abs".equals(f)) return Math.abs(x);
        throw new IllegalArgumentException("función desconocida " + f);
    }

    /** Valor exacto racional, o null si no lo es (funciones, potencias no enteras, variables). */
    public Q exact() {
        try {
            switch (k) {
                case NUM: return q;
                case ADD: { Q x = a.exact(), y = b.exact(); return x == null || y == null ? null : x.add(y); }
                case SUB: { Q x = a.exact(), y = b.exact(); return x == null || y == null ? null : x.sub(y); }
                case MUL: { Q x = a.exact(), y = b.exact(); return x == null || y == null ? null : x.mul(y); }
                case DIV: { Q x = a.exact(), y = b.exact(); return x == null || y == null ? null : x.div(y); }
                case NEG: { Q x = a.exact(); return x == null ? null : x.neg(); }
                case POW: {
                    Q x = a.exact(), y = b.exact();
                    if (x == null || y == null) return null;
                    if (y.isInt() && y.n.bitLength() < 14) return x.pow(y.n.intValue());
                    if (y.equals(Q.of(1, 2))) return x.sqrtExact();
                    return null;
                }
                case FN: {
                    Q x = a.exact();
                    if (x == null) return null;
                    if ("sqrt".equals(s)) return x.sqrtExact();
                    if ("abs".equals(s)) return x.abs();
                    return null;
                }
                default: return null;
            }
        } catch (ArithmeticException e) { return null; }
    }

    public TreeSet<String> vars() { TreeSet<String> o = new TreeSet<String>(); collect(o); return o; }
    private void collect(TreeSet<String> o) { if (k == VAR) o.add(s); if (a != null) a.collect(o); if (b != null) b.collect(o); }

    // ------------------------------------------------------------------ polinomios y formas lineales
    /** Coeficientes exactos [c0, c1, …] en la variable v, o null si no es un polinomio con coeficientes racionales. */
    public Q[] poly(String v) {
        List<Q> r = polyL(v);
        if (r == null) return null;
        int n = r.size();
        while (n > 1 && r.get(n - 1).isZero()) n--;
        Q[] o = new Q[n];
        for (int i = 0; i < n; i++) o[i] = r.get(i);
        return o;
    }
    private List<Q> polyL(String v) {
        switch (k) {
            case NUM: return one(q);
            case VAR: { if (!s.equals(v)) return null; List<Q> o = new ArrayList<Q>(); o.add(Q.ZERO); o.add(Q.ONE); return o; }
            case ADD: case SUB: {
                List<Q> x = a.polyL(v), y = b.polyL(v);
                if (x == null || y == null) return null;
                List<Q> o = new ArrayList<Q>();
                for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
                    Q p = i < x.size() ? x.get(i) : Q.ZERO, r = i < y.size() ? y.get(i) : Q.ZERO;
                    o.add(k == ADD ? p.add(r) : p.sub(r));
                }
                return o;
            }
            case NEG: { List<Q> x = a.polyL(v); if (x == null) return null; List<Q> o = new ArrayList<Q>(); for (Q c : x) o.add(c.neg()); return o; }
            case MUL: { List<Q> x = a.polyL(v), y = b.polyL(v); return x == null || y == null ? null : mulP(x, y); }
            case DIV: {
                List<Q> x = a.polyL(v); Q y = b.exact();
                if (x == null || y == null || y.isZero()) return null;
                List<Q> o = new ArrayList<Q>(); for (Q c : x) o.add(c.div(y)); return o;
            }
            case POW: {
                List<Q> x = a.polyL(v); Q e = b.exact();
                if (x == null || e == null || !e.isInt() || e.signum() < 0 || e.n.intValue() > 60) return null;
                List<Q> o = one(Q.ONE);
                for (int i = 0; i < e.n.intValue(); i++) o = mulP(o, x);
                return o;
            }
            default: { Q c = exact(); return c == null ? null : one(c); }
        }
    }
    static List<Q> one(Q c) { List<Q> o = new ArrayList<Q>(); o.add(c); return o; }
    static List<Q> mulP(List<Q> x, List<Q> y) {
        List<Q> o = new ArrayList<Q>();
        for (int i = 0; i < x.size() + y.size() - 1; i++) o.add(Q.ZERO);
        for (int i = 0; i < x.size(); i++) for (int j = 0; j < y.size(); j++) o.set(i + j, o.get(i + j).add(x.get(i).mul(y.get(j))));
        return o;
    }

    /** Forma lineal exacta: {"x": 2, "y": -1, "": 5} = 2x - y + 5, o null si no es lineal. */
    public Map<String, Q> linear() {
        switch (k) {
            case NUM: { Map<String, Q> m = new LinkedHashMap<String, Q>(); m.put("", q); return m; }
            case VAR: { Map<String, Q> m = new LinkedHashMap<String, Q>(); m.put(s, Q.ONE); return m; }
            case ADD: case SUB: {
                Map<String, Q> x = a.linear(), y = b.linear();
                if (x == null || y == null) return null;
                Map<String, Q> m = new LinkedHashMap<String, Q>(x);
                for (Map.Entry<String, Q> e : y.entrySet()) { Q c = m.containsKey(e.getKey()) ? m.get(e.getKey()) : Q.ZERO; m.put(e.getKey(), k == ADD ? c.add(e.getValue()) : c.sub(e.getValue())); }
                return m;
            }
            case NEG: { Map<String, Q> x = a.linear(); if (x == null) return null; Map<String, Q> m = new LinkedHashMap<String, Q>(); for (Map.Entry<String, Q> e : x.entrySet()) m.put(e.getKey(), e.getValue().neg()); return m; }
            case MUL: {
                Q ca = a.exact(), cb = b.exact();
                if (ca != null) return scale(b.linear(), ca);
                if (cb != null) return scale(a.linear(), cb);
                return null;
            }
            case DIV: { Q c = b.exact(); return c == null || c.isZero() ? null : scale(a.linear(), Q.ONE.div(c)); }
            default: { Q c = exact(); if (c == null) return null; Map<String, Q> m = new LinkedHashMap<String, Q>(); m.put("", c); return m; }
        }
    }
    static Map<String, Q> scale(Map<String, Q> x, Q c) {
        if (x == null) return null;
        Map<String, Q> m = new LinkedHashMap<String, Q>();
        for (Map.Entry<String, Q> e : x.entrySet()) m.put(e.getKey(), e.getValue().mul(c));
        return m;
    }

    // ------------------------------------------------------------------ derivada simbólica
    public Expr deriv(String v) { return simplify(d(v)); }
    private Expr d(String v) {
        switch (k) {
            case NUM: case CONST: return num(0);
            case VAR: return num(s.equals(v) ? 1 : 0);
            case ADD: return op(ADD, a.d(v), b.d(v));
            case SUB: return op(SUB, a.d(v), b.d(v));
            case NEG: return op(NEG, a.d(v), null);
            case MUL: return op(ADD, op(MUL, a.d(v), b), op(MUL, a, b.d(v)));
            case DIV: return op(DIV, op(SUB, op(MUL, a.d(v), b), op(MUL, a, b.d(v))), op(POW, b, num(2)));
            case POW: {
                if (!b.vars().contains(v)) return op(MUL, op(MUL, b, op(POW, a, op(SUB, b, num(1)))), a.d(v));  // n·u^(n-1)·u'
                // u^w = e^(w ln u): (u^w)' = u^w (w' ln u + w u'/u)
                return op(MUL, this, op(ADD, op(MUL, b.d(v), fn("ln", a)), op(DIV, op(MUL, b, a.d(v)), a)));
            }
            default: {
                Expr u = a, du = a.d(v), outer;
                if ("sqrt".equals(s)) outer = op(DIV, num(1), op(MUL, num(2), fn("sqrt", u)));
                else if ("sin".equals(s)) outer = fn("cos", u);
                else if ("cos".equals(s)) outer = op(NEG, fn("sin", u), null);
                else if ("tan".equals(s)) outer = op(DIV, num(1), op(POW, fn("cos", u), num(2)));
                else if ("asin".equals(s)) outer = op(DIV, num(1), fn("sqrt", op(SUB, num(1), op(POW, u, num(2)))));
                else if ("acos".equals(s)) outer = op(NEG, op(DIV, num(1), fn("sqrt", op(SUB, num(1), op(POW, u, num(2))))), null);
                else if ("atan".equals(s)) outer = op(DIV, num(1), op(ADD, num(1), op(POW, u, num(2))));
                else if ("ln".equals(s)) outer = op(DIV, num(1), u);
                else if ("log".equals(s)) outer = op(DIV, num(1), op(MUL, u, fn("ln", num(10))));
                else if ("exp".equals(s)) outer = fn("exp", u);
                else if ("abs".equals(s)) outer = op(DIV, u, fn("abs", u));
                else throw new IllegalArgumentException("no sé derivar " + s);
                return op(MUL, outer, du);  // regla de la cadena
            }
        }
    }

    /** Simplificación básica: plegado de constantes exactas, neutros (x+0, x·1, x^1), ceros (x·0) y polinomios a forma canónica. */
    public static Expr simplify(Expr e) {
        if (e.a == null && e.b == null) return e;
        Expr a = e.a == null ? null : simplify(e.a), b = e.b == null ? null : simplify(e.b);
        Expr r = e.k == FN ? fn(e.s, a) : op(e.k, a, b);
        Q c = r.exact();
        if (c != null) return num(c);
        Q qa = a == null ? null : a.exact(), qb = b == null ? null : b.exact();
        switch (r.k) {
            case ADD: if (isZ(qa)) return b; if (isZ(qb)) return a; break;
            case SUB: if (isZ(qb)) return a; if (isZ(qa)) return simplify(op(NEG, b, null)); break;
            case MUL: if (isZ(qa) || isZ(qb)) return num(0); if (isOne(qa)) return b; if (isOne(qb)) return a; break;
            case DIV: if (isZ(qa)) return num(0); if (isOne(qb)) return a; break;
            case POW: if (isOne(qb)) return a; if (isZ(qb)) return num(1); break;
            case NEG: if (a.k == NEG) return a.a; break;
            default: break;
        }
        TreeSet<String> vs = r.vars();
        if (vs.size() == 1) { Q[] p = r.poly(vs.first()); if (p != null) return fromPoly(p, vs.first()); }
        return r;
    }
    static boolean isZ(Q q) { return q != null && q.isZero(); }
    static boolean isOne(Q q) { return q != null && q.equals(Q.ONE); }

    public static Expr fromPoly(Q[] c, String v) {
        Expr e = null;
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i].isZero()) continue;
            Expr mono = i == 0 ? num(c[i].abs()) : i == 1 ? var(v) : op(POW, var(v), num(i));
            if (i > 0 && !c[i].abs().equals(Q.ONE)) mono = op(MUL, num(c[i].abs()), mono);
            if (e == null) e = c[i].signum() < 0 ? op(NEG, mono, null) : mono;
            else e = op(c[i].signum() < 0 ? SUB : ADD, e, mono);
        }
        return e == null ? num(0) : e;
    }

    // ------------------------------------------------------------------ texto
    @Override public String toString() { return str(0); }
    private String str(int parent) {
        String o; int p;
        switch (k) {
            case NUM: return q.signum() < 0 ? "(" + q + ")" : (q.isInt() ? q.toString() : "(" + q + ")");
            case VAR: return s;
            case CONST: return "pi".equals(s) ? "π" : "e";
            case ADD: p = 1; o = a.str(1) + " + " + b.str(1); break;
            case SUB: p = 1; o = a.str(1) + " - " + b.str(2); break;
            case MUL: p = 2; o = (a.k == NUM && (b.k == VAR || b.k == POW && b.a.k == VAR || b.k == FN) ? a.str(2) + b.str(3) : a.str(2) + "·" + b.str(3)); break;
            case DIV: p = 2; o = a.str(2) + "/" + b.str(3); break;
            case NEG: p = 3; o = "-" + a.str(3); break;
            case POW: p = 4; o = a.str(5) + "^" + b.str(4); break;
            default: return s + "(" + a.str(0) + ")";
        }
        return p < parent ? "(" + o + ")" : o;
    }
}
