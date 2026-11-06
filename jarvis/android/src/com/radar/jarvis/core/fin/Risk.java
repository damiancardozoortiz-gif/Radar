package com.radar.jarvis.core.fin;

import com.radar.jarvis.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gestor de riesgo y auditor. Reglas deterministas: ni Claude ni JARVIS pueden cambiarlas;
 * los límites solo los cambia el señor Rafael desde el panel financiero.
 * - Operaciones reales DESHABILITADAS en esta versión (no hay forma de activarlas).
 * - Solo cuenta DEMO, autorización humana para cada orden, stop loss obligatorio,
 *   límite de pérdida diaria, control de exposición y protección contra órdenes duplicadas.
 */
public final class Risk {
    public static final boolean REAL_TRADING_ENABLED = false;  // fijo en esta versión

    public static final class Limits {
        public double riskPct = 1.0, maxDailyLossPct = 3.0, maxTotalLots = 1.0;
        public int maxPositions = 3;
    }

    public static final class Order {
        public String symbol, side;  // side: "buy" | "sell"
        public double entry, sl, tp, lots;
        public long createdMs;
        public String id;
    }

    private Risk() {}

    /** Lote para arriesgar riskPct del saldo hasta el stop loss.
     *  contractSize: unidades por lote (forex 100000, oro 100…). Supone que la cuenta está en la divisa de cotización. */
    public static double lots(double balance, double riskPct, double entry, double sl, double contractSize, double lotStep, double minLot, double maxLot) {
        double dist = Math.abs(entry - sl);
        if (dist <= 0 || balance <= 0) return 0;
        double raw = balance * riskPct / 100.0 / (dist * contractSize);
        double stepped = Math.floor(raw / lotStep + 1e-9) * lotStep;
        if (stepped < minLot) return 0;  // con este stop, ni el lote mínimo cabe en el riesgo
        return Math.min(Math.round(stepped * 1000) / 1000.0, maxLot);
    }

    /** Pérdida si salta el stop (en divisa de la cuenta). */
    public static double lossAtStop(Order o, double contractSize) { return Math.abs(o.entry - o.sl) * contractSize * o.lots; }

    /**
     * Valida una orden. Devuelve la lista de motivos de rechazo (vacía = se puede PROPONER al usuario).
     * paused: PAUSA activa · demo: el conector confirma cuenta DEMO · todayLoss: pérdida realizada hoy (positiva)
     * open: posiciones abiertas {lots} · recent: órdenes de los últimos minutos (para duplicados).
     */
    public static List<String> check(Order o, Limits lim, double balance, double contractSize, boolean paused, boolean connected,
                                     boolean demo, double todayLoss, List<Order> open, List<Order> recent, long nowMs) {
        List<String> no = new ArrayList<String>();
        if (paused) no.add("JARVIS está en PAUSA");
        if (!connected) no.add("MT5 no está conectado");
        if (connected && !demo) no.add("la cuenta no es DEMO: las operaciones reales están deshabilitadas");
        if (!"buy".equals(o.side) && !"sell".equals(o.side)) no.add("dirección no válida");
        if (o.sl <= 0) no.add("falta el stop loss (obligatorio)");
        else if ("buy".equals(o.side) && o.sl >= o.entry || "sell".equals(o.side) && o.sl <= o.entry) no.add("el stop loss está del lado equivocado");
        if (o.tp > 0 && ("buy".equals(o.side) && o.tp <= o.entry || "sell".equals(o.side) && o.tp >= o.entry)) no.add("el take profit está del lado equivocado");
        if (o.lots <= 0) no.add("lote 0: con este stop no cabe ni el lote mínimo dentro del riesgo");
        if (o.sl > 0 && o.lots > 0 && balance > 0) {
            double risk = lossAtStop(o, contractSize) / balance * 100;
            if (risk > lim.riskPct + 1e-6) no.add(String.format(java.util.Locale.US, "arriesga %.2f %% y el límite por operación es %.2f %%", risk, lim.riskPct));
            if ((todayLoss + lossAtStop(o, contractSize)) / balance * 100 > lim.maxDailyLossPct + 1e-6)
                no.add(String.format(java.util.Locale.US, "superaría el límite de pérdida diaria (%.2f %%)", lim.maxDailyLossPct));
        }
        if (balance > 0 && todayLoss / balance * 100 >= lim.maxDailyLossPct) no.add("límite de pérdida diaria alcanzado: no se opera más hoy");
        double lots = o.lots;
        for (Order x : open) lots += x.lots;
        if (open.size() + 1 > lim.maxPositions) no.add("superaría el máximo de posiciones abiertas (" + lim.maxPositions + ")");
        if (lots > lim.maxTotalLots + 1e-9) no.add("superaría la exposición máxima (" + lim.maxTotalLots + " lotes en total)");
        for (Order r : recent)
            if (r.symbol.equals(o.symbol) && r.side.equals(o.side) && nowMs - r.createdMs < 120000) { no.add("orden duplicada: ya hay una igual en los últimos 2 minutos"); break; }
        return no;
    }

    /** Auditor: resultado acumulado, % de aciertos y máximo drawdown a partir de los resultados cerrados. */
    public static Map<String, Object> stats(double startBalance, List<Double> closedProfits) {
        double eq = startBalance, peak = startBalance, maxDd = 0, total = 0; int wins = 0;
        for (double p : closedProfits) {
            total += p; eq += p; if (p > 0) wins++;
            peak = Math.max(peak, eq);
            maxDd = Math.max(maxDd, peak > 0 ? (peak - eq) / peak * 100 : 0);
        }
        int n = closedProfits.size();
        return Json.map("operaciones", (long) n, "resultado", Math.round(total * 100) / 100.0,
                "rendimiento_pct", startBalance > 0 ? Math.round(total / startBalance * 10000) / 100.0 : null,
                "aciertos_pct", n > 0 ? Math.round(wins * 1000.0 / n) / 10.0 : null, "drawdown_max_pct", Math.round(maxDd * 100) / 100.0);
    }
}
