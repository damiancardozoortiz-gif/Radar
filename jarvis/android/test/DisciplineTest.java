import com.radar.jarvis.core.fin.*;
import java.io.File;
import java.util.*;

/** Regla de autodisciplina: ganancias (4.ª tras 3 ganadoras), pérdidas (bloqueo tras 2), volumen, PAUSA, autorización. Arg: carpeta temporal. */
public class DisciplineTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static boolean has(List<String> l, String s) { for (String x : l) if (x.contains(s)) return true; return false; }
    static Discipline.Trade t(String acc, double net, double lots, long closeMs) {
        Discipline.Trade t = new Discipline.Trade();
        t.account = acc; t.symbol = "EURUSD"; t.tf = "H1"; t.side = "buy"; t.entry = 1.1; t.exit = net >= 0 ? 1.102 : 1.098; t.lots = lots; t.sl = 1.095; t.tp = 1.11;
        t.net = net; t.commission = 0.7; t.spread = 0.8; t.openMs = closeMs - 3600000; t.closeMs = closeMs;
        t.strategy = "ruptura de resistencia H1"; t.entryReason = "cierre sobre resistencia con volumen"; t.exitReason = net >= 0 ? "take profit" : "stop loss";
        t.conditions = "tendencia alcista H4, RSI 58"; return t;
    }
    static Risk.Order order(double lots) { Risk.Order o = new Risk.Order(); o.symbol = "EURUSD"; o.side = "buy"; o.entry = 1.1; o.sl = 1.095; o.lots = lots; return o; }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]); dir.mkdirs();
        Discipline d = new Discipline(dir);
        long now = System.currentTimeMillis();
        check(has(d.check(order(0.1), false, 0, 0.2), "no hay un ciclo autorizado"), "sin autorización: no se permite ninguna orden");
        try { d.authorize(false, "", now); check(false, "autorizar sin revisar"); } catch (Exception e) { check(true, "autorizar sin revisar el riesgo: rechazado"); }
        d.authorize(true, "revisión inicial", now - 1000);
        check(d.check(order(0.1), false, 0, 0.2).isEmpty(), "ciclo autorizado: 1.ª operación permitida");
        check(d.check(order(0.1), true, 0, 0.2).get(0).contains("PAUSA"), "PAUSA: primer motivo de bloqueo (prioritario)");
        check(has(d.check(order(0.4), false, 0, 0.2), "mayor que el que fija el riesgo"), "volumen por encima del riesgo: rechazado");
        try { Discipline.Trade x = t("demo", 10, 0.1, now); x.sl = 0; x.exitReason = ""; d.record(x); check(false, "registro incompleto"); }
        catch (Exception e) { check(e.getMessage().contains("stop loss") && e.getMessage().contains("motivo de salida"), "registro sin stop loss ni motivo: rechazado → " + e.getMessage()); }
        d.record(t("demo", 25, 0.1, now)); d.record(t("demo", 18, 0.1, now));
        check(has(d.check(order(0.1), false, 1, 0.2), "3 anteriores estén cerradas"), "2 cerradas + 1 abierta: la 4.ª no puede ir antes de cerrar la 3.ª");
        d.record(t("demo", 30, 0.1, 1000));  // fecha de cierre antigua: cuenta igual (se mide por registro)
        check(d.cycle().size() == 3, "una fecha de cierre antigua no saca la operación del ciclo");
        check(d.check(order(0.1), false, 0, 0.2).isEmpty(), "3 ganadoras netas: 4.ª permitida");
        check(String.valueOf(d.panel().get("proxima")).contains("4.ª operación permitida"), "panel: próxima = 4.ª permitida");
        check(has(d.check(order(0.15), false, 0, 0.1), "mayor que el que fija el riesgo"), "tras ganar no se aumenta el volumen");
        d.record(t("demo", 12, 0.1, now));
        check(has(d.check(order(0.1), false, 0, 0.2), "ciclo completo"), "tras la 4.ª: ciclo completo, hace falta nueva autorización");

        d.authorize(true, "nuevo ciclo", System.currentTimeMillis());
        Thread.sleep(5);
        d.record(t("demo", 20, 0.1, now)); d.record(t("demo", -15, 0.1, now)); d.record(t("demo", 22, 0.1, now));
        check(has(d.check(order(0.1), false, 0, 0.2), "tras 3 ganadoras netas (2 de 3)"), "ganadora, perdedora, ganadora: no hay 4.ª");

        d.authorize(true, "otro ciclo", System.currentTimeMillis());
        Thread.sleep(5);
        d.record(t("real", -40, 0.1, now));
        check(has(d.check(order(0.2), false, 0, 0.2), "tras una pérdida el volumen no puede subir"), "tras una pérdida no se duplica el volumen");
        check(d.check(order(0.1), false, 0, 0.2).isEmpty(), "tras 1 pérdida: se sigue con el mismo volumen");
        Map<String, Object> r = d.record(t("demo", -35, 0.1, now));
        check(d.blocked() && r.get("bloqueo") != null, "2 pérdidas (una real y una demo): BLOQUEO inmediato");
        check(new File(String.valueOf(r.get("informe"))).isFile(), "informe de pérdidas generado: " + new File(String.valueOf(r.get("informe"))).getName());
        check(has(d.check(order(0.05), false, 0, 0.2), "BLOQUEO"), "bloqueado: ni una orden pequeña pasa");
        Discipline again = new Discipline(dir);
        check(again.blocked() && has(again.check(order(0.05), false, 0, 0.2), "BLOQUEO"), "el bloqueo sobrevive a un reinicio de la app");
        Map<String, Object> p = again.panel();
        Map<?, ?> demo = (Map<?, ?>) p.get("demo"), real = (Map<?, ?>) p.get("real");
        check(((Number) real.get("perdedoras")).intValue() == 1 && ((Number) demo.get("perdedoras")).intValue() == 2 && ((Number) demo.get("ganadoras")).intValue() == 6,
                "panel: demo y real por separado → demo " + demo + " · real " + real);
        check(String.valueOf(p.get("proxima")).startsWith("ninguna") && String.valueOf(p.get("bloqueo")).startsWith("BLOQUEADO"), "panel: bloqueo y próxima operación = ninguna");
        again.saveLesson("op-1", "demo", "las rupturas sin volumen fallaron dos veces");
        check(again.lessons(5).size() == 1 && ((Number) again.panel().get("aprendizajes")).intValue() == 1, "aprendizaje guardado y contado");
        again.authorize(true, "revisé el informe y mantengo el riesgo", System.currentTimeMillis());
        check(!again.blocked() && again.check(order(0.1), false, 0, 0.2).isEmpty(), "solo la autorización expresa reactiva");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
