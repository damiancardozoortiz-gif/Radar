package com.radar.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.radar.jarvis.core.Json;
import com.radar.jarvis.core.Lab;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Aislamiento del laboratorio en Android: un WebView NUEVO para cada ejecución, sin red (bloqueada y cada petición
 * respondida vacía), sin archivos ni contenido del teléfono, sin almacenamiento y SIN puente con la app
 * (no hay addJavascriptInterface). Si tarda más del límite, se destruye.
 * Llamar desde un hilo de fondo: espera el resultado.
 */
public class LabSandbox implements Lab.Sandbox {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public LabSandbox(Context c) { ctx = c.getApplicationContext(); }

    public String run(final String js, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("el laboratorio no se ejecuta en el hilo de la pantalla");
        final String[] out = new String[1];
        final WebView[] wv = new WebView[1];
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() { public void run() {
            try {
                WebView w = new WebView(ctx);
                wv[0] = w;
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setBlockNetworkLoads(true);
                s.setBlockNetworkImage(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                s.setDomStorageEnabled(false);
                s.setDatabaseEnabled(false);
                s.setGeolocationEnabled(false);
                s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setSaveFormData(false);
                w.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {  // ninguna petición sale
                        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return true; }
                    @Override public void onPageFinished(WebView v, String url) {
                        v.evaluateJavascript(js, new ValueCallback<String>() { public void onReceiveValue(String value) {
                            out[0] = value;
                            done.countDown();
                        }});
                    }
                });
                w.loadDataWithBaseURL("about:blank", "<!doctype html><meta charset=utf-8>", "text/html", "utf-8", null);
            } catch (Throwable e) { out[0] = null; done.countDown(); }
        }});
        boolean finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
        main.post(new Runnable() { public void run() { if (wv[0] != null) { try { wv[0].stopLoading(); wv[0].destroy(); } catch (Throwable ignored) {} } } });
        if (!finished) throw new IOException("tiempo agotado (" + timeoutMs / 1000 + " s): la herramienta no terminó");
        if (out[0] == null || "null".equals(out[0])) throw new IOException("la herramienta no devolvió nada");
        Object v = Json.parse(out[0]);  // evaluateJavascript devuelve el valor como JSON: aquí, un texto JSON entre comillas
        if (!(v instanceof String)) throw new IOException("respuesta inesperada del aislamiento");
        return (String) v;
    }
}
