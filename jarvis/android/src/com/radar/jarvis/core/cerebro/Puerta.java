package com.radar.jarvis.core.cerebro;

import com.radar.jarvis.core.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PUERTA NEURONAL CENTRAL. Todo mensaje entre módulos pasa por aquí y se controla, EN ESTE ORDEN:
 *  1. PAUSA (bloquea todo)            6. permiso (libre / requiere confirmación / prohibido)
 *  2. módulo destino válido           7. circuito abierto por fallos repetidos (recuperación tras un enfriamiento)
 *  3. conexión permitida              8. ciclo (el destino ya está en la cadena de llamadas de la traza)
 *  4. estado (solo los operativos     9. límites: saltos, mensajes por traza, tiempo total
 *     ejecutan; el resto lo dice)    10. contrato de ENTRADA → ejecución medida → contrato de SALIDA → registro
 *  5. activación bajo demanda (máx. MAX_ACTIVOS en memoria; se libera el menos usado)
 * Conexiones permitidas (NO malla completa): padre↔hijo, hermanos, cruzadas declaradas y aprendidas por uso (≥ 3 veces
 * juntas en trazas exitosas). El coordinador (origen 0, VULKAN) puede llamar a cualquier módulo.
 */
public final class Puerta {
    public interface Reloj { long ahora(); }
    public interface Pausa { boolean activa(); }

    public static final int COORDINADOR = 0, MAX_SALTOS = 12, MAX_MENSAJES = 48, MAX_ACTIVOS = 64, FALLOS_CORTE = 3;
    public static final long MAX_MS_TRAZA = 15000, ENFRIAMIENTO_MS = 60000;

    /** Una cadena de mensajes originada por un pedido: memoria compartida, permisos concedidos y camino de llamadas. */
    public static final class Traza {
        public final String id; public final long inicio;
        public final Map<String, Object> pizarra = new LinkedHashMap<String, Object>();  // memoria compartida de la traza
        public final Set<Integer> autorizados = new HashSet<Integer>();                  // confirmación humana concedida
        final Deque<Integer> pila = new ArrayDeque<Integer>();
        public final List<Integer> visitados = new ArrayList<Integer>();
        int mensajes;
        Traza(String id, long t) { this.id = id; inicio = t; }
    }

    public static final class Rechazo extends Exception {
        public final String motivo;
        Rechazo(String motivo, String msg) { super(msg); this.motivo = motivo; }
    }

    final Arbol arbol;
    final Map<Integer, Modulo> modulos;
    final Reloj reloj; final Pausa pausa;
    final Set<Long> cruzadas = new LinkedHashSet<Long>();
    final Map<Long, Integer> uso = new HashMap<Long, Integer>();   // conexiones aprendidas (peso por uso exitoso)
    final Deque<Map<String, Object>> registro = new ArrayDeque<Map<String, Object>>();
    final LinkedHashMap<Integer, Long> activos = new LinkedHashMap<Integer, Long>(16, 0.75f, true);
    final File archivo;
    int trazas, enviados, rechazados, errores;

    public Puerta(Arbol arbol, Map<Integer, Modulo> modulos, Reloj reloj, Pausa pausa, File archivoRegistro) {
        this.arbol = arbol; this.modulos = modulos; this.reloj = reloj; this.pausa = pausa; archivo = archivoRegistro;
    }

    static long par(int a, int b) { return a < b ? ((long) a << 32) | b : ((long) b << 32) | a; }
    public void cruzada(int a, int b) { cruzadas.add(par(a, b)); }

    public Traza nueva(String id) { trazas++; return new Traza(id, reloj.ahora()); }

    /** ¿Hay conexión directa permitida? */
    public boolean conectados(int a, int b) {
        if (a == COORDINADOR || b == COORDINADOR) return true;
        if (Arbol.padre(a) == b || Arbol.padre(b) == a || Arbol.hermano(a) == b) return true;
        if (cruzadas.contains(par(a, b))) return true;
        Integer w = uso.get(par(a, b));
        return w != null && w >= 3;
    }
    public String tipoConexion(int a, int b) {
        if (a == COORDINADOR || b == COORDINADOR) return "coordinación";
        if (Arbol.padre(a) == b || Arbol.padre(b) == a) return "jerárquica";
        if (Arbol.hermano(a) == b) return "horizontal";
        if (cruzadas.contains(par(a, b))) return "cruzada";
        Integer w = uso.get(par(a, b));
        return w != null && w >= 3 ? "aprendida" : "ninguna";
    }

    /** Envía datos de origen a destino dentro de la traza. Devuelve la salida del destino o lanza Rechazo con el motivo. */
    public Map<String, Object> enviar(int origen, int destino, Map<String, Object> datos, Traza t) throws Rechazo {
        long t0 = reloj.ahora();
        t.mensajes++; enviados++;
        try {
            if (pausa != null && pausa.activa()) throw new Rechazo("pausa", "PAUSA activa: la puerta no deja pasar ningún mensaje");
            if (!Arbol.valido(destino)) throw new Rechazo("destino", "el módulo " + destino + " no existe (1–2047)");
            if (!conectados(origen, destino)) throw new Rechazo("conexion", "no hay conexión entre " + origen + " y " + destino + ": no es padre/hijo, hermano, cruzada declarada ni aprendida");
            Modulo m = modulos.get(destino);
            if (m == null || Modulo.PLANIFICADO.equals(m.estado)) throw new Rechazo("planificado", "«" + arbol.nombre(destino) + "» está PLANIFICADO: todavía no tiene implementación");
            if (Modulo.CONECTADO.equals(m.estado)) throw new Rechazo("conectado", "«" + arbol.nombre(destino) + "» existe en JARVIS (" + m.implementacion + ") pero todavía no se invoca a través de la puerta");
            activar(destino);
            if (Modulo.PROHIBIDO.equals(m.permiso)) throw new Rechazo("permiso", "«" + arbol.nombre(destino) + "» está prohibido");
            if (Modulo.CONFIRMAR.equals(m.permiso) && !t.autorizados.contains(destino)) throw new Rechazo("permiso", "«" + arbol.nombre(destino) + "» necesita confirmación humana");
            if (m.abiertoHasta > t0) throw new Rechazo("circuito", "«" + arbol.nombre(destino) + "» quedó en pausa tras " + FALLOS_CORTE + " fallos seguidos; se reintenta en " + (m.abiertoHasta - t0) / 1000 + " s");
            if (t.pila.contains(destino)) throw new Rechazo("ciclo", "ciclo detectado: «" + arbol.nombre(destino) + "» ya está en la cadena de llamadas");
            if (t.pila.size() >= MAX_SALTOS) throw new Rechazo("limite", "límite de " + MAX_SALTOS + " saltos encadenados");
            if (t.mensajes > MAX_MENSAJES) throw new Rechazo("limite", "límite de " + MAX_MENSAJES + " mensajes por pedido");
            if (t0 - t.inicio > MAX_MS_TRAZA) throw new Rechazo("limite", "límite de tiempo del pedido (" + MAX_MS_TRAZA / 1000 + " s)");
            String err = contrato(m.entrada, datos);
            if (err != null) throw new Rechazo("contrato", "datos de entrada inválidos para «" + arbol.nombre(destino) + "»: " + err);
            t.pila.push(destino);
            Map<String, Object> out;
            try {
                out = m.motor.ejecutar(datos);
            } catch (Rechazo r) {
                throw r;
            } catch (Exception e) {
                fallo(m, t0, e.getMessage());
                throw new Rechazo("error", "«" + arbol.nombre(destino) + "» falló: " + e.getMessage());
            } finally { t.pila.pop(); }
            String errOut = contrato(m.salida, out);
            if (errOut != null) { fallo(m, t0, "salida inválida: " + errOut); throw new Rechazo("contrato", "«" + arbol.nombre(destino) + "» devolvió una salida que no cumple su contrato: " + errOut); }
            m.llamadas++; m.fallosSeguidos = 0; m.ultimoMs = reloj.ahora() - t0; m.ultimoError = null;
            m.registrar(Json.map("t", t0, "traza", t.id, "salida", resumen(out)));
            if (origen != COORDINADOR) aprender(origen, destino);
            if (!t.visitados.isEmpty()) aprender(t.visitados.get(t.visitados.size() - 1), destino);
            t.visitados.add(destino);
            t.pizarra.put(String.valueOf(destino), out);
            anotar(t, origen, destino, "ok", null, t0);
            return out;
        } catch (Rechazo r) {
            if (!"error".equals(r.motivo)) rechazados++; else errores++;
            anotar(t, origen, destino, r.motivo, r.getMessage(), t0);
            throw r;
        }
    }

    void fallo(Modulo m, long t0, String msg) {
        m.llamadas++; m.fallos++; m.fallosSeguidos++; m.ultimoError = msg;
        if (m.fallosSeguidos >= FALLOS_CORTE) m.abiertoHasta = t0 + ENFRIAMIENTO_MS;  // corta el módulo; vuelve a probarse después
    }
    /** Las conexiones que se usan juntas con éxito se refuerzan; a partir de 3 usos quedan habilitadas. */
    void aprender(int a, int b) { if (a == b || conectadosFijos(a, b)) return; long k = par(a, b); Integer w = uso.get(k); uso.put(k, w == null ? 1 : w + 1); }
    boolean conectadosFijos(int a, int b) { return Arbol.padre(a) == b || Arbol.padre(b) == a || Arbol.hermano(a) == b || cruzadas.contains(par(a, b)); }

    void activar(int id) {
        activos.put(id, reloj.ahora());
        Modulo m = modulos.get(id);
        if (m != null && m.activadoEn == 0) m.activadoEn = reloj.ahora();
        while (activos.size() > MAX_ACTIVOS) {  // libera el menos usado (sus resultados se descartan; la declaración queda)
            Integer viejo = activos.keySet().iterator().next();
            activos.remove(viejo);
            Modulo v = modulos.get(viejo);
            if (v != null) { v.resultados.clear(); v.activadoEn = 0; }
        }
    }

    static String contrato(Map<String, String> c, Map<String, Object> d) {
        if (d == null) return "sin datos";
        for (Map.Entry<String, String> e : c.entrySet()) {
            String k = e.getKey(); boolean opt = k.endsWith("?");
            if (opt) k = k.substring(0, k.length() - 1);
            Object v = d.get(k);
            if (v == null) { if (opt) continue; return "falta «" + k + "»"; }
            String tipo = e.getValue();
            boolean ok = "texto".equals(tipo) ? v instanceof String : "numero".equals(tipo) ? v instanceof Number : "entero".equals(tipo) ? v instanceof Number || v instanceof String && ((String) v).trim().matches("-?\\d{1,400}") : "lista".equals(tipo) ? v instanceof List
                    : "mapa".equals(tipo) ? v instanceof Map : "bool".equals(tipo) ? v instanceof Boolean : true;
            if (!ok) return "«" + k + "» debería ser " + tipo;
        }
        return null;
    }
    static Object resumen(Map<String, Object> out) { String s = Json.write(out); return s.length() > 300 ? s.substring(0, 300) + "…" : s; }

    void anotar(Traza t, int origen, int destino, String res, String detalle, long t0) {
        Map<String, Object> e = Json.map("t", t0, "traza", t.id, "de", origen, "a", destino, "resultado", res, "ms", reloj.ahora() - t0);
        if (detalle != null) e.put("detalle", detalle);
        registro.addLast(e);
        while (registro.size() > 300) registro.removeFirst();
        if (archivo != null) try {
            if (archivo.length() > 512 * 1024) { File old = new File(archivo.getPath() + ".1"); old.delete(); archivo.renameTo(old); }
            archivo.getParentFile().mkdirs();
            FileOutputStream f = new FileOutputStream(archivo, true);
            try { f.write((Json.write(e) + "\n").getBytes("UTF-8")); } finally { f.close(); }
        } catch (Exception ignored) {}
    }

    public List<Map<String, Object>> registro(int n) { List<Map<String, Object>> l = new ArrayList<Map<String, Object>>(registro); return l.subList(Math.max(0, l.size() - n), l.size()); }
    public Collection<Integer> activos() { return new ArrayList<Integer>(activos.keySet()); }
    public int pesoAprendido(int a, int b) { Integer w = uso.get(par(a, b)); return w == null ? 0 : w; }
    public Map<String, Object> estado() {
        int aprendidas = 0; for (int w : uso.values()) if (w >= 3) aprendidas++;
        return Json.map("trazas", trazas, "mensajes", enviados, "rechazados", rechazados, "errores", errores, "activos", activos.size(),
                "max_activos", MAX_ACTIVOS, "cruzadas", cruzadas.size(), "aprendidas", aprendidas, "pausa", pausa != null && pausa.activa());
    }
}
