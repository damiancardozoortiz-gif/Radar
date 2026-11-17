package com.radar.jarvis

import android.app.Application
import com.radar.jarvis.android.Sesion

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Sesion.iniciar(this)
    }
}
