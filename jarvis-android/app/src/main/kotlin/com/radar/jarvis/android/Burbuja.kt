package com.radar.jarvis.android

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.radar.jarvis.ui.MainActivity
import com.radar.jarvis.ui.OrbView
import kotlin.math.abs

/**
 * El puntito: el orbe de JARVIS flotando encima de cualquier aplicación mientras trabaja.
 *
 * Enseña lo que está haciendo y, cuando hace falta tu sí, la tarjeta con Aprobar y Rechazar.
 * Un toque: te escucha (o deja de hablar). Mantener pulsado: abre JARVIS. Arrastrar: lo mueve.
 * Se esconde cuando la pantalla de JARVIS está delante, porque ahí ya se ve todo.
 */
class Burbuja(private val ctx: Context) {
    private val principal = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(96) }

    private val orbe = OrbView(ctx).apply { mini = true; fuenteNivel = { Sesion.nivelVoz() } }
    private val texto = TextView(ctx).apply {
        setTextColor(Color.parseColor("#E6F3FA")); textSize = 13f; maxLines = 3
        setPadding(dp(12), dp(8), dp(12), dp(8)); maxWidth = dp(230)
        background = fondo("#E6091420", "#3A6ED7FF")
    }
    private val tarjetaTexto = TextView(ctx).apply { setTextColor(Color.parseColor("#FFE9C4")); textSize = 14f; maxWidth = dp(250) }
    private val si = boton("Aprobar", "#FFB84D", "#1A1000") { Sesion.decidir(true) }
    private val no = boton("Rechazar", "#22FFFFFF", "#E6F3FA") { Sesion.decidir(false) }
    private val tarjeta = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = fondo("#F21A1204", "#B3FFB84D")
        addView(tarjetaTexto)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0)
            addView(si, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(no, LinearLayout.LayoutParams(0, dp(44), 1f))
        })
    }
    private val raiz = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
        addView(orbe, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(texto, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(tarjeta, LinearLayout.LayoutParams(dp(278), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }
    private var puesta = false
    private val alCambiar: () -> Unit = { pintar() }

    private fun fondo(color: String, borde: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = 14 * d; setStroke(dp(1), Color.parseColor(borde))
    }

    private fun boton(etiqueta: String, color: String, tinta: String, accion: () -> Unit) = Button(ctx).apply {
        text = etiqueta; isAllCaps = false; setTextColor(Color.parseColor(tinta)); textSize = 14f
        background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = 10 * d }
        setOnClickListener { accion() }
    }

    fun mostrar() = principal.post {
        if (puesta) return@post
        orbe.setOnTouchListener(Arrastre())
        try { wm.addView(raiz, params); puesta = true } catch (e: Exception) { return@post }
        Sesion.observar(alCambiar)
        pintar()
    }

    fun quitar() = principal.post {
        Sesion.dejarDeObservar(alCambiar)
        if (puesta) runCatching { wm.removeView(raiz) }
        puesta = false
    }

    /** Mientras JARVIS hace un gesto, la burbuja deja pasar los toques (salvo si hay una tarjeta esperando tu sí). */
    fun dejarPasarToques(pasar: Boolean) = principal.post {
        if (!puesta) return@post
        val quiero = pasar && Sesion.peticion == null
        val tiene = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (quiero == tiene) return@post
        params.flags = if (quiero) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(raiz, params) }
    }

    private fun pintar() {
        if (!puesta) return
        val e = Sesion.estado
        val trabajando = e != Estado.ESPERA
        raiz.visibility = if (Sesion.enPrimerPlano) View.GONE else View.VISIBLE
        orbe.estado = e
        orbe.alpha = if (trabajando) 1f else 0.55f
        val p = Sesion.peticion
        tarjeta.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) tarjetaTexto.text = "NECESITO TU SÍ\n${p.accion}\n${p.motivo.replaceFirstChar { it.uppercase() }}."
        val frase = Sesion.subtitulo
        texto.visibility = if (p == null && trabajando && frase.isNotBlank()) View.VISIBLE else View.GONE
        texto.text = frase
    }

    private inner class Arrastre : View.OnTouchListener {
        private var y0 = 0f; private var py0 = 0; private var movido = false; private var t0 = 0L
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; py0 = params.y; movido = false; t0 = ev.eventTime }
                MotionEvent.ACTION_MOVE -> if (abs(ev.rawY - y0) > dp(8)) {
                    movido = true
                    params.y = (py0 + (ev.rawY - y0)).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(raiz, params) }
                }
                MotionEvent.ACTION_UP -> if (!movido) {
                    if (ev.eventTime - t0 > 500) {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else when (Sesion.estado) {
                        Estado.HABLANDO -> Sesion.detener("")
                        Estado.ESPERA, Estado.ESCUCHANDO -> Sesion.escucharVoz()
                        else -> ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    v.performClick()
                }
            }
            return true
        }
    }
}
