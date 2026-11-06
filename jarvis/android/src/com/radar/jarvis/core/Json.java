package com.radar.jarvis.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mínimo (sin dependencias): Map, List, String, Double/Long, Boolean, null. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw new IllegalArgumentException("JSON: sobra texto en " + j.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    public static String str(Object o) { return o instanceof String ? (String) o : null; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON incompleto");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i) != ':') throw new IllegalArgumentException("JSON: falta ':' en " + i);
            i++; ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("JSON: falta ',' en " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("JSON: falta ',' en " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON: se esperaba texto en " + i);
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(st, i);
        if (n.isEmpty()) throw new IllegalArgumentException("JSON: valor no válido en " + st);
        if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.valueOf(n);
        return Long.valueOf(n);
    }

    // ------------------------------------------------------------------ escribir
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder b) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { quote((String) o, b); return; }
        if (o instanceof Number || o instanceof Boolean) { b.append(o.toString()); return; }
        if (o instanceof Map) {
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = ((Map<String, Object>) o).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                quote(e.getKey(), b); b.append(':'); write(e.getValue(), b);
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
            return;
        }
        if (o instanceof List) {
            b.append('[');
            List<Object> l = (List<Object>) o;
            for (int k = 0; k < l.size(); k++) { if (k > 0) b.append(','); write(l.get(k), b); }
            b.append(']');
            return;
        }
        quote(o.toString(), b);
    }

    private static void quote(String v, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    /** Ayuda para construir objetos: Json.map("a", 1, "b", "x"). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object o : items) l.add(o);
        return l;
    }
}
