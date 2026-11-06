import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.fin.*;
import java.nio.file.*;
import java.util.*;

/** Conector MetaApi contra una MetaApi SIMULADA (test/mock_metaapi.py). Args: puerto, archivo de registro. */
public class MetaApiTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }

    public static void main(String[] a) throws Exception {
        String base = "http://127.0.0.1:" + a[0];
        MetaApi m = new MetaApi("tok-prueba", "acc-demo-1", base, base);
        check(!m.connected() && !m.isDemo(), "antes de comprobar: ni conectado ni DEMO (no se supone nada)");
        Map<String, Object> r = m.refresh();
        check(m.connected() && m.isDemo(), "cuenta desplegada y conectada; el servidor dice DEMO → " + r.get("tipo"));
        check(Double.valueOf(10000).equals(((Number) r.get("saldo")).doubleValue()) && "USD".equals(r.get("divisa")), "saldo y divisa leídos: " + r.get("saldo") + " " + r.get("divisa"));
        check(m.positions().size() == 1, "posiciones abiertas leídas");
        List<Market.Candle> h1 = m.candles("EURUSD", "H1", 300);
        boolean asc = true; for (int i = 1; i < h1.size(); i++) if (h1.get(i).t <= h1.get(i - 1).t) asc = false;
        check(h1.size() == 300 && asc, "300 velas H1 en orden cronológico (MetaApi las da al revés)");
        check(h1.get(h1.size() - 1).t == 1793966400L, "hora de la última vela: 2026-11-06 12:00 UTC");
        Map<String, List<Market.Candle>> by = new LinkedHashMap<String, List<Market.Candle>>();
        for (String tf : new String[]{"M15", "H1", "H4", "D1"}) by.put(tf, m.candles("EURUSD", tf, 200));
        Map<String, Object> an = Market.multiTf(by);
        check("M15".equals(an.get("temporalidad_base")) && Json.arr(an.get("analisis")).size() == 4, "análisis multitemporal con velas propias de cada temporalidad");
        check(!m.canTrade(), "fase 1: el conector no puede operar");
        Risk.Order o = new Risk.Order(); o.symbol = "EURUSD"; o.side = "buy"; o.entry = 1.1; o.sl = 1.095; o.lots = 0.1;
        String err = ""; try { m.execute(o); } catch (Exception e) { err = e.getMessage(); }
        check(err.contains("SOLO LECTURA"), "execute() se niega: " + err);
        try { m.candles("EURUSD", "H2", 10); check(false, "temporalidad inventada"); } catch (Exception e) { check(true, "temporalidad no válida: rechazada sin pedirla"); }
        MetaApi badTok = new MetaApi("tok-malo", "acc-demo-1", base, base);
        try { badTok.refresh(); check(false, "token malo aceptado"); } catch (Exception e) { check(e.getMessage().contains("rechazó el token") && !e.getMessage().contains("tok-malo"), "token no válido: error claro y sin mostrar el token → " + e.getMessage()); }
        check(!badTok.connected(), "token no válido: queda NO conectado");
        MetaApi noAcc = new MetaApi("tok-prueba", "otra", base, base);
        try { noAcc.refresh(); check(false, "cuenta inexistente aceptada"); } catch (Exception e) { check(e.getMessage().contains("404"), "cuenta inexistente: " + e.getMessage()); }
        MetaApi plain = new MetaApi("tok-prueba", "acc-demo-1", "http://ejemplo.org", null);
        try { plain.refresh(); check(false, "http aceptado"); } catch (Exception e) { check(e.getMessage().contains("no cifrada"), "conexión sin cifrar: rechazada"); }
        String log = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");
        check(!log.contains("\"POST\""), "ninguna petición de orden llegó al servidor");
        check(!new Mt5.NotConnected().canTrade(), "sin conexión: tampoco opera");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
