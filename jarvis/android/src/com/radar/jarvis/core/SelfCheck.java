package com.radar.jarvis.core;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AUTODIAGNÓSTICO: inventario técnico de JARVIS. Une lo que se sabe al compilar (inventario.json: versión, módulos,
 * líneas, permisos, resultado de las últimas pruebas) con lo que se comprueba en el teléfono (claves, permisos,
 * aislamiento…), el laboratorio, la experiencia, los agentes y el motor de evolución. Se guarda en
 * files/evolucion/inventario.json tras cada paso de la evolución. Solo cuenta lo registrado: no inventa capacidades.
 */
public final class SelfCheck {
    private SelfCheck() {}

    public static Map<String, Object> inventory(Brain b, Evolution evo, Vulkan vk, List<Map<String, Object>> probes) {
        Map<String, Object> st = null;
        try { st = Json.obj(Json.parse(b.assetText("inventario.json"))); } catch (Exception ignored) {}
        List<Object> failing = new ArrayList<Object>();
        if (probes != null) for (Map<String, Object> p : probes) if (!Boolean.TRUE.equals(p.get("ok"))) failing.add(p.get("modulo") + ": " + p.get("detalle"));
        for (Object e : Json.arr(b.experience.stats().get("errores_repetidos"))) failing.add("error repetido: " + e);
        Map<String, Object> inv = Json.map("actualizado", Evolution.now(),
                "compilacion", st == null ? "sin inventario de compilación" : st,
                "capacidades", Brain.CAPABILITIES,
                "herramientas_laboratorio", new ArrayList<Object>(b.lab.active().keySet()),
                "comprobaciones", probes == null ? new ArrayList<Object>() : new ArrayList<Object>(probes),
                "fallando", failing,
                "experiencia", b.experience.stats(),
                "evolucion", evo == null ? null : evo.status(System.currentTimeMillis()),
                "agentes", vk == null ? null : vk.agentsStatus());
        if (evo != null) {
            try { FileOutputStream o = new FileOutputStream(new File(evo.dir(), "inventario.json")); o.write(Json.write(inv).getBytes("UTF-8")); o.close(); } catch (Exception ignored) {}
        }
        return inv;
    }

    /** «¿Qué puedo hacer?» con lo registrado. */
    public static String canDo(Brain b) {
        String s = "Según mi inventario: " + Brain.CAPABILITIES.split("NO PUEDO")[0].replace("PUEDO: ", "").trim();
        if (!b.lab.active().isEmpty()) s += " Herramientas aprobadas del laboratorio: " + b.lab.active().keySet() + ".";
        return s;
    }

    public static String cannotDo() { String[] p = Brain.CAPABILITIES.split("NO PUEDO \\(todavía\\): "); return p.length > 1 ? "No puedo: " + p[1] : "No tengo esa lista."; }

    public static String failing(List<Map<String, Object>> probes, Brain b) {
        List<String> f = new ArrayList<String>();
        if (probes != null) for (Map<String, Object> p : probes) if (!Boolean.TRUE.equals(p.get("ok"))) f.add(p.get("modulo") + ": " + p.get("detalle"));
        for (Object e : Json.arr(b.experience.stats().get("errores_repetidos"))) f.add("error repetido: " + e);
        if (f.isEmpty()) return "Ninguna comprobación falla ahora y no hay errores repetidos registrados.";
        StringBuilder s = new StringBuilder("Esto falla:");
        for (String x : f) s.append("\n• ").append(x);
        return s.toString();
    }
}
