package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JARVIS NEURAL CORE 1.0 · red de 10 nodos conectados al cerebro existente (Brain), sin duplicarlo.
 *
 *  percepción → lenguaje (reglas + RED NEURONAL de intención) → memoria (8 niveles) → planificación (pedidos compuestos)
 *  → razonamiento (Claude vía Brain) / investigación (web vía Brain) → ejecución (órdenes del teléfono)
 *  → validación (fuentes, cerebro real, pausa) → aprendizaje (ejemplos y correcciones → reentrena la red) → respuesta.
 *
 * Cada nodo lleva su estado REAL (inactivo, procesando, listo, error, desconectado), sus veces, su tiempo y su última
 * nota: la interfaz los muestra tal cual. Lo único «neuronal» en sentido estricto es NeuralNet (clasificador de intención
 * entrenado en el teléfono); los nodos son módulos de software conectados, y así se presentan.
 */
public final class NeuralCore {
    public static final String[] NODES = {"percepcion", "lenguaje", "memoria", "planificacion", "razonamiento", "investigacion",
            "ejecucion", "validacion", "aprendizaje", "respuesta"};
    public static final String[] INTENTS = {"conversacion", "investigar_en_la_web", "informe", "recordar", "tareas", "leer_correo",
            "planear_el_dia", "parte_del_dia", "aprender", "redactar"};
    static final double MIN_CONFIDENCE = 0.8;  // con menos seguridad decide la conversación normal (un error de ruta cuesta más)
    static final int RETRAIN_EVERY = 10;
    static final Pattern SPLIT = Pattern.compile("\\s*(?:,\\s*)?\\by\\s+(?:luego\\s+|despues\\s+|tambien\\s+)?(?=(?:prepar|redact|escrib|hac|arm|cre|investig|analiz|busc|resum|compar|averigu|elabor|gener|anot|record|agreg)\\w*\\b)");

    private final Brain brain;
    private final File dir;
    private final String corpus;
    private volatile NeuralNet net;
    private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<String, Map<String, Object>>();
    private int newExamples;
    private long byRules, byNet, agree, compared;
    private volatile Map<String, Object> last;

    /** corpusJson: ejemplos semilla {intención: [frases]} (assets/neural-corpus.json). */
    public NeuralCore(Brain brain, File dataDir, String corpusJson) {
        this.brain = brain;
        this.dir = new File(dataDir, "neural");
        dir.mkdirs();
        this.corpus = corpusJson;
        for (String n : NODES) nodes.put(n, Json.map("estado", "inactivo", "veces", 0L, "ms", 0L, "nota", ""));
        net = NeuralNet.load(new File(dir, "red.bin"), INTENTS);
        if (net == null) retrain();
    }

    // ================================================================== red neuronal: entrenamiento
    /** Entrena con los ejemplos semilla + los pedidos reales (×2) + las correcciones (×3). Determinista. */
    public synchronized Map<String, Object> retrain() {
        List<String[]> ex = new ArrayList<String[]>();
        try {
            Map<String, Object> c = Json.obj(Json.parse(corpus));
            for (String k : c.keySet()) for (Object o : Json.arr(c.get(k))) ex.add(new String[]{String.valueOf(o), k});
        } catch (Exception ignored) {}
        for (Map<String, Object> e : examples()) {
            int w = "correccion".equals(e.get("fuente")) ? 3 : 2;
            for (int i = 0; i < w; i++) ex.add(new String[]{String.valueOf(e.get("texto")), String.valueOf(e.get("intencion"))});
        }
        java.util.Collections.shuffle(ex, new java.util.Random(3));
        NeuralNet n = new NeuralNet(INTENTS);
        Map<String, Object> r = n.train(ex, 80, 0.1f);
        net = n;
        newExamples = 0;
        try { n.save(new File(dir, "red.bin")); } catch (IOException e) { r.put("error", e.getMessage()); }
        log("entrenamiento", Json.write(r));
        return r;
    }

    public List<Map<String, Object>> examples() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String s = Lab.read(new File(dir, "ejemplos.jsonl"));
        if (s != null) for (String l : s.split("\n")) { try { Map<String, Object> m = Json.obj(Json.parse(l)); if (m != null) out.add(m); } catch (Exception ignored) {} }
        return out;
    }

    private synchronized void addExample(String text, String intent, String source) {
        append("ejemplos.jsonl", Json.write(Json.map("texto", text, "intencion", intent, "fuente", source)));
        if (++newExamples >= RETRAIN_EVERY || "correccion".equals(source)) retrain();
    }

    /** Corrección humana: «ese pedido era X». Se guarda con más peso y la red se reentrena en el acto. */
    public void correct(String text, String intent) throws IOException {
        if (!java.util.Arrays.asList(INTENTS).contains(intent)) throw new IOException("intención desconocida: " + intent);
        addExample(text, intent, "correccion");
        log("corrección", text + " → " + intent);
    }

    // ================================================================== enrutador neuronal
    /** Decide la intención: primero las reglas que ya funcionan; si no reconocen nada, la red (solo si está segura). */
    public Map<String, Object> decide(String q) {
        Map<String, Object> pred = net.predict(q);
        String nn = String.valueOf(pred.get("intencion"));
        double conf = ((Number) pred.get("confianza")).doubleValue();
        if (Router.device(q) != null) return Json.map("ruta", "dispositivo", "fuente", "reglas", "confianza", 1.0, "red", pred);
        String rule = Router.tool(q, brain.vault);
        // «buscar en mis notas» es una suposición por parecido de palabras, no una intención: si la red está segura de otra, decide la red
        if ("buscar_en_mi_cerebro".equals(rule) && !"conversacion".equals(nn) && conf >= MIN_CONFIDENCE) {
            synchronized (this) { byNet++; }
            return Json.map("ruta", nn, "fuente", "red neuronal", "confianza", conf, "red", pred, "en_lugar_de", rule);
        }
        if (!"conversacion".equals(rule)) {
            synchronized (this) { byRules++; if (java.util.Arrays.asList(INTENTS).contains(rule)) { compared++; if (rule.equals(nn)) agree++; } }
            return Json.map("ruta", rule, "fuente", "reglas", "confianza", 1.0, "red", pred);
        }
        if (!"conversacion".equals(nn) && conf >= MIN_CONFIDENCE) { synchronized (this) { byNet++; } return Json.map("ruta", nn, "fuente", "red neuronal", "confianza", conf, "red", pred); }
        return Json.map("ruta", "conversacion", "fuente", conf < MIN_CONFIDENCE ? "reglas (red poco segura)" : "reglas y red", "confianza", conf, "red", pred);
    }

    /** Pedido compuesto («investigá X y prepará una publicación») → pasos con su intención. Un solo paso si no lo es. */
    public List<Map<String, Object>> plan(String q) {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        String n = NeuralNet.norm(q);
        Matcher m = SPLIT.matcher(n);
        List<String> parts = new ArrayList<String>();
        int at = 0;
        while (m.find()) { if (m.start() > at) parts.add(n.substring(at, m.start())); at = m.end(); }
        parts.add(n.substring(at));
        if (parts.size() < 2) { steps.add(Json.map("texto", q, "decision", decide(q))); return steps; }
        for (String p : parts) if (!p.trim().isEmpty()) steps.add(Json.map("texto", p.trim(), "decision", decide(p.trim())));
        return steps;
    }

    // ================================================================== proceso completo
    public Map<String, Object> process(String q) {
        long t0 = System.currentTimeMillis();
        List<Object> trace = new ArrayList<Object>();
        begin("percepcion");
        String text = q == null ? "" : q.trim();
        end("percepcion", trace, text.split("\\s+").length + " palabras" + (text.endsWith("?") ? ", pregunta" : ""));

        begin("lenguaje");
        List<Map<String, Object>> steps = plan(text);
        Map<String, Object> d0 = Json.obj(steps.get(0).get("decision"));
        end("lenguaje", trace, steps.size() > 1 ? steps.size() + " pasos" : d0.get("ruta") + " (" + d0.get("fuente") + ", " + d0.get("confianza") + ")");

        begin("memoria");
        Map<String, Object> recall = NeuralMemory.recall(brain, text, 3);
        end("memoria", trace, recall.get("resumen") + "");

        Map<String, Object> out;
        if (steps.size() > 1) {
            begin("planificacion");
            end("planificacion", trace, steps.size() + " pasos en orden");
            StringBuilder det = new StringBuilder();
            Map<String, Object> r = null;
            String prev = null;
            int i = 0;
            for (Map<String, Object> st : steps) {
                i++;
                Map<String, Object> d = Json.obj(st.get("decision"));
                String route = String.valueOf(d.get("ruta"));
                String ask = String.valueOf(st.get("texto")) + (prev == null ? "" : "\n\nResultado del paso anterior (úsalo):\n" + prev);
                r = reason(ask, route, d, trace);
                if (Boolean.TRUE.equals(r.get("paused"))) break;
                String piece = r.get("detail") != null && !String.valueOf(r.get("detail")).isEmpty() ? String.valueOf(r.get("detail")) : String.valueOf(r.get("say"));
                det.append("## Paso ").append(i).append(": ").append(st.get("texto")).append("\n").append(piece).append("\n\n");
                prev = piece.length() > 6000 ? piece.substring(0, 6000) : piece;
            }
            out = r == null ? Json.map("say", "No pude completar el plan.") : new LinkedHashMap<String, Object>(r);
            out.put("detail", det.toString().trim());
            out.put("tool", "plan de " + steps.size() + " pasos");
        } else {
            out = reason(text, String.valueOf(d0.get("ruta")), d0, trace);
        }

        begin("ejecucion");
        end("ejecucion", trace, out.get("device") != null ? "orden del teléfono: " + Json.obj(out.get("device")).get("action") : "nada que ejecutar");
        if (out.get("device") == null) idle("ejecucion");

        begin("validacion");
        boolean ok = "claude".equals(out.get("brain")) || "app".equals(out.get("brain"));
        String v = Boolean.TRUE.equals(out.get("paused")) ? "en pausa: nada se ejecuta" : !ok ? "SIN cerebro: respuesta de respaldo" : out.get("warning") != null ? "con aviso: " + out.get("warning") : "respuesta válida";
        end("validacion", trace, v);
        if (!ok) markError("validacion", v);

        begin("aprendizaje");
        String learnt = "nada";
        if (ok && !Boolean.TRUE.equals(out.get("paused")) && steps.size() == 1 && java.util.Arrays.asList(INTENTS).contains(String.valueOf(d0.get("ruta")))
                && !"conversacion".equals(d0.get("ruta"))) {
            addExample(text, String.valueOf(d0.get("ruta")), String.valueOf(d0.get("fuente")).startsWith("reglas") ? "reglas" : "red");
            learnt = "ejemplo «" + d0.get("ruta") + "» guardado";
        }
        end("aprendizaje", trace, learnt);

        begin("respuesta");
        end("respuesta", trace, "lista");
        Map<String, Object> neural = Json.map("ruta", d0.get("ruta"), "fuente", d0.get("fuente"), "confianza", d0.get("confianza"),
                "pasos", (long) steps.size(), "nodos", trace, "memoria", recall, "ms", System.currentTimeMillis() - t0);
        out.put("neural", neural);
        last = Json.map("pedido", text, "decision", d0, "pasos", (long) steps.size());
        return out;
    }

    /** razonamiento (+ investigación si la intención usa la web): el cerebro existente hace el trabajo. */
    private Map<String, Object> reason(String q, String route, Map<String, Object> d, List<Object> trace) {
        boolean web = "investigar_en_la_web".equals(route) || "informe".equals(route) || "aprender".equals(route);
        if (web) begin("investigacion");
        begin("razonamiento");
        String forced = "red neuronal".equals(d.get("fuente")) || java.util.Arrays.asList(INTENTS).contains(route) && !"conversacion".equals(route) ? route : null;
        Map<String, Object> r = brain.answer(q, "dispositivo".equals(route) ? null : forced);
        String note = r.get("brain") + " · " + r.get("tool") + (r.get("searches") != null ? " · " + r.get("searches") + " búsquedas, " + r.get("sources") + " fuentes" : "");
        end("razonamiento", trace, note);
        if (!"claude".equals(r.get("brain")) && !"app".equals(r.get("brain")) && !"pausa".equals(r.get("brain"))) markError("razonamiento", String.valueOf(r.get("warning")));
        if (web) end("investigacion", trace, r.get("searches") != null ? r.get("searches") + " búsquedas, " + r.get("sources") + " fuentes reales" : "sin búsquedas");
        return r;
    }

    // ================================================================== estado de los nodos (para la interfaz)
    private synchronized void begin(String n) { Map<String, Object> m = nodes.get(n); m.put("estado", "procesando"); m.put("t", System.currentTimeMillis()); }

    private synchronized void end(String n, List<Object> trace, String note) {
        Map<String, Object> m = nodes.get(n);
        long ms = System.currentTimeMillis() - (m.get("t") instanceof Number ? ((Number) m.get("t")).longValue() : System.currentTimeMillis());
        if (!"error".equals(m.get("estado")) && !"desconectado".equals(m.get("estado"))) m.put("estado", "listo");
        m.put("veces", ((Number) m.get("veces")).longValue() + 1);
        m.put("ms", ms);
        m.put("nota", note);
        trace.add(Json.map("nodo", n, "ms", ms, "nota", note));
    }

    private synchronized void idle(String n) { nodes.get(n).put("estado", "inactivo"); }
    private synchronized void markError(String n, String why) { nodes.get(n).put("estado", "error"); nodes.get(n).put("nota", why); }

    /** La app informa de conexiones reales (sin clave de Claude → razonamiento desconectado, etc.). */
    public synchronized void setConnected(String n, boolean ok, String note) {
        Map<String, Object> m = nodes.get(n);
        if (m == null) return;
        if (!ok) { m.put("estado", "desconectado"); m.put("nota", note); }
        else if ("desconectado".equals(m.get("estado"))) { m.put("estado", "inactivo"); m.put("nota", note); }
    }

    /** Copia del estado de los nodos y de la red, para el panel. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> ns = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<String, Object>(e.getValue());
            c.remove("t");
            ns.put(e.getKey(), c);
        }
        int corr = 0;
        for (Map<String, Object> e : examples()) if ("correccion".equals(e.get("fuente"))) corr++;
        return Json.map("nodos", ns, "red", Json.map("intenciones", (long) INTENTS.length, "neuronas_ocultas", (long) NeuralNet.H, "rasgos", (long) NeuralNet.D,
                        "entrenada_con", (long) net.trainedOn, "precision_validacion", net.validationAccuracy < 0 ? null : Math.round(net.validationAccuracy * 1000) / 10.0,
                        "ejemplos_reales", (long) examples().size(), "correcciones", (long) corr,
                        "acuerdo_con_reglas", compared == 0 ? null : Math.round(agree * 1000.0 / compared) / 10.0, "decisiones_reglas", byRules, "decisiones_red", byNet),
                "ultima", last);
    }

    public Map<String, Object> lastDecision() { return last; }

    private void log(String what, String detail) { append("registro.jsonl", Json.write(Json.map("fecha", Evolution.now(), "accion", what, "detalle", detail))); }

    private void append(String file, String line) {
        try { FileOutputStream o = new FileOutputStream(new File(dir, file), true); o.write((line + "\n").getBytes("UTF-8")); o.close(); } catch (IOException ignored) {}
    }
}
