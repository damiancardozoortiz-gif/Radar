package com.radar.jarvis;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** RADAR (la aplicación web de la raíz del repositorio) dentro de JARVIS. */
public class RadarActivity extends Activity {
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Ui.BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/radar/index.html");
        if (JarvisApp.brainOrNull() != null) JarvisApp.brainOrNull().log("radar", "RADAR abierto");
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
