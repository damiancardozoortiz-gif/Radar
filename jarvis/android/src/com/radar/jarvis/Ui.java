package com.radar.jarvis;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Identidad visual holográfica: fondo casi negro, un solo acento cian, bordes finos y poco brillo. */
public final class Ui {
    public static final int BG = Color.rgb(4, 7, 11), PANEL = Color.rgb(10, 16, 23), LINE = Color.rgb(28, 44, 56),
            TEXT = Color.rgb(205, 222, 230), DIM = Color.rgb(108, 128, 142), ACCENT = Color.rgb(92, 225, 230),
            BAD = Color.rgb(229, 83, 75), WARN = Color.rgb(230, 180, 90), OK = Color.rgb(120, 200, 140);

    private Ui() {}

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable box(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(c, 1), stroke);
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView mono(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.12f);
        return t;
    }

    public static Button button(Context c, String s, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setBackground(box(c, Color.TRANSPARENT, color == ACCENT || color == BAD ? color : LINE, 18));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 40));
        b.setMinimumHeight(dp(c, 40));
        b.setOnClickListener(l);
        return b;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(box(c, PANEL, LINE, 10));
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return l;
    }
}
