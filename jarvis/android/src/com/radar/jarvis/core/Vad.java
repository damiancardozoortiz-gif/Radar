package com.radar.jarvis.core;

/**
 * Detección de voz y del FINAL DE FRASE adaptada al ruido ambiente (sustituye el umbral fijo 2200).
 * - Calibra el ruido de fondo en los primeros 300 ms y lo sigue actualizando mientras usted no habla.
 * - Umbral = ruido × 2 + margen (mín. 900, máx. 9000): detecta voz baja en silencio y no se queda «escuchando
 *   para siempre» en un sitio ruidoso.
 * - La voz sube y baja con las sílabas; un ruido parejo (ventilador, motor) no. Un sonido fuerte y parejo de más de
 *   0,6 s se toma como ruido nuevo y el umbral se ajusta a él.
 * - Fin de frase: silencio continuo de silenceMs tras al menos minSpeechMs de voz. Ruidos cortos (tos, golpe) no cuentan.
 * Se alimenta con la amplitud de cada trama (MediaRecorder.getMaxAmplitude cada 50 ms o AudioRecord).
 */
public final class Vad {
    public enum State { CALIBRANDO, ESPERANDO, HABLANDO, FIN, SIN_VOZ, MAXIMO }

    public int frameMs = 50, calibrateMs = 300, silenceMs = 800, minSpeechMs = 400, noSpeechMs = 8000, maxMs = 15000;
    private double noise = -1;
    private long elapsed, voiceMs, silentRun, calSum, calN, speechRun;
    private boolean speaking;
    private final int[] ring = new int[8];
    private int ringN;
    private State state = State.CALIBRANDO;

    public State state() { return state; }
    public int threshold() { return (int) Math.max(900, Math.min(9000, (noise < 0 ? 300 : noise) * 2.0 + 400)); }
    public long voiceMs() { return voiceMs; }
    public double noise() { return noise; }

    /** Una trama nueva. Devuelve el estado tras procesarla. */
    public State feed(int amplitude) {
        if (state == State.FIN || state == State.SIN_VOZ || state == State.MAXIMO) return state;
        elapsed += frameMs;
        if (elapsed <= calibrateMs) {  // calibración: el arranque de la grabación es ruido de fondo
            calSum += amplitude; calN++;
            noise = calSum / (double) calN;
            if (amplitude <= threshold()) return state = State.CALIBRANDO;
        }
        ring[ringN++ % ring.length] = amplitude;
        boolean loud = amplitude > threshold();
        if (loud) {
            speechRun += frameMs;
            silentRun = 0;
            if (!speaking && speechRun >= 150 && modulated()) speaking = true;  // 3 tramas seguidas y con sílabas: es voz
            if (!speaking && speechRun >= 600) { noise = average(); speechRun = 0; }  // fuerte y parejo: ruido nuevo
            if (speaking) voiceMs += frameMs;
        } else {
            speechRun = 0;
            silentRun += frameMs;
            if (!speaking) noise = noise < 0 ? amplitude : noise * 0.95 + amplitude * 0.05;  // sigue el ruido mientras no habla
        }
        if (elapsed >= maxMs) return state = State.MAXIMO;
        if (speaking && voiceMs >= minSpeechMs && silentRun >= silenceMs) return state = State.FIN;
        if (!speaking && elapsed >= noSpeechMs) return state = State.SIN_VOZ;
        return state = speaking ? State.HABLANDO : State.ESPERANDO;
    }

    /** La voz varía mucho de una trama a otra; el ruido parejo, poco. (máx − mín) / media de las últimas tramas. */
    private boolean modulated() {
        int n = Math.min(ringN, ring.length), k = (int) Math.min(n, Math.min(6, speechRun / frameMs));  // solo el tramo fuerte actual
        if (k < 3) return false;
        int max = 0, min = Integer.MAX_VALUE; long sum = 0;
        for (int i = 0; i < k; i++) { int v = ring[(ringN - 1 - i + ring.length * 4) % ring.length]; max = Math.max(max, v); min = Math.min(min, v); sum += v; }
        double mean = sum / (double) k;
        return mean > 0 && (max - min) / mean > 0.25;
    }

    private double average() {
        int n = Math.min(ringN, ring.length); long sum = 0;
        for (int i = 0; i < n; i++) sum += ring[i];
        return n == 0 ? 0 : sum / (double) n;
    }

    /** ¿Se oyó una frase válida? */
    public boolean heard() { return speaking && voiceMs >= minSpeechMs; }
}
