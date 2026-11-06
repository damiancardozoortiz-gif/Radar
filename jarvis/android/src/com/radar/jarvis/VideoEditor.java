package com.radar.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.radar.jarvis.core.Brain;
import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.video.ShortPlan;
import com.radar.jarvis.core.video.Subtitles;
import com.radar.jarvis.core.video.Timeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EDITOR DE VÍDEO («Creador»): importar, ver, línea de tiempo con miniaturas, dividir, recortar, borrar, mover, unir,
 * deshacer, subtítulos .srt con la transcripción REAL, Short con IA (plan validado + vista previa + su aprobación) y
 * exportar MP4 sin recodificar. El proyecto se guarda (files/video/proyecto.json) y sobrevive al cerrar la app.
 * Lo que necesita recodificar (9:16, resolución, texto o música grabados, transiciones) no está: se dice al pedirlo.
 */
public class VideoEditor {
    public static final int REQ_IMPORT = 41, REQ_SAVE_VIDEO = 42, REQ_SAVE_SRT = 43;

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final VideoView player;
    private final TimelineView tv;
    private final TextView info, out;
    private Timeline tl = new Timeline();
    private final Map<String, Uri> sources = new LinkedHashMap<String, Uri>();
    private final Map<String, VideoTools.Info> infos = new HashMap<String, VideoTools.Info>();
    private final Map<String, List<Subtitles.Word>> words = new HashMap<String, List<Subtitles.Word>>();
    private long cursor;
    private boolean busy, previewing;
    private File lastExport;
    private String lastSrt;
    private int previewClip;

    public VideoEditor(Activity a) {
        act = a;
        root = Ui.col(a);
        info = Ui.text(a, "Importe un vídeo para empezar.", 12, Ui.DIM);
        root.addView(info);
        LinearLayout r1 = Ui.row(a);
        r1.addView(btn("Importar", true, new View.OnClickListener() { public void onClick(View v) { pick(); } }));
        r1.addView(btn("Short IA", true, new View.OnClickListener() { public void onClick(View v) { askShort(); } }));
        r1.addView(btn("Subtítulos", false, new View.OnClickListener() { public void onClick(View v) { subtitles(); } }));
        r1.addView(btn("Exportar", true, new View.OnClickListener() { public void onClick(View v) { confirmExport(); } }));
        root.addView(r1, Ui.margins(a, 0, 6, 0, 0));
        player = new VideoView(a);
        player.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (player.isPlaying()) player.pause(); else player.start(); } });
        root.addView(player, new LinearLayout.LayoutParams(-1, Ui.dp(a, 190)));
        tv = new TimelineView(a);
        root.addView(tv, new LinearLayout.LayoutParams(-1, Ui.dp(a, 64)));
        LinearLayout r2 = Ui.row(a);
        r2.addView(btn("−1 s", false, new View.OnClickListener() { public void onClick(View v) { setCursor(cursor - 1000); } }));
        r2.addView(btn("+1 s", false, new View.OnClickListener() { public void onClick(View v) { setCursor(cursor + 1000); } }));
        r2.addView(btn("Dividir", false, new View.OnClickListener() { public void onClick(View v) { edit(tl.split(cursor), "dividido"); } }));
        r2.addView(btn("Quitar antes", false, new View.OnClickListener() { public void onClick(View v) { edit(tl.trimAt(cursor, true), "recortado el comienzo del clip"); } }));
        root.addView(r2, Ui.margins(a, 0, 4, 0, 0));
        LinearLayout r3 = Ui.row(a);
        r3.addView(btn("Quitar después", false, new View.OnClickListener() { public void onClick(View v) { edit(tl.trimAt(cursor, false), "recortado el final del clip"); } }));
        r3.addView(btn("Borrar clip", false, new View.OnClickListener() { public void onClick(View v) { long[] p = tl.locate(cursor); if (p != null) { tl.remove((int) p[0]); edit(true, "clip borrado"); } } }));
        r3.addView(btn("Deshacer", false, new View.OnClickListener() { public void onClick(View v) { edit(tl.undo(), "deshecho"); } }));
        r3.addView(btn("▶ Montaje", false, new View.OnClickListener() { public void onClick(View v) { preview(); } }));
        root.addView(r3, Ui.margins(a, 0, 4, 0, 0));
        out = Ui.text(a, "Sin recodificar: cortes al fotograma clave anterior, y para unir, vídeos del mismo formato.", 11, Ui.DIM);
        out.setTextIsSelectable(true);
        root.addView(out, Ui.margins(a, 0, 6, 0, 0));
        load();
    }

    public View view() { return root; }
    public boolean hasVideo() { return !sources.isEmpty() && tl.size() > 0; }

    private TextView btn(String s, boolean accent, View.OnClickListener l) {
        TextView b = Ui.button(act, s, accent ? Ui.ACCENT : Ui.TEXT, l);
        b.setTextSize(11);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.rightMargin = Ui.dp(act, 4);
        b.setLayoutParams(p);
        return b;
    }

    // ================================================================== importar / proyecto
    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        act.startActivityForResult(i, REQ_IMPORT);
    }

    /** La pantalla principal le pasa los resultados de los selectores. */
    public boolean onResult(int req, int res, Intent data) {
        if (req != REQ_IMPORT && req != REQ_SAVE_VIDEO && req != REQ_SAVE_SRT) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            try { act.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            addSource(uri);
        } else if (req == REQ_SAVE_VIDEO && lastExport != null) copyTo(lastExport, uri, "vídeo guardado");
        else if (req == REQ_SAVE_SRT && lastSrt != null) {
            try { OutputStream o = act.getContentResolver().openOutputStream(uri); o.write(lastSrt.getBytes("UTF-8")); o.close(); say("Subtítulos guardados."); }
            catch (Exception e) { say("No pude guardar los subtítulos: " + e.getMessage()); }
        }
        return true;
    }

    private void addSource(final Uri uri) {
        work("Analizando el vídeo…", new Runnable() { public void run() {
            try {
                VideoTools.Info in = VideoTools.probe(act, uri);
                final String id = "v" + (sources.size() + 1);
                sources.put(id, uri); infos.put(id, in);
                tl.add(id, 0, in.durationMs);
                save();
                ui(new Runnable() { public void run() {
                    if (sources.size() == 1) player.setVideoURI(uri);
                    say(id + ": " + infos.get(id).describe() + " · " + infos.get(id).keyframes.length + " fotogramas clave"
                            + (sources.size() > 1 ? "\nUnido al final del montaje." : ""));
                    refresh();
                }});
            } catch (final Exception e) { ui(new Runnable() { public void run() { say("No pude abrir el vídeo: " + e.getMessage()); } }); }
        }});
    }

    private File projectFile() { File d = new File(act.getFilesDir(), "video"); d.mkdirs(); return new File(d, "proyecto.json"); }

    private void save() {
        Map<String, Object> src = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Uri> e : sources.entrySet()) src.put(e.getKey(), e.getValue().toString());
        Map<String, Object> w = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<Subtitles.Word>> e : words.entrySet()) {
            List<Object> l = new ArrayList<Object>();
            for (Subtitles.Word x : e.getValue()) l.add(Json.list(x.text, x.start, x.end));
            w.put(e.getKey(), l);
        }
        try { FileOutputStream o = new FileOutputStream(projectFile()); o.write(Json.write(Json.map("fuentes", src, "montaje", tl.toList(), "palabras", w)).getBytes("UTF-8")); o.close(); }
        catch (Exception ignored) {}
    }

    private void load() {
        final String s = readQuiet(projectFile());
        if (s == null) return;
        work("Abriendo el proyecto guardado…", new Runnable() { public void run() {
            try {
                Map<String, Object> m = Json.obj(Json.parse(s));
                Map<String, Object> src = Json.obj(m.get("fuentes"));
                for (Map.Entry<String, Object> e : src.entrySet()) {
                    Uri u = Uri.parse(String.valueOf(e.getValue()));
                    try { infos.put(e.getKey(), VideoTools.probe(act, u)); sources.put(e.getKey(), u); } catch (Exception ignored) {}
                }
                Map<String, Object> w = Json.obj(m.get("palabras"));
                if (w != null) for (Map.Entry<String, Object> e : w.entrySet()) {
                    List<Subtitles.Word> l = new ArrayList<Subtitles.Word>();
                    for (Object o : Json.arr(e.getValue())) { List<Object> x = Json.arr(o); l.add(new Subtitles.Word(String.valueOf(x.get(0)), ((Number) x.get(1)).longValue(), ((Number) x.get(2)).longValue())); }
                    words.put(e.getKey(), l);
                }
                final Timeline t = Timeline.fromList(Json.arr(m.get("montaje")));
                ui(new Runnable() { public void run() {
                    for (Timeline.Clip c : t.clips()) if (!sources.containsKey(c.source)) { say("Un vídeo del proyecto ya no está disponible: empiece de nuevo con «Importar»."); return; }
                    tl = t;
                    if (!sources.isEmpty()) player.setVideoURI(sources.values().iterator().next());
                    say("Proyecto recuperado: " + tl.size() + " clips, " + secs(tl.duration()) + ".");
                    refresh();
                }});
            } catch (Exception ignored) {}
        }});
    }

    // ================================================================== edición
    private void edit(boolean ok, String what) {
        if (!ok) { say("No se puede ahí (clips de al menos 0,2 s)."); return; }
        save(); refresh(); say(what + " · " + tl.size() + " clips · " + secs(tl.duration()));
    }

    private void setCursor(long t) {
        cursor = Math.max(0, Math.min(tl.duration(), t));
        long[] p = tl.locate(cursor);
        if (p != null) {
            Timeline.Clip c = tl.clips().get((int) p[0]);
            Uri u = sources.get(c.source);
            if (u != null) { if (!u.equals(currentUri)) { player.setVideoURI(u); currentUri = u; } player.seekTo((int) p[1]); }
        }
        tv.invalidate();
        info.setText("Cursor " + secs(cursor) + " de " + secs(tl.duration()) + " · " + tl.size() + " clips");
    }
    private Uri currentUri;

    private void refresh() { tv.thumbs.clear(); tv.loadThumbs(); setCursor(Math.min(cursor, tl.duration())); }

    /** Reproduce el montaje: salta de clip en clip. */
    private void preview() {
        if (tl.size() == 0) return;
        previewing = true; previewClip = 0;
        playClip();
    }

    private void playClip() {
        if (!previewing || previewClip >= tl.size()) { previewing = false; player.pause(); say("Fin de la vista previa (" + secs(tl.duration()) + ")."); return; }
        final Timeline.Clip c = tl.clips().get(previewClip);
        Uri u = sources.get(c.source);
        if (!u.equals(currentUri)) { player.setVideoURI(u); currentUri = u; }
        player.seekTo((int) c.in);
        player.start();
        main.postDelayed(new Runnable() { public void run() {
            if (!previewing) return;
            if (player.getCurrentPosition() >= c.out || !player.isPlaying() && player.getCurrentPosition() > c.in + 300) { previewClip++; playClip(); }
            else main.postDelayed(this, 80);
        }}, 120);
    }

    // ================================================================== transcripción, subtítulos y Short con IA
    /** Transcribe (ElevenLabs Scribe, palabras con tiempos) las fuentes que falten. */
    private void ensureWords() throws Exception {
        Voice v = JarvisApp.voice(act);
        for (String id : sources.keySet()) {
            if (words.containsKey(id) || !infos.get(id).hasAudio) continue;
            if (!v.usesElevenLabs()) throw new Exception("para transcribir hace falta la clave de ElevenLabs");
            File a = VideoTools.extractAudio(act, sources.get(id), new File(act.getCacheDir(), "audio-" + id + ".m4a"));
            words.put(id, Subtitles.fromScribe(v.transcribeFull(a, "audio.m4a")));
            a.delete();
        }
        save();
    }

    private void subtitles() {
        if (tl.size() == 0) { say("Primero importe un vídeo."); return; }
        work("Transcribiendo el audio (ElevenLabs)…", new Runnable() { public void run() {
            try {
                ensureWords();
                List<Subtitles.Word> all = new ArrayList<Subtitles.Word>();
                for (String id : sources.keySet()) if (words.containsKey(id)) all.addAll(Subtitles.remap(words.get(id), id, tl));
                java.util.Collections.sort(all, new java.util.Comparator<Subtitles.Word>() { public int compare(Subtitles.Word x, Subtitles.Word y) { return Long.compare(x.start, y.start); } });
                final List<Subtitles.Cue> cues = Subtitles.cues(all);
                lastSrt = Subtitles.srt(cues);
                ui(new Runnable() { public void run() {
                    if (cues.isEmpty()) { say("No encontré voz para subtitular."); return; }
                    say(cues.size() + " subtítulos sincronizados con el montaje. Se guardan como archivo .srt (los reproductores y las redes los cargan aparte; grabarlos en la imagen es la etapa B).");
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-subrip").putExtra(Intent.EXTRA_TITLE, "jarvis-subtitulos.srt");
                    act.startActivityForResult(i, REQ_SAVE_SRT);
                }});
            } catch (final Exception e) { ui(new Runnable() { public void run() { say("Subtítulos: " + e.getMessage()); } }); }
        }});
    }

    private void askShort() {
        if (tl.size() == 0 || sources.isEmpty()) { say("Primero importe un vídeo."); return; }
        final String[] opts = {"Short de 15 s", "Short de 30 s", "Short de 60 s"};
        new AlertDialog.Builder(act).setTitle("¿Qué duración?").setItems(opts, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { makeShort(new long[]{15000, 30000, 60000}[w], "convertí este vídeo en un " + opts[w]); }
        }).show();
    }

    /** También lo usa la orden de voz «convertí este vídeo en un Short de 30 segundos». */
    public void makeShort(final long targetMs, final String request) {
        final String id = sources.keySet().iterator().next();
        final VideoTools.Info in = infos.get(id);
        work("Preparando el Short (transcripción + plan)…", new Runnable() { public void run() {
            String note = "";
            try { ensureWords(); } catch (Exception e) { note = "Sin transcripción (" + e.getMessage() + "): "; }
            List<Subtitles.Word> w = words.containsKey(id) ? words.get(id) : new ArrayList<Subtitles.Word>();
            Brain b = JarvisApp.brainOrNull();
            ShortPlan.Result r;
            Map<String, Object> ai = null;
            if (b != null && !b.apiKey.isEmpty() && !w.isEmpty()) {
                ai = b.videoPlan(request, Subtitles.transcriptWithTimes(w, 12000), in.durationMs, targetMs);
                r = ai.get("segments") != null ? ShortPlan.fromAi(Json.arr(ai.get("segments")), id, in.durationMs, targetMs, w) : ShortPlan.heuristic(id, in.durationMs, targetMs, w);
            } else r = ShortPlan.heuristic(id, in.durationMs, targetMs, w);
            final ShortPlan.Result fr = r; final Map<String, Object> fai = ai; final String fn = note;
            ui(new Runnable() { public void run() {
                tl.replace(fr.timeline.clips());
                save(); refresh();
                StringBuilder s = new StringBuilder(fn + "Plan " + fr.source + ": " + tl.size() + " clips, " + secs(tl.duration()) + ".");
                if (fai != null && fai.get("title") != null) s.append("\nTítulo: ").append(fai.get("title")).append("\nDescripción: ").append(fai.get("description")).append("\nHashtags: ").append(fai.get("hashtags"));
                if (!fr.notes.isEmpty()) s.append("\nAjustes: ").append(fr.notes);
                s.append("\nVea la vista previa («▶ Montaje»). Nada se exporta sin su aprobación; «Deshacer» vuelve al vídeo completo.");
                say(s.toString());
                preview();
            }});
        }});
    }

    // ================================================================== exportar (con aprobación)
    private void confirmExport() {
        if (tl.size() == 0) { say("No hay nada que exportar."); return; }
        new AlertDialog.Builder(act).setTitle("¿Exportar el montaje?")
                .setMessage(tl.size() + " clips, " + secs(tl.duration()) + ". Sin recodificar: cada clip empieza en el fotograma clave anterior al corte.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Exportar", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { export(); } }).show();
    }

    private void export() {
        previewing = false;
        work("Exportando…", new Runnable() { public void run() {
            try {
                File dir = act.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dir == null) dir = new File(act.getFilesDir(), "video");
                dir.mkdirs();
                final File f = new File(dir, "jarvis-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4");
                final Map<String, Object> r = VideoTools.export(act, tl, sources, infos, f, new VideoTools.Progress() {
                    public void at(final float x) { ui(new Runnable() { public void run() { info.setText("Exportando… " + Math.round(x * 100) + " %"); } }); }
                    public boolean cancelled() { return false; }
                });
                lastExport = f;
                Brain b = JarvisApp.brainOrNull(); if (b != null) b.log("video", "exportado " + f.getName() + " (" + r.get("bytes") + " bytes)");
                ui(new Runnable() { public void run() {
                    say("Exportado: " + f.getName() + " · " + secs(((Number) r.get("duracion_ms")).longValue()) + " · " + (((Number) r.get("bytes")).longValue() / 1024) + " KB"
                            + (((Number) r.get("adelanto_por_claves_ms")).longValue() > 0 ? " · los cortes empezaron " + r.get("adelanto_por_claves_ms") + " ms antes por los fotogramas clave" : "")
                            + "\nElija dónde guardarlo.");
                    act.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/mp4").putExtra(Intent.EXTRA_TITLE, f.getName()), REQ_SAVE_VIDEO);
                }});
            } catch (final Exception e) { ui(new Runnable() { public void run() { say("No se exportó: " + e.getMessage()); } }); }
        }});
    }

    private void copyTo(final File f, final Uri uri, final String done) {
        work("Guardando…", new Runnable() { public void run() {
            try {
                InputStream in = new FileInputStream(f); OutputStream o = act.getContentResolver().openOutputStream(uri);
                byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                in.close(); o.close();
                ui(new Runnable() { public void run() { say(done + "."); } });
            } catch (final Exception e) { ui(new Runnable() { public void run() { say("No pude guardar: " + e.getMessage()); } }); }
        }});
    }

    // ================================================================== utilidades
    private void work(final String msg, final Runnable r) {
        if (busy) { say("Espere: estoy trabajando."); return; }
        busy = true; info.setText(msg);
        new Thread(new Runnable() { public void run() { try { r.run(); } finally { busy = false; } } }).start();
    }

    private void ui(Runnable r) { main.post(r); }
    private void say(String s) { out.setText(s); out.setTextColor(Ui.TEXT); }
    static String secs(long ms) { return String.format(Locale.US, "%.1f s", ms / 1000.0); }

    private static String readQuiet(File f) {
        if (!f.isFile()) return null;
        try { InputStream in = new FileInputStream(f); return JarvisApp.readAll(in); } catch (Exception e) { return null; }
    }

    // ================================================================== línea de tiempo con miniaturas
    class TimelineView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();
        final RectF r = new RectF();

        TimelineView(Context c) {
            super(c);
            setOnTouchListener(new OnTouchListener() { public boolean onTouch(View v, MotionEvent e) {
                if (tl.duration() > 0) setCursor((long) (e.getX() / Math.max(1, getWidth()) * tl.duration()));
                return true;
            }});
        }

        void loadThumbs() {
            final List<Timeline.Clip> cl = tl.clips();
            new Thread(new Runnable() { public void run() {
                for (Timeline.Clip c : cl) {
                    String k = c.source + "@" + c.in;
                    if (thumbs.containsKey(k) || sources.get(c.source) == null) continue;
                    Bitmap b = VideoTools.thumbnail(act, sources.get(c.source), c.in, 96);
                    if (b != null) { thumbs.put(k, b); postInvalidate(); }
                }
            }}).start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            c.drawColor(Ui.BG);
            long total = tl.duration();
            if (total <= 0) { p.setColor(Ui.DIM); p.setTextSize(11 * d); c.drawText("Sin clips", 8 * d, h / 2, p); return; }
            float x = 0;
            for (Timeline.Clip cl : tl.clips()) {
                float cw = w * cl.length() / total;
                r.set(x + d, 4 * d, x + cw - d, h - 4 * d);
                Bitmap b = thumbs.get(cl.source + "@" + cl.in);
                if (b != null) { c.save(); c.clipRect(r); c.drawBitmap(b, null, new Rect((int) r.left, (int) r.top, (int) (r.left + (r.height() * b.getWidth() / Math.max(1, b.getHeight()))), (int) r.bottom), p); c.restore(); }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * d); p.setColor(Ui.ACCENT); c.drawRoundRect(r, 4 * d, 4 * d, p);
                p.setStyle(Paint.Style.FILL);
                x += cw;
            }
            float cx = w * cursor / total;
            p.setColor(Ui.BAD); c.drawRect(cx - d, 0, cx + d, h, p);
        }
    }
}
