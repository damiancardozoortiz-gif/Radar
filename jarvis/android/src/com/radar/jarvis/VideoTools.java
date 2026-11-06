package com.radar.jarvis;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import com.radar.jarvis.core.video.Timeline;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Herramientas de vídeo con las APIs de Android (sin librerías): analizar, miniaturas, extraer el audio y EXPORTAR el
 * montaje sin recodificar (MediaExtractor → MediaMuxer). Rápido y sin pérdida, con dos límites honestos:
 *  - cada clip empieza en el fotograma clave anterior al corte (se avisa de cuánto antes);
 *  - para unir, los vídeos deben tener el mismo formato (códec y tamaño). Si no, hace falta recodificar (etapa B).
 * Llamar fuera del hilo de la pantalla.
 */
public final class VideoTools {
    private VideoTools() {}

    public interface Progress { void at(float fraction); boolean cancelled(); }

    public static final class Info {
        public long durationMs; public int width, height, rotation; public String videoMime, audioMime;
        public int audioRate, audioChannels; public long[] keyframes = new long[0]; public boolean hasAudio;
        public String describe() {
            return String.format(java.util.Locale.US, "%.1f s · %dx%d%s · %s%s", durationMs / 1000.0, width, height, rotation != 0 ? " (rotado " + rotation + "°)" : "",
                    videoMime, hasAudio ? " + " + audioMime : " · sin audio");
        }
    }

    /** Analiza el vídeo: duración, tamaño, rotación, códecs y fotogramas clave (recorre solo las cabeceras de las muestras). */
    public static Info probe(Context c, Uri uri) throws IOException {
        Info in = new Info();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, uri, null);
            int vt = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m.startsWith("video/") && vt < 0) {
                    vt = i; in.videoMime = m; in.width = f.getInteger(MediaFormat.KEY_WIDTH); in.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) in.durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000;
                    if (f.containsKey("rotation-degrees")) in.rotation = f.getInteger("rotation-degrees");
                } else if (m.startsWith("audio/") && !in.hasAudio) {
                    in.hasAudio = true; in.audioMime = m;
                    in.audioRate = f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
                    in.audioChannels = f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
                }
            }
            if (vt < 0) throw new IOException("el archivo no tiene vídeo");
            ex.selectTrack(vt);
            List<Long> keys = new ArrayList<Long>();
            while (ex.getSampleTime() >= 0) {
                if ((ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) keys.add(ex.getSampleTime() / 1000);
                if (!ex.advance()) break;
                if (keys.size() > 20000) break;
            }
            in.keyframes = new long[keys.size()];
            for (int i = 0; i < keys.size(); i++) in.keyframes[i] = keys.get(i);
        } finally { ex.release(); }
        if (in.rotation == 0) {  // algunos teléfonos guardan la rotación solo en los metadatos
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try { r.setDataSource(c, uri); String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION); if (rot != null) in.rotation = Integer.parseInt(rot); }
            catch (Exception ignored) {} finally { r.release(); }
        }
        return in;
    }

    /** Miniatura del instante t (para la línea de tiempo), reducida al ancho pedido. */
    public static Bitmap thumbnail(Context c, Uri uri, long tMs, int width) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            Bitmap b = r.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            int h = Math.max(1, b.getHeight() * width / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, width, h, true);
            if (s != b) b.recycle();
            return s;
        } catch (Exception e) { return null; }
        finally { try { r.release(); } catch (Exception ignored) {} }
    }

    /** Extrae SOLO el audio (sin recodificar) a un .m4a, para transcribirlo con tiempos por palabra. */
    public static File extractAudio(Context c, Uri uri, File out) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mx = null;
        try {
            ex.setDataSource(c, uri, null);
            int at = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) { at = i; break; }
            if (at < 0) throw new IOException("el vídeo no tiene audio");
            ex.selectTrack(at);
            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int dst = mx.addTrack(ex.getTrackFormat(at));
            mx.start();
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                bi.set(0, n, ex.getSampleTime(), ex.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME);
                mx.writeSampleData(dst, buf, bi);
                ex.advance();
            }
            mx.stop();
            return out;
        } catch (IllegalStateException e) { throw new IOException("no pude extraer el audio: " + e.getMessage()); }
        finally { ex.release(); if (mx != null) try { mx.release(); } catch (Exception ignored) {} }
    }

    /**
     * Exporta el montaje a MP4 sin recodificar. sources: id de la fuente → Uri. Devuelve un resumen (duración real,
     * desplazamiento por fotogramas clave). Lanza IOException si los clips no son compatibles para unir.
     */
    public static Map<String, Object> export(Context c, Timeline tl, Map<String, Uri> sources, Map<String, Info> infos, File out, Progress p) throws IOException {
        if (tl.size() == 0) throw new IOException("la línea de tiempo está vacía");
        // compatibilidad: mismo códec y tamaño de vídeo; mismo audio (o ninguno)
        Info ref = null;
        for (Timeline.Clip cl : tl.clips()) {
            Info i = infos.get(cl.source);
            if (i == null) throw new IOException("falta la información de " + cl.source);
            if (ref == null) { ref = i; continue; }
            if (!i.videoMime.equals(ref.videoMime) || i.width != ref.width || i.height != ref.height || i.rotation != ref.rotation)
                throw new IOException("los vídeos tienen distinto formato (" + ref.describe() + " / " + i.describe() + "): unirlos requiere recodificar (etapa B)");
            if (i.hasAudio != ref.hasAudio || i.hasAudio && (!i.audioMime.equals(ref.audioMime) || i.audioRate != ref.audioRate || i.audioChannels != ref.audioChannels))
                throw new IOException("el audio de los vídeos es distinto: unirlos requiere recodificar (etapa B)");
        }
        MediaMuxer mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (ref.rotation != 0) mx.setOrientationHint(ref.rotation);
        long offsetUs = 0, shiftedMs = 0, total = tl.duration(), done = 0;
        ByteBuffer buf = ByteBuffer.allocate(4 << 20);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            // 1) todas las pistas se declaran ANTES de empezar (formato del primer clip; los demás ya se comprobaron iguales)
            int[] dst = {-1, -1};
            MediaExtractor fx = new MediaExtractor();
            try {
                fx.setDataSource(c, sources.get(tl.clips().get(0).source), null);
                for (int i = 0; i < fx.getTrackCount(); i++) {
                    MediaFormat f = fx.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m.startsWith("video/") && dst[0] < 0) dst[0] = mx.addTrack(f);
                    else if (m.startsWith("audio/") && dst[1] < 0) dst[1] = mx.addTrack(f);
                }
            } finally { fx.release(); }
            mx.start();
            // 2) cada clip: vídeo desde el fotograma clave anterior al corte; el audio usa el MISMO origen para no desincronizarse
            for (Timeline.Clip cl : tl.clips()) {
                Info info = infos.get(cl.source);
                long realIn = Timeline.snapToKeyframe(cl.in, info.keyframes);
                shiftedMs += cl.in - realIn;
                long baseUs = -1, clipEndUs = offsetUs;
                for (int kind = 0; kind < 2; kind++) {  // 0 vídeo, 1 audio
                    if (dst[kind] < 0) continue;
                    MediaExtractor ex = new MediaExtractor();
                    try {
                        ex.setDataSource(c, sources.get(cl.source), null);
                        int src = -1;
                        for (int i = 0; i < ex.getTrackCount(); i++) {
                            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                            if (kind == 0 ? m.startsWith("video/") : m.startsWith("audio/")) { src = i; break; }
                        }
                        if (src < 0) continue;
                        ex.selectTrack(src);
                        ex.seekTo(realIn * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        if (kind == 0) baseUs = ex.getSampleTime();
                        if (baseUs < 0) break;
                        while (true) {
                            if (p != null && p.cancelled()) throw new IOException("cancelado");
                            long t = ex.getSampleTime();
                            if (t < 0 || t >= cl.out * 1000) break;
                            if (t >= baseUs) {
                                int n = ex.readSampleData(buf, 0);
                                if (n < 0) break;
                                long outT = offsetUs + (t - baseUs);
                                bi.set(0, n, outT, (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                                mx.writeSampleData(dst[kind], buf, bi);
                                if (outT > clipEndUs) clipEndUs = outT;
                                if (kind == 0 && p != null) p.at(Math.min(1f, (done + (t - baseUs) / 1000f) / Math.max(1, total)));
                            }
                            if (!ex.advance()) break;
                        }
                    } finally { ex.release(); }
                }
                done += cl.length();
                offsetUs = clipEndUs + 33000;  // un fotograma (~30 fps) después de lo último escrito
            }
            mx.stop();
        } catch (IllegalStateException e) {
            throw new IOException("el exportador falló: " + e.getMessage());
        } finally { try { mx.release(); } catch (Exception ignored) {} }
        if (p != null) p.at(1f);
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("archivo", out.getAbsolutePath());
        r.put("bytes", out.length());
        r.put("duracion_ms", offsetUs / 1000);
        r.put("adelanto_por_claves_ms", shiftedMs);
        return r;
    }
}
