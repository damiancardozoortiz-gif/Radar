package com.radar.jarvis.core;

import java.util.List;

/** Saludo de arranque. Solo dice "Todos los sistemas están operativos" si la comprobación real no encontró fallos. */
public final class Greeting {
    private Greeting() {}

    /** 5:00–11:59 Buen día · 12:00–19:59 Buenas tardes · resto Buenas noches. */
    public static String salutation(int hour) {
        if (hour >= 5 && hour < 12) return "Buen día";
        if (hour >= 12 && hour < 20) return "Buenas tardes";
        return "Buenas noches";
    }

    /** problems: módulos que fallaron en la comprobación (vacío = todo bien). */
    public static String text(int hour, List<String> problems) {
        StringBuilder b = new StringBuilder(salutation(hour)).append(", señor Rafael. JARVIS está a su servicio. ");
        if (problems == null || problems.isEmpty()) {
            b.append("Todos los sistemas están operativos. ");
        } else {
            b.append("Atención: ");
            for (int i = 0; i < problems.size(); i++) {
                if (i > 0) b.append(i == problems.size() - 1 ? " y " : ", ");
                b.append(problems.get(i));
            }
            b.append(". ");
        }
        return b.append("¿En qué puedo ayudarlo hoy?").toString();
    }
}
