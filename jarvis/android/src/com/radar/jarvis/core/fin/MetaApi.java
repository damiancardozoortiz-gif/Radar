package com.radar.jarvis.core.fin;

import com.radar.jarvis.core.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Conector MT5 a través de MetaApi (metaapi.cloud): MetaApi mantiene el terminal MT5 en su nube y lo expone por REST.
 * FASE 1 · SOLO LECTURA: lee estado de la cuenta, saldo, tipo (DEMO/REAL), posiciones y velas. NO envía órdenes:
 * execute() falla siempre, sin tocar la red.
 *
 * Rutas tomadas del SDK oficial (metaapi.cloud-sdk 29.3.4):
 *  - dominio:  GET https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/users/current/servers/mt-client-api → {domain}
 *  - cuenta:   GET {provisioning}/users/current/accounts/{id} → region, state, connectionStatus
 *  - velas:    GET https://mt-market-data-client-api-v1.{region}.{domain}/users/current/accounts/{id}/historical-market-data/symbols/{s}/timeframes/{tf}/candles
 * Rutas de la API REST de cliente que el SDK no usa (él va por websocket) y que deben confirmarse con el servicio real:
 *  - GET https://mt-client-api-v1.{region}.{domain}/users/current/accounts/{id}/account-information
 *  - GET …/positions
 * Cabecera de autenticación: auth-token. El token nunca aparece en mensajes de error ni en el registro.
 */
public final class MetaApi implements Mt5 {
    public static final String DEFAULT_DOMAIN = "agiliumtrade.agiliumtrade.ai";
    public static final String[] TF_API = {"1m", "5m", "15m", "30m", "1h", "4h", "1d"};  // mismo orden que Market.TF

    private final String token, accountId, provisioning, fixedBase;
    private String clientBase, marketBase;
    private volatile Map<String, Object> lastAccount, lastInfo;
    private volatile String lastError;

    public MetaApi(String token, String accountId) {
        this(token, accountId, "https://mt-provisioning-api-v1." + DEFAULT_DOMAIN, null);
    }

    /** Para pruebas: todas las rutas contra un servidor local (solo se acepta http en 127.0.0.1). */
    public MetaApi(String token, String accountId, String provisioning, String fixedBase) {
        this.token = token == null ? "" : token.trim();
        this.accountId = accountId == null ? "" : accountId.trim();
        this.provisioning = provisioning;
        this.fixedBase = fixedBase;
        if (fixedBase != null) { clientBase = fixedBase; marketBase = fixedBase; }
    }

    public String name() { return "MetaApi"; }
    public boolean canTrade() { return false; }  // fase de solo lectura

    /** Según la última comprobación (no hace red: se puede llamar desde la interfaz). */
    public boolean connected() {
        Map<String, Object> a = lastAccount;
        return a != null && "DEPLOYED".equals(a.get("state")) && "CONNECTED".equals(a.get("connectionStatus"));
    }

    /** true solo si el servidor de MT5 dice que la cuenta es DEMO (según la última lectura). */
    public boolean isDemo() {
        Map<String, Object> i = lastInfo;
        return i != null && "ACCOUNT_TRADE_MODE_DEMO".equals(i.get("type"));
    }

    public String lastError() { return lastError; }

    /** Comprobación completa: cuenta en MetaApi + información de la cuenta MT5. Devuelve un resumen legible. */
    public Map<String, Object> refresh() throws IOException {
        try {
            Map<String, Object> a = Json.obj(get(provisioning + "/users/current/accounts/" + enc(accountId)));
            if (a == null) throw new IOException("MetaApi devolvió una respuesta vacía para la cuenta");
            lastAccount = a;
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("cuenta", a.get("name"));
            out.put("servidor", a.get("server"));
            out.put("estado", a.get("state"));
            out.put("conexion", a.get("connectionStatus"));
            out.put("region", a.get("region"));
            if (!connected()) {
                out.put("aviso", "DEPLOYED".equals(a.get("state"))
                        ? "MetaApi no está conectado al bróker (" + a.get("connectionStatus") + ")"
                        : "la cuenta no está desplegada en MetaApi (estado " + a.get("state") + "): despliéguela desde la web de MetaApi");
                lastError = String.valueOf(out.get("aviso"));
                return out;
            }
            Map<String, Object> i = account();
            out.put("broker", i.get("broker"));
            out.put("tipo", "ACCOUNT_TRADE_MODE_DEMO".equals(i.get("type")) ? "DEMO" : "ACCOUNT_TRADE_MODE_REAL".equals(i.get("type")) ? "REAL" : i.get("type"));
            out.put("saldo", i.get("balance"));
            out.put("equidad", i.get("equity"));
            out.put("divisa", i.get("currency"));
            out.put("apalancamiento", i.get("leverage"));
            out.put("solo_lectura_por_contrasena_de_inversor", i.get("investorMode"));
            lastError = null;
            return out;
        } catch (IOException e) { lastError = e.getMessage(); throw e; }
    }

    public Map<String, Object> account() throws IOException {
        Map<String, Object> i = Json.obj(get(client() + "/users/current/accounts/" + enc(accountId) + "/account-information"));
        if (i == null) throw new IOException("MetaApi devolvió una respuesta vacía para la información de la cuenta");
        lastInfo = i;
        return i;
    }

    public List<Map<String, Object>> positions() throws IOException {
        List<Object> l = Json.arr(get(client() + "/users/current/accounts/" + enc(accountId) + "/positions"));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (l != null) for (Object o : l) if (Json.obj(o) != null) out.add(Json.obj(o));
        return out;
    }

    /** Velas reales en orden cronológico. tf: M1, M5, M15, M30, H1, H4 o D1. limit ≤ 1000. */
    public List<Market.Candle> candles(String symbol, String tf, int limit) throws IOException {
        int k = -1;
        for (int i = 0; i < Market.TF.length; i++) if (Market.TF[i].equals(tf)) k = i;
        if (k < 0) throw new IOException("temporalidad no válida: " + tf);
        String url = market() + "/users/current/accounts/" + enc(accountId) + "/historical-market-data/symbols/" + enc(symbol)
                + "/timeframes/" + TF_API[k] + "/candles?limit=" + Math.max(1, Math.min(1000, limit));
        List<Object> l = Json.arr(get(url));
        List<Market.Candle> out = new ArrayList<Market.Candle>();
        if (l != null) for (Object o : l) {
            Map<String, Object> c = Json.obj(o);
            if (c == null) continue;
            long t = parseTime(Json.str(c.get("time")));
            if (t <= 0) continue;
            double v = c.get("tickVolume") instanceof Number ? ((Number) c.get("tickVolume")).doubleValue()
                    : c.get("volume") instanceof Number ? ((Number) c.get("volume")).doubleValue() : 0;
            try {
                out.add(new Market.Candle(t, ((Number) c.get("open")).doubleValue(), ((Number) c.get("high")).doubleValue(),
                        ((Number) c.get("low")).doubleValue(), ((Number) c.get("close")).doubleValue(), v));
            } catch (Exception ignored) {}  // vela incompleta: se descarta, no se rellena
        }
        Collections.sort(out, new Comparator<Market.Candle>() { public int compare(Market.Candle a, Market.Candle b) { return a.t < b.t ? -1 : a.t > b.t ? 1 : 0; } });
        return out;
    }

    /** Fase de solo lectura: nunca envía órdenes. */
    public Map<String, Object> execute(Risk.Order o) throws IOException {
        throw new IOException("conector MetaApi en modo SOLO LECTURA: JARVIS no envía órdenes a MT5 en esta versión");
    }

    // ------------------------------------------------------------------ red
    private synchronized String client() throws IOException { resolve(); return clientBase; }
    private synchronized String market() throws IOException { resolve(); return marketBase; }

    private void resolve() throws IOException {
        if (clientBase != null && marketBase != null) return;
        Map<String, Object> a = lastAccount;
        if (a == null) a = Json.obj(get(provisioning + "/users/current/accounts/" + enc(accountId)));
        String region = a == null ? null : Json.str(a.get("region"));
        if (region == null || region.isEmpty()) throw new IOException("MetaApi no indicó la región de la cuenta");
        Map<String, Object> d = Json.obj(get(provisioning + "/users/current/servers/mt-client-api"));
        String domain = d == null ? null : Json.str(d.get("domain"));
        if (domain == null || domain.isEmpty()) domain = DEFAULT_DOMAIN;
        clientBase = "https://mt-client-api-v1." + region + "." + domain;
        marketBase = "https://mt-market-data-client-api-v1." + region + "." + domain;
    }

    private Object get(String url) throws IOException {
        if (token.isEmpty()) throw new IOException("falta el token de MetaApi");
        if (accountId.isEmpty()) throw new IOException("falta el identificador de la cuenta de MetaApi");
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1:")) throw new IOException("conexión no cifrada rechazada");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);  // las velas históricas pueden tardar
        c.setRequestProperty("auth-token", token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : read(in);
            if (code == 202) throw new IOException("MetaApi aún está preparando los datos (202): reintente en unos segundos");
            if (code >= 400) {
                Map<String, Object> e = null;
                try { e = Json.obj(Json.parse(body)); } catch (Exception ignored) {}
                String msg = e != null && e.get("message") != null ? String.valueOf(e.get("message")) : "";
                if (code == 401 || code == 403) throw new IOException("MetaApi rechazó el token (" + code + ")" + (msg.isEmpty() ? "" : ": " + msg));
                if (code == 404) throw new IOException("MetaApi no encontró el recurso (404)" + (msg.isEmpty() ? "" : ": " + msg));
                if (code == 429) throw new IOException("MetaApi: demasiadas peticiones (429), espere un momento");
                throw new IOException("MetaApi respondió " + code + (msg.isEmpty() ? "" : ": " + msg));
            }
            return body.isEmpty() ? null : Json.parse(body);
        } finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }

    /** ISO 8601 UTC ("2020-04-07T03:45:00.000Z") → segundos epoch; 0 si no se entiende. */
    static long parseTime(String s) {
        if (s == null) return 0;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String f : fmts) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
                p.setTimeZone(TimeZone.getTimeZone("UTC"));
                p.setLenient(false);
                return p.parse(s).getTime() / 1000;
            } catch (Exception ignored) {}
        }
        return 0;
    }
}
