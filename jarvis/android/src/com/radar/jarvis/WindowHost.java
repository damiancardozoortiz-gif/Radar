package com.radar.jarvis;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Administrador de ventanas flotantes dentro de la app (no usa ventanas del sistema: no pide permisos).
 * Cada ventana: aparece con animación, diseño holográfico semitransparente, se mueve arrastrando la barra,
 * se minimiza al «dock», se expande y se cierra. Cerrar solo la oculta: su contenido se conserva y se recupera
 * al volver a abrirla (por voz o con su icono).
 */
public class WindowHost extends FrameLayout {
    public interface Listener { void changed(); }

    final class Win {
        final String id; final String title; final LinearLayout frame; final View content;
        boolean visible, minimized, expanded;
        Win(String id, String title, LinearLayout frame, View content) { this.id = id; this.title = title; this.frame = frame; this.content = content; }
    }

    private final Map<String, Win> wins = new LinkedHashMap<String, Win>();
    private final LinearLayout dock;
    private Listener listener;

    public WindowHost(Context c, LinearLayout dock) {
        super(c);
        this.dock = dock;
        setClipChildren(false);
    }

    public void setListener(Listener l) { listener = l; }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    /** Abre (o recupera) una ventana. Si ya existe, conserva su contenido y la trae al frente. */
    public void open(String id, String title, View content) {
        Win w = wins.get(id);
        if (w == null) { w = create(id, title, content); wins.put(id, w); }
        show(w);
    }

    public boolean has(String id) { return wins.containsKey(id); }
    public boolean isVisible(String id) { Win w = wins.get(id); return w != null && w.visible && !w.minimized; }

    /** Abre si está oculta; oculta si está visible (lo que hace el icono). */
    public void toggle(String id, String title, View content) {
        if (isVisible(id)) close(id); else open(id, title, content);
    }

    private Win create(final String id, String title, View content) {
        final LinearLayout f = new LinearLayout(getContext());
        f.setOrientation(LinearLayout.VERTICAL);
        f.setBackground(Ui.box(getContext(), (0xE6 << 24) | (Ui.PANEL & 0xFFFFFF), (0xC0 << 24) | (Ui.ACCENT & 0xFFFFFF), dp(14)));
        f.setPadding(dp(10), dp(6), dp(10), dp(10));
        f.setElevation(dp(12));

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.mono(getContext(), "◈ " + title.toUpperCase(), 11, Ui.ACCENT);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(barBtn("–", new OnClickListener() { public void onClick(View v) { minimize(id); } }));
        bar.addView(barBtn("⤢", new OnClickListener() { public void onClick(View v) { expand(id); } }));
        bar.addView(barBtn("✕", new OnClickListener() { public void onClick(View v) { close(id); } }));
        f.addView(bar);
        View line = new View(getContext());
        line.setBackgroundColor((0x60 << 24) | (Ui.ACCENT & 0xFFFFFF));
        f.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        if (content.getParent() instanceof ViewGroup) ((ViewGroup) content.getParent()).removeView(content);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        clp.topMargin = dp(6);
        f.addView(content, clp);

        // arrastrar por la barra de título
        bar.setOnTouchListener(new OnTouchListener() {
            float sx, sy, tx, ty;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); tx = f.getTranslationX(); ty = f.getTranslationY(); f.bringToFront(); return true;
                    case MotionEvent.ACTION_MOVE:
                        float maxX = getWidth() * 0.45f, maxY = getHeight() * 0.45f;
                        f.setTranslationX(Math.max(-maxX, Math.min(maxX, tx + e.getRawX() - sx)));
                        f.setTranslationY(Math.max(-maxY, Math.min(maxY, ty + e.getRawY() - sy)));
                        return true;
                    default: return true;
                }
            }
        });
        Win w = new Win(id, title, f, content);
        f.setVisibility(GONE);
        addView(f, normalParams());
        return w;
    }

    private TextView barBtn(String s, OnClickListener l) {
        TextView b = Ui.mono(getContext(), s, 15, Ui.TEXT);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private LayoutParams normalParams() {
        LayoutParams p = new LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.58f), Gravity.CENTER);
        return p;
    }

    private void show(Win w) {
        w.visible = true; w.minimized = false;
        removeChip(w.id);
        w.frame.setVisibility(VISIBLE);
        w.frame.bringToFront();
        w.frame.setAlpha(0f); w.frame.setScaleX(0.86f); w.frame.setScaleY(0.86f);
        w.frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        changed();
    }

    public void close(String id) {
        final Win w = wins.get(id);
        if (w == null) return;
        w.visible = false; w.minimized = false;
        removeChip(id);
        w.frame.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160).withEndAction(new Runnable() { public void run() {
            if (!w.visible) w.frame.setVisibility(GONE);
        }}).start();
        changed();
    }

    public void minimize(final String id) {
        final Win w = wins.get(id);
        if (w == null) return;
        w.minimized = true;
        w.frame.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).translationYBy(getHeight() * 0.3f).setDuration(200).withEndAction(new Runnable() { public void run() {
            if (w.minimized) { w.frame.setVisibility(GONE); w.frame.setTranslationY(0); w.frame.setTranslationX(0); }
        }}).start();
        addChip(w);
        changed();
    }

    public void expand(String id) {
        Win w = wins.get(id);
        if (w == null) return;
        w.expanded = !w.expanded;
        w.frame.setTranslationX(0); w.frame.setTranslationY(0);
        w.frame.setLayoutParams(w.expanded ? new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT) : normalParams());
    }

    /** Pantalla limpia: oculta todas las ventanas y el dock. Nada se pierde. */
    public void hideAll() {
        for (Win w : wins.values()) { w.visible = false; w.minimized = false; w.frame.setVisibility(GONE); }
        dock.removeAllViews();
        changed();
    }

    public boolean anyVisible() { for (Win w : wins.values()) if (w.visible && !w.minimized) return true; return false; }

    public List<String> visibleIds() { List<String> l = new ArrayList<String>(); for (Win w : wins.values()) if (w.visible && !w.minimized) l.add(w.id); return l; }

    private void addChip(final Win w) {
        removeChip(w.id);
        TextView chip = Ui.mono(getContext(), "▣ " + w.title, 11, Ui.ACCENT);
        chip.setTag(w.id);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setBackground(Ui.box(getContext(), (0xD0 << 24) | (Ui.PANEL & 0xFFFFFF), Ui.LINE, dp(12)));
        chip.setOnClickListener(new OnClickListener() { public void onClick(View v) { show(w); } });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(6);
        dock.addView(chip, p);
    }

    private void removeChip(String id) {
        for (int i = dock.getChildCount() - 1; i >= 0; i--) if (id.equals(dock.getChildAt(i).getTag())) dock.removeViewAt(i);
    }

    private void changed() { if (listener != null) listener.changed(); }
}
