package com.radar.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.radar.jarvis.core.Brain;
import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.fin.Market;
import com.radar.jarvis.core.fin.MetaApi;
import com.radar.jarvis.core.fin.Mt5;
import com.radar.jarvis.core.fin.Risk;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** JARVIS FINANCIAL INTELLIGENCE: análisis multitemporal, estratega, gestor de riesgo y ticket de orden (solo DEMO).
 *  MT5 por MetaApi en fase de SOLO LECTURA: lee cuenta, posiciones y velas; no envía órdenes. */
public class FinanceActivity extends Activity {
    static final int REQ_CSV = 21;
    private Brain brain;
    private Mt5 mt5;              // MetaApi (solo lectura) si está configurado; si no, sin conexión (no se simula)
    private Map<String, Object> mt5Info;  // última lectura de la cuenta
    private String mt5Error;
    private List<Market.Candle> candles = new ArrayList<Market.Candle>();
    private Map<String, Object> analysis;
    private Map<String, Object> proposal;
    private final List<Risk.Order> recent = new ArrayList<Risk.Order>();
    private LinearLayout body;
    private TextView status, result;
    private EditText symbol, contract, balance;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        brain = JarvisApp.brainOrNull();
        if (brain == null) { finish(); return; }
        mt5 = JarvisApp.mt5(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        body = Ui.col(this);
        int pad = Ui.dp(this, 14);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        setContentView(sv);
        build();
        checkMt5();
    }

    /** Lee de verdad la cuenta en MetaApi (segundo plano) y refresca la pantalla. */
    private void checkMt5() {
        if (!(mt5 instanceof MetaApi)) return;
        final MetaApi m = (MetaApi) mt5;
        new Thread(new Runnable() { public void run() {
            Map<String, Object> info = null; String err = null;
            try { info = m.refresh(); if (!m.connected()) err = m.lastError(); } catch (Exception e) { err = e.getMessage(); }
            final Map<String, Object> fi = info; final String fe = err;
            JarvisApp.ui(new Runnable() { public void run() {
                mt5Info = fi; mt5Error = fe;
                if (fi != null && fi.get("saldo") instanceof Number) JarvisApp.store(FinanceActivity.this).put("fin_balance", String.valueOf(((Number) fi.get("saldo")).doubleValue()));
                brain.log("finanzas", "MT5 comprobado: " + (fe == null ? "conectado" : fe));
                build();
            }});
        }}).start();
    }

    private String mt5Line() {
        if (!(mt5 instanceof MetaApi)) return "NO CONFIGURADO (ver «Conexión MT5»)";
        if (mt5Error != null) return "ERROR: " + mt5Error;
        if (mt5Info == null) return "comprobando…";
        return "conectado vía MetaApi · cuenta " + mt5Info.get("tipo") + " · SOLO LECTURA";
    }

    private void build() {
        body.removeAllViews();
        body.addView(Ui.mono(this, "JARVIS FINANCIAL INTELLIGENCE", 13, Ui.ACCENT));
        status = Ui.text(this, "", 12, Ui.TEXT);
        body.addView(status, Ui.margins(this, 0, 6, 0, 0));
        body.addView(Ui.button(this, "⏹ EMERGENCIA: PAUSAR TODO", Ui.BAD, new View.OnClickListener() { public void onClick(View v) {
            brain.setPause(true, "emergencia financiera"); brain.log("finanzas", "emergencia"); refreshStatus();
            Toast.makeText(FinanceActivity.this, "PAUSA activada. Nada se ejecuta.", Toast.LENGTH_LONG).show();
        }}), Ui.margins(this, 0, 8, 0, 0));

        section("ESPECIALISTAS");
        body.addView(Ui.text(this,
                "• Investigador financiero (Claude + búsqueda web): " + (brain.apiKey.isEmpty() ? "sin clave de Claude" : "listo") + "\n"
              + "• Analista técnico, de tendencias y de liquidez (cálculo en el teléfono): listo · datos: " + (candles.isEmpty() ? "ninguno" : candles.size() + " velas") + "\n"
              + "• Estratega de operaciones (Claude, solo con los números calculados): " + (brain.apiKey.isEmpty() ? "sin clave" : "listo") + "\n"
              + "• Gestor de riesgo (reglas fijas): activo\n"
              + "• Ejecutor MT5: " + mt5Line() + "\n"
              + "• Auditor de resultados: registra cada propuesta y decisión", 12, Ui.TEXT));

        section("DATOS Y ANÁLISIS");
        symbol = field("Instrumento (p. ej. EURUSD)", JarvisApp.store(this).get("fin_symbol", "EURUSD"), false);
        body.addView(symbol);
        body.addView(Ui.text(this, mt5 instanceof MetaApi
                ? "«Velas MT5» lee de su cuenta, vía MetaApi, M5, M15, H1, H4 y D1 del instrumento. También puede importar un CSV."
                : "Sin conexión a MT5, importe velas reales: en MetaTrader 5 de escritorio, Ver → Símbolos → Barras → Exportar (CSV). "
                + "Cualquier temporalidad de M1 a D1: JARVIS calcula las mayores.", 12, Ui.DIM));
        LinearLayout r = Ui.row(this);
        if (mt5 instanceof MetaApi) {
            r.addView(Ui.button(this, "Velas MT5", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { loadMt5Candles(); } }));
            r.addView(gap());
        }
        r.addView(Ui.button(this, "Importar CSV", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { pickCsv(); } }));
        r.addView(gap());
        r.addView(Ui.button(this, "Estratega", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { strategist(); } }));
        r.addView(gap());
        r.addView(Ui.button(this, "Investigar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { research(); } }));
        body.addView(r, Ui.margins(this, 0, 6, 0, 0));
        result = Ui.text(this, analysis == null ? "Sin análisis todavía." : summary(analysis), 12, Ui.TEXT);
        body.addView(result, Ui.margins(this, 0, 8, 0, 0));

        section("RIESGO (solo usted cambia estos límites)");
        final Risk.Limits lim = limits();
        final EditText rp = field("Riesgo por operación %", String.valueOf(lim.riskPct), true);
        final EditText dl = field("Pérdida diaria máxima %", String.valueOf(lim.maxDailyLossPct), true);
        final EditText mp = field("Máximo de posiciones", String.valueOf(lim.maxPositions), true);
        final EditText ml = field("Exposición máxima (lotes)", String.valueOf(lim.maxTotalLots), true);
        body.addView(label("Riesgo por operación (%)")); body.addView(rp);
        body.addView(label("Pérdida diaria máxima (%)")); body.addView(dl);
        body.addView(label("Máximo de posiciones abiertas")); body.addView(mp);
        body.addView(label("Exposición máxima (lotes)")); body.addView(ml);
        body.addView(Ui.button(this, "Guardar límites", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            try {
                Store s = JarvisApp.store(FinanceActivity.this);
                s.put("fin_riskPct", String.valueOf(Double.parseDouble(rp.getText().toString())));
                s.put("fin_dailyPct", String.valueOf(Double.parseDouble(dl.getText().toString())));
                s.put("fin_maxPos", String.valueOf(Integer.parseInt(mp.getText().toString())));
                s.put("fin_maxLots", String.valueOf(Double.parseDouble(ml.getText().toString())));
                brain.log("finanzas", "límites de riesgo cambiados por el usuario");
                Toast.makeText(FinanceActivity.this, "Límites guardados.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(FinanceActivity.this, "Número no válido.", Toast.LENGTH_LONG).show(); }
        }}));

        section("TICKET DE ORDEN (DEMO)");
        body.addView(label("Saldo de la cuenta DEMO (para calcular el lote)"));
        balance = field("Saldo", JarvisApp.store(this).get("fin_balance", "10000"), true);
        body.addView(balance);
        body.addView(label("Tamaño de contrato (forex 100000, oro 100, índices según su bróker)"));
        contract = field("Contrato", JarvisApp.store(this).get("fin_contract", "100000"), true);
        body.addView(contract);
        body.addView(Ui.button(this, proposal == null ? "Preparar orden (pida antes una estrategia)" : "Preparar orden propuesta", Ui.ACCENT,
                new View.OnClickListener() { public void onClick(View v) { ticket(); } }), Ui.margins(this, 0, 8, 0, 0));

        section("CONEXIÓN MT5 (MetaApi · solo lectura)");
        StringBuilder st = new StringBuilder("Estado: ").append(mt5Line());
        if (mt5Info != null) for (Map.Entry<String, Object> e : mt5Info.entrySet()) st.append("\n").append(e.getKey()).append(": ").append(e.getValue());
        body.addView(Ui.text(this, st.toString(), 12, mt5Error == null ? Ui.TEXT : Ui.BAD));
        body.addView(Ui.text(this, "Cómo conectar (una vez, desde el navegador del teléfono):\n"
                + "1) Cree una cuenta en metaapi.cloud.\n"
                + "2) Añada su cuenta DEMO de MT5 con la CONTRASEÑA DE INVERSOR (así MetaApi solo puede leer) y espere a que quede «Deployed».\n"
                + "3) Copie el ID de la cuenta y genere un token de acceso; lo más seguro es limitarlo a esa cuenta y a lectura.\n"
                + "4) Péguelos aquí. El token se guarda cifrado en el teléfono y no se muestra.\n"
                + "MetaApi es un servicio de terceros (de pago, con prueba): su contraseña de MT5 queda en su nube.", 12, Ui.DIM));
        final EditText acc = field("ID de la cuenta en MetaApi", JarvisApp.store(this).get("metaapi_account", ""), false);
        acc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        final EditText tok = field(JarvisApp.store(this).hasSecret("metaapi_token") ? "Token guardado (escriba otro para cambiarlo)" : "Token de MetaApi", "", false);
        tok.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(acc, Ui.margins(this, 0, 6, 0, 0));
        body.addView(tok, Ui.margins(this, 0, 6, 0, 0));
        LinearLayout cr = Ui.row(this);
        cr.addView(Ui.button(this, "Guardar y comprobar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
            Store s = JarvisApp.store(FinanceActivity.this);
            String a = acc.getText().toString().trim(), t = tok.getText().toString().trim();
            if (a.isEmpty() || (t.isEmpty() && !s.hasSecret("metaapi_token"))) { Toast.makeText(FinanceActivity.this, "Faltan el ID de la cuenta o el token.", Toast.LENGTH_LONG).show(); return; }
            s.put("metaapi_account", a);
            if (!t.isEmpty()) s.secret("metaapi_token", t);
            brain.log("finanzas", "conexión MetaApi configurada por el usuario");
            JarvisApp.resetMt5(); mt5 = JarvisApp.mt5(FinanceActivity.this); mt5Info = null; mt5Error = null;
            build(); checkMt5();
        }}));
        cr.addView(gap());
        cr.addView(Ui.button(this, "Olvidar conexión", Ui.BAD, new View.OnClickListener() { public void onClick(View v) {
            Store s = JarvisApp.store(FinanceActivity.this);
            s.secret("metaapi_token", ""); s.put("metaapi_account", "");
            brain.log("finanzas", "conexión MetaApi borrada por el usuario");
            JarvisApp.resetMt5(); mt5 = JarvisApp.mt5(FinanceActivity.this); mt5Info = null; mt5Error = null;
            build();
        }}));
        body.addView(cr, Ui.margins(this, 0, 6, 0, 0));
        body.addView(Ui.text(this, "Esta versión NO envía órdenes a MT5 (solo lectura). Operaciones reales: DESHABILITADAS.", 12, Ui.DIM),
                Ui.margins(this, 0, 6, 0, 0));
        section("AUDITORÍA (últimas acciones financieras)");
        StringBuilder au = new StringBuilder();
        for (String l : brain.tail("acciones.jsonl", 40)) if (l.contains("finanzas")) au.append(l).append('\n');
        body.addView(Ui.text(this, au.length() == 0 ? "Sin acciones todavía." : au.toString(), 11, Ui.DIM));
        refreshStatus();
    }

    private void refreshStatus() {
        status.setText("MT5: " + mt5Line() + " · Operaciones reales: DESHABILITADAS"
                + (brain.isPaused() ? "\nPAUSA ACTIVA: nada se ejecuta" : ""));
        status.setTextColor(brain.isPaused() ? Ui.BAD : Ui.TEXT);
    }

    // ------------------------------------------------------------------ datos y análisis
    private void pickCsv() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_CSV);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CSV || res != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() { public void run() {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                String text = new String(b.toByteArray(), "UTF-8").replace("\u0000", "");  // los CSV de MT5 pueden venir en UTF-16
                final List<Market.Candle> cs = Market.parseCsv(text);
                final Map<String, Object> an = cs.size() >= 60 ? Market.multi(cs) : null;
                JarvisApp.ui(new Runnable() { public void run() {
                    candles = cs; analysis = an;
                    brain.log("finanzas", "velas importadas: " + cs.size());
                    if (an == null) Toast.makeText(FinanceActivity.this, "Solo " + cs.size() + " velas válidas: hacen falta al menos 60.", Toast.LENGTH_LONG).show();
                    build();
                }});
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { Toast.makeText(FinanceActivity.this, "No pude leer el archivo: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        }}).start();
    }

    /** Velas reales de la cuenta MT5 (MetaApi) en varias temporalidades, y análisis. */
    private void loadMt5Candles() {
        final String sym = symbol.getText().toString().trim().toUpperCase(Locale.US);
        if (sym.isEmpty()) return;
        JarvisApp.store(this).put("fin_symbol", sym);
        result.setText("Leyendo velas de " + sym + " en MT5…");
        new Thread(new Runnable() { public void run() {
            final Map<String, List<Market.Candle>> by = new LinkedHashMap<String, List<Market.Candle>>();
            String err = null;
            for (String tf : new String[]{"M5", "M15", "H1", "H4", "D1"}) {
                try { by.put(tf, mt5.candles(sym, tf, 300)); }
                catch (Exception e) { err = tf + ": " + e.getMessage(); break; }
            }
            final String fe = err;
            final Map<String, Object> an = fe == null ? Market.multiTf(by) : null;
            JarvisApp.ui(new Runnable() { public void run() {
                if (fe != null) { result.setText("No pude leer las velas de MT5 (" + fe + ")."); brain.log("finanzas", "velas MT5 fallidas: " + fe); return; }
                analysis = an; candles = by.get("M5");
                brain.log("finanzas", "velas MT5 leídas: " + sym);
                build();
            }});
        }}).start();
    }

    @SuppressWarnings("unchecked")
    static String summary(Map<String, Object> m) {
        StringBuilder b = new StringBuilder();
        if (m.get("aviso") != null) return String.valueOf(m.get("aviso"));
        b.append("Datos: ").append(m.get("temporalidad_base")).append(" · ").append(m.get("desde")).append(" → ").append(m.get("hasta")).append("\n");
        for (Object o : Json.arr(m.get("analisis"))) {
            Map<String, Object> a = Json.obj(o);
            b.append("\n").append(a.get("temporalidad")).append(": ");
            if (a.get("aviso") != null) { b.append(a.get("aviso")); continue; }
            b.append(a.get("tendencia")).append(" · estructura ").append(a.get("estructura"))
             .append("\n  precio ").append(a.get("precio")).append(" · RSI ").append(a.get("rsi14")).append(" (").append(a.get("rsi_lectura")).append(")")
             .append(" · MACD ").append(a.get("macd_cruce")).append(" · ATR ").append(a.get("atr14"))
             .append("\n  soporte ").append(a.get("soporte") == null ? "—" : Json.obj(a.get("soporte")).get("precio"))
             .append(" · resistencia ").append(a.get("resistencia") == null ? "—" : Json.obj(a.get("resistencia")).get("precio"))
             .append(" · vela: ").append(a.get("vela"));
            List<Object> f = Json.arr(a.get("rupturas_falsas"));
            if (f != null && !f.isEmpty()) b.append(" · ").append(f.size()).append(" ruptura(s) falsa(s)");
        }
        return b.toString();
    }

    private void strategist() {
        if (analysis == null) { Toast.makeText(this, "Importe antes las velas.", Toast.LENGTH_LONG).show(); return; }
        if (brain.isPaused()) { Toast.makeText(this, "En pausa.", Toast.LENGTH_LONG).show(); return; }
        final String sym = symbol.getText().toString().trim().toUpperCase(Locale.US);
        JarvisApp.store(this).put("fin_symbol", sym);
        result.setText("Consultando al estratega…");
        final Risk.Limits lim = limits();
        new Thread(new Runnable() { public void run() {
            final Map<String, Object> r = brain.strategy(sym, analysis, Json.map("riesgo_por_operacion_pct", lim.riskPct,
                    "perdida_diaria_max_pct", lim.maxDailyLossPct, "max_posiciones", (long) lim.maxPositions, "exposicion_max_lotes", lim.maxTotalLots));
            JarvisApp.ui(new Runnable() { public void run() {
                proposal = Json.obj(r.get("proposal"));
                build();
                result.setText(summary(analysis) + "\n\n— ESTRATEGA —\n" + r.get("say") + "\n\n" + r.get("detail")
                        + (r.get("warning") != null ? "\n\n" + r.get("warning") : ""));
                if (!Boolean.TRUE.equals(r.get("paused"))) JarvisApp.voice(FinanceActivity.this).speak(String.valueOf(r.get("say")), null);
            }});
        }}).start();
    }

    private void research() {
        final String sym = symbol.getText().toString().trim();
        result.setText("Investigando " + sym + "…");
        new Thread(new Runnable() { public void run() {
            final Map<String, Object> r = brain.answer("Investiga en internet la situación actual del mercado de " + sym
                    + ": noticias, contexto macro y sentimiento. Separa datos de opinión.");
            JarvisApp.ui(new Runnable() { public void run() {
                result.setText(r.get("say") + "\n\n" + (r.get("detail") == null ? "" : r.get("detail")) + (r.get("warning") != null ? "\n\n" + r.get("warning") : ""));
                JarvisApp.voice(FinanceActivity.this).speak(String.valueOf(r.get("say")), null);
            }});
        }}).start();
    }

    // ------------------------------------------------------------------ orden (autorización humana)
    private void ticket() {
        if (proposal == null) { Toast.makeText(this, "Pida antes una estrategia con una propuesta.", Toast.LENGTH_LONG).show(); return; }
        final Risk.Order o = new Risk.Order();
        try {
            o.symbol = symbol.getText().toString().trim().toUpperCase(Locale.US);
            o.side = String.valueOf(proposal.get("direccion"));
            o.entry = ((Number) proposal.get("entrada")).doubleValue();
            o.sl = proposal.get("sl") instanceof Number ? ((Number) proposal.get("sl")).doubleValue() : 0;
            o.tp = proposal.get("tp") instanceof Number ? ((Number) proposal.get("tp")).doubleValue() : 0;
        } catch (Exception e) { Toast.makeText(this, "La propuesta no trae números válidos.", Toast.LENGTH_LONG).show(); return; }
        final Risk.Limits lim = limits();
        final double bal = num(balance, 0), cs = num(contract, 100000);
        JarvisApp.store(this).put("fin_balance", String.valueOf(bal));
        JarvisApp.store(this).put("fin_contract", String.valueOf(cs));
        o.lots = Risk.lots(bal, lim.riskPct, o.entry, o.sl, cs, 0.01, 0.01, 100);
        o.createdMs = System.currentTimeMillis();
        boolean demo = false;
        try { demo = mt5.isDemo(); } catch (Exception ignored) {}
        List<String> no = Risk.check(o, lim, bal, cs, brain.isPaused(), mt5.connected(), demo, 0, new ArrayList<Risk.Order>(), recent, o.createdMs);
        if (!mt5.canTrade()) no.add("el conector MT5 está en modo SOLO LECTURA: JARVIS no envía órdenes (puede copiarla a mano en MT5)");
        String t = String.format(Locale.US, "%s %s\nEntrada %.5f · SL %.5f · TP %s\nLote calculado: %.2f (riesgo %.2f %% de %.2f)\nPérdida si salta el stop: %.2f",
                o.side.toUpperCase(Locale.US), o.symbol, o.entry, o.sl, o.tp > 0 ? String.format(Locale.US, "%.5f", o.tp) : "—",
                o.lots, lim.riskPct, bal, Risk.lossAtStop(o, cs));
        brain.log("finanzas", "orden preparada " + o.side + " " + o.symbol + " (" + (no.isEmpty() ? "válida" : "rechazada por riesgo") + ")");
        AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("Ticket de orden · DEMO").setNegativeButton("Cancelar", null);
        if (!no.isEmpty()) {
            StringBuilder b = new StringBuilder(t).append("\n\nNO SE PUEDE EJECUTAR:");
            for (String x : no) b.append("\n• ").append(x);
            d.setMessage(b.toString());
        } else {
            d.setMessage(t + "\n\n¿Autoriza esta orden en la cuenta DEMO?").setPositiveButton("Autorizo (DEMO)", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) {
                    recent.add(o);
                    try { Map<String, Object> r = mt5.execute(o); brain.log("finanzas", "orden ejecutada en DEMO: " + r.get("stringCode")); }
                    catch (Exception e) { brain.log("finanzas", "orden no ejecutada: " + e.getMessage()); Toast.makeText(FinanceActivity.this, e.getMessage(), Toast.LENGTH_LONG).show(); }
                }
            });
        }
        d.show();
    }

    // ------------------------------------------------------------------ utilidades
    private Risk.Limits limits() {
        Store s = JarvisApp.store(this);
        Risk.Limits l = new Risk.Limits();
        try {
            l.riskPct = Double.parseDouble(s.get("fin_riskPct", "1.0"));
            l.maxDailyLossPct = Double.parseDouble(s.get("fin_dailyPct", "3.0"));
            l.maxPositions = Integer.parseInt(s.get("fin_maxPos", "3"));
            l.maxTotalLots = Double.parseDouble(s.get("fin_maxLots", "1.0"));
        } catch (Exception ignored) {}
        return l;
    }

    private static double num(EditText e, double def) { try { return Double.parseDouble(e.getText().toString().trim()); } catch (Exception x) { return def; } }

    private EditText field(String hint, String value, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value); e.setSingleLine(true);
        e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.DIM);
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setBackground(Ui.box(this, Ui.PANEL, Ui.LINE, 8));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        return e;
    }

    private TextView label(String s) { TextView t = Ui.text(this, s, 12, Ui.DIM); t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 2)); return t; }

    private void section(String s) { TextView t = Ui.mono(this, s, 11, Ui.DIM); t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4)); body.addView(t); }

    private View gap() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 6), 1)); return v; }
}
