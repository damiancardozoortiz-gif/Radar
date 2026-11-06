package com.radar.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** El reactor holográfico de JARVIS: anillos de marcas que giran según el estado. */
public class Reactor extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = System.currentTimeMillis();
    public volatile String state = "en espera";
    public volatile float level = 0;

    public Reactor(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2 - Ui.dp(getContext(), 8), R = Math.min(w, h) * 0.42f;
        double sec = (System.currentTimeMillis() - t0) / 1000.0;
        boolean active = !"en espera".equals(state) && !"en pausa".equals(state);
        float speed = "escuchando".equals(state) ? 0.6f : "pensando".equals(state) ? 1.6f : "hablando".equals(state) ? 0.9f : 0.15f;
        int base = "en pausa".equals(state) ? Ui.BAD : active ? Ui.ACCENT : Ui.DIM;
        float[][] rings = {{1f, 72, 0.05f, 1}, {0.84f, 48, 0.08f, -1.4f}, {0.68f, 36, 0.04f, 0.8f}, {0.53f, 24, 0.06f, -2f}};
        for (int i = 0; i < rings.length; i++) {
            float r = R * rings[i][0];
            int n = (int) rings[i][1];
            p.setColor(base);
            p.setAlpha(active ? 90 + 40 * i : 45 + 15 * i);
            p.setStrokeWidth(Ui.dp(getContext(), i % 2 == 1 ? 1.5f : 1f));
            double rot = sec * speed * rings[i][3] * 0.6 + i;
            for (int k = 0; k < n; k++) {
                if (i == 1 && k % 6 == 0) continue;
                double a = rot + k * 2 * Math.PI / n;
                float extra = "escuchando".equals(state) && i == 0 ? level * R * 0.16f * (0.5f + 0.5f * (float) Math.sin(k * 1.7 + sec * 8)) : 0;
                float len = R * rings[i][2] + extra;
                c.drawLine(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                        cx + (float) Math.cos(a) * (r - len), cy + (float) Math.sin(a) * (r - len), p);
            }
        }
        double pulse = "hablando".equals(state) ? 0.5 + 0.5 * Math.sin(sec * 9) : "pensando".equals(state) ? 0.5 + 0.5 * Math.sin(sec * 4) : 0.3;
        p.setAlpha((int) (60 + 120 * pulse));
        c.drawCircle(cx, cy, R * 0.38f, p);
        t.setColor(base);
        t.setTextSize(Ui.dp(getContext(), 12));
        c.drawText("J.A.R.V.I.S.", cx, cy + Ui.dp(getContext(), 4), t);
        t.setColor(Ui.DIM);
        t.setTextSize(Ui.dp(getContext(), 11));
        c.drawText(state.toUpperCase(), cx, h - Ui.dp(getContext(), 6), t);
        postInvalidateOnAnimation();
    }
}
