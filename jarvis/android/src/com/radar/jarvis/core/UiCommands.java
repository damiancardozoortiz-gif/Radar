package com.radar.jarvis.core;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Órdenes de pantalla («abre el chat», «oculta todas las ventanas»…). Se resuelven en el teléfono, sin Claude:
 * instantáneo y sin coste. Solo frases cortas y completas, para no confundir «abre el chat de WhatsApp» con el chat.
 * Devuelve {op: open|close|hide_all|home, target, say} o null si no es una orden de pantalla.
 */
public final class UiCommands {
    private UiCommands() {}

    static final String OPEN = "(abre|abri|abrime|abreme|muestra|muestrame|mostrame|mostra|ensename|ensenime|ver|quiero ver|pon|pone|trae|traeme|despliega)";
    static final String CLOSE = "(cierra|cerra|cerrame|oculta|ocultame|esconde|escondeme|quita|quitame|minimiza)";
    static final String ART = "(el |la |los |las |mi |mis )?";
    static final String TAIL = "( por favor)?";

    static final String[][] TARGETS = {
        {"chat", "(chat|conversacion)"},
        {"results", "(ultima )?(investigacion|investigaciones|resultado|resultados|informe|informes|ultimo informe|ultimo resultado)"},
        {"history", "(historial|registro|actividad|registro de actividad)"},
        {"editor", "(editor|editor de video|editor de videos|editor multimedia)"},
        {"panel", "(panel|panel de control|ajustes|configuracion|administracion)"},
        {"lab", "(laboratorio|lab|laboratorio de automejora)"},
        {"evo", "(evolucion|panel de evolucion|fenix|motor de evolucion)"},
        {"neural", "(red neuronal|neural core|nucleo neuronal|cerebro neuronal)"},
    };

    static final String[] NAMES = {"el chat", "los resultados", "el historial", "el editor de vídeo", "el panel", "el laboratorio", "el panel de evolución", "el Neural Core"};

    public static Map<String, Object> parse(String text) {
        if (text == null) return null;
        String t = norm(text);
        if (t.length() > 70) return null;
        if (Pattern.matches(CLOSE + " (todas )?(las )?ventanas" + TAIL + "|(cierra|cerra|oculta|esconde) todo" + TAIL
                + "|limpia (la )?pantalla" + TAIL + "|pantalla limpia" + TAIL, t))
            return Json.map("op", "hide_all", "target", "all", "say", "Ventanas ocultas.");
        if (Pattern.matches("(vuelve|volve|volver|regresa|regresar|ve|ir|anda) (a )?(la )?pantalla principal" + TAIL + "|pantalla principal" + TAIL
                + "|(vuelve|volve|regresa) al inicio" + TAIL + "|inicio" + TAIL, t))
            return Json.map("op", "home", "target", "all", "say", "Pantalla principal.");
        for (int i = 0; i < TARGETS.length; i++) {
            String target = TARGETS[i][0], words = TARGETS[i][1];
            if (Pattern.matches(OPEN + " " + ART + words + TAIL, t))
                return Json.map("op", "open", "target", target, "say", "Abro " + NAMES[i] + ".");
            if (Pattern.matches(CLOSE + " " + ART + words + TAIL, t))
                return Json.map("op", "close", "target", target, "say", "Cierro " + NAMES[i] + ".");
        }
        return null;
    }

    /** minúsculas, sin tildes, sin «JARVIS,» al principio ni signos. */
    static String norm(String s) {
        String t = Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(oye |hey |ok )?jarvis ", "").replaceFirst("^(por favor )", "");
        return t.trim();
    }
}
