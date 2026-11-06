package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FÉNIX 80 · motor de evolución de JARVIS («JARVIS, trabaja en ti mismo»).
 *
 * REGLA OBLIGATORIA: una mejora a la vez. Una mejora está ACTIVA mientras esté en análisis, desarrollo, pruebas o
 * corrección; mientras haya una activa no se empieza otra. Estados:
 * PENDIENTE → EN ANÁLISIS → EN DESARROLLO → EN PRUEBAS → (CORRIGIENDO → EN PRUEBAS)* → VERIFICADA → APROBADA → INTEGRADA,
 * más BLOQUEADA (necesita autorización o un recurso) y DESCARTADA (agotó sus intentos).
 * VERIFICADA exige evidencias (pruebas superadas y sin regresiones). APROBADA solo la pone el usuario.
 *
 * La misión avanza por pasos (tick) con intervalos configurables y límites (llamadas a la API, mejoras, minutos):
 * nunca trabaja sin fin. Se puede pausar, reanudar y detener; la PAUSA de JARVIS la detiene antes que nada.
 * El trabajo concreto (agentes, Claude, laboratorio) lo hace un Worker (Vulkan); esta clase decide el orden y lleva
 * el registro, sin red: se prueba entera en el ordenador.
 */
public final class Evolution {
    public static final String PENDIENTE = "PENDIENTE", ANALISIS = "EN ANÁLISIS", DESARROLLO = "EN DESARROLLO", PRUEBAS = "EN PRUEBAS",
            CORRIGIENDO = "CORRIGIENDO", VERIFICADA = "VERIFICADA", APROBADA = "APROBADA", INTEGRADA = "INTEGRADA",
            BLOQUEADA = "BLOQUEADA", DESCARTADA = "DESCARTADA";
    static final List<String> ACTIVE = Arrays.asList(ANALISIS, DESARROLLO, PRUEBAS, CORRIGIENDO);

    /** Prioridad inicial que fijó el señor Rafael (1 = más importante). */
    public static final String[] CATEGORIES = {"estabilidad y seguridad", "autodiagnóstico", "memoria y aprendizaje", "programación",
            "laboratorio y pruebas", "director VULKAN", "agentes", "voz e interacción", "integración de herramientas", "capacidades avanzadas"};

    /** Tipos de mejora: solo «herramienta» se puede terminar dentro del teléfono; «nativo» necesita compilar la app fuera. */
    public static final String HERRAMIENTA = "herramienta", NATIVO = "nativo", HUMANO = "humano", POR_CLASIFICAR = "por clasificar";

    /** El que hace el trabajo de cada paso (VULKAN y sus agentes). */
    public interface Worker {
        boolean paused();
        /** Diagnóstico: crea mejoras en el registro (evo.create). Devuelve llamadas a la API gastadas. */
        int diagnose(Evolution evo) throws Exception;
        /** Avanza UN paso de la mejora activa. Devuelve {resultado: avance|exito|fallo|bloqueo, api: n, nota}. */
        Map<String, Object> step(Evolution evo, Map<String, Object> imp) throws Exception;
    }

    public static final class Config {
        public long retryMs = 60000, nextMs = 120000, cycleMs = 300000;  // tras fallo · tras éxito · diagnóstico periódico
        public int maxApiCalls = 40, maxImprovements = 3, maxMinutes = 60, maxAttempts = 3;
        public boolean webAllowed = false;  // investigación externa: solo si el usuario la autoriza
    }

    private final File dir;
    private final Experience exp;
    public final Config config = new Config();
    private Map<String, Object> mission;

    public Evolution(File dataDir, Experience exp) {
        this.dir = new File(dataDir, "evolucion");
        new File(dir, "mejoras").mkdirs();
        this.exp = exp;
        Map<String, Object> m = Json.obj(parse(Lab.read(new File(dir, "mision.json"))));
        mission = m != null ? m : Json.map("estado", "INACTIVA");
        Map<String, Object> c = Json.obj(parse(Lab.read(new File(dir, "config.json"))));
        if (c != null) loadConfig(c);
    }

    // ================================================================== registro de mejoras
    /** Crea una mejora PENDIENTE (o devuelve la existente si ya hay una igual sin terminar). */
    public synchronized Map<String, Object> create(String title, String goal, String module, String kind, String category,
                                                   Map<String, Object> scores, String source) throws IOException {
        String fp = Lab.slug(module + "-" + title);
        for (Map<String, Object> m : all()) {
            if (fp.equals(m.get("huella")) && !DESCARTADA.equals(m.get("estado")) && !INTEGRADA.equals(m.get("estado"))) return m;
            if (fp.equals(m.get("huella")) && DESCARTADA.equals(m.get("estado"))) return m;  // ya se demostró inútil: no se repite
        }
        int n = all().size() + 1;
        String id = String.format(Locale.US, "M%03d", n);
        Map<String, Object> m = Json.map("id", id, "nombre", title, "objetivo", goal, "modulo", module, "tipo", kind,
                "categoria", category, "puntos", scores == null ? new LinkedHashMap<String, Object>() : scores, "origen", source,
                "huella", fp, "estado", PENDIENTE, "intentos", 0L, "archivos", new ArrayList<Object>(), "pruebas", new ArrayList<Object>(),
                "errores_corregidos", new ArrayList<Object>(), "evidencias", new ArrayList<Object>(), "respaldo", null, "resultado", null,
                "historial", Json.list(Json.map("fecha", now(), "estado", PENDIENTE, "nota", "detectada: " + source)), "creada", now());
        save(m);
        log(id, "detectada", title + " (" + module + ", " + kind + ")");
        return m;
    }

    public synchronized List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        File[] f = new File(dir, "mejoras").listFiles();
        if (f != null) { Arrays.sort(f); for (File x : f) { Map<String, Object> m = Json.obj(parse(Lab.read(x))); if (m != null) out.add(m); } }
        return out;
    }

    public synchronized Map<String, Object> get(String id) { return Json.obj(parse(Lab.read(new File(dir, "mejoras/" + id + ".json")))); }

    /** La mejora activa (como mucho una). */
    public synchronized Map<String, Object> active() {
        for (Map<String, Object> m : all()) if (ACTIVE.contains(m.get("estado"))) return m;
        return null;
    }

    /** Cambia de estado si la transición es válida. evidence se añade al registro. */
    public synchronized Map<String, Object> transition(String id, String to, String note, Map<String, Object> evidence, boolean byUser) throws IOException {
        Map<String, Object> m = get(id);
        if (m == null) throw new IOException("no existe la mejora " + id);
        String from = String.valueOf(m.get("estado"));
        if (!allowed(from, to)) throw new IOException("transición no permitida: " + from + " → " + to);
        if (ANALISIS.equals(to) && !BLOQUEADA.equals(from)) {
            Map<String, Object> act = active();
            if (act != null && !id.equals(act.get("id"))) throw new IOException("regla de una mejora a la vez: " + act.get("id") + " sigue activa (" + act.get("estado") + ")");
        }
        if (VERIFICADA.equals(to)) {
            if (evidence == null || !Boolean.TRUE.equals(evidence.get("pruebas_ok")) || !Boolean.TRUE.equals(evidence.get("regresion_ok")))
                throw new IOException("VERIFICADA exige pruebas superadas y sin regresiones");
        }
        if (APROBADA.equals(to) && !byUser) throw new IOException("solo el señor Rafael aprueba una mejora");
        if (CORRIGIENDO.equals(to)) m.put("intentos", ((Number) m.get("intentos")).longValue() + 1);
        m.put("estado", to);
        Json.arr(m.get("historial")).add(Json.map("fecha", now(), "estado", to, "nota", note));
        if (evidence != null) Json.arr(m.get("evidencias")).add(evidence);
        save(m);
        log(id, to, note);
        return m;
    }

    static boolean allowed(String from, String to) {
        if (BLOQUEADA.equals(to)) return ACTIVE.contains(from) || PENDIENTE.equals(from);
        if (DESCARTADA.equals(to)) return ACTIVE.contains(from) || PENDIENTE.equals(from) || BLOQUEADA.equals(from);
        if (PENDIENTE.equals(from)) return ANALISIS.equals(to);
        if (ANALISIS.equals(from)) return DESARROLLO.equals(to);
        if (DESARROLLO.equals(from)) return PRUEBAS.equals(to);
        if (PRUEBAS.equals(from)) return VERIFICADA.equals(to) || CORRIGIENDO.equals(to);
        if (CORRIGIENDO.equals(from)) return PRUEBAS.equals(to);
        if (VERIFICADA.equals(from)) return APROBADA.equals(to);
        if (APROBADA.equals(from)) return INTEGRADA.equals(to);
        if (BLOQUEADA.equals(from)) return ANALISIS.equals(to);  // desbloqueo: lo autoriza el usuario
        return false;
    }

    /** Guarda campos de trabajo en la mejora (archivos, pruebas, versión candidata…). */
    public synchronized void update(String id, Map<String, Object> fields) throws IOException {
        Map<String, Object> m = get(id);
        if (m == null) return;
        m.putAll(fields);
        save(m);
    }

    /** Prioridad: categoría (orden del usuario) + impacto, seguridad, estabilidad y probabilidad − coste y dificultad. */
    public static double priority(Map<String, Object> m) {
        int cat = Arrays.asList(CATEGORIES).indexOf(String.valueOf(m.get("categoria")));
        double base = cat < 0 ? 0 : (CATEGORIES.length - cat) * 2;
        Map<String, Object> p = Json.obj(m.get("puntos"));
        return base + 3 * pt(p, "impacto") + 3 * pt(p, "seguridad") + 2 * pt(p, "estabilidad") + 2 * pt(p, "probabilidad")
                - pt(p, "coste") - pt(p, "dificultad");
    }

    /** La siguiente mejora que se PUEDE desarrollar aquí (las nativas y las que necesitan al usuario quedan a la espera). */
    public synchronized Map<String, Object> nextExecutable() {
        Map<String, Object> best = null;
        for (Map<String, Object> m : all()) {
            if (!PENDIENTE.equals(m.get("estado"))) continue;
            String k = String.valueOf(m.get("tipo"));
            if (!HERRAMIENTA.equals(k) && !POR_CLASIFICAR.equals(k)) continue;
            if (best == null || priority(m) > priority(best)) best = m;
        }
        return best;
    }

    // ================================================================== misión
    public synchronized Map<String, Object> mission() { return mission; }
    public synchronized String missionState() { return String.valueOf(mission.get("estado")); }

    public synchronized void start(long now, boolean webAllowed) throws IOException {
        if ("ACTIVA".equals(missionState())) return;
        config.webAllowed = webAllowed;
        saveConfig();
        mission = Json.map("estado", "ACTIVA", "inicio", now, "inicio_txt", now(), "api", 0L, "mejoras_verificadas", 0L, "pasos", 0L,
                "proxima_ms", now, "ultimo_diagnostico_ms", 0L, "motivo", null, "web", webAllowed);
        saveMission();
        log("-", "misión iniciada", "web " + (webAllowed ? "autorizada" : "no autorizada"));
    }

    public synchronized void pause(String why) throws IOException { if (isOn()) { mission.put("estado", "PAUSADA"); mission.put("motivo", why); saveMission(); log("-", "misión pausada", why); } }
    public synchronized void resume(long now) throws IOException { if ("PAUSADA".equals(missionState()) || "BLOQUEADA".equals(missionState())) { mission.put("estado", "ACTIVA"); mission.put("motivo", null); mission.put("proxima_ms", now); saveMission(); log("-", "misión reanudada", ""); } }
    public synchronized void stop(String why) throws IOException { if (isOn()) { mission.put("estado", "DETENIDA"); mission.put("motivo", why); saveMission(); log("-", "misión detenida", why); } }
    private boolean isOn() { String s = missionState(); return "ACTIVA".equals(s) || "PAUSADA".equals(s) || "BLOQUEADA".equals(s); }

    /** Cuánto falta para el próximo paso (ms); -1 si la misión no está activa. */
    public synchronized long waitMs(long now) {
        if (!"ACTIVA".equals(missionState())) return -1;
        return Math.max(0, num(mission.get("proxima_ms")) - now);
    }

    /**
     * Un paso de la misión. Devuelve qué pasó (texto). Respeta: PAUSA, límites, una mejora a la vez, intervalos.
     */
    public String tick(long now, Worker w) {
        synchronized (this) {
            if (!"ACTIVA".equals(missionState())) return "misión " + missionState().toLowerCase(Locale.ROOT);
            if (now < num(mission.get("proxima_ms"))) return "esperando";
        }
        try {
            if (w.paused()) { pause("PAUSA de JARVIS"); return "pausada por la PAUSA"; }
            String limit = limitReached(now);
            if (limit != null) { finish("TERMINADA", limit); return "terminada: " + limit; }
            Map<String, Object> imp = active();
            if (imp == null) {
                boolean cycle = !Boolean.TRUE.equals(mission.get("diagnosticada")) || now - num(mission.get("ultimo_diagnostico_ms")) >= config.cycleMs;
                if (cycle) {
                    int api = w.diagnose(this);
                    synchronized (this) { mission.put("ultimo_diagnostico_ms", now); mission.put("diagnosticada", true); addApi(api); saveMission(); }
                    log("-", "diagnóstico", "mejoras registradas: " + all().size());
                }
                Map<String, Object> next = nextExecutable();
                if (next == null) {
                    finish("TERMINADA", "no hay mejoras que se puedan desarrollar en el teléfono ahora; las demás necesitan autorización o compilar la app fuera");
                    return "sin mejoras ejecutables";
                }
                transition(String.valueOf(next.get("id")), ANALISIS, "seleccionada por prioridad " + Math.round(priority(next)), null, false);
                schedule(now, 0);
                return "seleccionada " + next.get("id") + ": " + next.get("nombre");
            }
            Map<String, Object> r = w.step(this, imp);
            String res = String.valueOf(r.get("resultado"));
            synchronized (this) { addApi(r.get("api") instanceof Number ? ((Number) r.get("api")).intValue() : 0); mission.put("pasos", num(mission.get("pasos")) + 1); saveMission(); }
            if ("exito".equals(res)) {
                synchronized (this) { mission.put("mejoras_verificadas", num(mission.get("mejoras_verificadas")) + 1); saveMission(); }
                schedule(now, config.nextMs);
            } else if ("fallo".equals(res)) schedule(now, config.retryMs);
            else if ("bloqueo".equals(res)) { schedule(now, config.nextMs); }
            else schedule(now, 0);
            return imp.get("id") + " · " + res + (r.get("nota") != null ? ": " + r.get("nota") : "");
        } catch (Exception e) {
            log("-", "error del motor", String.valueOf(e.getMessage()));
            schedule(now, config.retryMs);
            return "error: " + e.getMessage();
        }
    }

    private synchronized String limitReached(long now) {
        if (num(mission.get("api")) >= config.maxApiCalls) return "límite de " + config.maxApiCalls + " llamadas a la API";
        if (num(mission.get("mejoras_verificadas")) >= config.maxImprovements) return "límite de " + config.maxImprovements + " mejoras por misión";
        if (now - num(mission.get("inicio")) >= config.maxMinutes * 60000L) return "límite de " + config.maxMinutes + " minutos";
        return null;
    }

    private synchronized void finish(String state, String why) throws IOException { mission.put("estado", state); mission.put("motivo", why); saveMission(); log("-", "misión " + state.toLowerCase(Locale.ROOT), why); }
    private synchronized void schedule(long now, long delay) { mission.put("proxima_ms", now + delay); try { saveMission(); } catch (IOException ignored) {} }
    private void addApi(int n) { mission.put("api", num(mission.get("api")) + n); }

    // ================================================================== aprendizaje estructurado
    /**
     * Registra una experiencia completa. tipo: «hecho comprobado» (lo demuestran pruebas), «hipótesis» o «conclusión».
     * También se guarda en Experience para que se recupere ante tareas parecidas.
     */
    public synchronized void learn(String module, String problem, String cause, String solution, String tests, String result,
                                   double confidence, String lesson, String kind) {
        Map<String, Object> e = Json.map("fecha", now(), "modulo", module, "problema", problem, "causa", cause, "solucion", solution,
                "pruebas", tests, "resultado", result, "confianza", Math.round(confidence * 100) / 100.0, "aprendizaje", lesson, "tipo", kind);
        append("aprendizajes.jsonl", Json.write(e));
        if (exp != null && lesson != null) exp.learn("evolucion", module + ": " + problem, "[" + kind + "] " + lesson);
    }

    public synchronized List<Map<String, Object>> learnings() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String s = Lab.read(new File(dir, "aprendizajes.jsonl"));
        if (s != null) for (String l : s.split("\n")) { Map<String, Object> m = Json.obj(parse(l)); if (m != null) out.add(m); }
        return out;
    }

    // ================================================================== respuestas con registros reales
    public synchronized String whatImproving() {
        Map<String, Object> a = active();
        String st = "Motor FÉNIX 80: misión " + missionState().toLowerCase(Locale.ROOT) + (mission.get("motivo") != null ? " (" + mission.get("motivo") + ")" : "") + ". ";
        if (a == null) return st + "Ninguna mejora en curso.";
        return st + "Trabajo en " + a.get("id") + ", «" + a.get("nombre") + "» (" + a.get("modulo") + "), estado " + a.get("estado") + ", intento " + a.get("intentos") + ".";
    }

    public synchronized String learnedToday() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map<String, Object> l : learnings()) if (String.valueOf(l.get("fecha")).startsWith(today)) { n++; b.append("\n• [").append(l.get("tipo")).append("] ").append(l.get("aprendizaje")); }
        return n == 0 ? "Hoy no registré aprendizajes." : "Hoy registré " + n + " aprendizaje" + (n == 1 ? "" : "s") + ":" + b;
    }

    public synchronized String errorsFixed() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map<String, Object> m : all()) for (Object e : Json.arr(m.get("errores_corregidos"))) { n++; b.append("\n• ").append(m.get("id")).append(": ").append(e); }
        return n == 0 ? "Todavía no corregí errores en mis mejoras." : "Corregí " + n + " error" + (n == 1 ? "" : "es") + " durante el desarrollo:" + b;
    }

    public synchronized String newCapabilities() {
        StringBuilder b = new StringBuilder();
        int ver = 0, integ = 0;
        for (Map<String, Object> m : all()) {
            String s = String.valueOf(m.get("estado"));
            if (VERIFICADA.equals(s) || APROBADA.equals(s)) { ver++; b.append("\n• ").append(m.get("nombre")).append(" (verificada, espera su aprobación)"); }
            if (INTEGRADA.equals(s)) { integ++; b.append("\n• ").append(m.get("nombre")).append(" (integrada)"); }
        }
        return ver + integ == 0 ? "Todavía no desarrollé capacidades nuevas verificadas." : integ + " integrada(s) y " + ver + " verificada(s) a la espera de su aprobación:" + b;
    }

    public synchronized String missing() {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> m : all()) {
            String s = String.valueOf(m.get("estado")), k = String.valueOf(m.get("tipo"));
            if (BLOQUEADA.equals(s) || (PENDIENTE.equals(s) && (NATIVO.equals(k) || HUMANO.equals(k))))
                b.append("\n• ").append(m.get("nombre")).append(" — ").append(NATIVO.equals(k) ? "necesita compilar la app fuera del teléfono" : HUMANO.equals(k) ? "necesita su acción" : lastNote(m));
        }
        return b.length() == 0 ? "No tengo bloqueos registrados." : "Para evolucionar me falta:" + b;
    }

    private static String lastNote(Map<String, Object> m) { List<Object> h = Json.arr(m.get("historial")); return h.isEmpty() ? "" : String.valueOf(Json.obj(h.get(h.size() - 1)).get("nota")); }

    /** Resumen para el panel. */
    public synchronized Map<String, Object> status(long now) {
        Map<String, Integer> byState = new LinkedHashMap<String, Integer>();
        for (Map<String, Object> m : all()) { String s = String.valueOf(m.get("estado")); byState.put(s, byState.containsKey(s) ? byState.get(s) + 1 : 1); }
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Integer> e : byState.entrySet()) counts.put(e.getKey(), (long) e.getValue());
        Map<String, Object> next = nextExecutable();
        return Json.map("mision", mission, "activa", active(), "por_estado", counts, "aprendizajes", (long) learnings().size(),
                "minutos", num(mission.get("inicio")) > 0 ? (now - num(mission.get("inicio"))) / 60000 : 0L,
                "proxima", next == null ? null : next.get("id") + " · " + next.get("nombre"),
                "config", Json.map("reintento_s", config.retryMs / 1000, "siguiente_s", config.nextMs / 1000, "ciclo_s", config.cycleMs / 1000,
                        "max_api", (long) config.maxApiCalls, "max_mejoras", (long) config.maxImprovements, "max_min", (long) config.maxMinutes,
                        "web", config.webAllowed));
    }

    public List<String> log(int n) {
        List<String> out = new ArrayList<String>();
        String s = Lab.read(new File(dir, "registro.jsonl"));
        if (s == null) return out;
        String[] l = s.split("\n");
        for (int i = Math.max(0, l.length - n); i < l.length; i++) if (!l[i].trim().isEmpty()) out.add(l[i]);
        return out;
    }

    // ================================================================== persistencia
    public synchronized void saveConfig() throws IOException {
        write(new File(dir, "config.json"), Json.write(Json.map("retryMs", config.retryMs, "nextMs", config.nextMs, "cycleMs", config.cycleMs,
                "maxApiCalls", (long) config.maxApiCalls, "maxImprovements", (long) config.maxImprovements, "maxMinutes", (long) config.maxMinutes,
                "maxAttempts", (long) config.maxAttempts, "webAllowed", config.webAllowed)));
    }

    private void loadConfig(Map<String, Object> c) {
        config.retryMs = num(c.get("retryMs"), config.retryMs); config.nextMs = num(c.get("nextMs"), config.nextMs); config.cycleMs = num(c.get("cycleMs"), config.cycleMs);
        config.maxApiCalls = (int) num(c.get("maxApiCalls"), config.maxApiCalls); config.maxImprovements = (int) num(c.get("maxImprovements"), config.maxImprovements);
        config.maxMinutes = (int) num(c.get("maxMinutes"), config.maxMinutes); config.maxAttempts = (int) num(c.get("maxAttempts"), config.maxAttempts);
        config.webAllowed = Boolean.TRUE.equals(c.get("webAllowed"));
    }

    private void save(Map<String, Object> m) throws IOException { write(new File(dir, "mejoras/" + m.get("id") + ".json"), Json.write(m)); }
    private void saveMission() throws IOException { write(new File(dir, "mision.json"), Json.write(mission)); }

    private void log(String id, String action, String detail) { append("registro.jsonl", Json.write(Json.map("fecha", now(), "mejora", id, "accion", action, "detalle", detail))); }

    private void append(String file, String line) {
        try { FileOutputStream o = new FileOutputStream(new File(dir, file), true); o.write((line + "\n").getBytes("UTF-8")); o.close(); } catch (IOException ignored) {}
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write(s.getBytes("UTF-8")); o.close();
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IOException("no pude guardar " + f.getName()); }
    }

    public File dir() { return dir; }
    private static Object parse(String s) { try { return s == null ? null : Json.parse(s); } catch (Exception e) { return null; } }
    static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    private static long num(Object o, long d) { return o instanceof Number ? ((Number) o).longValue() : d; }
    private static double pt(Map<String, Object> p, String k) { return p != null && p.get(k) instanceof Number ? ((Number) p.get(k)).doubleValue() : 0; }
    static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
}
