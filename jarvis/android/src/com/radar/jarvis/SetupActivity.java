package com.radar.jarvis;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Asistente de configuración inicial. Se muestra al instalar y al actualizar (SETUP_VERSION):
 *  claves, y después cada permiso de uno en uno, con su explicación. Nada se concede solo. */
public class SetupActivity extends Activity {
    private LinearLayout body;
    private int step = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        body = Ui.col(this);
        int pad = Ui.dp(this, 20);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        setContentView(sv);
        show();
    }

    @Override protected void onResume() { super.onResume(); if (step == 2) show(); }  // al volver de Ajustes

    private void show() {
        body.removeAllViews();
        body.addView(Ui.mono(this, "J.A.R.V.I.S. · CONFIGURACIÓN " + (step + 1) + "/3", 12, Ui.ACCENT));
        if (step == 0) welcome(); else if (step == 1) keys(); else perms();
    }

    private void welcome() {
        add(Ui.text(this, "Hola. Soy JARVIS.", 26, Ui.TEXT), 16);
        add(Ui.text(this, "Vas a darme tres cosas, y todas se pueden cambiar después desde el panel:\n\n"
                + "1. Tu clave de la API de Claude, para pensar. Se paga aparte por uso en console.anthropic.com; no es tu suscripción de Claude.\n"
                + "2. Opcional: tu clave de ElevenLabs, para la voz. Sin ella hablo con la voz de Android.\n"
                + "3. Los permisos del teléfono, de uno en uno. Android te preguntará cada uno y tú decides.\n\n"
                + "Las claves se guardan cifradas con el Keystore de este teléfono. Nunca envío mensajes, compro ni publico nada sin que lo confirmes.", 15, Ui.TEXT), 12);
        add(Ui.button(this, "Empezar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) { step = 1; show(); } }), 24);
    }

    private void keys() {
        final Store s = JarvisApp.store(this);
        add(Ui.text(this, "Claves", 22, Ui.TEXT), 16);
        add(Ui.text(this, "Clave de la API de Claude (sk-ant-…). " + (s.hasSecret("anthropic") ? "Ya hay una guardada: déjalo vacío para conservarla." : ""), 14, Ui.DIM), 10);
        final EditText ak = field("sk-ant-…");
        add(ak, 6);
        add(Ui.text(this, "Clave de ElevenLabs (opcional). " + (s.hasSecret("elevenlabs") ? "Ya hay una guardada." : ""), 14, Ui.DIM), 16);
        final EditText ek = field("clave de ElevenLabs");
        add(ek, 6);
        add(Ui.text(this, "ID de voz de ElevenLabs (opcional; vacío = la primera de tu cuenta).", 14, Ui.DIM), 16);
        final EditText vid = field("voice id");
        vid.setInputType(InputType.TYPE_CLASS_TEXT);
        vid.setText(s.get("voice_id", ""));
        add(vid, 6);
        add(Ui.button(this, "Guardar y seguir", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
            try {
                if (ak.getText().length() > 0) s.secret("anthropic", ak.getText().toString());
                if (ek.getText().length() > 0) s.secret("elevenlabs", ek.getText().toString());
                s.put("voice_id", vid.getText().toString().trim());
                if (JarvisApp.brainOrNull() != null) JarvisApp.applySettings(SetupActivity.this, JarvisApp.brainOrNull());
                if (!s.hasSecret("anthropic")) Toast.makeText(SetupActivity.this, "Sin clave de Claude funcionaré en modo respaldo.", Toast.LENGTH_LONG).show();
                step = 2; show();
            } catch (RuntimeException e) {
                Toast.makeText(SetupActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }}), 20);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setBackground(Ui.box(this, Ui.PANEL, Ui.LINE, 8));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        return e;
    }

    private void perms() {
        add(Ui.text(this, "Permisos", 22, Ui.TEXT), 16);
        add(Ui.text(this, "Pulsa cada uno. Android te mostrará su diálogo y tú decides. Internet lo concede Android al instalar. "
                + "Archivos y fotos no necesitan permiso: usarás el selector oficial de Android. Bluetooth no lo usa esta versión.", 14, Ui.DIM), 8);
        for (int i = 0; i < Perms.ALL.length; i++) {
            final Perms.P p = Perms.ALL[i];
            final int code = i;
            String st = Perms.state(this, p);
            LinearLayout c = Ui.card(this);
            LinearLayout head = Ui.row(this);
            TextView name = Ui.text(this, p.name + (p.optional ? " (opcional)" : ""), 16, Ui.TEXT);
            head.addView(name, Ui.lp(0, -2, 1));
            head.addView(Ui.mono(this, st, 11, "concedido".equals(st) ? Ui.OK : st.startsWith("no") ? Ui.DIM : Ui.BAD));
            c.addView(head);
            c.addView(Ui.text(this, p.why, 13, Ui.TEXT));
            c.addView(Ui.text(this, p.when, 12, Ui.DIM));
            if (!"concedido".equals(st)) {
                if (st.startsWith("bloqueado") || st.equals("denegado") && p.manifest == null)
                    c.addView(Ui.text(this, "Para activarlo: " + Perms.howTo(p), 12, Ui.WARN));
                c.addView(Ui.button(this, st.startsWith("bloqueado") || p.manifest == null ? "Abrir ajustes" : "Conceder", Ui.ACCENT,
                        new View.OnClickListener() { public void onClick(View v) { Perms.request(SetupActivity.this, p, code); } }));
            }
            body.addView(c, Ui.margins(this, 0, 10, 0, 0));
        }
        add(Ui.button(this, "Terminar", Ui.ACCENT, new View.OnClickListener() { public void onClick(View v) {
            JarvisApp.store(SetupActivity.this).num("setup_version", JarvisApp.SETUP_VERSION);
            startActivity(new Intent(SetupActivity.this, MainActivity.class));
            finish();
        }}), 20);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        JarvisApp.brain(this, new JarvisApp.Ready() { public void ready(com.radar.jarvis.core.Brain b) {
            b.log("permiso", Perms.ALL[Math.min(code, Perms.ALL.length - 1)].name + ": " + (res.length > 0 && res[0] == 0 ? "concedido" : "denegado"));
        }});
        show();
    }

    private void add(View v, int topDp) { body.addView(v, Ui.margins(this, 0, topDp, 0, 0)); }
}
