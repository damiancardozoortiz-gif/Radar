package com.radar.jarvis.core.ciencia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * MATEMÁTICA DISCRETA Y LÓGICA: primalidad (Miller-Rabin determinista para 64 bits), factorización (división + rho de
 * Pollard), MCD/MCM, inverso modular, combinatoria, Fibonacci exacto, tablas de verdad (tautología/contradicción,
 * equivalencia de fórmulas) y caminos mínimos en grafos (Dijkstra).
 */
public final class Discreta {
    private Discreta() {}

    // ------------------------------------------------------------------ números
    static final long[] MR = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    public static boolean isPrime(BigInteger n) {
        if (n.compareTo(BigInteger.valueOf(2)) < 0) return false;
        if (n.bitLength() > 64) return n.isProbablePrime(64);
        for (long p : MR) { BigInteger bp = BigInteger.valueOf(p); if (n.equals(bp)) return true; if (n.mod(bp).signum() == 0) return false; }
        BigInteger d = n.subtract(BigInteger.ONE); int s = 0;
        while (!d.testBit(0)) { d = d.shiftRight(1); s++; }
        outer:
        for (long a : MR) {  // con estas 12 bases es DETERMINISTA para n < 3,3·10^24
            BigInteger x = BigInteger.valueOf(a).modPow(d, n);
            if (x.equals(BigInteger.ONE) || x.equals(n.subtract(BigInteger.ONE))) continue;
            for (int r = 1; r < s; r++) { x = x.multiply(x).mod(n); if (x.equals(n.subtract(BigInteger.ONE))) continue outer; }
            return false;
        }
        return true;
    }
    /** Factorización en primos: {primo → exponente}. */
    public static TreeMap<BigInteger, Integer> factor(BigInteger n) {
        if (n.signum() <= 0) throw new IllegalArgumentException("factorizo enteros positivos");
        if (n.bitLength() > 100) throw new IllegalArgumentException("el número es demasiado grande para factorizarlo en el teléfono");
        TreeMap<BigInteger, Integer> f = new TreeMap<BigInteger, Integer>();
        for (long p = 2; p < 10000 && BigInteger.valueOf(p * p).compareTo(n) <= 0; p++) {
            BigInteger bp = BigInteger.valueOf(p);
            while (n.mod(bp).signum() == 0) { f.put(bp, (f.containsKey(bp) ? f.get(bp) : 0) + 1); n = n.divide(bp); }
        }
        split(n, f);
        return f;
    }
    static void split(BigInteger n, TreeMap<BigInteger, Integer> f) {
        if (n.equals(BigInteger.ONE)) return;
        if (isPrime(n)) { f.put(n, (f.containsKey(n) ? f.get(n) : 0) + 1); return; }
        BigInteger d = rho(n);
        split(d, f); split(n.divide(d), f);
    }
    static BigInteger rho(BigInteger n) {
        if (!n.testBit(0)) return BigInteger.valueOf(2);
        for (long c = 1; c < 200; c++) {
            BigInteger x = BigInteger.valueOf(2), y = x, d = BigInteger.ONE, bc = BigInteger.valueOf(c);
            while (d.equals(BigInteger.ONE)) {
                x = x.multiply(x).add(bc).mod(n);
                y = y.multiply(y).add(bc).mod(n); y = y.multiply(y).add(bc).mod(n);
                d = x.subtract(y).abs().gcd(n);
            }
            if (!d.equals(n)) return d;
        }
        throw new ArithmeticException("no pude factorizar " + n);
    }
    public static String showFactors(TreeMap<BigInteger, Integer> f) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<BigInteger, Integer> e : f.entrySet()) { if (b.length() > 0) b.append(" · "); b.append(e.getKey()); if (e.getValue() > 1) b.append('^').append(e.getValue()); }
        return b.length() == 0 ? "1" : b.toString();
    }
    public static BigInteger product(TreeMap<BigInteger, Integer> f) { BigInteger r = BigInteger.ONE; for (Map.Entry<BigInteger, Integer> e : f.entrySet()) r = r.multiply(e.getKey().pow(e.getValue())); return r; }
    public static BigInteger lcm(BigInteger a, BigInteger b) { return a.signum() == 0 || b.signum() == 0 ? BigInteger.ZERO : a.divide(a.gcd(b)).multiply(b).abs(); }
    public static BigInteger modInverse(BigInteger a, BigInteger m) {
        if (!a.gcd(m).equals(BigInteger.ONE)) throw new ArithmeticException(a + " no tiene inverso módulo " + m + " (no son coprimos)");
        return a.modInverse(m);
    }
    public static BigInteger fibonacci(int n) {
        if (n < 0 || n > 100000) throw new IllegalArgumentException("n fuera de rango");
        BigInteger a = BigInteger.ZERO, b = BigInteger.ONE;
        for (int i = 0; i < n; i++) { BigInteger t = a.add(b); a = b; b = t; }
        return a;
    }
    public static BigInteger factorial(int n) { if (n < 0 || n > 5000) throw new IllegalArgumentException("n fuera de rango"); BigInteger r = BigInteger.ONE; for (int i = 2; i <= n; i++) r = r.multiply(BigInteger.valueOf(i)); return r; }
    public static BigInteger permutations(int n, int k) { if (k < 0 || k > n) return BigInteger.ZERO; BigInteger r = BigInteger.ONE; for (int i = n - k + 1; i <= n; i++) r = r.multiply(BigInteger.valueOf(i)); return r; }

    // ------------------------------------------------------------------ lógica proposicional
    /** Fórmula: variables (letras), no/¬/!/~, y/∧/&, o/∨/|, →/->/=>, ↔/<->/<=>, paréntesis. */
    public static final class Logic {
        final String t; int i; final TreeSet<String> vars = new TreeSet<String>();
        Logic(String s) {
            t = (" " + s.toLowerCase(java.util.Locale.ROOT) + " ").replace("<->", "↔").replace("<=>", "↔").replace("->", "→").replace("=>", "→")
                    .replace(" si y solo si ", " ↔ ").replace(" entonces ", " → ").replace(" y ", " ∧ ").replace(" o ", " ∨ ").replace(" no ", " ¬ ")
                    .replace('&', '∧').replace('|', '∨').replace('!', '¬').replace('~', '¬').trim();
        }
        interface Node { boolean v(Map<String, Boolean> m); }
        Node parse() { Node n = iff(); ws(); if (i < t.length()) throw new IllegalArgumentException("no entiendo '" + t.substring(i) + "'"); return n; }
        void ws() { while (i < t.length() && t.charAt(i) == ' ') i++; }
        boolean eat(char c) { ws(); if (i < t.length() && t.charAt(i) == c) { i++; return true; } return false; }
        Node iff() { final Node a = imp(); if (eat('↔')) { final Node b = iff(); return new Node() { public boolean v(Map<String, Boolean> m) { return a.v(m) == b.v(m); } }; } return a; }
        Node imp() { final Node a = or(); if (eat('→')) { final Node b = imp(); return new Node() { public boolean v(Map<String, Boolean> m) { return !a.v(m) || b.v(m); } }; } return a; }
        Node or() { Node a = and(); while (eat('∨')) { final Node x = a, y = and(); a = new Node() { public boolean v(Map<String, Boolean> m) { return x.v(m) || y.v(m); } }; } return a; }
        Node and() { Node a = not(); while (eat('∧')) { final Node x = a, y = not(); a = new Node() { public boolean v(Map<String, Boolean> m) { return x.v(m) && y.v(m); } }; } return a; }
        Node not() {
            if (eat('¬')) { final Node a = not(); return new Node() { public boolean v(Map<String, Boolean> m) { return !a.v(m); } }; }
            if (eat('(')) { Node a = iff(); if (!eat(')')) throw new IllegalArgumentException("falta cerrar un paréntesis"); return a; }
            ws();
            int st = i;
            while (i < t.length() && Character.isLetterOrDigit(t.charAt(i))) i++;
            if (st == i) throw new IllegalArgumentException("falta una variable");
            final String name = t.substring(st, i);
            if ("v".equals(name) || "verdadero".equals(name)) return new Node() { public boolean v(Map<String, Boolean> m) { return true; } };
            if ("f".equals(name) || "falso".equals(name)) return new Node() { public boolean v(Map<String, Boolean> m) { return false; } };
            vars.add(name);
            return new Node() { public boolean v(Map<String, Boolean> m) { return m.get(name); } };
        }
    }
    public static Map<String, Object> truthTable(String formula) {
        Logic l = new Logic(formula);
        Logic.Node n = l.parse();
        List<String> vs = new ArrayList<String>(l.vars);
        if (vs.size() > 10) throw new IllegalArgumentException("demasiadas variables (máx. 10)");
        List<String> rows = new ArrayList<String>();
        int trues = 0, total = 1 << vs.size();
        for (int r = 0; r < total; r++) {
            Map<String, Boolean> m = new HashMap<String, Boolean>();
            StringBuilder b = new StringBuilder();
            for (int j = 0; j < vs.size(); j++) { boolean v = (r >> (vs.size() - 1 - j) & 1) == 0; m.put(vs.get(j), v); b.append(v ? "V " : "F "); }
            boolean res = n.v(m);
            if (res) trues++;
            rows.add(b.append("| ").append(res ? "V" : "F").toString());
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("variables", vs);
        o.put("tabla", rows);
        o.put("clasificacion", trues == total ? "tautología (siempre verdadera)" : trues == 0 ? "contradicción (siempre falsa)" : "contingencia (" + trues + " de " + total + " filas verdaderas)");
        o.put("verificado", true);
        return o;
    }
    /** ¿Son lógicamente equivalentes? (comparación fila a fila de todas las asignaciones). */
    public static boolean equivalent(String f1, String f2) {
        Logic a = new Logic(f1), b = new Logic(f2);
        Logic.Node na = a.parse(), nb = b.parse();
        TreeSet<String> vs = new TreeSet<String>(a.vars); vs.addAll(b.vars);
        List<String> l = new ArrayList<String>(vs);
        for (int r = 0; r < 1 << l.size(); r++) {
            Map<String, Boolean> m = new HashMap<String, Boolean>();
            for (int j = 0; j < l.size(); j++) m.put(l.get(j), (r >> j & 1) == 1);
            if (na.v(m) != nb.v(m)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ grafos
    /** Aristas "A-B:4, B-C:2" (no dirigidas; "A>B:3" dirigida). Dijkstra desde src hasta dst. */
    public static Map<String, Object> shortestPath(String edges, String src, String dst) {
        Map<String, Map<String, Double>> g = new HashMap<String, Map<String, Double>>();
        for (String e : edges.split("[,;\\n]+")) {
            e = e.trim(); if (e.isEmpty()) continue;
            boolean directed = e.contains(">");
            String[] p = e.split("[->:]+");
            if (p.length != 3) throw new IllegalArgumentException("arista mal escrita: " + e + " (formato A-B:peso)");
            double w = Double.parseDouble(p[2].trim());
            if (w < 0) throw new IllegalArgumentException("Dijkstra no admite pesos negativos");
            edge(g, p[0].trim(), p[1].trim(), w);
            if (!directed) edge(g, p[1].trim(), p[0].trim(), w);
        }
        final Map<String, Double> dist = new HashMap<String, Double>();
        Map<String, String> prev = new HashMap<String, String>();
        PriorityQueue<Object[]> pq = new PriorityQueue<Object[]>(16, new java.util.Comparator<Object[]>() { public int compare(Object[] a, Object[] b) { return Double.compare((Double) a[1], (Double) b[1]); } });
        dist.put(src, 0.0); pq.add(new Object[]{src, 0.0});
        while (!pq.isEmpty()) {
            Object[] cur = pq.poll(); String u = (String) cur[0]; double d = (Double) cur[1];
            if (d > dist.get(u)) continue;
            Map<String, Double> adj = g.get(u);
            if (adj != null) for (Map.Entry<String, Double> e : adj.entrySet()) {
                double nd = d + e.getValue();
                if (!dist.containsKey(e.getKey()) || nd < dist.get(e.getKey())) { dist.put(e.getKey(), nd); prev.put(e.getKey(), u); pq.add(new Object[]{e.getKey(), nd}); }
            }
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        if (!dist.containsKey(dst)) { o.put("camino", "no hay camino de " + src + " a " + dst); o.put("verificado", true); return o; }
        List<String> path = new ArrayList<String>();
        for (String at = dst; at != null; at = prev.get(at)) path.add(0, at);
        double check = 0;
        for (int i = 0; i + 1 < path.size(); i++) check += g.get(path.get(i)).get(path.get(i + 1));
        o.put("camino", Algebra.join(path, " → "));
        o.put("costo", Num.fmt(dist.get(dst)));
        o.put("verificado", Math.abs(check - dist.get(dst)) < 1e-9);
        return o;
    }
    static void edge(Map<String, Map<String, Double>> g, String a, String b, double w) {
        if (!g.containsKey(a)) g.put(a, new HashMap<String, Double>());
        Double old = g.get(a).get(b);
        if (old == null || w < old) g.get(a).put(b, w);
    }
}
