package com.radar.jarvis.core.ciencia;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * QUÍMICA COMPUTACIONAL (sin laboratorio físico): tabla de masas atómicas (pesos atómicos convencionales IUPAC),
 * lectura de fórmulas (paréntesis, corchetes, hidratos, cargas), masa molar, composición porcentual, fórmula empírica,
 * BALANCEO de ecuaciones por álgebra lineal exacta (espacio nulo racional) con verificación átomo por átomo,
 * estequiometría con reactivo limitante, pH, diluciones y molaridad.
 * SEGURIDAD: {@link #danger(String)} detecta pedidos de síntesis de sustancias peligrosas; el cerebro los rechaza.
 */
public final class Quimica {
    private Quimica() {}

    static final String TABLE =
        "H 1.008,He 4.0026,Li 6.94,Be 9.0122,B 10.81,C 12.011,N 14.007,O 15.999,F 18.998,Ne 20.180,Na 22.990,Mg 24.305,Al 26.982,Si 28.085,P 30.974,S 32.06,Cl 35.45,Ar 39.95," +
        "K 39.098,Ca 40.078,Sc 44.956,Ti 47.867,V 50.942,Cr 51.996,Mn 54.938,Fe 55.845,Co 58.933,Ni 58.693,Cu 63.546,Zn 65.38,Ga 69.723,Ge 72.630,As 74.922,Se 78.971,Br 79.904,Kr 83.798," +
        "Rb 85.468,Sr 87.62,Y 88.906,Zr 91.224,Nb 92.906,Mo 95.95,Tc 98,Ru 101.07,Rh 102.91,Pd 106.42,Ag 107.87,Cd 112.41,In 114.82,Sn 118.71,Sb 121.76,Te 127.60,I 126.90,Xe 131.29," +
        "Cs 132.91,Ba 137.33,La 138.91,Ce 140.12,Pr 140.91,Nd 144.24,Pm 145,Sm 150.36,Eu 151.96,Gd 157.25,Tb 158.93,Dy 162.50,Ho 164.93,Er 167.26,Tm 168.93,Yb 173.05,Lu 174.97," +
        "Hf 178.49,Ta 180.95,W 183.84,Re 186.21,Os 190.23,Ir 192.22,Pt 195.08,Au 196.97,Hg 200.59,Tl 204.38,Pb 207.2,Bi 208.98,Po 209,At 210,Rn 222,Fr 223,Ra 226,Ac 227,Th 232.04,Pa 231.04,U 238.03";
    public static final Map<String, Double> MASS = new LinkedHashMap<String, Double>();
    public static final Map<String, Integer> Z = new HashMap<String, Integer>();
    static {
        int z = 1;
        for (String e : TABLE.split(",")) { String[] p = e.split(" "); MASS.put(p[0], Double.parseDouble(p[1])); Z.put(p[0], z++); }
    }

    /** Fórmula → {elemento → cantidad}; la carga va en la clave "carga". "CuSO4·5H2O", "Ca(OH)2", "SO4^2-", "Fe^3+". */
    public static TreeMap<String, Integer> parse(String f) {
        f = f.trim();
        int charge = 0;
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("\\^?\\{?(\\d*)([+-])\\}?$").matcher(f);
        if (cm.find() && cm.start() > 0) {
            int mag = cm.group(1).isEmpty() ? 1 : Integer.parseInt(cm.group(1));
            charge = "+".equals(cm.group(2)) ? mag : -mag;
            f = f.substring(0, cm.start());
        }
        TreeMap<String, Integer> total = new TreeMap<String, Integer>();
        for (String part : f.split("[·*.•]")) {
            part = part.trim(); if (part.isEmpty()) continue;
            int mult = 1, i = 0;
            while (i < part.length() && Character.isDigit(part.charAt(i))) i++;
            if (i > 0) { mult = Integer.parseInt(part.substring(0, i)); part = part.substring(i); }
            int[] pos = {0};
            Map<String, Integer> g = group(part, pos);
            if (pos[0] != part.length()) throw new IllegalArgumentException("fórmula mal escrita cerca de '" + part.substring(pos[0]) + "'");
            for (Map.Entry<String, Integer> e : g.entrySet()) add(total, e.getKey(), e.getValue() * mult);
        }
        if (total.isEmpty()) throw new IllegalArgumentException("fórmula vacía");
        if (charge != 0) total.put("carga", charge);
        return total;
    }
    static Map<String, Integer> group(String s, int[] pos) {
        Map<String, Integer> m = new TreeMap<String, Integer>();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '(' || c == '[') {
                pos[0]++;
                Map<String, Integer> inner = group(s, pos);
                if (pos[0] >= s.length() || (s.charAt(pos[0]) != ')' && s.charAt(pos[0]) != ']')) throw new IllegalArgumentException("falta cerrar un paréntesis en la fórmula");
                pos[0]++;
                int k = number(s, pos);
                for (Map.Entry<String, Integer> e : inner.entrySet()) add(m, e.getKey(), e.getValue() * k);
            } else if (c == ')' || c == ']') {
                return m;
            } else if (Character.isUpperCase(c)) {
                int st = pos[0]++;
                while (pos[0] < s.length() && Character.isLowerCase(s.charAt(pos[0]))) pos[0]++;
                String el = s.substring(st, pos[0]);
                if (!MASS.containsKey(el)) throw new IllegalArgumentException("elemento desconocido: " + el);
                add(m, el, number(s, pos));
            } else throw new IllegalArgumentException("carácter inesperado '" + c + "' en la fórmula");
        }
        return m;
    }
    static int number(String s, int[] pos) {
        int st = pos[0];
        while (pos[0] < s.length() && Character.isDigit(s.charAt(pos[0]))) pos[0]++;
        return st == pos[0] ? 1 : Integer.parseInt(s.substring(st, pos[0]));
    }
    static void add(Map<String, Integer> m, String k, int v) { m.put(k, (m.containsKey(k) ? m.get(k) : 0) + v); }

    public static double molarMass(String formula) {
        double s = 0;
        for (Map.Entry<String, Integer> e : parse(formula).entrySet()) if (!"carga".equals(e.getKey())) s += MASS.get(e.getKey()) * e.getValue();
        return s;
    }
    public static Map<String, Object> describe(String formula) {
        TreeMap<String, Integer> c = parse(formula);
        double M = molarMass(formula);
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("formula", formula);
        o.put("masa_molar", Num.fmt(Num.sig(M, 6)) + " g/mol");
        o.put("valor", M);
        Map<String, String> comp = new LinkedHashMap<String, String>();
        double check = 0;
        for (Map.Entry<String, Integer> e : c.entrySet()) {
            if ("carga".equals(e.getKey())) continue;
            double pct = MASS.get(e.getKey()) * e.getValue() / M * 100;
            check += pct;
            comp.put(e.getKey() + " ×" + e.getValue(), Num.fmt(Num.sig(pct, 5)) + " %");
        }
        o.put("composicion", comp);
        if (c.containsKey("carga")) o.put("carga", c.get("carga"));
        o.put("verificado", Math.abs(check - 100) < 1e-9);
        o.put("fuente_de_masas", "pesos atómicos convencionales IUPAC (abreviados)");
        return o;
    }
    /** Fórmula empírica a partir de % en masa {"C": 40.0, "H": 6.71, "O": 53.29}. */
    public static String empirical(Map<String, Double> pct) {
        Map<String, Double> mol = new LinkedHashMap<String, Double>();
        double min = Double.MAX_VALUE;
        for (Map.Entry<String, Double> e : pct.entrySet()) {
            Double m = MASS.get(e.getKey());
            if (m == null) throw new IllegalArgumentException("elemento desconocido: " + e.getKey());
            double n = e.getValue() / m; mol.put(e.getKey(), n); min = Math.min(min, n);
        }
        for (int mult = 1; mult <= 12; mult++) {
            boolean ok = true;
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Double> e : mol.entrySet()) {
                double r = e.getValue() / min * mult;
                if (Math.abs(r - Math.round(r)) > 0.08) { ok = false; break; }
                b.append(e.getKey()); if (Math.round(r) != 1) b.append(Math.round(r));
            }
            if (ok) return b.toString();
        }
        throw new IllegalArgumentException("no encontré proporciones enteras simples (¿datos con mucho error?)");
    }

    // ------------------------------------------------------------------ balanceo
    public static final class Balanced {
        public final List<String> reactants = new ArrayList<String>(), products = new ArrayList<String>();
        public final List<BigInteger> coef = new ArrayList<BigInteger>();
        public String text; public boolean verified;
    }
    /** "Fe + O2 -> Fe2O3", "C3H8 + O2 = CO2 + H2O", "MnO4^- + Fe^2+ + H^+ -> Mn^2+ + Fe^3+ + H2O". */
    public static Balanced balance(String eq) {
        String[] sides = eq.replace("→", "->").replace("⟶", "->").replace("=>", "->").replace("<->", "->").split("->|=", 2);
        if (sides.length != 2) throw new IllegalArgumentException("separá reactivos y productos con '->' o '='");
        Balanced b = new Balanced();
        for (String s : splitSpecies(sides[0])) b.reactants.add(s);
        for (String s : splitSpecies(sides[1])) b.products.add(s);
        List<String> all = new ArrayList<String>(b.reactants); all.addAll(b.products);
        List<TreeMap<String, Integer>> comps = new ArrayList<TreeMap<String, Integer>>();
        java.util.TreeSet<String> elements = new java.util.TreeSet<String>();
        for (String s : all) { TreeMap<String, Integer> c = parse(s); comps.add(c); elements.addAll(c.keySet()); }
        for (String el : elements) {  // un elemento que aparece de un solo lado no se puede conservar
            if ("carga".equals(el)) continue;
            boolean l = false, r = false;
            for (int j = 0; j < all.size(); j++) if (comps.get(j).containsKey(el)) { if (j < b.reactants.size()) l = true; else r = true; }
            if (!(l && r)) throw new IllegalArgumentException("el elemento " + el + " aparece solo de un lado: la reacción está mal planteada");
        }
        List<String> rows = new ArrayList<String>(elements);
        int n = all.size();
        Q[][] a = new Q[rows.size()][n + 1];
        for (int i = 0; i < rows.size(); i++) {
            for (int j = 0; j < n; j++) {
                Integer v = comps.get(j).get(rows.get(i));
                int x = v == null ? 0 : v;
                a[i][j] = Q.of(j < b.reactants.size() ? x : -x);
            }
            a[i][n] = Q.ZERO;
        }
        int rank = Algebra.rref(a, n);
        if (rank != n - 1) throw new IllegalArgumentException(rank >= n ? "no hay forma de balancearla (¿especies mal escritas?)" : "tiene más de un balanceo independiente: hay que separarla en reacciones");
        // variable libre = la columna sin pivote → 1; el resto se despeja
        int free = -1;
        boolean[] pivot = new boolean[n];
        int[] pivRow = new int[n];
        for (int i = 0; i < rank; i++) for (int j = 0; j < n; j++) if (!a[i][j].isZero()) { pivot[j] = true; pivRow[j] = i; break; }
        for (int j = 0; j < n; j++) if (!pivot[j]) free = j;
        Q[] x = new Q[n];
        for (int j = 0; j < n; j++) x[j] = j == free ? Q.ONE : a[pivRow[j]][free].neg();
        BigInteger lcm = BigInteger.ONE;
        for (Q q : x) lcm = lcm.divide(lcm.gcd(q.d)).multiply(q.d);
        BigInteger g = BigInteger.ZERO;
        List<BigInteger> ints = new ArrayList<BigInteger>();
        for (Q q : x) { BigInteger v = q.mul(Q.of(lcm)).n; ints.add(v); g = g.gcd(v); }
        boolean neg = ints.get(0).signum() < 0;
        for (BigInteger v : ints) {
            BigInteger w = v.divide(g); if (neg) w = w.negate();
            if (w.signum() <= 0) throw new IllegalArgumentException("no hay balanceo con coeficientes positivos (revisá la reacción)");
            b.coef.add(w);
        }
        // verificación átomo por átomo (y carga)
        boolean ok = true;
        for (String el : elements) {
            BigInteger l = BigInteger.ZERO, r = BigInteger.ZERO;
            for (int j = 0; j < n; j++) {
                Integer v = comps.get(j).get(el);
                if (v == null) continue;
                BigInteger t = b.coef.get(j).multiply(BigInteger.valueOf(v));
                if (j < b.reactants.size()) l = l.add(t); else r = r.add(t);
            }
            if (!l.equals(r)) ok = false;
        }
        b.verified = ok;
        StringBuilder t = new StringBuilder();
        for (int j = 0; j < n; j++) {
            if (j > 0) t.append(j == b.reactants.size() ? " → " : " + ");
            if (!b.coef.get(j).equals(BigInteger.ONE)) t.append(b.coef.get(j)).append(' ');
            t.append(all.get(j));
        }
        b.text = t.toString();
        return b;
    }
    static List<String> splitSpecies(String side) {
        List<String> o = new ArrayList<String>();
        // separa por " + " (con espacios) para no romper cargas como Fe^3+
        for (String s : side.trim().split("\\s+\\+\\s+")) {
            s = s.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\s+(.*)$").matcher(s);  // coeficiente escrito por el usuario: se ignora
            if (m.find()) s = m.group(2);
            if (!s.isEmpty()) o.add(s);
        }
        if (o.isEmpty()) throw new IllegalArgumentException("falta un lado de la reacción");
        return o;
    }

    /** Reactivo limitante y rendimiento teórico: masas en gramos de los reactivos (en el orden de la ecuación). */
    public static Map<String, Object> stoichiometry(String eq, double[] gramsReactants) {
        Balanced b = balance(eq);
        if (gramsReactants.length != b.reactants.size()) throw new IllegalArgumentException("indicá la masa de cada reactivo (" + b.reactants.size() + ")");
        int lim = -1; double best = Double.MAX_VALUE;
        Map<String, String> moles = new LinkedHashMap<String, String>();
        for (int i = 0; i < b.reactants.size(); i++) {
            double n = gramsReactants[i] / molarMass(b.reactants.get(i));
            moles.put(b.reactants.get(i), Num.fmt(Num.sig(n, 6)) + " mol");
            double ext = n / b.coef.get(i).doubleValue();
            if (ext < best) { best = ext; lim = i; }
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("ecuacion", b.text);
        o.put("moles", moles);
        o.put("reactivo_limitante", b.reactants.get(lim));
        Map<String, String> prod = new LinkedHashMap<String, String>();
        double massIn = 0, massOut = 0;
        for (int i = 0; i < b.reactants.size(); i++) massIn += best * b.coef.get(i).doubleValue() * molarMass(b.reactants.get(i));
        for (int j = 0; j < b.products.size(); j++) {
            double g = best * b.coef.get(b.reactants.size() + j).doubleValue() * molarMass(b.products.get(j));
            massOut += g;
            prod.put(b.products.get(j), Num.fmt(Num.sig(g, 6)) + " g");
        }
        o.put("rendimiento_teorico", prod);
        o.put("verificado", b.verified && Num.close(massIn, massOut, 1e-3));  // conservación de la masa (con masas atómicas redondeadas)
        o.put("verificacion", "balanceo comprobado átomo por átomo; masa que reacciona " + Num.fmt(Num.sig(massIn, 6)) + " g = masa producida " + Num.fmt(Num.sig(massOut, 6)) + " g");
        return o;
    }

    // ------------------------------------------------------------------ soluciones
    public static final double KW = 1e-14;  // a 25 °C
    /** pH de un ácido fuerte monoprótico (incluye el agua cuando está muy diluido). */
    public static double phStrongAcid(double c) { double h = c / 2 + Math.sqrt(c * c / 4 + KW); return -Math.log10(h); }
    public static double phStrongBase(double c) { double oh = c / 2 + Math.sqrt(c * c / 4 + KW); return 14 + Math.log10(oh); }
    /** Ácido débil: x² / (C - x) = Ka → x por la cuadrática exacta (sin aproximar). */
    public static double phWeakAcid(double c, double ka) { double x = (-ka + Math.sqrt(ka * ka + 4 * ka * c)) / 2; return -Math.log10(x); }
    public static double dilution(double c1, double v1, double c2) { return c1 * v1 / c2; }  // V2
    public static double molarity(double grams, String formula, double liters) { return grams / molarMass(formula) / liters; }

    // ------------------------------------------------------------------ seguridad química
    static final String[] TARGETS = {"explosiv", "bomba", "tatp", "triperóxido", "nitroglicerina", "rdx", "petn", "hmx", "c-4", "c4 ", "pólvora negra", "napalm", "molotov",
            "sarín", "sarin", "tabún", "tabun", "somán", "soman", "vx", "novichok", "agente nervioso", "gas nervioso", "gas mostaza", "iperita", "fosgeno", "cloro gaseoso como arma",
            "ricina", "ántrax", "antrax", "toxina botulínica", "cianuro de hidrógeno", "ácido cianhídrico", "metanfetamina", "fentanilo", "cocaína", "heroína", "lsd", "mdma", "éxtasis", "arma química", "arma biológica"};
    static final String[] HOW = {"sintetiz", "síntesis", "fabric", "produc", "prepar", "elabor", "cómo hacer", "como hacer", "hacer un", "hacer una", "receta", "obten", "conseguir", "cocinar", "armar", "precursor", "paso a paso", "instrucciones"};
    /** Devuelve el motivo si el pedido busca fabricar algo peligroso; null si es seguro (p. ej. calcular la masa molar del TNT). */
    public static String danger(String text) {
        String t = " " + text.toLowerCase(java.util.Locale.ROOT) + " ";
        String target = null;
        for (String k : TARGETS) if (t.contains(k)) { target = k.trim(); break; }
        if (target == null) return null;
        for (String h : HOW) if (t.contains(h)) return "pide cómo obtener o fabricar una sustancia peligrosa (" + target + "): no doy instrucciones de síntesis ni de preparación";
        return null;
    }
}
