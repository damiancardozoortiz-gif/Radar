package com.radar.jarvis.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Voz en STREAMING: lee audio PCM (16 bits, little-endian, mono) a medida que llega de ElevenLabs y lo entrega al
 * reproductor trozo a trozo. La primera palabra suena en cuanto llega el primer trozo, sin esperar al audio completo.
 * También calcula el volumen real de cada trozo (para el holograma) sin depender del Visualizer de Android.
 */
public final class PcmStream {
    private PcmStream() {}

    public interface Sink {
        /** Reproduce (o guarda) estos bytes. Puede bloquear hasta que el reproductor tenga sitio. */
        void write(byte[] b, int off, int len) throws IOException;
        /** true si hay que cortar (usted habló, PAUSA…). */
        boolean stopped();
        /** Volumen del trozo (0..1) y milisegundos desde el inicio. */
        void level(float level, long firstByteMs);
    }

    /** Bombea el audio. Devuelve {bytes, trozos, primer_byte_ms, cortado}. nowMs0: hora de la petición. */
    public static Map<String, Object> pump(InputStream in, Sink sink, long t0Ms) throws IOException {
        byte[] buf = new byte[4096];
        int carry = -1;  // byte suelto: las muestras son de 2 bytes y un trozo puede partir una muestra
        long bytes = 0, chunks = 0, first = -1;
        boolean cut = false;
        byte[] out = new byte[buf.length + 1];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (sink.stopped()) { cut = true; break; }
            if (first < 0) first = System.currentTimeMillis() - t0Ms;
            int len = 0;
            if (carry >= 0) out[len++] = (byte) carry;
            System.arraycopy(buf, 0, out, len, n);
            len += n;
            carry = (len % 2 == 1) ? out[--len] & 0xFF : -1;
            if (len > 0) {
                sink.level(level(out, 0, len), first);
                sink.write(out, 0, len);
                bytes += len; chunks++;
            }
        }
        in.close();
        return Json.map("bytes", bytes, "trozos", chunks, "primer_byte_ms", first, "cortado", cut);
    }

    /** Volumen RMS de PCM 16 bits little-endian, normalizado (0..1, con algo de ganancia para que se vea). */
    public static float level(byte[] b, int off, int len) {
        long n = 0; double sum = 0;
        for (int i = off; i + 1 < off + len; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            sum += (double) s * s; n++;
        }
        if (n == 0) return 0;
        return (float) Math.min(1.0, Math.sqrt(sum / n) / 32768.0 * 4.0);
    }
}
