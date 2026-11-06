package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VULKAN · director operativo de la evolución (el Worker de FÉNIX 80).
 * Recibe la misión, reparte cada fase a un agente especialista, revisa sus resultados y consolida.
 * Agentes con función, formato de salida y registro propios (files/evolucion/agentes/<agente>.jsonl):
 *  - arquitectura (Claude): clasifica la mejora, fija criterios de aceptación y diseño.
 *  - investigacion (Claude + web): solo si el usuario autorizó la investigación externa en la misión.
 *  - programacion (Claude): escribe la herramienta y sus pruebas; corrige con los fallos reales.
 *  - android (Claude): para mejoras nativas, redacta la propuesta de código para compilar fuera.
 *  - seguridad (determinista): control de seguridad del código; nunca deja tocar permisos, claves ni controles.
 *  - calidad (determinista): pruebas aisladas, regresión, comparación antes/después.
 *  - memoria (determinista): registra la experiencia con hechos, hipótesis y conclusiones.
 * Preparados, sin lógica propia todavía: inteligencia artificial, optimización, voz e interfaz, finanzas.
 * Claude es el motor de los agentes que escriben o razonan: eso es asistencia de Claude, no una capacidad propia de JARVIS.
 */
public final class Vulkan implements Evolution.Worker {
    /** Comprobaciones del teléfono (las da la app): [{modulo, ok, detalle, tipo: humano|nativo}]. */
    public interface Probe { List<Map<String, Object>> run(); }

    public static final String[][] AGENTS = {
            {"arquitectura", "Claude", "clasifica mejoras, criterios de aceptación y diseño"},
            {"investigacion", "Claude + web", "investiga soluciones (solo con autorización)"},
            {"programacion", "Claude", "escribe y corrige herramientas con sus pruebas"},
            {"android", "Claude", "propuestas de código nativo para compilar fuera del teléfono"},
            {"seguridad", "determinista", "control de seguridad del código y de los límites"},
            {"calidad", "determinista", "pruebas aisladas, regresión y comparación antes/después"},
            {"memoria", "determinista", "registro de experiencias y aprendizajes"},
            {"ia", "preparado", "sin lógica propia todavía"},
            {"optimizacion", "preparado", "sin lógica propia todavía"},
            {"voz_interfaz", "preparado", "sin lógica propia todavía"},
            {"finanzas", "preparado", "sin lógica propia todavía"}};

    private final Brain brain;
    private final Lab.Sandbox sandbox;
    private final Probe probe;
    private final List<Map<String, Object>> known;  // pendientes conocidos (auditoría), empaquetados en la app
    private final File agentDir;
    private volatile List<Map<String, Object>> lastProbes = new ArrayList<Map<String, Object>>();

    public Vulkan(Brain brain, Lab.Sandbox sandbox, Probe probe, List<Map<String, Object>> knownIssues, File evoDir) {
        this.brain = brain; this.sandbox = sandbox; this.probe = probe;
        this.known = knownIssues == null ? new ArrayList<Map<String, Object>>() : knownIssues;
        this.agentDir = new File(evoDir, "agentes");
        agentDir.mkdirs();
    }

    public boolean paused() { return brain.isPaused(); }

    // ================================================================== diagnóstico (sin coste de API)
    public int diagnose(Evolution evo) throws Exception {
        int created = 0;
        // seguridad: comprobaciones reales del teléfono
        if (probe != null) lastProbes = probe.run();
        for (Map<String, Object> p : lastProbes) {
            if (Boolean.TRUE.equals(p.get("ok"))) continue;
            evo.create("Resolver: " + p.get("detalle"), "Que la comprobación «" + p.get("modulo") + "» pase", String.valueOf(p.get("modulo")),
                    "nativo".equals(p.get("tipo")) ? Evolution.NATIVO : Evolution.HUMANO, "estabilidad y seguridad",
                    Json.map("impacto", 4L, "seguridad", 4L, "estabilidad", 4L), "autodiagnóstico");
            created++;
        }
        agent("seguridad", "diagnóstico", "comprobaciones del teléfono revisadas");
        // calidad: herramientas del laboratorio que fallaron
        Map<String, Map<String, Object>> latest = new LinkedHashMap<String, Map<String, Object>>();  // solo cuenta la ÚLTIMA versión de cada herramienta
        for (Map<String, Object> m : brain.lab.all()) {
            Map<String, Object> cur = latest.get(String.valueOf(m.get("id")));
            if (cur == null || ((Number) m.get("version")).intValue() > ((Number) cur.get("version")).intValue()) latest.put(String.valueOf(m.get("id")), m);
        }
        for (Map<String, Object> m : latest.values())
            if ("fallida".equals(m.get("estado")) && !inEvolution(evo, String.valueOf(m.get("id")))) {
                evo.create("Corregir la herramienta " + m.get("id"), "Que «" + m.get("id") + "» pase todas sus pruebas", "lab/" + m.get("id"),
                        Evolution.HERRAMIENTA, "laboratorio y pruebas", Json.map("impacto", 2L, "estabilidad", 2L, "probabilidad", 3L), "pruebas fallidas");
                created++;
            }
        agent("calidad", "diagnóstico", "herramientas fallidas revisadas");
        // memoria: errores que se repiten
        for (Object e : Json.arr(brain.experience.stats().get("errores_repetidos"))) {
            evo.create("Error repetido: " + e, "Que deje de repetirse", "cerebro", Evolution.POR_CLASIFICAR, "estabilidad y seguridad",
                    Json.map("impacto", 3L, "estabilidad", 3L), "experiencia");
            created++;
        }
        agent("memoria", "diagnóstico", "errores repetidos revisados");
        // arquitectura: capacidades que faltan (lo que JARVIS reconoció no saber) y pendientes conocidos
        for (String path : brain.lab.proposals()) {
            String name = new File(path).getName();
            String body = Lab.read(new File(path));
            if (body == null || !body.contains("Tipo: capacidad que falta")) continue;
            String title = body.split("\n", 2)[0].replaceFirst("^#\\s*", "").trim();
            evo.create("Aprender a " + title, "Nueva capacidad: " + title, "capacidades", Evolution.POR_CLASIFICAR, "capacidades avanzadas",
                    Json.map("impacto", 3L, "probabilidad", 2L, "coste", 1L), "propuesta " + name);
            created++;
        }
        for (Map<String, Object> k : known) {
            evo.create(String.valueOf(k.get("nombre")), String.valueOf(k.get("objetivo")), String.valueOf(k.get("modulo")),
                    String.valueOf(k.get("tipo")), String.valueOf(k.get("categoria")), Json.obj(k.get("puntos")), "auditoría");
            created++;
        }
        agent("arquitectura", "diagnóstico", "capacidades que faltan y pendientes conocidos: " + created + " registros revisados");
        SelfCheck.inventory(brain, evo, this, lastProbes);
        return 0;
    }

    // ================================================================== un paso de la mejora activa
    public Map<String, Object> step(Evolution evo, Map<String, Object> imp) throws Exception {
        try { return stepInner(evo, imp); } finally { SelfCheck.inventory(brain, evo, this, lastProbes); }  // inventario al día tras cada paso
    }

    public List<Map<String, Object>> lastProbes() { return lastProbes; }

    private Map<String, Object> stepInner(Evolution evo, Map<String, Object> imp) throws Exception {
        String id = String.valueOf(imp.get("id")), st = String.valueOf(imp.get("estado"));
        if (Evolution.ANALISIS.equals(st)) return analyse(evo, imp);
        if (Evolution.DESARROLLO.equals(st)) return develop(evo, imp, null);
        if (Evolution.PRUEBAS.equals(st)) return test(evo, imp);
        if (Evolution.CORRIGIENDO.equals(st)) {
            if (((Number) imp.get("intentos")).intValue() > evo.config.maxAttempts) {
                evo.transition(id, Evolution.DESCARTADA, "agotó " + evo.config.maxAttempts + " intentos de corrección", null, false);
                evo.learn(String.valueOf(imp.get("modulo")), String.valueOf(imp.get("nombre")), "las correcciones no lograron pasar las pruebas (hipótesis: el enfoque no sirve)",
                        "ninguna", "pruebas en el laboratorio", "descartada", 0.6, "Este enfoque para «" + imp.get("nombre") + "» falló " + imp.get("intentos") + " veces: no repetirlo igual.", "conclusión");
                agent("memoria", "experiencia", id + " descartada");
                return Json.map("resultado", "fallo", "api", 0L, "nota", "descartada tras agotar los intentos");
            }
            return develop(evo, imp, Json.arr(imp.get("ultimos_fallos")));
        }
        return Json.map("resultado", "bloqueo", "api", 0L, "nota", "estado " + st + " no avanza solo");
    }

    /** arquitectura: clasificar y fijar criterios. */
    private Map<String, Object> analyse(Evolution evo, Map<String, Object> imp) throws Exception {
        String id = String.valueOf(imp.get("id"));
        String kind = String.valueOf(imp.get("tipo"));
        int api = 0;
        Map<String, Object> design = Json.map("tipo", kind);
        if (Evolution.POR_CLASIFICAR.equals(kind) || Evolution.HERRAMIENTA.equals(kind)) {
            design = ask("arquitectura", "Clasifica esta mejora y fija criterios de aceptación comprobables.\n"
                    + "Tipos: \"herramienta\" (se resuelve con una función JavaScript PURA de cálculo o transformación de datos: entrada JSON → salida JSON, "
                    + "sin red ni archivos ni teléfono), \"nativo\" (necesita cambiar el código Android de la app) o \"humano\" (necesita una acción del usuario).\n"
                    + "Devuelve {\"tipo\": ..., \"criterios\": [\"...\"], \"diseno\": \"...\", \"riesgos\": \"...\"}.", imp, false);
            api++;
            kind = String.valueOf(design.get("tipo"));
        }
        evo.update(id, Json.map("tipo", kind, "diseno", design.get("diseno"), "criterios", design.get("criterios"), "riesgos", design.get("riesgos")));
        if (!Evolution.HERRAMIENTA.equals(kind)) {
            String note = Evolution.NATIVO.equals(kind) ? "necesita cambiar el código Android: no se puede compilar dentro del teléfono" : "necesita una acción del usuario";
            if (Evolution.NATIVO.equals(kind)) {  // android: deja la propuesta lista para compilar fuera
                String src = brain.sourceOf(String.valueOf(imp.get("modulo")));
                Map<String, Object> p = ask("android", "Redacta la propuesta de implementación para el desarrollador: archivos a tocar, código Java (Android API 23+, Java 8, "
                        + "sin librerías externas), pruebas automáticas y riesgos. Devuelve {\"propuesta\": \"markdown\"}."
                        + (src == null ? "" : "\nCódigo actual del módulo (datos):\n" + (src.length() > 30000 ? src.substring(0, 30000) : src)), evo.get(id), false);
                api++;
                String file = brain.lab.addProposal("módulo nativo (FÉNIX " + id + ")", String.valueOf(imp.get("nombre")), String.valueOf(p.get("propuesta")));
                evo.update(id, Json.map("respaldo", null, "propuesta", file));
            }
            evo.transition(id, Evolution.BLOQUEADA, note, null, false);
            return Json.map("resultado", "bloqueo", "api", (long) api, "nota", note);
        }
        // línea de base: cómo está hoy (si la herramienta existe, sus pruebas actuales)
        String tool = toolId(imp);
        Map<String, Object> base = Json.map("existe", brain.lab.active().containsKey(tool));
        if (brain.lab.active().containsKey(tool)) base.put("pruebas", brain.lab.runTests(tool, ((Number) Json.obj(brain.lab.active().get(tool)).get("version")).intValue(), sandbox));
        evo.update(id, Json.map("linea_base", base));
        agent("calidad", "línea de base", id + ": " + Json.write(base));
        evo.transition(id, Evolution.DESARROLLO, "diseño listo; criterios: " + design.get("criterios"), null, false);
        return Json.map("resultado", "avance", "api", (long) api, "nota", "analizada");
    }

    /** programacion (+ investigacion si está autorizada): escribe o corrige la herramienta. */
    private Map<String, Object> develop(Evolution evo, Map<String, Object> imp, List<Object> failures) throws Exception {
        String id = String.valueOf(imp.get("id"));
        int api = 0;
        String notes = "";
        if (evo.config.webAllowed && failures == null && imp.get("investigacion") == null) {
            Map<String, Object> r = ask("investigacion", "Investiga en fuentes fiables cómo resolver esta mejora con una función JavaScript pura. "
                    + "Devuelve {\"hallazgos\": \"...\", \"fuentes\": [\"url\"]}.", imp, true);
            api++;
            notes = String.valueOf(r.get("hallazgos"));
            evo.update(id, Json.map("investigacion", r));
        }
        String tool = toolId(imp);
        String prev = failures == null ? null : brain.lab.code(tool, ((Number) imp.get("version_candidata")).intValue());
        String task = failures == null
                ? "Escribe la herramienta que cumpla los criterios de aceptación." + (notes.isEmpty() ? "" : "\nInvestigación previa: " + notes)
                : "CORREGIR. La versión anterior falló. Fallos reales de las pruebas:\n" + Json.write(failures) + "\nCódigo anterior:\n" + prev
                  + "\nCorrige el código; conserva las pruebas correctas.";
        Map<String, Object> h = ask("programacion", task + "\nReglas: JavaScript puro con function run(entrada); prohibido " + java.util.Arrays.toString(Lab.FORBIDDEN)
                + ", comillas invertidas y acceso por texto entre corchetes. Al menos 3 pruebas con casos límite.\n"
                + "Devuelve {\"codigo\": \"function run(entrada){...}\", \"pruebas\": [{\"entrada\": ..., \"esperado\": ...}], \"descripcion\": \"...\", \"por_que\": \"...\"}.", imp, false);
        api++;
        Map<String, Object> c = brain.lab.addCandidate(tool, String.valueOf(imp.get("nombre")), String.valueOf(h.get("descripcion")), Json.str(h.get("codigo")),
                Json.arr(h.get("pruebas")), Json.str(h.get("por_que")), "FÉNIX " + id);
        agent("seguridad", "control del código", tool + " v" + c.get("version") + ": " + (Json.arr(c.get("problemas")).isEmpty() ? "sin problemas" : c.get("problemas")));
        List<Object> files = Json.arr(imp.get("archivos"));
        files.add("lab/herramientas/" + tool + "/v" + c.get("version"));
        Map<String, Object> f = Json.map("version_candidata", c.get("version"), "herramienta", tool, "archivos", files, "por_que", h.get("por_que"));
        if (failures != null) { List<Object> pend = Json.arr(imp.get("en_correccion")); if (pend == null) pend = new ArrayList<Object>(); pend.add(Json.write(failures)); f.put("en_correccion", pend); }
        evo.update(id, f);
        evo.transition(id, Evolution.PRUEBAS, (failures == null ? "versión " : "corrección: versión ") + c.get("version") + " lista para probar", null, false);
        return Json.map("resultado", "avance", "api", (long) api, "nota", "versión " + c.get("version") + " escrita");
    }

    /** calidad: pruebas aisladas + regresión + comparación con la línea de base. */
    private Map<String, Object> test(Evolution evo, Map<String, Object> imp) throws Exception {
        String id = String.valueOf(imp.get("id")), tool = String.valueOf(imp.get("herramienta"));
        int v = ((Number) imp.get("version_candidata")).intValue();
        Map<String, Object> meta = brain.lab.meta(tool, v);
        List<Object> probs = Json.arr(meta.get("problemas"));
        Map<String, Object> t;
        List<Object> fails = new ArrayList<Object>();
        if (probs != null && !probs.isEmpty()) {
            t = Json.map("ok", false, "pasan", 0L, "total", 0L, "error", "seguridad: " + probs);
            fails.add("control de seguridad: " + probs);
        } else {
            t = brain.lab.test(tool, v, sandbox);
            Map<String, Object> m2 = brain.lab.meta(tool, v);
            Map<String, Object> pr = Json.obj(m2.get("pruebas"));
            List<Object> res = pr == null ? null : Json.arr(pr.get("resultados"));
            List<Object> tests = Json.arr(Json.parse(Lab.read(new File(new File(brain.lab.dirOf(tool), "v" + v), "pruebas.json"))));
            if (t.get("error") != null) fails.add("error: " + t.get("error"));
            if (res != null) for (int i = 0; i < res.size(); i++) {
                Map<String, Object> r = Json.obj(res.get(i));
                if (!Boolean.TRUE.equals(r.get("ok"))) fails.add(Json.map("entrada", Json.obj(tests.get(i)).get("entrada"), "esperado", Json.obj(tests.get(i)).get("esperado"),
                        "obtenido", r.get("salida"), "error", r.get("error")));
            }
        }
        Map<String, Object> reg = brain.lab.regression(sandbox);
        boolean ok = Boolean.TRUE.equals(t.get("ok")) && Boolean.TRUE.equals(reg.get("ok"));
        List<Object> history = Json.arr(imp.get("pruebas"));
        history.add(Json.map("fecha", Evolution.now(), "version", (long) v, "pasan", t.get("pasan"), "total", t.get("total"), "regresion", reg));
        evo.update(id, Json.map("pruebas", history, "ultimos_fallos", fails));
        agent("calidad", "pruebas", id + " " + tool + " v" + v + ": " + t.get("pasan") + "/" + t.get("total") + " · regresión " + (Boolean.TRUE.equals(reg.get("ok")) ? "ok" : reg.get("fallos")));
        if (ok) {
            Map<String, Object> ev = Json.map("pruebas_ok", true, "regresion_ok", true, "pasan", t.get("pasan"), "total", t.get("total"),
                    "regresion", reg, "antes", imp.get("linea_base"), "despues", Json.map("version", (long) v, "pasan", t.get("pasan"), "total", t.get("total")));
            List<Object> fixed = Json.arr(imp.get("errores_corregidos"));
            List<Object> pend = Json.arr(imp.get("en_correccion"));
            if (pend != null) fixed.addAll(pend);  // solo cuentan como corregidos cuando la corrección queda verificada
            evo.update(id, Json.map("errores_corregidos", fixed, "en_correccion", new ArrayList<Object>(),
                    "resultado", "verificada: " + t.get("pasan") + "/" + t.get("total") + " pruebas, sin regresiones; espera su aprobación"));
            evo.transition(id, Evolution.VERIFICADA, "pruebas " + t.get("pasan") + "/" + t.get("total") + " y regresión ok", ev, false);
            evo.learn(String.valueOf(imp.get("modulo")), String.valueOf(imp.get("nombre")), pend != null && !pend.isEmpty() ? "la primera versión falló pruebas" : "capacidad inexistente",
                    "herramienta " + tool + " v" + v, t.get("pasan") + "/" + t.get("total") + " pruebas aisladas + regresión", "verificada",
                    1.0, String.valueOf(imp.get("por_que")), "hecho comprobado");
            agent("memoria", "experiencia", id + " verificada");
            return Json.map("resultado", "exito", "api", 0L, "nota", "verificada " + t.get("pasan") + "/" + t.get("total"));
        }
        evo.transition(id, Evolution.CORRIGIENDO, "fallos: " + fails.size() + (Boolean.TRUE.equals(reg.get("ok")) ? "" : " · regresión"), null, false);
        evo.learn(String.valueOf(imp.get("modulo")), String.valueOf(imp.get("nombre")), "hipótesis: el código no cubre estos casos", "se reintenta con los fallos reales",
                t.get("pasan") + "/" + t.get("total"), "fallida", 0.5, "Fallos en " + tool + " v" + v + ": " + Json.write(fails), "hipótesis");
        return Json.map("resultado", "fallo", "api", 0L, "nota", t.get("pasan") + "/" + t.get("total") + " pruebas");
    }

    /** Aprobación del usuario: activa la herramienta (respaldo en el laboratorio) y la marca INTEGRADA. */
    public static void approve(Evolution evo, Lab lab, String id) throws IOException {
        Map<String, Object> m = evo.get(id);
        if (m == null || !Evolution.VERIFICADA.equals(m.get("estado"))) throw new IOException("solo se aprueba una mejora VERIFICADA");
        evo.transition(id, Evolution.APROBADA, "aprobada por el señor Rafael", null, true);
        lab.approve(String.valueOf(m.get("herramienta")), ((Number) m.get("version_candidata")).intValue(), true);
        evo.update(id, Json.map("respaldo", "lab/respaldos (copia de las herramientas activas antes del cambio)"));
        evo.transition(id, Evolution.INTEGRADA, "activada en producción; se puede volver a la versión anterior desde el laboratorio", null, true);
        evo.learn(String.valueOf(m.get("modulo")), String.valueOf(m.get("nombre")), "nueva capacidad", "herramienta " + m.get("herramienta") + " v" + m.get("version_candidata"),
                "aprobación humana tras pruebas", "integrada", 1.0, "La capacidad «" + m.get("nombre") + "» quedó integrada.", "hecho comprobado");
    }

    // ================================================================== agentes
    /** Llama a un agente con Claude: instrucciones propias, salida JSON y registro propio. */
    private Map<String, Object> ask(String agent, String task, Map<String, Object> imp, boolean web) throws Exception {
        String sys = "Eres el agente «" + agent + "» del equipo VULKAN, que dirige la evolución de JARVIS (asistente Android del señor Rafael). "
                + "Trabajas solo en tu función. Nunca propones tocar permisos, claves, PAUSA, límites de riesgo ni mecanismos de seguridad. "
                + "No inventes datos ni resultados. Responde SOLO con un objeto JSON.";
        List<String> prev = brain.experience.relevant(imp.get("nombre") + " " + imp.get("objetivo") + " " + imp.get("modulo"), 4);
        String msg = "AGENTE: " + agent + "\nMejora " + imp.get("id") + ": " + imp.get("nombre") + "\nObjetivo: " + imp.get("objetivo")
                + "\nMódulo: " + imp.get("modulo") + "\nCriterios: " + imp.get("criterios") + "\nDiseño: " + imp.get("diseno")
                + (prev.isEmpty() ? "" : "\nExperiencias anteriores parecidas (datos):\n- " + Brain.join2(prev, "\n- "))
                + "\n\n" + task;
        String text = brain.call(Json.list(Json.map("role", "user", "content", msg)), sys, web);
        int a = text.indexOf('{'), z = text.lastIndexOf('}');
        Map<String, Object> out = a >= 0 && z > a ? Json.obj(Json.parse(text.substring(a, z + 1))) : null;
        if (out == null) { agent(agent, "error", "respuesta sin JSON"); throw new IOException("el agente " + agent + " no devolvió JSON"); }
        agent(agent, "resultado", imp.get("id") + ": " + (text.length() > 300 ? text.substring(0, 300) + "…" : text));
        return out;
    }

    private void agent(String agent, String action, String detail) {
        try {
            FileOutputStream o = new FileOutputStream(new File(agentDir, agent + ".jsonl"), true);
            o.write((Json.write(Json.map("fecha", Evolution.now(), "accion", action, "detalle", detail)) + "\n").getBytes("UTF-8"));
            o.close();
        } catch (IOException ignored) {}
    }

    /** Registro de cada agente: cuántas acciones y la última. */
    public Map<String, Object> agentsStatus() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String[] a : AGENTS) {
            String s = Lab.read(new File(agentDir, a[0] + ".jsonl"));
            String[] l = s == null ? new String[0] : s.trim().split("\n");
            out.put(a[0], Json.map("motor", a[1], "funcion", a[2], "acciones", (long) (s == null ? 0 : l.length), "ultima", s == null ? null : l[l.length - 1]));
        }
        return out;
    }

    /** ¿Alguna mejora ya trabaja (o trabajó con éxito) sobre esta herramienta? Evita duplicar trabajo. */
    private static boolean inEvolution(Evolution evo, String tool) {
        for (Map<String, Object> m : evo.all()) {
            if (!tool.equals(m.get("herramienta"))) continue;
            String st = String.valueOf(m.get("estado"));
            if (!Evolution.DESCARTADA.equals(st)) return true;
        }
        return false;
    }

    private static String toolId(Map<String, Object> imp) {
        String t = Json.str(imp.get("herramienta"));
        if (t != null) return t;
        String m = String.valueOf(imp.get("modulo"));
        return m.startsWith("lab/") ? m.substring(4) : Lab.slug(String.valueOf(imp.get("nombre")).replaceFirst("^Aprender a ", ""));
    }
}
