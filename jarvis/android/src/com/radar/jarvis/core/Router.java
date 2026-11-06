package com.radar.jarvis.core;

import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Elige herramienta (agent/tools.py) y reconoce órdenes del teléfono (agent/dispositivo.py). */
public final class Router {
    private Router() {}

    private static boolean has(String qs, String regex) { return Pattern.compile("\\b(" + regex + ")\\b").matcher(qs).find(); }

    static String clean(String q) {
        String n = Vault.norm(q).replaceAll("[^a-z0-9ñ ]+", " ").replaceAll("\\s+", " ").trim();
        return " " + n + " ";
    }

    /** Herramienta del cerebro: recordar, investigar_en_la_web, leer_correo, planear_el_dia, parte_del_dia,
     *  buscar_en_mi_cerebro o conversacion. */
    public static String tool(String question, Vault vault) {
        String qs = clean(question);
        if (has(qs, "(agrega|agrega|anade|anadi|crea|crea|apunta|pon) (una |la )?tarea|mis tareas|lista de tareas|(completa|termina|tacha|marca|borra) (la )?tarea|tareas pendientes")) return "tareas";
        if (has(qs, "(aprende|aprendas|aprender|ensenate|aprende) a |quiero que aprendas|nueva capacidad|nueva herramienta")) return "aprender";
        if (has(qs, "(prepara|haz|hazme|redacta|elabora|genera|arma|arma) (un |el |una )?(informe|reporte|analisis completo)")) return "informe";
        if (has(qs, "recuerda|recordar|apunta|anota|apuntalo|anotalo|guarda|guardalo|no olvides|memoriza")) return "recordar";
        if (has(qs, "internet|en la web|en la red|busca en google|en google|online|investiga\\w*|averigua\\w*|el mercado|del mercado|este producto|ese producto|cotizacion|noticias|precio actual")) return "investigar_en_la_web";
        if (has(qs, "correos?|e ?mails?|mails?|bandeja|me ha escrito|me han escrito|me escribio|mensajes?")) return "leer_correo";
        if (has(qs, "planea\\w*|planifica\\w*|prioridades|organiza(me)? el dia")
                || (has(qs, "plan") && !has(qs, "plan de (pensiones|ahorro|jubilacion|salud|vida|empresa)"))) return "planear_el_dia";
        if (has(qs, "hoy|agenda|semana|calendario|reuniones?|parte del dia|atrasad\\w*|pendientes?|manana|que tengo")) return "parte_del_dia";
        if (has(qs, "opinas|piensas|crees|te parece|me recomiendas")) return "conversacion";
        if (Vault.tokens(question).isEmpty()) return "conversacion";
        if (vault != null && !vault.search(question, 1).isEmpty() && vault.search(question, 1).get(0).score >= 2.5) return "buscar_en_mi_cerebro";
        return "conversacion";
    }

    public static boolean isPause(String q) {
        String c = clean(q).trim();
        return c.equals("pausa") || c.equals("jarvis pausa") || c.equals("pausa jarvis") || c.equals("para")
                || c.equals("detente") || c.equals("stop") || c.equals("alto");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    /** Orden del teléfono o null. Abrir apps nunca se ejecuta solo: la app pide confirmación. */
    public static Map<String, Object> device(String question) {
        String orig = Normalizer.normalize(question, Normalizer.Form.NFC).trim();
        String q = Vault.norm(orig);
        if (q.length() != orig.length()) orig = q;
        String qs = clean(question);
        if (has(qs, "(detener|deten|detene|para|parar) todo|pausa(r)? (todas las |todo|las )?operaciones|pausa todo|emergencia")) return Json.map("action", "stop_all", "say", "Detengo todo.");
        if (has(qs, "(abre|abri|abrir|enciende|encende|activa|pon|prende|prende) (la )?camara")) return Json.map("action", "camera_open", "say", "Abro la cámara.");
        if (has(qs, "(haz|saca|toma|hazme|sacame|captura) (una )?(foto|fotografia)")) return Json.map("action", "camera_open", "say", "Abro la cámara: pulsa capturar.");
        if (has(qs, "que ves|que estas viendo|lee (este|el) (documento|texto|papel)|que es esto|que pone")) return Json.map("action", "camera_open", "say", "Enséñamelo y pulsa capturar.");
        if (has(qs, "donde estoy|mi ubicacion|en que calle estoy")) return Json.map("action", "location", "say", "Miro dónde estás.");
        if (has(qs, "(elige|elegi|abre|abri|selecciona|busca) (un |el |una )?(archivo|documento|pdf|foto|imagen)")) return Json.map("action", "pick_file", "say", "Elija el archivo, señor Rafael.");
        if (has(qs, "(abre|abri) (el )?(panel )?financiero|jarvis financial|metatrader|mt5|panel de trading")) return Json.map("action", "finance", "say", "Abro el panel financiero.");
        if (has(qs, "abre radar|abri radar|radar")) return Json.map("action", "radar", "say", "Abro RADAR.");
        Matcher m = Pattern.compile("\\b(?:busca|pon|ponme|abre)\\b(?: en)? youtube (.+)$|\\b(?:busca|pon|ponme) (.+?) en youtube\\b").matcher(q);
        if (m.find()) { String t = g(orig, m, 1, 2); return open("YouTube", "https://www.youtube.com/results?search_query=" + enc(t), "Buscar «" + t + "» en YouTube"); }
        m = Pattern.compile("\\b(?:como llego|llevame|ruta|indicaciones)\\b(?: a| hasta| hacia)? (.+)$").matcher(q);
        if (m.find()) { String t = g(orig, m, 1, 1); return open("Google Maps", "https://www.google.com/maps/dir/?api=1&destination=" + enc(t), "Ruta hasta «" + t + "»"); }
        m = Pattern.compile("\\b(?:busca|buscame|encuentra)\\b (.+?) en (?:google )?maps\\b").matcher(q);
        if (m.find()) { String t = g(orig, m, 1, 1); return open("Google Maps", "https://www.google.com/maps/search/?api=1&query=" + enc(t), "Buscar «" + t + "» en Maps"); }
        m = Pattern.compile("\\b(?:escribe|manda|envia|mandale|enviale)\\b.*\\bwhatsapp\\b(?:.*?\\b(?:diciendo|que diga|con el texto)\\s*(.+))?$").matcher(q);
        if (m.find() || has(qs, "abre whatsapp")) {
            String t = (m.find(0) && m.group(1) != null) ? orig.substring(m.start(1), m.end(1)).trim() : "";
            return open("WhatsApp", "https://wa.me/" + (t.isEmpty() ? "" : "?text=" + enc(t)), "Abrir WhatsApp" + (t.isEmpty() ? "" : " con «" + t + "»") + ". Lo envías tú.");
        }
        if (has(qs, "abre gmail")) return open("Gmail", "https://mail.google.com/", "Abrir Gmail");
        if (has(qs, "abre instagram")) return open("Instagram", "https://www.instagram.com/", "Abrir Instagram");
        return null;
    }

    private static String g(String orig, Matcher m, int a, int b) {
        int i = m.group(a) != null ? a : b;
        return orig.substring(m.start(i), m.end(i)).replaceAll("^[\\s¿?¡!.]+|[\\s¿?¡!.]+$", "");
    }

    private static Map<String, Object> open(String app, String url, String label) {
        return Json.map("action", "open_app", "app", app, "url", url, "label", label, "say", "Te dejo el botón para abrirlo. " + label + ".");
    }
}
