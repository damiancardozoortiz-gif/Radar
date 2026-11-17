package com.radar.jarvis.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView

/** Colores y piezas que comparten las pantallas de JARVIS. */
object Estilo {
    val FONDO = Color.parseColor("#03060B")
    val PANEL = Color.parseColor("#B8091420")
    val LINEA = Color.parseColor("#446ED7FF")
    val TINTA = Color.parseColor("#E6F3FA")
    val TENUE = Color.parseColor("#7F95A6")
    val CIAN = Color.parseColor("#5EE4FF")
    val MENTA = Color.parseColor("#5EFFC4")
    val AMBAR = Color.parseColor("#FFB84D")
    val ROJO = Color.parseColor("#FF6B6B")

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun caja(c: Context, color: Int = PANEL, borde: Int = LINEA, radio: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radio).toFloat(); setStroke(dp(c, 1), borde)
    }

    fun texto(c: Context, t: String, tam: Float = 14f, color: Int = TINTA, mono: Boolean = false) = TextView(c).apply {
        text = t; textSize = tam; setTextColor(color)
        if (mono) { typeface = Typeface.MONOSPACE; letterSpacing = 0.18f }
    }

    fun boton(c: Context, t: String, color: Int = Color.parseColor("#14FFFFFF"), tinta: Int = TINTA, borde: Int = LINEA, accion: () -> Unit) =
        Button(c).apply {
            text = t; isAllCaps = false; textSize = 14f; setTextColor(tinta); minHeight = dp(c, 48); minimumHeight = dp(c, 48)
            background = caja(c, color, borde, 10); setPadding(dp(c, 16), 0, dp(c, 16), 0); stateListAnimator = null
            setOnClickListener { accion() }
        }

    /** Deja sitio para la barra de estado, la de navegación y el teclado (la app dibuja de borde a borde). */
    fun respetarBordes(v: View) {
        v.setOnApplyWindowInsetsListener { vista, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                vista.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                vista.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}
