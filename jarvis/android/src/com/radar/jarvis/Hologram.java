package com.radar.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * Holograma de JARVIS (NEURAL CORE): figura humanoide holográfica dentro de un anillo, sobre una plataforma de anillos,
 * con los 10 nodos del Neural Core alrededor y paneles de estado. Todo dibujado en la app (sin imágenes ni librerías).
 *
 * TODO lo que muestra es real: los nodos usan el estado de NeuralCore; los paneles, el estado de Claude, la voz, la
 * memoria, la evolución y los tiempos MEDIDOS. Si algo no está conectado, se ve desconectado. Si no hay medición, «sin medir».
 * Estados: «en espera» (respira), «escuchando» (ondas con su voz), «pensando» (escaneo), «hablando» (el núcleo y el
 * brillo siguen el volumen real del audio), «en pausa» (rojo). Tocar la figura oculta o muestra los paneles.
 */
public class Hologram extends View {
    public volatile String state = "en espera";
    public volatile float level = 0;       // micrófono (0..1)
    public volatile float voiceLevel = 0;  // salida de voz (0..1)
    public volatile String alert;          // texto de alerta o null
    public Voice voice;                    // para saber si habla y con qué volumen

    /** Datos reales para los paneles (los pone MainActivity). */
    public static final class Hud {
        public String[] status = new String[0];   // líneas «Claude: conectado»…
        public boolean[] statusOk = new boolean[0];
        public String perf = "sin medir", next = "";
        public String[] nodes = new String[0];    // nombre de cada nodo
        public String[] nodeState = new String[0]; // inactivo | procesando | listo | error | desconectado
    }
    public volatile Hud hud = new Hud();
    public volatile boolean hudVisible = true;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = System.currentTimeMillis();
    private final float[] pa = new float[36], pr = new float[36], ps = new float[36];
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private float smooth, hudAlpha = 1f;
    private Shader bg;
    private int bgH;

    public Hologram(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        Random r = new Random(7);
        for (int i = 0; i < pa.length; i++) { pa[i] = (float) (r.nextFloat() * Math.PI * 2); pr[i] = 0.9f + r.nextFloat() * 0.6f; ps[i] = 0.1f + r.nextFloat() * 0.4f; }
        setOnTouchListener(new OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) { hudVisible = !hudVisible; v.performClick(); }
                return true;
            }
        });
    }

    private int color() {
        if (alert != null || "en pausa".equals(state)) return Ui.BAD;
        if ("pensando".equals(state)) return Color.rgb(120, 190, 255);
        return Ui.ACCENT;
    }

    private static int alpha(int c, float a) { return (Math.max(0, Math.min(255, (int) (a * 255))) << 24) | (c & 0xFFFFFF); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float sec = (System.currentTimeMillis() - t0) / 1000f;
        if (voice != null && voice.isSpeaking() && !"en pausa".equals(state)) { state = "hablando"; voiceLevel = voice.outLevel; }
        boolean paused = "en pausa".equals(state), speaking = "hablando".equals(state), listening = "escuchando".equals(state), thinking = "pensando".equals(state);
        int col = color();
        float target = speaking ? (voiceLevel > 0.02f ? voiceLevel : 0.25f + 0.2f * (float) Math.abs(Math.sin(sec * 8))) : listening ? level * 0.6f : 0;
        smooth += (target - smooth) * 0.3f;
        float breathe = paused ? 0 : (float) Math.sin(sec * 1.3);
        float speed = paused ? 0.05f : thinking ? 1.8f : listening ? 0.8f : speaking ? 1.1f : 0.3f;

        // ---- fondo: degradado y suelo en perspectiva
        if (bg == null || bgH != (int) h) { bg = new LinearGradient(0, 0, 0, h, Color.rgb(3, 8, 14), Color.rgb(2, 14, 22), Shader.TileMode.CLAMP); bgH = (int) h; }
        fill.setShader(bg); c.drawRect(0, 0, w, h, fill); fill.setShader(null);
        float cx = w * 0.5f, floorY = h * 0.80f, cy = h * 0.44f;
        float R = Math.min(w * 0.42f, h * 0.27f);
        stroke.setStrokeWidth(d);
        for (int i = 0; i < 7; i++) {  // líneas del suelo
            float y = floorY + (h - floorY) * (i / 7f) * (i / 7f);
            stroke.setColor(alpha(col, 0.07f + 0.02f * i)); c.drawLine(0, y, w, y, stroke);
        }
        for (int i = -6; i <= 6; i++) { stroke.setColor(alpha(col, 0.06f)); c.drawLine(cx + i * w * 0.02f, floorY, cx + i * w * 0.16f, h, stroke); }

        // ---- plataforma de anillos bajo los pies
        for (int i = 0; i < 4; i++) {
            float rx = R * (0.55f + i * 0.28f), ry = rx * 0.22f;
            rect.set(cx - rx, floorY - ry, cx + rx, floorY + ry);
            stroke.setColor(alpha(col, (paused ? 0.2f : 0.55f - i * 0.1f) + smooth * 0.3f));
            stroke.setStrokeWidth(d * (i == 0 ? 3f : 1.5f));
            if (i % 2 == 0) c.drawOval(rect, stroke);
            else { float rot = sec * speed * 30 * (i == 1 ? 1 : -1); for (int k = 0; k < 12; k++) c.drawArc(rect, rot + k * 30, 18, false, stroke); }
        }
        fill.setShader(new RadialGradient(cx, floorY, R * 0.7f, alpha(col, paused ? 0.15f : 0.35f + smooth * 0.4f), alpha(col, 0), Shader.TileMode.CLAMP));
        rect.set(cx - R * 0.8f, floorY - R * 0.2f, cx + R * 0.8f, floorY + R * 0.2f); c.drawOval(rect, fill); fill.setShader(null);

        // ---- anillo detrás de la figura (segmentos que giran) + halo
        fill.setShader(new RadialGradient(cx, cy, R * 1.25f, alpha(col, paused ? 0.08f : 0.18f + smooth * 0.2f), alpha(col, 0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 1.25f, fill); fill.setShader(null);
        float[][] rings = {{1.0f, 48, 0.7f, 0.6f, 1.2f}, {0.92f, 12, 0.45f, -1.1f, 3f}, {0.84f, 90, 0.5f, 0.35f, 0.8f}};
        for (float[] rg : rings) {
            float r = R * rg[0] * (1 + 0.01f * breathe);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            stroke.setStrokeWidth(d * rg[4]); stroke.setColor(alpha(col, paused ? 0.25f : 0.6f));
            float step = 360f / rg[1], rot = sec * speed * 25 * rg[3];
            for (int k = 0; k < rg[1]; k++) if (k % 9 != 0) c.drawArc(rect, rot + k * step, step * rg[2], false, stroke);
        }
        if (listening) for (int k = 0; k < 3; k++) {
            float ph = (sec * 0.8f + k / 3f) % 1f;
            stroke.setColor(alpha(col, (1 - ph) * (0.25f + level))); stroke.setStrokeWidth(d * 1.5f);
            c.drawCircle(cx, cy, R * (0.9f + ph * 0.6f), stroke);
        }

        // ---- figura humanoide
        drawFigure(c, cx, cy, R, d, col, paused, thinking, sec, breathe);

        // ---- partículas
        for (int i = 0; i < pa.length; i++) {
            if (!paused) pa[i] += ps[i] * speed * 0.015f;
            float r = R * pr[i], x = cx + (float) Math.cos(pa[i]) * r, y = cy + (float) Math.sin(pa[i]) * r * 0.9f;
            fill.setColor(alpha(col, paused ? 0.15f : 0.25f + 0.4f * (float) Math.abs(Math.sin(sec * ps[i] * 3 + i))));
            c.drawCircle(x, y, d * (0.8f + (i % 3) * 0.5f), fill);
        }

        // ---- nodos del Neural Core alrededor (estado real)
        Hud hd = hud;
        float target2 = hudVisible ? (speaking || listening ? 0.35f : 1f) : 0f;
        hudAlpha += (target2 - hudAlpha) * 0.15f;
        int n = hd.nodes.length;
        for (int i = 0; i < n; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / n;
            float nx = cx + (float) Math.cos(a) * R * 1.12f, ny = cy + (float) Math.sin(a) * R * 1.12f;
            String st = i < hd.nodeState.length ? hd.nodeState[i] : "inactivo";
            int nc = "listo".equals(st) ? Ui.OK : "procesando".equals(st) ? Ui.ACCENT : "error".equals(st) ? Ui.BAD : "desconectado".equals(st) ? Color.rgb(90, 90, 90) : Ui.DIM;
            float pulse = "procesando".equals(st) ? 0.5f + 0.5f * (float) Math.sin(sec * 10) : 1f;
            if ("procesando".equals(st)) { stroke.setColor(alpha(Ui.ACCENT, 0.5f * pulse)); stroke.setStrokeWidth(d); c.drawLine(nx, ny, cx, cy - R * 0.08f, stroke); }
            fill.setColor(alpha(nc, 0.9f * pulse)); c.drawCircle(nx, ny, d * 4.5f, fill);
            stroke.setColor(alpha(nc, 0.6f)); stroke.setStrokeWidth(d); c.drawCircle(nx, ny, d * 7f, stroke);
            if (hudAlpha > 0.05f) {
                text.setTextAlign(Math.cos(a) < -0.2 ? Paint.Align.RIGHT : Math.cos(a) > 0.2 ? Paint.Align.LEFT : Paint.Align.CENTER);
                text.setTextSize(d * 8.5f); text.setColor(alpha(nc, 0.85f * hudAlpha));
                float off = d * 10;
                c.drawText(hd.nodes[i].substring(0, Math.min(5, hd.nodes[i].length())).toUpperCase(), nx + (float) Math.cos(a) * off, ny + (float) Math.sin(a) * off + d * 3, text);
            }
        }

        // ---- paneles con datos reales
        if (hudAlpha > 0.05f) drawPanels(c, w, h, d, col);

        // ---- alerta y estado
        text.setTextAlign(Paint.Align.CENTER);
        if (alert != null) {
            float a = 0.5f + 0.5f * (float) Math.sin(sec * 5);
            stroke.setColor(alpha(Ui.BAD, 0.4f + 0.5f * a)); stroke.setStrokeWidth(d * 3); c.drawCircle(cx, cy, R * 1.04f, stroke);
            text.setColor(Ui.BAD); text.setTextSize(d * 12); c.drawText(alert, cx, floorY + R * 0.36f, text);
        }
        text.setColor(paused ? Ui.BAD : alpha(col, 0.9f)); text.setTextSize(d * 11);
        c.drawText(paused ? "EN PAUSA" : state.toUpperCase(), cx, floorY + R * 0.36f + (alert != null ? d * 16 : 0), text);
        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }

    /** Figura humanoide holográfica (cabeza, torso, brazos y piernas) con núcleo en el pecho. */
    private void drawFigure(Canvas c, float cx, float cy, float R, float d, int col, boolean paused, boolean thinking, float sec, float breathe) {
        float s = R / 100f;                  // unidades de diseño: la figura mide ~190 de alto
        float top = cy - 78 * s + breathe * s * 0.8f, bottom = cy + 112 * s;
        path.reset();
        // torso (hombros → cintura)
        path.moveTo(cx - 30 * s, top + 34 * s); path.lineTo(cx + 30 * s, top + 34 * s);
        path.lineTo(cx + 22 * s, top + 86 * s); path.lineTo(cx + 15 * s, top + 104 * s);
        path.lineTo(cx - 15 * s, top + 104 * s); path.lineTo(cx - 22 * s, top + 86 * s); path.close();
        Shader body = new LinearGradient(cx, top, cx, bottom, alpha(col, paused ? 0.12f : 0.35f + smooth * 0.3f), alpha(col, 0.05f), Shader.TileMode.CLAMP);
        fill.setShader(body); c.drawPath(path, fill); fill.setShader(null);
        stroke.setColor(alpha(col, paused ? 0.35f : 0.85f)); stroke.setStrokeWidth(d * 1.6f); c.drawPath(path, stroke);
        // cabeza y cuello
        rect.set(cx - 11 * s, top, cx + 11 * s, top + 26 * s);
        fill.setShader(body); c.drawOval(rect, fill); fill.setShader(null); c.drawOval(rect, stroke);
        c.drawLine(cx - 5 * s, top + 26 * s, cx - 6 * s, top + 34 * s, stroke); c.drawLine(cx + 5 * s, top + 26 * s, cx + 6 * s, top + 34 * s, stroke);
        // brazos (hombro → codo → mano), un poco separados del cuerpo
        float[] side = {-1, 1};
        for (float sd : side) {
            float sx = cx + sd * 30 * s, sy = top + 36 * s, ex = cx + sd * 40 * s, ey = top + 72 * s, hx = cx + sd * 44 * s, hy = top + 104 * s;
            stroke.setStrokeWidth(d * 7f * s / 1.2f); stroke.setColor(alpha(col, paused ? 0.1f : 0.25f));
            c.drawLine(sx, sy, ex, ey, stroke); c.drawLine(ex, ey, hx, hy, stroke);
            stroke.setStrokeWidth(d * 1.6f); stroke.setColor(alpha(col, paused ? 0.35f : 0.85f));
            c.drawLine(sx, sy, ex, ey, stroke); c.drawLine(ex, ey, hx, hy, stroke);
            c.drawCircle(hx, hy + 3 * s, 3.5f * s, stroke);
            // pierna (cadera → rodilla → pie)
            float kx = cx + sd * 14 * s, ky = top + 150 * s, fx = cx + sd * 15 * s, fy = bottom;
            float hpx = cx + sd * 10 * s, hpy = top + 104 * s;
            stroke.setStrokeWidth(d * 9f * s / 1.2f); stroke.setColor(alpha(col, paused ? 0.1f : 0.22f));
            c.drawLine(hpx, hpy, kx, ky, stroke); c.drawLine(kx, ky, fx, fy, stroke);
            stroke.setStrokeWidth(d * 1.6f); stroke.setColor(alpha(col, paused ? 0.35f : 0.85f));
            c.drawLine(hpx, hpy, kx, ky, stroke); c.drawLine(kx, ky, fx, fy, stroke);
            c.drawLine(fx, fy, fx + sd * 7 * s, fy, stroke);
        }
        // líneas de «circuito» en el torso
        stroke.setStrokeWidth(d); stroke.setColor(alpha(col, paused ? 0.15f : 0.4f));
        c.drawLine(cx, top + 62 * s, cx, top + 100 * s, stroke);
        c.drawLine(cx - 16 * s, top + 80 * s, cx + 16 * s, top + 80 * s, stroke);
        // núcleo en el pecho: late con la voz real; gira al pensar
        float coreY = top + 52 * s, coreR = (6.5f + smooth * 4f) * s;
        fill.setShader(new RadialGradient(cx, coreY, coreR * 3, alpha(Color.WHITE, paused ? 0.2f : 0.9f), alpha(col, 0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, coreY, coreR * 3, fill); fill.setShader(null);
        stroke.setColor(alpha(col, 0.95f)); stroke.setStrokeWidth(d * 1.4f); c.drawCircle(cx, coreY, coreR * 1.6f, stroke);
        if (thinking) for (int k = 0; k < 3; k++) {
            double a = sec * 5 + k * 2.1;
            fill.setColor(alpha(col, 0.9f)); c.drawCircle(cx + (float) Math.cos(a) * coreR * 2.2f, coreY + (float) Math.sin(a) * coreR * 2.2f, d * 2.2f, fill);
        }
        // escaneo al pensar
        if (thinking) {
            float yy = top + ((sec * 0.6f) % 1f) * (bottom - top);
            stroke.setColor(alpha(col, 0.7f)); stroke.setStrokeWidth(d * 1.5f); c.drawLine(cx - 50 * s, yy, cx + 50 * s, yy, stroke);
        }
    }

    /** Paneles semitransparentes: título, estado del sistema, rendimiento medido y próximas mejoras. */
    private void drawPanels(Canvas c, float w, float h, float d, int col) {
        Hud hd = hud;
        float a = hudAlpha, pad = d * 8, left = d * 76;  // deja sitio al menú lateral
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(alpha(col, a)); text.setTextSize(d * 15);
        c.drawText("J.A.R.V.I.S.", left, d * 30, text);
        text.setTextSize(d * 9); text.setColor(alpha(Ui.DIM, a));
        c.drawText("NEURAL CORE 1.0", left, d * 43, text);
        // estado del sistema (arriba a la derecha)
        float pw = Math.min(w * 0.46f, d * 190), px = w - pw - pad, py = d * 18, lh = d * 13;
        panel(c, px, py, pw, d * 22 + lh * hd.status.length, d, col, a, "ESTADO DEL SISTEMA");
        for (int i = 0; i < hd.status.length; i++) {
            boolean ok = i < hd.statusOk.length && hd.statusOk[i];
            fill.setColor(alpha(ok ? Ui.OK : Ui.BAD, a)); c.drawCircle(px + d * 10, py + d * 26 + i * lh - d * 3, d * 3, fill);
            text.setColor(alpha(Ui.TEXT, a)); text.setTextSize(d * 9.5f); c.drawText(fit(hd.status[i], pw - d * 22), px + d * 18, py + d * 26 + i * lh, text);
        }
        // rendimiento y próximas mejoras (abajo)
        float bh = d * 40, by = h - bh - d * 70;
        panel(c, left, by, (w - left) * 0.5f - pad, bh, d, col, a, "RENDIMIENTO");
        text.setColor(alpha(Ui.TEXT, a)); text.setTextSize(d * 9.5f); c.drawText(fit(hd.perf, (w - left) * 0.5f - pad - d * 12), left + d * 8, by + d * 30, text);
        float nx = left + (w - left) * 0.5f;
        panel(c, nx, by, w - nx - pad, bh, d, col, a, "PRÓXIMAS MEJORAS");
        text.setColor(alpha(Ui.TEXT, a)); c.drawText(fit(hd.next, w - nx - pad - d * 12), nx + d * 8, by + d * 30, text);
    }

    private void panel(Canvas c, float x, float y, float w, float h, float d, int col, float a, String title) {
        rect.set(x, y, x + w, y + h);
        fill.setColor(alpha(Ui.PANEL, 0.75f * a)); c.drawRoundRect(rect, d * 8, d * 8, fill);
        stroke.setColor(alpha(col, 0.55f * a)); stroke.setStrokeWidth(d); c.drawRoundRect(rect, d * 8, d * 8, stroke);
        text.setTextAlign(Paint.Align.LEFT); text.setColor(alpha(col, a)); text.setTextSize(d * 8.5f);
        c.drawText(title, x + d * 8, y + d * 13, text);
    }

    private String fit(String s, float max) {
        if (s == null) return "";
        if (text.measureText(s) <= max) return s;
        while (s.length() > 1 && text.measureText(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
