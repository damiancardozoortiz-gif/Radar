package com.radar.jarvis.core;

import java.util.regex.Pattern;

/** Órdenes de voz del motor de evolución, resueltas en el teléfono y respondidas con registros reales (sin Claude). */
public final class EvoCommands {
    private EvoCommands() {}

    static final String[][] RULES = {
        {"pausa_total", "pausa total|pausa todo|pausa general"},
        {"start", "(trabaja|trabaje|trabajá|trabajar) en (ti|vos|usted) mismo|mejorate|mejorate a ti mismo|evoluciona|inicia (la )?evolucion|empieza a evolucionar"},
        {"stop", "(deten|detene|para|pará|terminá|termina) (la )?evolucion|(deja|dejá) de trabajar en (ti|vos) mismo"},
        {"pause", "(pausa|pausá|pausar) (la )?evolucion"},
        {"resume", "(reanuda|reanudá|continua|continuá|segui|seguí) (con )?(la )?evolucion"},
        {"improving", "que (estas|estás|esta) mejorando|en que (estas|estás) trabajando"},
        {"learned", "que aprendiste( hoy)?"},
        {"fixed", "que errores corregiste"},
        {"new_caps", "que capacidades nuevas (desarrollaste|tenes|tienes)"},
        {"missing", "que te falta( para evolucionar)?"},
        {"can_do", "que (podes|puedes) hacer( ahora| actualmente)?"},
        {"cannot_do", "que no (podes|puedes) hacer"},
        {"failing", "que (esta|está) fallando|que falla"},
        {"status", "estado de (la )?evolucion|como va (la )?evolucion"},
    };

    /** Devuelve la clave de la orden o null. */
    public static String parse(String text) {
        if (text == null) return null;
        String t = UiCommands.norm(text).replaceAll("^(y |bueno |a ver )", "");
        if (t.length() > 80) return null;
        for (String[] r : RULES) if (Pattern.matches("(" + r[1] + ")( por favor)?", t)) return r[0];
        return null;
    }
}
