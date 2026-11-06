package com.radar.jarvis.core.ciencia;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UNIDADES Y DIMENSIONES (SI): cada unidad = factor a SI + vector de dimensiones [m, kg, s, A, K, mol, cd].
 * Convierte solo entre unidades de la MISMA dimensión (si no, explica el error dimensional). Admite prefijos (k, M, m, µ…),
 * productos y cocientes ("km/h", "kg·m/s^2", "N m"). Temperaturas con desplazamiento (°C, °F) solo en conversión directa.
 */
public final class Unidades {
    private Unidades() {}
    static final String[] DIM = {"m", "kg", "s", "A", "K", "mol", "cd"};

    public static final class U {
        public final double f; public final int[] d;
        U(double f, int... d) { this.f = f; this.d = new int[7]; System.arraycopy(d, 0, this.d, 0, d.length); }
        U mul(U o) { int[] r = new int[7]; for (int i = 0; i < 7; i++) r[i] = d[i] + o.d[i]; return new U(f * o.f, r); }
        U pow(int e) { int[] r = new int[7]; for (int i = 0; i < 7; i++) r[i] = d[i] * e; return new U(Math.pow(f, e), r); }
        public boolean sameDim(U o) { return java.util.Arrays.equals(d, o.d); }
        public String dims() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 7; i++) if (d[i] != 0) { if (b.length() > 0) b.append('·'); b.append(DIM[i]); if (d[i] != 1) b.append('^').append(d[i]); }
            return b.length() == 0 ? "adimensional" : b.toString();
        }
    }

    static final Map<String, U> BASE = new HashMap<String, U>();
    static void put(String names, U u) { for (String n : names.split(",")) BASE.put(n, u); }
    static {
        put("m,metro,metros", new U(1, 1));
        put("g,gramo,gramos", new U(1e-3, 0, 1));
        put("s,seg,segundo,segundos", new U(1, 0, 0, 1));
        put("A,amperio,amperios,ampere", new U(1, 0, 0, 0, 1));
        put("K,kelvin", new U(1, 0, 0, 0, 0, 1));
        put("mol", new U(1, 0, 0, 0, 0, 0, 1));
        put("cd", new U(1, 0, 0, 0, 0, 0, 0, 1));
        put("min,minuto,minutos", new U(60, 0, 0, 1));
        put("h,hora,horas", new U(3600, 0, 0, 1));
        put("d,dia,día,dias,días", new U(86400, 0, 0, 1));
        put("año,anio,años", new U(365.25 * 86400, 0, 0, 1));
        put("in,pulgada,pulgadas", new U(0.0254, 1));
        put("ft,pie,pies", new U(0.3048, 1));
        put("mi,milla,millas", new U(1609.344, 1));
        put("nmi", new U(1852, 1));
        put("ua,au", new U(149597870700.0, 1));
        put("ly,al", new U(9460730472580800.0, 1));
        put("L,l,litro,litros", new U(1e-3, 3));
        put("t,tonelada,toneladas", new U(1000, 0, 1));
        put("lb,libra,libras", new U(0.45359237, 0, 1));
        put("oz", new U(0.028349523125, 0, 1));
        put("N,newton,newtons", new U(1, 1, 1, -2));
        put("J,joule,julio,julios", new U(1, 2, 1, -2));
        put("W,watt,vatio,vatios", new U(1, 2, 1, -3));
        put("Pa,pascal", new U(1, -1, 1, -2));
        put("bar", new U(1e5, -1, 1, -2));
        put("atm", new U(101325, -1, 1, -2));
        put("mmHg", new U(133.322387415, -1, 1, -2));          // mercurio convencional
        put("torr,Torr", new U(101325.0 / 760, -1, 1, -2));     // exactamente 1/760 atm (≠ mmHg en la 7.ª cifra)
        put("psi", new U(6894.757293168, -1, 1, -2));
        put("Hz,hercio", new U(1, 0, 0, -1));
        put("C,coulomb", new U(1, 0, 0, 1, 1));
        put("V,voltio,voltios", new U(1, 2, 1, -3, -1));
        put("Ω,ohm,ohmio,ohmios", new U(1, 2, 1, -3, -2));
        put("F,faradio", new U(1, -2, -1, 4, 2));
        put("T,tesla", new U(1, 0, 1, -2, -1));
        put("Wh", new U(3600, 2, 1, -2));
        put("cal,caloría,calorias,calorías", new U(4.184, 2, 1, -2));
        put("eV", new U(1.602176634e-19, 2, 1, -2));
        put("cv,hp", new U(735.49875, 2, 1, -3));
        put("rad", new U(1));
        put("°,deg,grado,grados", new U(Math.PI / 180));
        put("M,molar", new U(1000, -3, 0, 0, 0, 0, 1));  // mol/L
        put("Da,u", new U(1.66053906660e-27, 0, 1));
    }
    static final String[][] PREFIX = {{"Y", "1e24"}, {"Z", "1e21"}, {"E", "1e18"}, {"P", "1e15"}, {"T", "1e12"}, {"G", "1e9"}, {"M", "1e6"}, {"k", "1e3"}, {"h", "1e2"}, {"da", "1e1"},
            {"d", "1e-1"}, {"c", "1e-2"}, {"m", "1e-3"}, {"µ", "1e-6"}, {"u", "1e-6"}, {"n", "1e-9"}, {"p", "1e-12"}, {"f", "1e-15"}};

    static final String PREFIXABLE = ",m,g,s,A,K,mol,cd,L,l,N,J,W,Pa,Hz,C,V,Ω,F,T,Wh,eV,cal,M,bar,";

    /** Una unidad simple (con prefijo y exponente): "km", "s^2", "cm³". */
    static U atom(String s) {
        int exp = 1;
        s = s.replace("²", "^2").replace("³", "^3");
        int c = s.indexOf('^');
        if (c > 0) { exp = Integer.parseInt(s.substring(c + 1)); s = s.substring(0, c); }
        U u = BASE.get(s);
        if (u == null) for (String[] p : PREFIX) if (s.startsWith(p[0]) && s.length() > p[0].length()) {
            String rest = s.substring(p[0].length());
            if (PREFIXABLE.contains("," + rest + ",") && BASE.containsKey(rest)) { U b = BASE.get(rest); u = new U(b.f * Double.parseDouble(p[1]), b.d); break; }
        }
        if (u == null) throw new IllegalArgumentException("unidad desconocida: " + s);
        return u.pow(exp);
    }
    /** Unidad compuesta: "kg·m/s^2", "km/h", "N m", "mol/L". */
    public static U parse(String s) {
        s = s.trim().replace("*", "·").replace(" ", "·").replace("°C", "K").replace("ºC", "K");
        if (s.isEmpty() || "1".equals(s)) return new U(1);
        U r = new U(1);
        String[] parts = s.split("/");
        for (int i = 0; i < parts.length; i++) for (String a : parts[i].split("·")) {
            if (a.isEmpty()) continue;
            U u = atom(a);
            r = r.mul(i == 0 ? u : u.pow(-1));
        }
        return r;
    }

    /** Convierte value de "from" a "to". Temperaturas: C, F, K directas. */
    public static Map<String, Object> convert(double value, String from, String to) {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        Double t = temperature(value, from, to);
        if (t != null) {
            o.put("resultado", Num.fmt(Num.sig(t, 12)) + " " + to);
            double back = temperature(t, to, from);
            o.put("verificado", Math.abs(back - value) < 1e-9 * Math.max(1, Math.abs(value)));
            return o;
        }
        U a = parse(from), b = parse(to);
        if (!a.sameDim(b)) throw new IllegalArgumentException("error dimensional: " + from + " es " + a.dims() + " y " + to + " es " + b.dims() + "; no se pueden convertir");
        double r = value * a.f / b.f;
        o.put("resultado", Num.fmt(Num.sig(r, 12)) + " " + to);
        o.put("valor", r);
        o.put("dimension", a.dims());
        o.put("verificado", Num.close(r * b.f / a.f, value, 1e-12) || value == 0);
        return o;
    }
    static Double temperature(double v, String from, String to) {
        String f = tk(from), t = tk(to);
        if (f == null || t == null) return null;
        double k = "C".equals(f) ? v + 273.15 : "F".equals(f) ? (v - 32) * 5 / 9 + 273.15 : v;
        return "C".equals(t) ? k - 273.15 : "F".equals(t) ? (k - 273.15) * 9 / 5 + 32 : k;
    }
    static String tk(String u) {
        u = u.trim().replace("º", "°");
        if ("°C".equals(u) || "C°".equals(u) || "celsius".equalsIgnoreCase(u)) return "C";
        if ("°F".equals(u) || "F°".equals(u) || "fahrenheit".equalsIgnoreCase(u)) return "F";
        if ("K".equals(u) || "kelvin".equalsIgnoreCase(u)) return "K";
        return null;
    }
    /** Comprueba que una fórmula es dimensionalmente coherente: dims(izquierda) == dims(derecha). */
    public static boolean coherent(String left, String right) { return parse(left).sameDim(parse(right)); }
}
