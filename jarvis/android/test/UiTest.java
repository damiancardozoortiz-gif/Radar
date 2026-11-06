import com.radar.jarvis.core.UiCommands;
import java.util.Map;

/** Órdenes de pantalla resueltas en el teléfono (sin Claude). */
public class UiTest {
    static int ok = 0, bad = 0;
    static void is(String q, String op, String target) {
        Map<String, Object> m = UiCommands.parse(q);
        boolean c = op == null ? m == null : m != null && op.equals(m.get("op")) && target.equals(m.get("target"));
        if (c) ok++; else bad++;
        System.out.println((c ? "PASA " : "FALLA") + "  «" + q + "» → " + (m == null ? "Claude" : m.get("op") + " " + m.get("target")));
    }
    public static void main(String[] a) {
        is("JARVIS, abre el chat", "open", "chat");
        is("abrí el chat", "open", "chat");
        is("Jarvis, cerrá el chat.", "close", "chat");
        is("JARVIS, muéstrame la investigación", "open", "results");
        is("mostrame los resultados por favor", "open", "results");
        is("JARVIS, abre el editor", "open", "editor");
        is("abre el editor de vídeo", "open", "editor");
        is("JARVIS, oculta todas las ventanas", "hide_all", "all");
        is("cerrá todo", "hide_all", "all");
        is("JARVIS, vuelve a la pantalla principal", "home", "all");
        is("volvé a la pantalla principal", "home", "all");
        is("muestra el historial", "open", "history");
        is("abre el panel", "open", "panel");
        // no son órdenes de pantalla: van a Claude o al enrutador
        is("abre el chat de WhatsApp con Ricardo", null, null);
        is("investigá el mercado de seguros de hogar en Madrid", null, null);
        is("abre el panel financiero", null, null);
        is("¿qué opinas del último informe de ventas que te mandé ayer por la tarde?", null, null);
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
