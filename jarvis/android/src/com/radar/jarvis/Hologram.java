package com.radar.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Random;

/**
 * Holograma de JARVIS: la identidad principal de la pantalla.
 * Estados: «en espera» (respira), «escuchando» (reacciona a su voz), «pensando» (giro y pulsos),
 * «hablando» (la boca y los anillos siguen el volumen real del audio de ElevenLabs), «en pausa» (rojo, apagado)
 * y alerta (bloqueo financiero u otro aviso: anillo rojo intermitente con texto).
 * Todo se dibuja en la app (sin imágenes ni librerías), ~60 fps mientras está en pantalla.
 */
public class Hologram extends View {
    public volatile String state = "en espera";
    public volatile float level = 0;       // micrófono (0..1)
    public volatile float voiceLevel = 0;  // salida de voz (0..1), lo pone Voice
    public volatile String alert;          // texto de alerta o null
    public Voice voice;                    // para saber si habla y con qué volumen

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = System.currentTimeMillis();
    private final float[] pa = new float[48], pr = new float[48], ps = new float[48];
    private final RectF rect = new RectF();
    private final Path wave = new Path();
    private float smooth;  // nivel suavizado para que la boca no tiemble

    public Hologram(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        Random r = new Random(7);
        for (int i = 0; i < pa.length; i++) { pa[i] = (float) (r.nextFloat() * Math.PI * 2); pr[i] = 0.55f + r.nextFloat() * 0.75f; ps[i] = 0.1f + r.nextFloat() * 0.5f; }
    }

    private int color() {
        if (alert != null) return Ui.BAD;
        if ("en pausa".equals(state)) return Ui.BAD;
        if ("pensando".equals(state)) return Color.rgb(140, 200, 255);
        return Ui.ACCENT;
    }

    private static int alpha(int c, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        float cx = w / 2, cy = h * 0.46f, R = Math.min(w, h * 0.9f) * 0.34f;
        float sec = (System.currentTimeMillis() - t0) / 1000f;
        if (voice != null && voice.isSpeaking() && !"en pausa".equals(state)) { state = "hablando"; voiceLevel = voice.outLevel; }
        boolean paused = "en pausa".equals(state), speaking = "hablando".equals(state), listening = "escuchando".equals(state), thinking = "pensando".equals(state);
        int col = color();

        // nivel de la boca: audio real si llega; si no (voz de Android), un pulso suave
        float target = speaking ? (voiceLevel > 0.02f ? voiceLevel : 0.25f + 0.25f * (float) Math.abs(Math.sin(sec * 9) * Math.sin(sec * 3.7))) : listening ? level * 0.5f : 0;
        smooth += (target - smooth) * 0.35f;

        float breathe = paused ? 1f : 1f + 0.035f * (float) Math.sin(sec * 1.3);
        float speed = paused ? 0.05f : thinking ? 1.8f : listening ? 0.7f : speaking ? 1.0f : 0.25f;

        // 1) halo
        fill.setShader(new RadialGradient(cx, cy, R * 1.9f * breathe, alpha(col, paused ? 30 : 70 + (int) (60 * smooth)), alpha(col, 0), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R * 1.9f * breathe, fill);
        fill.setShader(null);

        // 2) anillos segmentados que giran
        float[][] rings = {{1.18f, 14, 0.5f, 1.2f}, {1.0f, 40, 0.75f, -0.8f}, {0.86f, 9, 0.35f, 1.7f}, {0.74f, 60, 0.6f, -0.5f}};
        for (int i = 0; i < rings.length; i++) {
            float r = R * rings[i][0] * breathe + (listening && i == 0 ? level * R * 0.12f : 0);
            int n = (int) rings[i][1];
            float step = 360f / n, sweep = step * rings[i][2];
            float rot = sec * speed * rings[i][3] * 40 + i * 30;
            rect.set(cx - r, cy - r, cx + r, cy + r);
            stroke.setColor(alpha(col, paused ? 50 : 110 + 30 * i));
            stroke.setStrokeWidth(d * (i == 0 ? 2.2f : i == 2 ? 3f : 1.2f));
            for (int k = 0; k < n; k++) {
                if (i == 1 && k % 7 == 0) continue;
                c.drawArc(rect, rot + k * step, sweep, false, stroke);
            }
        }

        // 3) ondas al escuchar
        if (listening) for (int k = 0; k < 3; k++) {
            float ph = (sec * 0.8f + k / 3f) % 1f;
            stroke.setColor(alpha(col, (int) (160 * (1 - ph) * (0.3f + level))));
            stroke.setStrokeWidth(d * 1.5f);
            c.drawCircle(cx, cy, R * (0.8f + ph * 0.9f), stroke);
        }

        // 4) partículas en órbita
        for (int i = 0; i < pa.length; i++) {
            if (!paused) pa[i] += ps[i] * speed * 0.02f;
            float r = R * pr[i] * breathe;
            float x = cx + (float) Math.cos(pa[i]) * r, y = cy + (float) Math.sin(pa[i]) * r * 0.92f;
            fill.setColor(alpha(col, paused ? 40 : 90 + (int) (120 * Math.abs(Math.sin(sec * ps[i] * 3 + i)))));
            c.drawCircle(x, y, d * (1 + (i % 3) * 0.6f), fill);
        }

        // 5) rostro: óvalo con núcleo, ojos y boca de onda
        float fr = R * 0.52f * breathe;
        fill.setShader(new RadialGradient(cx, cy, fr, alpha(col, paused ? 40 : 120), alpha(col, 10), Shader.TileMode.CLAMP));
        rect.set(cx - fr * 0.82f, cy - fr, cx + fr * 0.82f, cy + fr);
        c.drawOval(rect, fill);
        fill.setShader(null);
        stroke.setColor(alpha(col, paused ? 70 : 200));
        stroke.setStrokeWidth(d * 1.6f);
        c.drawOval(rect, stroke);

        // ojos: se entrecierran al pensar, parpadean de vez en cuando, apagados en pausa
        float blink = (sec % 4.2f) < 0.12f ? 0.15f : 1f;
        float eyeH = paused ? 0.06f : thinking ? 0.35f : blink;
        float ex = fr * 0.32f, ey = cy - fr * 0.22f, ew = fr * 0.26f;
        fill.setColor(alpha(col, paused ? 90 : 235));
        for (int s = -1; s <= 1; s += 2) {
            float x = cx + s * ex + (thinking ? (float) Math.sin(sec * 2) * fr * 0.05f : 0);
            rect.set(x - ew / 2, ey - fr * 0.07f * eyeH, x + ew / 2, ey + fr * 0.07f * eyeH);
            c.drawRoundRect(rect, d * 3, d * 3, fill);
        }

        // boca: onda que sigue el audio
        wave.reset();
        float mw = fr * 0.9f, my = cy + fr * 0.36f;
        int pts = 28;
        for (int k = 0; k <= pts; k++) {
            float x = cx - mw / 2 + mw * k / pts;
            float env = (float) Math.sin(Math.PI * k / pts);
            float amp = fr * 0.22f * smooth * env;
            float y = my + (float) Math.sin(k * 0.9f + sec * 14) * amp;
            if (k == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
        }
        stroke.setColor(alpha(col, paused ? 80 : 230));
        stroke.setStrokeWidth(d * 2f);
        c.drawPath(wave, stroke);

        // 6) puntos que giran al pensar
        if (thinking) for (int k = 0; k < 3; k++) {
            double a = sec * 4 + k * 2.1;
            fill.setColor(alpha(col, 220));
            c.drawCircle(cx + (float) Math.cos(a) * fr * 1.12f, cy + (float) Math.sin(a) * fr * 1.12f, d * 3, fill);
        }

        // 7) línea de escaneo
        float sy = cy - R * 1.2f + ((sec * 0.35f) % 1f) * R * 2.4f;
        stroke.setColor(alpha(col, paused ? 15 : 45));
        stroke.setStrokeWidth(d);
        c.drawLine(cx - R * 1.3f, sy, cx + R * 1.3f, sy, stroke);

        // 8) alerta
        if (alert != null) {
            float a = 0.5f + 0.5f * (float) Math.sin(sec * 5);
            stroke.setColor(alpha(Ui.BAD, (int) (120 + 120 * a)));
            stroke.setStrokeWidth(d * 3);
            c.drawCircle(cx, cy, R * 1.32f, stroke);
            text.setColor(alpha(Ui.BAD, 255));
            text.setTextSize(d * 12);
            c.drawText(alert, cx, cy + R * 1.55f, text);
        }

        // 9) estado
        text.setColor(paused ? Ui.BAD : Ui.DIM);
        text.setTextSize(d * 11);
        c.drawText(paused ? "EN PAUSA" : state.toUpperCase(), cx, Math.min(h - d * 6, cy + R * 1.55f + (alert != null ? d * 18 : 0)), text);
        text.setColor(alpha(col, 200));
        text.setTextSize(d * 13);
        c.drawText("J.A.R.V.I.S.", cx, cy - R * 1.32f - d * 8, text);

        if (getVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
