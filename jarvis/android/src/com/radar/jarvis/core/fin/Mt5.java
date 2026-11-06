package com.radar.jarvis.core.fin;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ejecutor MT5: lo que tiene que cumplir cualquier conexión con MetaTrader 5.
 * Implementaciones: NotConnected (sin conexión) y MetaApi (fase 1: solo lectura). Vías reales evaluadas (ver README):
 *  - MetaApi (servicio en la nube de terceros con API REST: la única vía sin ordenador ni servidor propio).
 *  - Expert Advisor MQL5 con WebRequest en un MT5 de escritorio encendido (VPS) + un servidor intermedio.
 * No se simula ninguna conexión: NotConnected responde "no conectado" y no ejecuta nada.
 */
public interface Mt5 {
    String name();
    boolean connected();
    /** false = el conector solo lee (no hay forma de enviar órdenes). */
    boolean canTrade();
    /** true solo si el servidor confirma que la cuenta es DEMO. */
    boolean isDemo() throws IOException;
    Map<String, Object> account() throws IOException;          // saldo, equidad, divisa
    List<Map<String, Object>> positions() throws IOException;
    List<Market.Candle> candles(String symbol, String tf, int limit) throws IOException;
    /** Ejecuta una orden ya validada por Risk y AUTORIZADA por el usuario. Devuelve el resultado del servidor. */
    Map<String, Object> execute(Risk.Order o) throws IOException;

    final class NotConnected implements Mt5 {
        public String name() { return "sin conexión"; }
        public boolean connected() { return false; }
        public boolean canTrade() { return false; }
        public boolean isDemo() { return false; }
        public Map<String, Object> account() throws IOException { throw new IOException("MT5 no está conectado"); }
        public List<Map<String, Object>> positions() { return Collections.emptyList(); }
        public List<Market.Candle> candles(String s, String tf, int n) throws IOException { throw new IOException("MT5 no está conectado: importe velas en CSV"); }
        public Map<String, Object> execute(Risk.Order o) throws IOException { throw new IOException("MT5 no está conectado: no se ejecuta nada"); }
    }
}
