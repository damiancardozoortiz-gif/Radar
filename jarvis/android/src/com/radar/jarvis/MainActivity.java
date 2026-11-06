package com.radar.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.media.AudioManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.radar.jarvis.core.Brain;
import com.radar.jarvis.core.Greeting;
import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.UiCommands;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pantalla principal de JARVIS 3.0: holograma a pantalla completa (pantalla limpia), ventanas flotantes
 *  (chat, resultados, historial, editor) que solo aparecen cuando usted las pide, por voz o con su icono. */
public class MainActivity extends Activity {
    static final int REQ_CAMERA = 11, REQ_FILE = 12, REQ_PERM = 13;
    private Brain brain;
    private Hologram holo;
    private WindowHost windows;
    private LinearLayout chat, chatWin, results, historyBox;
    private ScrollView resultsScroll, historyScroll;
    private TextView chatIcon, resultsIcon, thinkingLine;
    private boolean unseenChat, unseenResults;
    private ScrollView chatScroll;
    private EditText input;
    private TextView status;
    private TextView pauseBtn, voiceBtn;
    private boolean conversation, busy, muted;
    private SpeechRecognizer recognizer;
    private volatile String health = "comprobando sistemas…";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        if (JarvisApp.store(this).num("setup_version") < JarvisApp.SETUP_VERSION) {  // instalación nueva o actualización
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);  // las teclas de volumen controlan la voz de JARVIS
        build();
        say("Iniciando sistemas…", "sistema");
        JarvisApp.brain(this, new JarvisApp.Ready() { public void ready(Brain br) {
            brain = br;
            JarvisApp.applySettings(MainActivity.this, br);
            JarvisApp.voice(MainActivity.this).setOnInterrupt(new Runnable() { public void run() {  // usted habla: JARVIS se calla y escucha
                if (brain != null && !brain.isPaused()) { conversation = true; listen(); }
            }});
            chat.removeAllViews();
            for (Map<String, Object> d : br.pending()) approvalCard(d);  // borradores que sobrevivieron
            refresh();
            if (!JarvisApp.greeted) { JarvisApp.greeted = true; greet(); }
            else say("Sistemas activos, señor Rafael.", "sistema");
        }});
    }

    // ================================================================== arranque: comprobación real y saludo (una vez)
    private void greet() {
        new Thread(new Runnable() { public void run() {
            final List<String> problems = checkSystems();
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            final String text = Greeting.text(hour, problems);
            brain.log("arranque", problems.isEmpty() ? "todos los sistemas operativos" : "avisos: " + problems.size());
            JarvisApp.ui(new Runnable() { public void run() {
                say(text, "jarvis");
                refresh();
                speakThen(text);
            }});
        }}).start();
    }

    /** Comprueba de verdad cada sistema. Devuelve lo que NO funciona (vacío = todo bien). */
    private List<String> checkSystems() {
        List<String> p = new ArrayList<String>();
        StringBuilder h = new StringBuilder();
        if (brain.isPaused()) p.add("estoy en pausa");
        if (brain.apiKey.isEmpty()) { p.add("el cerebro Claude no tiene clave"); h.append("Claude: sin clave"); }
        else {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(brain.baseUrl + "/v1/models?limit=1").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("x-api-key", brain.apiKey);
                c.setRequestProperty("anthropic-version", Brain.API_VERSION);
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) h.append("Claude: conectado");
                else if (code == 401 || code == 403) { p.add("la clave de Claude no es válida"); h.append("Claude: clave no válida"); }
                else { p.add("Claude responde con error " + code); h.append("Claude: error ").append(code); }
            } catch (Exception e) { p.add("no hay conexión con Claude"); h.append("Claude: sin conexión"); }
        }
        Voice v = JarvisApp.voice(this);
        if (!v.usesElevenLabs()) { p.add("ElevenLabs no está configurado, uso la voz del teléfono"); h.append(" · ElevenLabs: sin clave"); }
        else {
            try { v.chooseVoice(); v.voices(); h.append(" · ElevenLabs: conectado"); }
            catch (Exception e) { p.add("ElevenLabs no responde, uso la voz del teléfono"); h.append(" · ElevenLabs: ").append(e.getMessage()); }
        }
        File mem = new File(getFilesDir(), "memory");
        if (!mem.isDirectory() || !mem.canWrite()) { p.add("la memoria no está disponible"); h.append(" · memoria: no disponible"); }
        else h.append(" · memoria: ").append(brain.memories().size()).append(" recuerdos");
        if (!Perms.granted(this, "android.permission.RECORD_AUDIO")) { p.add("el micrófono no tiene permiso"); h.append(" · micrófono: sin permiso"); }
        if (JarvisApp.discipline(this).blocked()) { p.add("las operaciones financieras están bloqueadas por dos pérdidas y esperan su revisión"); h.append(" · finanzas: BLOQUEADAS"); }
        com.radar.jarvis.core.fin.Mt5 m5 = JarvisApp.mt5(this);
        if (m5 instanceof com.radar.jarvis.core.fin.MetaApi) {
            try {
                ((com.radar.jarvis.core.fin.MetaApi) m5).refresh();
                if (m5.connected()) h.append(" · MT5: conectado (").append(m5.isDemo() ? "DEMO" : "NO DEMO").append(", solo lectura)");
                else { p.add("MetaTrader no está conectado"); h.append(" · MT5: ").append(((com.radar.jarvis.core.fin.MetaApi) m5).lastError()); }
            } catch (Exception e) { p.add("MetaTrader no responde"); h.append(" · MT5: ").append(e.getMessage()); }
        } else h.append(" · MT5: no configurado");
        health = h.toString();
        return p;
    }

    // ================================================================== interfaz
    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 10);

        holo = new Hologram(this);
        holo.voice = JarvisApp.voice(this);
        root.addView(holo, new FrameLayout.LayoutParams(-1, -1));

        status = Ui.mono(this, "", 10, Ui.DIM);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        sp.topMargin = pad;
        root.addView(status, sp);

        // contenido de las ventanas (se crean una vez: cerrar una ventana no borra nada)
        chatScroll = new ScrollView(this);
        chat = Ui.col(this);
        chatScroll.addView(chat);
        chatWin = Ui.col(this);
        chatWin.addView(chatScroll, Ui.lp(-1, 0, 1));
        LinearLayout in = Ui.row(this);
        input = new EditText(this);
        input.setHint("Escriba una orden…");
        input.setHintTextColor(Ui.DIM);
        input.setTextColor(Ui.TEXT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(Ui.box(this, Ui.BG, Ui.LINE, 20));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) { send(); return true; }
        });
        in.addView(input, Ui.lp(0, -2, 1));
        in.addView(Ui.button(this, "Enviar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { send(); } }));
        chatWin.addView(in, Ui.margins(this, 0, 6, 0, 0));
        resultsScroll = new ScrollView(this);
        results = Ui.col(this);
        results.addView(Ui.text(this, "Aquí quedan las investigaciones, informes y análisis. Nada se abre solo.", 12, Ui.DIM));
        resultsScroll.addView(results);
        historyScroll = new ScrollView(this);
        historyBox = Ui.col(this);
        historyScroll.addView(historyBox);

        LinearLayout dock = Ui.row(this);
        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.addView(dock);
        windows = new WindowHost(this, dock);
        windows.setListener(new WindowHost.Listener() { public void changed() { badges(); } });
        root.addView(windows, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bottom = Ui.col(this);
        bottom.addView(dockScroll, Ui.margins(this, 0, 0, 0, 6));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = Ui.row(this);
        bar.addView(icon("🎤", "Hablar", new View.OnClickListener() { public void onClick(View v) { micToggle(); } }));
        chatIcon = icon("💬", "Chat", new View.OnClickListener() { public void onClick(View v) { toggleWin("chat"); } });
        bar.addView(chatIcon);
        resultsIcon = icon("🔎", "Resultados", new View.OnClickListener() { public void onClick(View v) { toggleWin("results"); } });
        bar.addView(resultsIcon);
        bar.addView(icon("🕘", "Historial", new View.OnClickListener() { public void onClick(View v) { toggleWin("history"); } }));
        bar.addView(icon("🎬", "Editor", new View.OnClickListener() { public void onClick(View v) { toggleWin("editor"); } }));
        bar.addView(icon("📷", "Cámara", new View.OnClickListener() { public void onClick(View v) { camera(); } }));
        bar.addView(icon("📁", "Archivo", new View.OnClickListener() { public void onClick(View v) { pickFile(); } }));
        bar.addView(icon("📈", "Finanzas", new View.OnClickListener() { public void onClick(View v) { finance(); } }));
        bar.addView(icon("📡", "RADAR", new View.OnClickListener() { public void onClick(View v) { radar(); } }));
        voiceBtn = icon("🔊", "Voz", new View.OnClickListener() { public void onClick(View v) { toggleVoice(); } });
        bar.addView(voiceBtn);
        bar.addView(icon("⚙", "Panel", new View.OnClickListener() { public void onClick(View v) { panel(); } }));
        pauseBtn = icon("⏸", "PAUSA", new View.OnClickListener() { public void onClick(View v) { togglePause(); } });
        pauseBtn.setTextColor(Ui.BAD);
        bar.addView(pauseBtn);
        hs.addView(bar);
        bottom.addView(hs);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.leftMargin = pad; bp.rightMargin = pad; bp.bottomMargin = pad;
        root.addView(bottom, bp);
        setContentView(root);
    }

    private TextView icon(String glyph, String label, View.OnClickListener l) {
        TextView t = Ui.text(this, glyph + "\n" + label, 11, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        t.setBackground(Ui.box(this, (0xB0 << 24) | (Ui.PANEL & 0xFFFFFF), Ui.LINE, Ui.dp(this, 14)));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = Ui.dp(this, 6);
        t.setLayoutParams(p);
        return t;
    }

    // ================================================================== ventanas (solo cuando usted las pide)
    private void toggleWin(String id) {
        if (windows.isVisible(id)) windows.close(id); else openWin(id);
    }

    private void openWin(String id) {
        if ("chat".equals(id)) { unseenChat = false; windows.open("chat", "Chat", chatWin); chatScroll.post(new Runnable() { public void run() { chatScroll.fullScroll(View.FOCUS_DOWN); } }); }
        else if ("results".equals(id)) { unseenResults = false; windows.open("results", "Resultados", resultsScroll); }
        else if ("history".equals(id)) { fillHistory(); windows.open("history", "Historial", historyScroll); }
        else if ("editor".equals(id)) {
            LinearLayout e = Ui.col(this);
            e.addView(Ui.text(this, "Editor de vídeo: TODAVÍA NO DISPONIBLE.\n\nSe construye en la fase 5 de JARVIS 3.0 (importar, recortar, unir, "
                    + "subtítulos, 9:16 y 16:9, exportar) con los componentes de vídeo de Android. No se muestra una maqueta.", 13, Ui.TEXT));
            windows.open("editor", "Editor de vídeo", e);
        }
        else if ("panel".equals(id)) panel();
        badges();
    }

    private void fillHistory() {
        historyBox.removeAllViews();
        if (brain == null) return;
        List<String> l = brain.tail("acciones.jsonl", 60);
        if (l.isEmpty()) historyBox.addView(Ui.text(this, "Sin actividad registrada.", 12, Ui.DIM));
        for (int i = l.size() - 1; i >= 0; i--) {
            String line = l.get(i);
            try {
                Map<String, Object> m = Json.obj(Json.parse(line));
                historyBox.addView(Ui.text(this, m.get("fecha") + " · " + m.get("tipo") + " · " + m.get("detalle"), 12, Ui.TEXT), Ui.margins(this, 0, 3, 0, 0));
            } catch (Exception e) { historyBox.addView(Ui.text(this, line, 11, Ui.DIM)); }
        }
    }

    /** Avisos discretos en los iconos (sin abrir nada). */
    private void badges() {
        if (chatIcon == null) return;
        chatIcon.setText((unseenChat ? "💬•" : "💬") + "\nChat");
        chatIcon.setTextColor(unseenChat ? Ui.ACCENT : Ui.TEXT);
        resultsIcon.setText((unseenResults ? "🔎•" : "🔎") + "\nResultados");
        resultsIcon.setTextColor(unseenResults ? Ui.ACCENT : Ui.TEXT);
    }

    /** Órdenes de pantalla: se resuelven aquí, sin Claude. */
    private void uiCommand(Map<String, Object> c) {
        String op = String.valueOf(c.get("op")), target = String.valueOf(c.get("target"));
        if ("hide_all".equals(op) || "home".equals(op)) windows.hideAll();
        else if ("open".equals(op)) openWin(target);
        else if ("close".equals(op)) windows.close(target);
        if (brain != null) brain.log("pantalla", op + " " + target);
        speakThen(String.valueOf(c.get("say")));
    }

    private View gap() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 6), 1)); return v; }

    private TextView say(String text, String who) {
        TextView t = Ui.text(this, text, "user".equals(who) ? 14 : 15, "user".equals(who) ? Ui.DIM : "sistema".equals(who) ? Ui.WARN : Ui.TEXT);
        if ("user".equals(who)) t.setGravity(Gravity.END);
        final String copy = text;
        t.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("JARVIS", copy));
            Toast.makeText(MainActivity.this, "Copiado.", Toast.LENGTH_SHORT).show();
            return true;
        }});
        chat.addView(t, Ui.margins(this, 0, 6, 0, 0));
        if (!"user".equals(who) && windows != null && !windows.isVisible("chat")) { unseenChat = true; badges(); }
        chatScroll.post(new Runnable() { public void run() { chatScroll.fullScroll(View.FOCUS_DOWN); } });
        return t;
    }

    private void refresh() {
        if (brain == null) return;
        Voice v = JarvisApp.voice(this);
        String s = health
                + "\ncerebro: " + (brain.apiKey.isEmpty() ? "SIN CLAVE" : brain.brainState)
                + (brain.lastMs > 0 ? " · " + String.format(Locale.US, "%.1f s", brain.lastMs / 1000.0) : "")
                + " · voz: " + (!v.enabled ? "DESACTIVADA" : v.usesElevenLabs() ? "ElevenLabs" + (v.voiceName.isEmpty() ? "" : " (" + v.voiceName + ")") : "Android")
                + " · servicio: " + (JarvisService.running ? "activo" : "parado")
                + (brain.isPaused() ? "\nPAUSA ACTIVA" : "");
        status.setText(brain.isPaused() ? "PAUSA ACTIVA · nada se ejecuta" : "");
        status.setTextColor(brain.isPaused() ? Ui.BAD : Ui.DIM);
        pauseBtn.setText(brain.isPaused() ? "▶\nQuitar pausa" : "⏸\nPAUSA");
        voiceBtn.setText(v.enabled ? "🔊\nVoz" : "🔇\nVoz");
        holo.state = brain.isPaused() ? "en pausa" : busy ? "pensando" : v.isSpeaking() ? "hablando" : conversation ? "escuchando" : "en espera";
        holo.alert = JarvisApp.discipline(this).blocked() ? "BLOQUEO FINANCIERO · revise el riesgo" : null;
        if (thinkingLine != null && !busy) { chat.removeView(thinkingLine); thinkingLine = null; }
        status.setContentDescription(s);  // detalle completo para lectores de pantalla; a la vista solo el holograma
    }

    // ================================================================== conversación
    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        ask(q);
    }

    private void ask(final String q) {
        if (brain == null || busy) return;
        say(q, "user");
        Map<String, Object> cmd = UiCommands.parse(q);
        if (cmd != null) { say(String.valueOf(cmd.get("say")), "sistema"); uiCommand(cmd); return; }
        busy = true;
        thinkingLine = Ui.mono(this, "◌ JARVIS está pensando…", 11, Ui.ACCENT);
        chat.addView(thinkingLine);
        refresh();
        new Thread(new Runnable() { public void run() {
            final Map<String, Object> r = brain.answer(q);
            JarvisApp.ui(new Runnable() { public void run() { busy = false; show(r); } });
        }}).start();
    }

    private void show(Map<String, Object> r) {
        String sayTxt = String.valueOf(r.get("say"));
        TextView t = say(sayTxt, "jarvis");
        String meta = r.get("tool") + " · " + r.get("brain") + (r.get("ms") instanceof Long && (Long) r.get("ms") > 0
                ? String.format(Locale.US, " · %.1f s", (Long) r.get("ms") / 1000.0) : "");
        TextView m = Ui.mono(this, meta, 10, "claude".equals(r.get("brain")) ? Ui.ACCENT : Ui.WARN);
        chat.addView(m);
        Object det = r.get("detail");
        if (det != null && !String.valueOf(det).isEmpty() && !"null".equals(String.valueOf(det))) {
            addResult(String.valueOf(r.get("tool")), String.valueOf(det), r.get("report") == null ? null : String.valueOf(r.get("report")));
            say("↳ resultado completo en «Resultados»", "sistema").setTextSize(11);
        }
        if (r.get("warning") != null) say(String.valueOf(r.get("warning")), "sistema");
        if (r.get("approval") != null) approvalCard(Json.obj(r.get("approval")));
        if (r.get("task") != null) say("✓ " + r.get("task"), "sistema");
        if (Boolean.TRUE.equals(r.get("paused"))) stopAll(false);
        refresh();
        Map<String, Object> dev = Json.obj(r.get("device"));
        if (dev != null) device(dev);
        speakThen(sayTxt);
    }

    /** Guarda un resultado en la ventana «Resultados» (no la abre: usted la pide). */
    private void addResult(String tool, final String text, String reportPath) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.mono(this, tool.toUpperCase() + " · " + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date()), 10, Ui.ACCENT));
        TextView t = Ui.text(this, text, 13, Ui.TEXT);
        t.setTextIsSelectable(true);
        c.addView(t);
        LinearLayout row = Ui.row(this);
        row.addView(Ui.button(this, "Copiar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("JARVIS", text));
            Toast.makeText(MainActivity.this, "Copiado.", Toast.LENGTH_SHORT).show();
        }}));
        row.addView(gap());
        row.addView(Ui.button(this, "Compartir…", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir"));
            if (brain != null) brain.log("resultado", "compartido por el usuario");
        }}));
        c.addView(row);
        if (reportPath != null) c.addView(Ui.mono(this, "guardado: " + new File(reportPath).getName(), 10, Ui.DIM));
        results.addView(c, 1, Ui.margins(this, 0, 8, 0, 0));  // lo más nuevo arriba
        if (!windows.isVisible("results")) { unseenResults = true; badges(); }
    }

    private void speakThen(String text) {
        if (muted || brain.isPaused()) { afterSpeak(); return; }
        JarvisApp.voice(this).speak(text, new Runnable() { public void run() { afterSpeak(); } });
        refresh();
    }

    private void afterSpeak() {
        refresh();
        if (conversation && !brain.isPaused()) listen();  // modo conversación: vuelve a escuchar solo
    }

    // ================================================================== micrófono
    private void micToggle() {
        if (brain == null) return;
        if (brain.isPaused()) { Toast.makeText(this, "JARVIS está en pausa.", Toast.LENGTH_SHORT).show(); return; }
        if (conversation) { conversation = false; JarvisApp.voice(this).cancelRecording(); if (recognizer != null) recognizer.cancel(); refresh(); return; }
        if (!Perms.granted(this, "android.permission.RECORD_AUDIO")) {
            JarvisApp.store(this).flag("asked_mic", true);
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_PERM);
            return;
        }
        JarvisApp.voice(this).stopSpeaking();
        conversation = true;
        listen();
    }

    private void listen() {
        refresh();
        final Voice v = JarvisApp.voice(this);
        if (!v.usesElevenLabs()) { listenAndroid(); return; }
        new Thread(new Runnable() { public void run() {
            v.record(new Voice.Level() { public void level(float l, long ms) { holo.level = l; } }, new Voice.Recorded() {
                public void done(final File audio, final String err) {
                    if (audio == null) {
                        JarvisApp.ui(new Runnable() { public void run() {
                            if ("no te he oído".equals(err) && conversation) { conversation = false; say("No te he oído. Pulsa Hablar cuando quieras.", "sistema"); }
                            else if (err != null && !"cancelado".equals(err)) { conversation = false; say("Micrófono: " + err, "sistema"); }
                            refresh();
                        }});
                        return;
                    }
                    try {
                        final String text = v.transcribe(audio);
                        JarvisApp.ui(new Runnable() { public void run() { if (!text.isEmpty()) ask(text); else listen(); } });
                    } catch (final Exception e) {  // si falla la transcripción, sigue escuchando
                        brain.error("transcripción: " + e.getMessage());
                        JarvisApp.ui(new Runnable() { public void run() { if (conversation) listen(); } });
                    }
                }
            });
        }}).start();
    }

    /** Sin clave de ElevenLabs: reconocimiento de voz de Android (lo indica la pantalla). */
    private void listenAndroid() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { conversation = false; say("Este teléfono no tiene reconocimiento de voz de Android. Pon una clave de ElevenLabs en el panel.", "sistema"); refresh(); return; }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {}
            public void onBeginningOfSpeech() {}
            public void onRmsChanged(float db) { holo.level = Math.max(0, Math.min(1, db / 10f)); }
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {}
            public void onError(int e) { if (conversation && e != SpeechRecognizer.ERROR_CLIENT) { conversation = false; say("No te he oído. Pulsa Hablar cuando quieras.", "sistema"); } refresh(); }
            public void onResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) ask(l.get(0)); else if (conversation) listen();
            }
            public void onPartialResults(Bundle p) {}
            public void onEvent(int t, Bundle p) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        recognizer.startListening(i);
    }

    // ================================================================== órdenes del teléfono
    private void device(final Map<String, Object> d) {
        String a = String.valueOf(d.get("action"));
        if ("camera_open".equals(a)) camera();
        else if ("pick_file".equals(a)) pickFile();
        else if ("radar".equals(a)) radar();
        else if ("finance".equals(a)) finance();
        else if ("location".equals(a)) location();
        else if ("stop_all".equals(a)) stopAll(true);
        else if ("open_app".equals(a)) confirmOpen(String.valueOf(d.get("label")), String.valueOf(d.get("url")), String.valueOf(d.get("app")));
    }

    /** Abrir otra app SIEMPRE espera tu confirmación. Nada se envía solo. */
    private void confirmOpen(String label, final String url, final String app) {
        new AlertDialog.Builder(this).setTitle(app).setMessage(label + "\n\n" + url)
                .setPositiveButton("Abrir", new DialogInterface.OnClickListener() { public void onClick(DialogInterface di, int w) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); brain.log("app", "abierta por el usuario: " + app); }
                    catch (Exception e) { Toast.makeText(MainActivity.this, "No hay app para abrirlo.", Toast.LENGTH_LONG).show(); }
                }})
                .setNegativeButton("Cancelar", null).show();
    }

    private void camera() {
        if (brain != null && brain.isPaused()) { Toast.makeText(this, "JARVIS está en pausa.", Toast.LENGTH_SHORT).show(); return; }
        startActivityForResult(new Intent(this, CameraActivity.class), REQ_CAMERA);
    }

    private void pickFile() {
        if (brain != null && brain.isPaused()) { Toast.makeText(this, "JARVIS está en pausa.", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "text/*", "application/json", "application/pdf"});
        startActivityForResult(i, REQ_FILE);
    }

    private void radar() { startActivity(new Intent(this, RadarActivity.class)); }

    private void finance() { if (brain != null) startActivity(new Intent(this, FinanceActivity.class)); }

    private void toggleVoice() {
        Voice v = JarvisApp.voice(this);
        v.enabled = !v.enabled;
        JarvisApp.store(this).flag("voice_off", !v.enabled);
        if (!v.enabled) v.stopSpeaking();
        refresh();
    }

    private void location() {
        if (!Perms.granted(this, "android.permission.ACCESS_FINE_LOCATION")) {
            JarvisApp.store(this).flag("asked_location", true);
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, REQ_PERM);
            say("Necesito el permiso de ubicación. Vuelve a preguntarme cuando lo concedas.", "sistema");
            return;
        }
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) { showLocation(l); return; }
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, new LocationListener() {
                public void onLocationChanged(Location loc) { showLocation(loc); }
                public void onStatusChanged(String p, int s, Bundle e) {}
                public void onProviderEnabled(String p) {}
                public void onProviderDisabled(String p) { say("La ubicación está desactivada en el teléfono.", "sistema"); }
            }, null);
        } catch (SecurityException e) { say("Sin permiso de ubicación.", "sistema"); }
        catch (IllegalArgumentException e) { say("La ubicación está desactivada en el teléfono.", "sistema"); }
    }

    private void showLocation(Location l) {
        brain.log("ubicacion", "leída una vez (±" + Math.round(l.getAccuracy()) + " m), no enviada a Claude");
        String c = String.format(Locale.US, "%.5f,%.5f", l.getLatitude(), l.getLongitude());
        say("Estás en " + c + " (±" + Math.round(l.getAccuracy()) + " m).", "jarvis");
        confirmOpen("Ver tu ubicación en Google Maps", "https://www.google.com/maps/search/?api=1&query=" + c, "Google Maps");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (res != RESULT_OK || data == null || brain == null) return;
        if (req == REQ_CAMERA) {
            String path = data.getStringExtra("jpeg");
            if (path != null) vision(new File(path), "image/jpeg", "Foto de la cámara");
        } else if (req == REQ_FILE) {
            final Uri uri = data.getData();
            String type = getContentResolver().getType(uri);
            if (type != null && type.startsWith("image/")) visionUri(uri);
            else if ("application/pdf".equals(type)) readPdf(uri);
            else readTextDoc(uri);
        }
    }

    private void vision(final File f, final String mime, String label) {
        say(label + " → visión", "user");
        busy = true; refresh();
        new Thread(new Runnable() { public void run() {
            try {
                final Map<String, Object> r = brain.vision("¿Qué ves? Si hay texto, léelo.", jpegSmall(BitmapFactory.decodeFile(f.getAbsolutePath())), mime);
                JarvisApp.ui(new Runnable() { public void run() { busy = false; show(r); } });
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("No pude leer la foto: " + e.getMessage(), "sistema"); refresh(); } });
            }
        }}).start();
    }

    private void visionUri(final Uri uri) {
        say("Imagen elegida → visión", "user");
        busy = true; refresh();
        new Thread(new Runnable() { public void run() {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                Bitmap bm = BitmapFactory.decodeStream(in);
                in.close();
                brain.log("archivo", "imagen elegida por el usuario");
                final Map<String, Object> r = brain.vision("Describe esta imagen. Si es un documento, léelo.", jpegSmall(bm), "image/jpeg");
                JarvisApp.ui(new Runnable() { public void run() { busy = false; show(r); } });
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("No pude leer la imagen: " + e.getMessage(), "sistema"); refresh(); } });
            }
        }}).start();
    }

    private void readTextDoc(final Uri uri) {
        busy = true; refresh();
        new Thread(new Runnable() { public void run() {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n; long total = 0;
                while ((n = in.read(buf)) > 0 && (total += n) < 2_000_000) b.write(buf, 0, n);
                in.close();
                final String text = new String(b.toByteArray(), "UTF-8");
                final Map<String, Object> r = brain.document(String.valueOf(uri.getLastPathSegment()), text);
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("Documento elegido", "user"); show(r); } });
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("Solo puedo leer imágenes y texto: " + e.getMessage(), "sistema"); refresh(); } });
            }
        }}).start();
    }

    private void readPdf(final Uri uri) {
        busy = true; refresh();
        new Thread(new Runnable() { public void run() {
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 30_000_000) throw new java.io.IOException("el PDF pasa de 30 MB"); b.write(buf, 0, n); }
                in.close();
                final Map<String, Object> r = brain.pdf(String.valueOf(uri.getLastPathSegment()), b.toByteArray());
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("PDF elegido", "user"); show(r); } });
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { busy = false; say("No pude leer el PDF: " + e.getMessage(), "sistema"); refresh(); } });
            }
        }}).start();
    }

    static byte[] jpegSmall(Bitmap bm) {
        float s = Math.min(1f, 1600f / Math.max(bm.getWidth(), bm.getHeight()));
        Bitmap sc = s < 1 ? Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * s), Math.round(bm.getHeight() * s), true) : bm;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        sc.compress(Bitmap.CompressFormat.JPEG, 85, o);
        return o.toByteArray();
    }

    // ================================================================== borradores (autorización humana)
    private void approvalCard(final Map<String, Object> d) {
        final LinearLayout c = Ui.card(this);
        c.addView(Ui.mono(this, "BORRADOR · espera tu sí. JARVIS nunca envía nada.", 10, Ui.WARN));
        c.addView(Ui.text(this, String.valueOf(d.get("text")), 13, Ui.TEXT));
        final LinearLayout row = Ui.row(this);
        row.addView(Ui.button(this, "Aprobar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { decide(c, row, d, true); } }));
        row.addView(gap());
        row.addView(Ui.button(this, "Descartar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { decide(c, row, d, false); } }));
        c.addView(row);
        chat.addView(c, Ui.margins(this, 0, 8, 0, 0));
        if (windows != null && !windows.isVisible("chat")) { unseenChat = true; badges(); }  // borrador pendiente: aviso en el icono, sin abrir nada
    }

    private void decide(LinearLayout card, LinearLayout row, final Map<String, Object> d, boolean ok) {
        Map<String, Object> r = brain.decide(String.valueOf(d.get("id")), ok);
        if (r == null) { Toast.makeText(this, "En pausa: quita la pausa para decidir.", Toast.LENGTH_LONG).show(); return; }
        card.removeView(row);
        card.addView(Ui.mono(this, String.valueOf(r.get("state")).toUpperCase() + " · " + r.get("result"), 10, Ui.DIM));
        if ("aprobado".equals(r.get("state"))) {
            final String text = String.valueOf(d.get("text"));
            LinearLayout acts = Ui.row(this);
            acts.addView(Ui.button(this, "Abrir en Gmail", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_TEXT, text), "Abrir con"));
            }}));
            acts.addView(gap());
            acts.addView(Ui.button(this, "Compartir…", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Compartir con"));
            }}));
            card.addView(acts);
        }
    }

    // ================================================================== PAUSA
    private void togglePause() {
        if (brain == null) return;
        if (brain.isPaused()) { brain.setPause(false, ""); say("Pausa quitada.", "sistema"); }
        else stopAll(true);
        refresh();
    }

    /** PAUSA / DETENER TODO: corta la llamada a Claude, la voz, el micrófono y el servicio de palmadas. */
    private void stopAll(boolean setPause) {
        conversation = false;
        JarvisApp.voice(this).cancelRecording();
        JarvisApp.voice(this).stopSpeaking();
        if (recognizer != null) recognizer.cancel();
        if (setPause && brain != null && !brain.isPaused()) { brain.setPause(true, "botón"); say("EN PAUSA. Nada se ejecuta hasta que la quites.", "sistema"); }
        Intent s = new Intent(this, JarvisService.class).setAction(JarvisService.ACTION_PAUSE);
        if (JarvisService.running) startService(s);
        refresh();
    }

    // ================================================================== panel de administración
    private void panel() {
        if (brain == null) return;
        final AlertDialog[] dlg = new AlertDialog[1];
        ScrollView sv = new ScrollView(this);
        LinearLayout p = Ui.col(this);
        int pad = Ui.dp(this, 16);
        p.setPadding(pad, pad, pad, pad);
        p.setBackgroundColor(Ui.BG);
        sv.addView(p);

        p.addView(Ui.mono(this, "ASISTENTE Y SERVICIOS", 11, Ui.DIM));
        p.addView(Ui.text(this, "Cerebro: " + (brain.apiKey.isEmpty() ? "sin clave (modo respaldo)" : brain.brainState) + " · modelo " + brain.model
                + "\nVoz: " + (JarvisApp.voice(this).usesElevenLabs() ? "ElevenLabs" : "voz de Android") + (JarvisApp.voice(this).lastError.isEmpty() ? "" : " · " + JarvisApp.voice(this).lastError)
                + "\nServicio en segundo plano: " + (JarvisService.running ? "ACTIVO" + (JarvisService.clapOn ? " (escucha palmadas)" : "") : "parado")
                + "\nNotas: " + brain.vault.notes.size() + " · memoria: " + brain.memories().size() + " · borradores pendientes: " + brain.pending().size()
                + "\nComprobación al arrancar: " + health
                + "\nAgentes: investigador · analistas técnico/tendencias/liquidez · estratega · gestor de riesgo · ejecutor MT5 (MetaApi, solo lectura) · auditor"
                + "\nPAUSA: " + (brain.isPaused() ? "SÍ (" + brain.pausedBy() + ")" : "no")
                + (brain.lastError.isEmpty() ? "" : "\nÚltimo error: " + brain.lastError), 13, Ui.TEXT));
        LinearLayout r1 = Ui.row(this);
        r1.addView(Ui.button(this, JarvisService.running ? "Detener servicio" : "Iniciar servicio", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
            service(!JarvisService.running, false); dlg[0].dismiss();
        }}));
        r1.addView(gap());
        r1.addView(Ui.button(this, "Escucha por 2 palmadas", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            if (!Perms.granted(MainActivity.this, "android.permission.RECORD_AUDIO")) { Toast.makeText(MainActivity.this, "Primero concede el micrófono.", Toast.LENGTH_LONG).show(); return; }
            service(true, true); dlg[0].dismiss();
        }}));
        p.addView(r1);
        LinearLayout r2 = Ui.row(this);
        r2.addView(Ui.button(this, brain.isPaused() ? "Quitar PAUSA" : "PAUSA", Ui.BAD, new View.OnClickListener() { public void onClick(View v) { togglePause(); dlg[0].dismiss(); } }));
        r2.addView(gap());
        r2.addView(Ui.button(this, muted ? "Activar voz" : "Silenciar voz", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { muted = !muted; dlg[0].dismiss(); } }));
        p.addView(r2);

        p.addView(sectionTitle("VOZ"));
        final Voice vo = JarvisApp.voice(this);
        p.addView(Ui.text(this, "ElevenLabs: " + (vo.usesElevenLabs() ? "conectado · voz «" + (vo.voiceName.isEmpty() ? "se elige al hablar" : vo.voiceName) + "» · calidad "
                + ("eleven_flash_v2_5".equals(vo.model) ? "rápida" : "natural") : "sin clave (voz de Android)")
                + "\nInterrupción al hablar: " + (vo.bargeIn ? "activada" : "desactivada"), 13, Ui.TEXT));
        LinearLayout rv = Ui.row(this);
        rv.addView(Ui.button(this, "Elegir voz", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { dlg[0].dismiss(); chooseVoice(); } }));
        rv.addView(gap());
        rv.addView(Ui.button(this, "eleven_flash_v2_5".equals(vo.model) ? "Calidad: natural" : "Calidad: rápida", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            vo.model = "eleven_flash_v2_5".equals(vo.model) ? "eleven_multilingual_v2" : "eleven_flash_v2_5";
            JarvisApp.store(MainActivity.this).put("voice_model", vo.model); dlg[0].dismiss();
        }}));
        p.addView(rv);
        LinearLayout rv2 = Ui.row(this);
        rv2.addView(Ui.button(this, vo.bargeIn ? "Interrupción: desactivar" : "Interrupción: activar", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            vo.bargeIn = !vo.bargeIn; JarvisApp.store(MainActivity.this).flag("bargein_off", !vo.bargeIn); dlg[0].dismiss();
        }}));
        p.addView(rv2);
        p.addView(Ui.text(this, "Volumen de JARVIS: " + Math.round(vo.volume * 100) + " %", 12, Ui.DIM));
        android.widget.SeekBar vol = new android.widget.SeekBar(this);
        vol.setMax(100);
        vol.setProgress(Math.round(vo.volume * 100));
        vol.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar sb, int v, boolean u) { if (u) { vo.volume = Math.max(0.05f, v / 100f); } }
            public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                Store st = JarvisApp.store(MainActivity.this); st.num("volume", Math.round(vo.volume * 100)); st.num("volume_set", 1);
            }
        });
        p.addView(vol);

        p.addView(sectionTitle("TAREAS"));
        List<Map<String, Object>> ts = brain.tasks();
        if (ts.isEmpty()) p.addView(Ui.text(this, "Ninguna. Diga «JARVIS, agregá una tarea…».", 13, Ui.DIM));
        for (Map<String, Object> t : ts) p.addView(Ui.text(this, ("hecha".equals(t.get("estado")) ? "✓ " : "• ") + t.get("texto"), 13, "hecha".equals(t.get("estado")) ? Ui.DIM : Ui.TEXT));

        p.addView(sectionTitle("PERMISOS (estado real de Android)"));
        for (int i = 0; i < Perms.ALL.length; i++) {
            final Perms.P perm = Perms.ALL[i];
            final int code = i;
            String st = Perms.state(this, perm);
            LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, perm.name + ": " + st, 13, "concedido".equals(st) ? Ui.OK : Ui.WARN), Ui.lp(0, -2, 1));
            if (!"concedido".equals(st)) row.addView(Ui.button(this, st.startsWith("bloqueado") || perm.manifest == null ? "Ajustes" : "Pedir", Ui.ACCENT,
                    new View.OnClickListener() { public void onClick(View v) { Perms.request(MainActivity.this, perm, REQ_PERM); dlg[0].dismiss(); } }));
            p.addView(row);
        }
        p.addView(Ui.button(this, "Abrir ajustes de Android de JARVIS", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { Perms.openAppSettings(MainActivity.this); } }));
        p.addView(Ui.button(this, "Repetir el asistente inicial", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) {
            JarvisApp.store(MainActivity.this).num("setup_version", 0); startActivity(new Intent(MainActivity.this, SetupActivity.class)); finish();
        }}));

        p.addView(sectionTitle("CLAVES Y MODELO"));
        p.addView(Ui.text(this, "Claude: " + (brain.apiKey.isEmpty() ? "falta" : "guardada (cifrada)") + " · ElevenLabs: "
                + (JarvisApp.voice(this).usesElevenLabs() ? "guardada (cifrada)" : "no") + "\nCambiarlas: «Repetir el asistente inicial».", 13, Ui.TEXT));
        p.addView(Ui.button(this, "Cambiar modelo (" + brain.model + ")", Ui.TEXT, new View.OnClickListener() { public void onClick(View v) { dlg[0].dismiss(); chooseModel(); } }));

        p.addView(sectionTitle("BORRADORES PENDIENTES"));
        List<Map<String, Object>> pend = brain.pending();
        if (pend.isEmpty()) p.addView(Ui.text(this, "Ninguno.", 13, Ui.DIM));
        for (Map<String, Object> d : pend) p.addView(Ui.text(this, "• " + String.valueOf(d.get("text")).replace("\n", " ").substring(0, Math.min(90, String.valueOf(d.get("text")).length())) + "… (en el chat)", 12, Ui.TEXT));

        p.addView(sectionTitle("ERRORES"));
        List<String> errs = brain.tail("errores.log", 8);
        p.addView(Ui.text(this, errs.isEmpty() ? "Ninguno." : join(errs), 11, errs.isEmpty() ? Ui.DIM : Ui.WARN));
        p.addView(sectionTitle("ÚLTIMAS ACCIONES"));
        List<String> acts = brain.tail("acciones.jsonl", 10);
        p.addView(Ui.text(this, acts.isEmpty() ? "Ninguna." : join(acts), 11, Ui.DIM));

        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar_Fullscreen).setView(sv)
                .setPositiveButton("Cerrar", null).create();
        dlg[0].show();
    }

    private TextView sectionTitle(String s) {
        TextView t = Ui.mono(this, s, 11, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        return t;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(s).append('\n');
        return b.toString().trim();
    }

    private void chooseVoice() {
        final Voice v = JarvisApp.voice(this);
        if (!v.usesElevenLabs()) { Toast.makeText(this, "Sin clave de ElevenLabs.", Toast.LENGTH_LONG).show(); return; }
        new Thread(new Runnable() { public void run() {
            try {
                final List<Map<String, Object>> vs = v.voices();
                java.util.Collections.sort(vs, new java.util.Comparator<Map<String, Object>>() {
                    public int compare(Map<String, Object> a, Map<String, Object> b) { return Voice.score(b) - Voice.score(a); }
                });
                final String[] names = new String[vs.size()];
                for (int i = 0; i < vs.size(); i++) {
                    Map<String, Object> l = Json.obj(vs.get(i).get("labels"));
                    names[i] = vs.get(i).get("name") + (l == null ? "" : " · " + l.values());
                }
                JarvisApp.ui(new Runnable() { public void run() {
                    new AlertDialog.Builder(MainActivity.this).setTitle("Voz de JARVIS (primero las más graves y masculinas)")
                            .setItems(names, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                v.setVoice(Json.str(vs.get(w).get("voice_id")), String.valueOf(vs.get(w).get("name")));
                                refresh();
                                v.speak("Así sonaré a partir de ahora, señor Rafael.", null);
                            }}).show();
                }});
            } catch (final Exception e) {
                JarvisApp.ui(new Runnable() { public void run() { Toast.makeText(MainActivity.this, "No pude leer sus voces: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            }
        }}).start();
    }

    private void chooseModel() {
        final String[] models = {"claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"};
        new AlertDialog.Builder(this).setTitle("Modelo del cerebro").setItems(new String[]{
                "Claude Opus 5.5 (recomendado)", "Claude Sonnet 5.5 (más barato)", "Claude Haiku 5.5 (el más barato)"},
                new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    JarvisApp.store(MainActivity.this).put("model", models[w]);
                    JarvisApp.applySettings(MainActivity.this, brain);
                    refresh();
                }}).show();
    }

    private void service(boolean on, boolean clap) {
        Intent i = new Intent(this, JarvisService.class);
        if (on) {
            if (brain.isPaused()) { Toast.makeText(this, "Quita la pausa primero.", Toast.LENGTH_LONG).show(); return; }
            i.setAction(JarvisService.ACTION_START).putExtra("clap", clap);
            JarvisApp.store(this).flag("service_on", true);
            JarvisApp.store(this).flag("clap_on", clap);
            if (android.os.Build.VERSION.SDK_INT >= 26) {  // startForegroundService (API 26) por reflexión
                try { android.content.Context.class.getMethod("startForegroundService", Intent.class).invoke(this, i); }
                catch (Exception e) { startService(i); }
            } else startService(i);
            say(clap ? "Servicio activo: dos palmadas y te escucho (notificación visible)." : "Servicio activo.", "sistema");
        } else {
            JarvisApp.store(this).flag("service_on", false);
            startService(i.setAction(JarvisService.ACTION_STOP));
            say("Servicio detenido.", "sistema");
        }
        holo.postDelayed(new Runnable() { public void run() { refresh(); } }, 600);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (brain != null && perms.length > 0) brain.log("permiso", perms[0].replace("android.permission.", "") + ": " + (res.length > 0 && res[0] == 0 ? "concedido" : "denegado"));
        if (res.length > 0 && res[0] != 0 && perms.length > 0 && !shouldShowRequestPermissionRationale(perms[0]))
            say("Permiso denegado. Para activarlo: Ajustes → Aplicaciones → JARVIS → Permisos.", "sistema");
        refresh();
    }

    @Override protected void onResume() { super.onResume(); if (brain != null) { JarvisApp.applySettings(this, brain); refresh(); } }

    @Override protected void onPause() {  // sin pantalla no se escucha (el servicio de palmadas es aparte y visible)
        super.onPause();
        if (conversation) { conversation = false; JarvisApp.voice(this).cancelRecording(); if (recognizer != null) recognizer.cancel(); }
    }

    @Override protected void onDestroy() { if (recognizer != null) recognizer.destroy(); super.onDestroy(); }
}
