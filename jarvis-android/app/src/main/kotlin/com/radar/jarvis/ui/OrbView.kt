package com.radar.jarvis.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.radar.jarvis.android.Estado
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * El orbe: la cara de JARVIS. Un punto de luz dentro de una esfera de partículas que gira.
 * Cada estado tiene su color, su velocidad y su energía; la voz lo hace latir.
 */
class OrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private class Modo(val r: Float, val g: Float, val b: Float, val giro: Float, val ruido: Float, val vel: Float, val brillo: Float)

    private val modos = mapOf(
        Estado.ESPERA to Modo(94f, 228f, 255f, 0.10f, 0.025f, 0.5f, 0.60f),
        Estado.ESCUCHANDO to Modo(120f, 240f, 255f, 0.22f, 0.05f, 1.2f, 0.90f),
        Estado.PENSANDO to Modo(150f, 170f, 255f, 0.75f, 0.10f, 2.4f, 0.85f),
        Estado.HABLANDO to Modo(94f, 228f, 255f, 0.30f, 0.07f, 1.6f, 1.00f),
        Estado.ACTUANDO to Modo(94f, 255f, 196f, 0.50f, 0.07f, 1.8f, 0.95f),
        Estado.ESPERA_SI to Modo(255f, 184f, 77f, 0.14f, 0.04f, 0.9f, 1.00f),
        Estado.PAUSA to Modo(255f, 96f, 96f, 0.02f, 0.008f, 0.2f, 0.40f),
    )

    var estado = Estado.ESPERA
    /** De 0 a 1: el volumen de la voz. Se pregunta en cada fotograma. */
    var fuenteNivel: () -> Float = { 0f }
    /** El orbe pequeño de la burbuja flotante lleva menos puntos y no dibuja la base. */
    var mini = false
        set(v) { field = v; crearPuntos() }

    private var px = FloatArray(0); private var py = FloatArray(0); private var pz = FloatArray(0)
    private var cr = 94f; private var cg = 228f; private var cb = 255f
    private var giro = 0.1f; private var ruido = 0.025f; private var vel = 0.5f; private var brillo = 0.6f; private var niv = 0f
    private var angulo = 0f; private var tRuido = 0f; private var tSat = 0f; private var ultimo = 0L
    private val pinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val capas = Array(4) { FloatArray(0) }
    private val cuenta = IntArray(4)
    private val ovalo = RectF()

    init { crearPuntos() }

    private fun crearPuntos() {
        val n = if (mini) 220 else 900
        px = FloatArray(n); py = FloatArray(n); pz = FloatArray(n)
        val oro = (Math.PI * (3 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {                      // puntos repartidos por igual sobre la esfera
            val y = 1f - i / (n - 1f) * 2f
            val r = sqrt(1f - y * y)
            px[i] = cos(oro * i) * r; py[i] = y; pz[i] = sin(oro * i) * r
        }
        for (k in 0..3) capas[k] = FloatArray(n * 2)
    }

    private fun color(alfa: Float) = Color.argb((alfa.coerceIn(0f, 1f) * 255).toInt(), cr.toInt(), cg.toInt(), cb.toInt())

    override fun onDraw(c: Canvas) {
        val ahora = System.nanoTime()
        val dt = if (ultimo == 0L) 0.016f else min(0.05f, (ahora - ultimo) / 1e9f)
        ultimo = ahora
        val m = modos.getValue(estado)
        val k = 1f - exp(-dt * 4f)
        cr += (m.r - cr) * k; cg += (m.g - cg) * k; cb += (m.b - cb) * k
        giro += (m.giro - giro) * k; ruido += (m.ruido - ruido) * k; vel += (m.vel - vel) * k; brillo += (m.brillo - brillo) * k
        niv += (fuenteNivel().coerceIn(0f, 1f) - niv) * (1f - exp(-dt * 14f))
        angulo += dt * giro; tRuido += dt * vel; tSat += dt * (0.6f + giro * 2f)

        val w = width.toFloat(); val h = height.toFloat()
        if (w < 8 || h < 8) return
        val cx = w / 2
        val cy = if (mini) h / 2 else h * 0.44f
        val radio = if (mini) min(w, h) * 0.36f else min(w * 0.34f, h * 0.30f)
        val t = ahora / 1e9
        val late = (0.5 + 0.5 * sin(t * if (estado == Estado.ESPERA_SI) 3.2 else 1.1)).toFloat()
        val energia = ruido + niv * 0.34f

        if (!mini) {                                 // la base del «proyector»
            val by = cy + radio * 1.55f
            linea.color = color(0.35f * brillo)
            ovalo.set(cx - radio * 0.5f, by - radio * 0.07f, cx + radio * 0.5f, by + radio * 0.07f); c.drawOval(ovalo, linea)
            linea.color = color(0.12f * brillo)
            ovalo.set(cx - radio * 0.72f, by - radio * 0.10f, cx + radio * 0.72f, by + radio * 0.10f); c.drawOval(ovalo, linea)
        }

        // halo
        pinta.shader = RadialGradient(cx, cy, radio * 1.5f, intArrayOf(color(0.22f * brillo + niv * 0.15f), color(0.05f * brillo), color(0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radio * 1.5f, pinta)
        pinta.shader = null

        // esfera de partículas, en cuatro capas de profundidad (las de atrás, más tenues)
        val ca = cos(angulo); val sa = sin(angulo)
        val incl = 0.38f + 0.06f * sin(t / 5.2).toFloat()
        val ci = cos(incl); val si = sin(incl)
        cuenta.fill(0)
        for (i in px.indices) {
            val n = (sin(px[i] * 3.1f + tRuido * 1.3f) + sin(py[i] * 2.7f - tRuido * 1.1f) + sin(pz[i] * 3.7f + tRuido * 0.9f)) / 3f
            val r = radio * (1f + n * energia * 2.2f)
            val x1 = px[i] * ca + pz[i] * sa
            val z1 = -px[i] * sa + pz[i] * ca
            val y2 = py[i] * ci - z1 * si
            val z2 = py[i] * si + z1 * ci
            val persp = 1.31f / (1.9f - z2 * 0.45f)
            val capa = (((z2 + 1f) / 2f) * 3.999f).toInt().coerceIn(0, 3)
            val j = cuenta[capa]
            capas[capa][j] = cx + x1 * r * persp; capas[capa][j + 1] = cy + y2 * r * persp
            cuenta[capa] = j + 2
        }
        val d = resources.displayMetrics.density
        pinta.strokeCap = Paint.Cap.ROUND
        for (capa in 0..3) {
            pinta.color = color((0.14f + capa * 0.22f) * brillo)
            pinta.strokeWidth = (if (mini) 0.9f else 1.3f) * d * (0.7f + capa * 0.28f) * (1f + niv * 0.5f)
            c.drawPoints(capas[capa], 0, cuenta[capa], pinta)
        }

        // anillos orbitales con su satélite
        val anillos = arrayOf(floatArrayOf(1.28f, 0.30f, -24f), floatArrayOf(1.42f, 0.16f, 28f))
        for ((i, a) in anillos.withIndex()) {
            c.save(); c.rotate(a[2], cx, cy)
            linea.color = color((0.18f + if (estado == Estado.ESPERA_SI) late * 0.3f else 0f) * brillo)
            ovalo.set(cx - radio * a[0], cy - radio * a[0] * a[1], cx + radio * a[0], cy + radio * a[0] * a[1])
            c.drawOval(ovalo, linea)
            val ang = tSat * (if (i == 0) 1f else -0.7f) + i * 2f
            pinta.color = color(0.9f * brillo)
            c.drawCircle(cx + cos(ang) * radio * a[0], cy + sin(ang) * radio * a[0] * a[1], (if (mini) 1.6f else 2.6f) * d, pinta)
            c.restore()
        }

        // el puntito: el núcleo
        val nucleo = radio * (0.10f + 0.02f * late + niv * 0.10f)
        pinta.shader = RadialGradient(cx, cy, nucleo * 4.5f,
            intArrayOf(Color.argb((min(1f, brillo + 0.2f) * 242).toInt(), 255, 255, 255), color(0.85f * brillo), color(0.16f * brillo), color(0f)),
            floatArrayOf(0f, 0.16f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, nucleo * 4.5f, pinta)
        pinta.shader = null

        postInvalidateOnAnimation()
    }
}
