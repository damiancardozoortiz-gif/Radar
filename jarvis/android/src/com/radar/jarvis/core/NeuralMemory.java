package com.radar.jarvis.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MEMORIA NEURONAL por niveles: una sola vista sobre lo que JARVIS ya guarda (no duplica datos).
 *  inmediata: historial corto de la conversación (persiste al cerrar la app) · conversaciones: turnos guardados
 *  conocimientos: notas (Vault) · experiencias: tareas registradas · errores: fallos registrados
 *  soluciones: aprendizajes verificados (evolución, laboratorio, investigación) · preferencias: lo que pidió recordar
 *  habilidades: capacidades + herramientas aprobadas del laboratorio.
 * recall() busca en todos los niveles a la vez para no reprocesar desde cero.
 */
public final class NeuralMemory {
    private NeuralMemory() {}

    public static Map<String, Object> levels(Brain b) {
        Map<String, Object> st = b.experience.stats();
        return Json.map("inmediata", (long) b.historySize(), "conversaciones", (long) b.historySize(),
                "conocimientos", (long) b.vault.notes.size(), "experiencias", st.get("tareas"), "errores", st.get("fallos"),
                "soluciones", st.get("aprendizajes"), "preferencias", (long) b.memories().size(),
                "habilidades", (long) (b.lab.active().size() + Brain.CAPABILITIES.split(";").length));
    }

    /** Recuerdos útiles para esta petición, por nivel. */
    public static Map<String, Object> recall(Brain b, String q, int n) {
        List<Object> know = new ArrayList<Object>(), prefs = new ArrayList<Object>(), exp = new ArrayList<Object>();
        for (Vault.Hit h : b.vault.search(q, n)) if (h.score >= 2.5) know.add(h.note.title);
        java.util.Set<String> qt = new java.util.HashSet<String>(Vault.tokens(q));
        for (String m : b.memories()) { int c = 0; for (String t : Vault.tokens(m)) if (qt.contains(t)) c++; if (c >= 1 && prefs.size() < n) prefs.add(m.length() > 80 ? m.substring(0, 80) + "…" : m); }
        for (String e : b.experience.relevant(q, n)) exp.add(e.length() > 80 ? e.substring(0, 80) + "…" : e);
        int total = know.size() + prefs.size() + exp.size();
        return Json.map("conocimientos", know, "preferencias", prefs, "experiencias", exp,
                "resumen", total == 0 ? "nada relacionado" : know.size() + " notas, " + prefs.size() + " preferencias, " + exp.size() + " experiencias");
    }
}
