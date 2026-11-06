package com.radar.jarvis.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LABORATORIO DE AUTOMEJORA (supervisado).
 * JARVIS puede preparar herramientas nuevas (JavaScript puro: entrada JSON → salida JSON, sin red, sin archivos,
 * sin acceso al teléfono), con sus pruebas. Cada versión queda guardada aparte (versionado), se prueba aislada,
 * y SOLO se activa con la aprobación del usuario. Antes de cada cambio se copia el estado (respaldo) y todo
 * queda en el registro. Se puede volver a la versión anterior o desactivar.
 *
 * Lo que el laboratorio NO puede hacer, por diseño: tocar permisos, claves, PAUSA, límites de riesgo, la regla de
 * disciplina ni su propio código. Esta clase solo escribe dentro de files/lab y no conoce esos módulos.
 * Las mejoras que necesitan cambiar la app (código Android) se guardan como PROPUESTAS para el desarrollador.
 */
public final class Lab {
    /** Ejecuta JavaScript aislado y devuelve el texto que produce. En Android: WebView sin red ni archivos. */
    public interface Sandbox { String run(String js, long timeoutMs) throws Exception; }

    public static final long TIMEOUT_MS = 4000;
    /** Palabras que no puede contener una herramienta (red, almacenamiento, escapes del aislamiento, temporizadores). */
    static final String[] FORBIDDEN = {"fetch", "XMLHttpRequest", "WebSocket", "EventSource", "eval", "Function", "import", "require",
            "process", "globalThis", "window", "self", "document", "navigator", "location", "localStorage", "sessionStorage", "indexedDB",
            "Android", "constructor", "__proto__", "prototype", "setTimeout", "setInterval", "postMessage", "Worker", "Proxy", "Reflect", "with"};

    private final File dir;

    public Lab(File dataDir) {
        this.dir = new File(dataDir, "lab");
        new File(dir, "herramientas").mkdirs();
        new File(dir, "respaldos").mkdirs();
        new File(dir, "propuestas").mkdirs();
    }

    // ================================================================== validación
    /** Problemas del código (vacío = se puede probar). */
    public static List<String> check(String code) {
        List<String> p = new ArrayList<String>();
        if (code == null || code.trim().isEmpty()) { p.add("no hay código"); return p; }
        if (code.length() > 20000) p.add("el código pasa de 20.000 caracteres");
        if (!Pattern.compile("function\\s+run\\s*\\(").matcher(code).find()) p.add("falta la función run(entrada)");
        String noStrings = code.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", "\"\"");
        noStrings = noStrings.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        for (String f : FORBIDDEN)
            if (Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(f) + "(?![A-Za-z0-9_$])").matcher(noStrings).find()) p.add("usa «" + f + "», que no está permitido");
        if (noStrings.contains("`")) p.add("no se permiten plantillas con comillas invertidas");
        if (Pattern.compile("\\[\\s*['\"]").matcher(code).find()) p.add("no se permite acceder a propiedades por texto entre corchetes");
        return p;
    }

    /** Programa de prueba: define run() en un ámbito cerrado, ejecuta cada caso y devuelve JSON con los resultados. */
    public static String harness(String code, List<Object> tests) {
        return "(function(){\"use strict\";\n"
                + "function __eq(a,b){return JSON.stringify(a)===JSON.stringify(b);}\n"
                + "var run=(function(){\"use strict\";\n" + code + "\n;return run;})();\n"
                + "var T=" + Json.write(tests) + ",R=[];\n"
                + "for(var i=0;i<T.length;i++){try{var o=run(JSON.parse(JSON.stringify(T[i].entrada)));"
                + "R.push({ok:__eq(o,T[i].esperado),salida:o===undefined?null:o});}catch(e){R.push({ok:false,error:String(e&&e.message||e)});}}\n"
                + "return JSON.stringify(R);})()";
    }

    /** Programa para usar una herramienta aprobada con una entrada. */
    public static String single(String code, Object input) {
        return "(function(){\"use strict\";var run=(function(){\"use strict\";\n" + code + "\n;return run;})();"
                + "try{return JSON.stringify({ok:true,salida:run(" + Json.write(input) + ")});}catch(e){return JSON.stringify({ok:false,error:String(e&&e.message||e)});}})()";
    }

    // ================================================================== candidatas y versiones
    /** Guarda una versión NUEVA (candidata, inactiva). Devuelve {id, version, problemas}. */
    public synchronized Map<String, Object> addCandidate(String id, String name, String description, String code, List<Object> tests, String why, String origin) throws IOException {
        id = slug(id == null || id.isEmpty() ? name : id);
        if (id.isEmpty()) throw new IOException("la herramienta necesita un nombre");
        if (tests == null || tests.size() < 2) throw new IOException("hacen falta al menos 2 pruebas");
        List<String> probs = check(code);
        int v = versions(id).size() + 1;
        File vd = new File(new File(dir, "herramientas/" + id), "v" + v);
        vd.mkdirs();
        write(new File(vd, "herramienta.js"), code == null ? "" : code);
        write(new File(vd, "pruebas.json"), Json.write(tests));
        Map<String, Object> meta = Json.map("id", id, "nombre", name, "descripcion", description, "version", (long) v,
                "estado", probs.isEmpty() ? "candidata" : "rechazada por seguridad", "problemas", new ArrayList<Object>(probs),
                "por_que", why, "origen", origin, "creada", now());
        write(new File(vd, "meta.json"), Json.write(meta));
        log("nueva versión", id, v, probs.isEmpty() ? "candidata: " + description : "rechazada: " + probs);
        return Json.map("id", id, "version", (long) v, "problemas", new ArrayList<Object>(probs));
    }

    /** Ejecuta las pruebas de una versión en el aislamiento y guarda el resultado. */
    public synchronized Map<String, Object> test(String id, int v, Sandbox sb) throws IOException {
        Map<String, Object> meta = meta(id, v);
        if (meta == null) throw new IOException("no existe " + id + " v" + v);
        String code = read(new File(vdir(id, v), "herramienta.js"));
        if (!check(code).isEmpty()) throw new IOException("el código no pasa el control de seguridad: " + check(code));
        List<Object> tests = Json.arr(Json.parse(read(new File(vdir(id, v), "pruebas.json"))));
        int pass = 0; String err = null; List<Object> res = new ArrayList<Object>();
        try {
            String out = sb.run(harness(code, tests), TIMEOUT_MS);
            List<Object> r = Json.arr(Json.parse(out));
            if (r == null) throw new IOException("respuesta inesperada del aislamiento");
            res = r;
            for (Object o : r) if (Boolean.TRUE.equals(Json.obj(o).get("ok"))) pass++;
        } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
        boolean ok = err == null && pass == tests.size();
        meta.put("estado", ok ? "probada" : "fallida");
        meta.put("pruebas", Json.map("pasan", (long) pass, "total", (long) tests.size(), "error", err, "resultados", res, "fecha", now()));
        write(new File(vdir(id, v), "meta.json"), Json.write(meta));
        log("pruebas", id, v, pass + "/" + tests.size() + (err == null ? "" : " · " + err));
        return Json.map("ok", ok, "pasan", (long) pass, "total", (long) tests.size(), "error", err);
    }

    /** Activa una versión probada. SOLO desde una acción del usuario (byUser = true): respaldo, activación y registro. */
    public synchronized void approve(String id, int v, boolean byUser) throws IOException {
        if (!byUser) throw new IOException("solo el usuario puede aprobar cambios");
        Map<String, Object> meta = meta(id, v);
        if (meta == null) throw new IOException("no existe " + id + " v" + v);
        if (!"probada".equals(meta.get("estado"))) throw new IOException("solo se aprueba una versión que pasó todas sus pruebas (estado: " + meta.get("estado") + ")");
        backup();
        Map<String, Object> act = active();
        Map<String, Object> cur = Json.obj(act.get(id));
        List<Object> hist = cur == null || Json.arr(cur.get("historial")) == null ? new ArrayList<Object>() : Json.arr(cur.get("historial"));
        if (cur != null) hist.add(cur.get("version"));
        act.put(id, Json.map("version", (long) v, "nombre", meta.get("nombre"), "descripcion", meta.get("descripcion"), "historial", hist, "aprobada", now()));
        write(new File(dir, "activas.json"), Json.write(act));
        meta.put("estado", "activa");
        write(new File(vdir(id, v), "meta.json"), Json.write(meta));
        log("aprobada por el usuario", id, v, "activada");
    }

    public synchronized void reject(String id, int v) throws IOException {
        Map<String, Object> meta = meta(id, v);
        if (meta == null) return;
        meta.put("estado", "rechazada por el usuario");
        write(new File(vdir(id, v), "meta.json"), Json.write(meta));
        log("rechazada por el usuario", id, v, "");
    }

    /** Vuelve a la versión anterior (o desactiva si no hay). */
    public synchronized String rollback(String id) throws IOException {
        Map<String, Object> act = active();
        Map<String, Object> cur = Json.obj(act.get(id));
        if (cur == null) throw new IOException(id + " no está activa");
        backup();
        List<Object> hist = Json.arr(cur.get("historial"));
        if (hist == null || hist.isEmpty()) { act.remove(id); write(new File(dir, "activas.json"), Json.write(act)); log("desactivada (sin versión anterior)", id, num(cur.get("version")), ""); return "desactivada"; }
        long prev = ((Number) hist.remove(hist.size() - 1)).longValue();
        cur.put("version", prev);
        write(new File(dir, "activas.json"), Json.write(act));
        log("restaurada versión anterior", id, (int) prev, "");
        return "v" + prev;
    }

    public synchronized void deactivate(String id) throws IOException {
        Map<String, Object> act = active();
        if (act.remove(id) == null) return;
        backup();
        write(new File(dir, "activas.json"), Json.write(act));
        log("desactivada por el usuario", id, 0, "");
    }

    /** Usa una herramienta APROBADA. */
    public Map<String, Object> use(String id, Object input, Sandbox sb) throws Exception {
        Map<String, Object> cur = Json.obj(active().get(id));
        if (cur == null) throw new IOException("la herramienta «" + id + "» no está aprobada");
        int v = num(cur.get("version"));
        String code = read(new File(vdir(id, v), "herramienta.js"));
        if (!check(code).isEmpty()) throw new IOException("la herramienta no pasa el control de seguridad");
        Map<String, Object> r = Json.obj(Json.parse(sb.run(single(code, input), TIMEOUT_MS)));
        log("uso", id, v, Boolean.TRUE.equals(r.get("ok")) ? "ok" : "error: " + r.get("error"));
        return r;
    }

    // ================================================================== consultas
    public synchronized Map<String, Object> active() {
        Map<String, Object> m = Json.obj(parseQuiet(read(new File(dir, "activas.json"))));
        return m == null ? new LinkedHashMap<String, Object>() : m;
    }

    /** Todas las versiones de todas las herramientas, lo más nuevo primero. */
    public synchronized List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File[] ts = new File(dir, "herramientas").listFiles();
        if (ts != null) for (File t : ts) for (Map<String, Object> m : versions(t.getName())) out.add(0, m);
        return out;
    }

    public List<Map<String, Object>> versions(String id) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int v = 1; ; v++) { Map<String, Object> m = meta(id, v); if (m == null) break; out.add(m); }
        return out;
    }

    public Map<String, Object> meta(String id, int v) { return Json.obj(parseQuiet(read(new File(vdir(id, v), "meta.json")))); }
    public String code(String id, int v) { return read(new File(vdir(id, v), "herramienta.js")); }

    /** Texto para el prompt: qué herramientas aprobadas hay y cómo pedirlas. */
    public String promptList() {
        Map<String, Object> act = active();
        if (act.isEmpty()) return "";
        StringBuilder b = new StringBuilder("Herramientas del laboratorio APROBADAS por el señor Rafael (cálculo puro, sin red). Para usar una, "
                + "devuelve además \"usar_herramienta\": {\"id\": ..., \"entrada\": <JSON>} y la app la ejecuta:\n");
        for (Map.Entry<String, Object> e : act.entrySet()) b.append("- ").append(e.getKey()).append(": ").append(Json.obj(e.getValue()).get("descripcion")).append('\n');
        return b.toString();
    }

    // ================================================================== propuestas y registro
    /** Propuesta de mejora (capacidad que falta, módulo nativo, límite detectado). */
    public synchronized String addProposal(String kind, String title, String body) throws IOException {
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + slug(title) + ".md";
        if (name.length() > 90) name = name.substring(0, 86) + ".md";
        write(new File(dir, "propuestas/" + name), "# " + title + "\n\nTipo: " + kind + " · " + now() + "\n\n" + (body == null ? "" : body));
        log("propuesta", slug(title), 0, kind);
        return new File(dir, "propuestas/" + name).getAbsolutePath();
    }

    public List<String> proposals() {
        List<String> out = new ArrayList<String>();
        File[] f = new File(dir, "propuestas").listFiles();
        if (f != null) { java.util.Arrays.sort(f); for (int i = f.length - 1; i >= 0; i--) out.add(f[i].getAbsolutePath()); }
        return out;
    }

    public List<String> log(int n) {
        String s = read(new File(dir, "registro.jsonl"));
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] l = s.split("\n");
        for (int i = Math.max(0, l.length - n); i < l.length; i++) if (!l[i].trim().isEmpty()) out.add(l[i]);
        return out;
    }

    private void log(String action, String id, int v, String detail) {
        try {
            FileOutputStream o = new FileOutputStream(new File(dir, "registro.jsonl"), true);
            o.write((Json.write(Json.map("fecha", now(), "accion", action, "herramienta", id, "version", (long) v, "detalle", detail)) + "\n").getBytes("UTF-8"));
            o.close();
        } catch (IOException ignored) {}
    }

    private void backup() throws IOException {
        String s = read(new File(dir, "activas.json"));
        write(new File(dir, "respaldos/activas-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date()) + ".json"), s == null ? "{}" : s);
    }

    // ================================================================== utilidades
    private File vdir(String id, int v) { return new File(new File(dir, "herramientas/" + slug(id)), "v" + v); }

    public static String slug(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return t.length() > 40 ? t.substring(0, 40).replaceAll("-+$", "") : t;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
    static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
    private static Object parseQuiet(String s) { try { return s == null ? null : Json.parse(s); } catch (Exception e) { return null; } }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write(s.getBytes("UTF-8")); o.close();
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("no pude guardar " + f.getName()); }
    }

    static String read(File f) {
        if (!f.isFile()) return null;
        try {
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()]; int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (IOException e) { return null; }
    }
}
