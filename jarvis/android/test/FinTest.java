import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.fin.*;
import java.util.*;

/** Pruebas del módulo financiero con valores conocidos (sin red). */
public class FinTest {
    static int ok = 0, bad = 0;
    static void check(boolean c, String m) { if (c) ok++; else bad++; System.out.println((c ? "PASA " : "FALLA") + "  " + m); }
    static boolean near(double a, double b, double tol) { return Math.abs(a - b) <= tol; }

    public static void main(String[] a) {
        // RSI(14) de Wilder sobre la serie clásica de referencia (StockCharts): primer valor ≈ 70,53
        double[] c = {44.3389, 44.0902, 44.1497, 43.6124, 44.3278, 44.8264, 45.0955, 45.4245, 45.8433, 46.0826, 45.8931, 46.0328, 45.6140, 46.2820, 46.2820, 46.0028, 46.0328, 46.4116, 46.2222, 45.6439};
        double[] r = Market.rsi(c, 14);
        check(near(r[14], 70.53, 0.05), String.format("RSI(14) de referencia: %.2f (esperado 70,53)", r[14]));
        double[] s = Market.sma(new double[]{1, 2, 3, 4, 5}, 3);
        check(Double.isNaN(s[1]) && s[2] == 2 && s[4] == 4, "SMA(3)");
        double[] e = Market.ema(new double[]{1, 2, 3, 4, 5, 6}, 3);
        check(e[2] == 2 && near(e[5], 5, 1e-9), "EMA(3)");
        Map<String, Object> f = Market.fibonacci(100, 200, true);
        check(near((Double) f.get("0.618"), 138.2, 1e-9) && near((Double) f.get("0.5"), 150, 1e-9), "Fibonacci 61,8 % y 50 %");
        // CSV exportado de MT5 (tabuladores, fecha con puntos)
        StringBuilder csv = new StringBuilder("<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>\t<VOL>\t<SPREAD>\n");
        Random rnd = new Random(7);
        double p = 1.1000;
        long t0 = 1735689600L;  // 2025-01-01 00:00 UTC
        for (int i = 0; i < 3000; i++) {
            double o = p, cl = p + (rnd.nextGaussian() * 0.0004) + 0.00002;
            double h = Math.max(o, cl) + Math.abs(rnd.nextGaussian()) * 0.0002, l = Math.min(o, cl) - Math.abs(rnd.nextGaussian()) * 0.0002;
            long t = t0 + i * 300L;
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy.MM.dd\tHH:mm:ss"); df.setTimeZone(TimeZone.getTimeZone("UTC"));
            csv.append(df.format(new Date(t * 1000))).append(String.format(Locale.US, "\t%.5f\t%.5f\t%.5f\t%.5f\t%d\t0\t10\n", o, h, l, cl, 100 + rnd.nextInt(200)));
            p = cl;
        }
        List<Market.Candle> cs = Market.parseCsv(csv.toString());
        check(cs.size() == 3000 && Market.TF[Market.baseTf(cs)].equals("M5"), "CSV de MT5 leído: " + cs.size() + " velas M5");
        List<Market.Candle> h1 = Market.resample(cs, 3600);
        check(h1.size() == 250 && near(h1.get(0).o, cs.get(0).o, 1e-12) && near(h1.get(0).c, cs.get(11).c, 1e-12), "M5 → H1 agrupado bien (" + h1.size() + " velas)");
        Map<String, Object> m = Market.multi(cs);
        List<Object> tfs = Json.arr(m.get("analisis"));
        check(tfs.size() == 6, "análisis multitemporal M5…D1 (" + tfs.size() + " temporalidades)");
        Map<String, Object> m5 = Json.obj(tfs.get(0));
        check(m5.get("rsi14") != null && m5.get("atr14") != null && m5.get("estructura") != null && m5.get("tendencia") != null, "M5: RSI, ATR, estructura y tendencia");
        check(String.valueOf(Json.obj(tfs.get(5)).get("aviso")).contains("pocas velas"), "D1 con 10 velas: avisa que faltan datos (no inventa)");
        // gestor de riesgo
        double lots = Risk.lots(10000, 1.0, 1.1000, 1.0950, 100000, 0.01, 0.01, 100);
        check(near(lots, 0.20, 1e-9), "lote: 1 % de 10.000 con stop de 50 pips en EURUSD = " + lots + " (esperado 0,20)");
        Risk.Limits lim = new Risk.Limits();
        Risk.Order o = new Risk.Order(); o.symbol = "EURUSD"; o.side = "buy"; o.entry = 1.1; o.sl = 1.095; o.tp = 1.11; o.lots = lots; o.createdMs = 1000;
        List<String> no = Risk.check(o, lim, 10000, 100000, false, true, true, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000);
        check(no.isEmpty(), "orden correcta en DEMO: se puede proponer " + no);
        Risk.Order nosl = new Risk.Order(); nosl.symbol = "EURUSD"; nosl.side = "buy"; nosl.entry = 1.1; nosl.lots = 0.1; nosl.createdMs = 1000;
        check(Risk.check(nosl, lim, 10000, 100000, false, true, true, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("stop loss"), "sin stop loss: rechazada");
        check(Risk.check(o, lim, 10000, 100000, false, true, false, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("no es DEMO"), "cuenta real: rechazada");
        check(Risk.check(o, lim, 10000, 100000, false, false, false, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("no está conectado"), "sin MT5: rechazada");
        check(Risk.check(o, lim, 10000, 100000, true, true, true, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("PAUSA"), "en pausa: rechazada");
        check(Risk.check(o, lim, 10000, 100000, false, true, true, 250, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("pérdida diaria"), "superaría el límite diario: rechazada");
        check(Risk.check(o, lim, 10000, 100000, false, true, true, 0, new ArrayList<Risk.Order>(), Arrays.asList(o), 60000).toString().contains("duplicada"), "orden duplicada: rechazada");
        Risk.Order big = new Risk.Order(); big.symbol = "EURUSD"; big.side = "sell"; big.entry = 1.1; big.sl = 1.105; big.lots = 1.0; big.createdMs = 1000;
        check(Risk.check(big, lim, 10000, 100000, false, true, true, 0, new ArrayList<Risk.Order>(), new ArrayList<Risk.Order>(), 1000).toString().contains("arriesga 5.00 %"), "arriesgar 5 % con límite 1 %: rechazada");
        check(!Risk.REAL_TRADING_ENABLED, "operaciones reales deshabilitadas en esta versión");
        Map<String, Object> st = Risk.stats(10000, Arrays.asList(200.0, -500.0, 100.0, -300.0, 400.0));
        check(near((Double) st.get("drawdown_max_pct"), 6.86, 0.01) && near((Double) st.get("resultado"), -100, 1e-9), "auditor: resultado -100 y drawdown máx " + st.get("drawdown_max_pct") + " %");
        check(!new Mt5.NotConnected().connected(), "MT5: no se simula ninguna conexión");
        System.out.println(ok + " PASA · " + bad + " FALLA");
        System.exit(bad == 0 ? 0 : 1);
    }
}
