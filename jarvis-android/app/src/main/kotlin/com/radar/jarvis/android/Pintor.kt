package com.radar.jarvis.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.radar.jarvis.nucleo.Capa
import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Escena
import com.radar.jarvis.nucleo.Guion
import com.radar.jarvis.nucleo.Lienzo
import java.io.File
import kotlin.math.roundToInt

/**
 * Pinta lo que JARVIS escribió en el lenguaje del lienzo: cada fotograma se dibuja con el propio Android,
 * sin ningún servicio de fuera. Lo mismo vale para una imagen suelta que para cada fotograma de un video.
 *
 * @param fotograma devuelve la imagen de un medio (un fotograma de un video, o una imagen), o null si no se puede leer.
 */
class Pintor(private val guion: Guion, private val fotograma: (medio: String, segundo: Double) -> Bitmap?) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val letra = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val imagenes = HashMap<String, Bitmap?>()
    private val caminos = HashMap<Capa, Path>()
    private val w = guion.ancho.toFloat()
    private val h = guion.alto.toFloat()

    /** Pinta en el lienzo cómo se ve el guion en el segundo `t`. */
    fun pintar(c: Canvas, t: Double) {
        val (escena, local, alfa) = Lienzo.en(guion, t)
        c.drawColor(Color.BLACK)
        val fundido = if (alfa < 0.999) c.saveLayerAlpha(0f, 0f, w, h, (alfa * 255).roundToInt()) else -1
        fondo(c, escena)
        for (capa in escena.capas) runCatching { capa(c, capa, local) }     // una capa que falla no estropea el resto del fotograma
        if (fundido >= 0) c.restoreToCount(fundido)
    }

    private fun fondo(c: Canvas, e: Escena) {
        pincel.reset(); pincel.isAntiAlias = true
        if (e.fondo2 != null) {
            val p = Lienzo.degradado(w.toDouble(), h.toDouble(), e.angulo)
            pincel.shader = LinearGradient(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), e.fondo, e.fondo2, Shader.TileMode.CLAMP)
        } else pincel.color = e.fondo
        c.drawRect(0f, 0f, w, h, pincel)
        pincel.shader = null
    }

    private fun conAlfa(color: Int, opacidad: Double) = Color.argb((Color.alpha(color) * opacidad).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun relleno(k: Capa, opacidad: Double, alto: Float): Boolean {
        val color = k.relleno ?: return false
        pincel.reset(); pincel.isAntiAlias = true; pincel.style = Paint.Style.FILL
        if (k.relleno2 != null) pincel.shader = LinearGradient(0f, -alto / 2, 0f, alto / 2, conAlfa(color, opacidad), conAlfa(k.relleno2, opacidad), Shader.TileMode.CLAMP)
        else pincel.color = conAlfa(color, opacidad)
        return true
    }

    private fun borde(k: Capa, opacidad: Double): Boolean {
        val color = k.borde ?: return false
        pincel.reset(); pincel.isAntiAlias = true; pincel.style = Paint.Style.STROKE
        pincel.strokeWidth = k.grosor.toFloat(); pincel.strokeCap = Paint.Cap.ROUND; pincel.strokeJoin = Paint.Join.ROUND
        pincel.color = conAlfa(color, opacidad)
        return true
    }

    private fun camino(k: Capa): Path = caminos.getOrPut(k) {
        Path().apply {
            for (o in k.ordenes) when (o.orden) {
                'M' -> moveTo(o.n[0].toFloat(), o.n[1].toFloat())
                'L' -> lineTo(o.n[0].toFloat(), o.n[1].toFloat())
                'C' -> cubicTo(o.n[0].toFloat(), o.n[1].toFloat(), o.n[2].toFloat(), o.n[3].toFloat(), o.n[4].toFloat(), o.n[5].toFloat())
                'Q' -> quadTo(o.n[0].toFloat(), o.n[1].toFloat(), o.n[2].toFloat(), o.n[3].toFloat())
                'Z' -> close()
            }
        }
    }

    /** El trozo del camino que ya está dibujado cuando va por `progreso` (de 0 a 1). */
    private fun parcial(entero: Path, progreso: Double): Path {
        val medida = PathMeasure(entero, false)
        var total = 0f
        do { total += medida.length } while (medida.nextContour())
        var falta = total * progreso.toFloat()
        val salida = Path()
        val m = PathMeasure(entero, false)
        do {
            if (falta <= 0f) break
            m.getSegment(0f, minOf(falta, m.length), salida, true)
            falta -= m.length
        } while (m.nextContour())
        return salida
    }

    private fun capa(c: Canvas, k: Capa, t: Double) {
        val p = Lienzo.pose(k, t)
        if (!p.visible || p.opacidad < 0.004 || p.escala <= 0.0) return
        c.save()
        c.translate(p.x.toFloat(), p.y.toFloat())
        if (p.giro != 0.0) c.rotate(p.giro.toFloat())
        if (p.escala != 1.0) c.scale(p.escala.toFloat(), p.escala.toFloat())
        val aw = k.ancho.toFloat(); val ah = k.alto.toFloat()
        when (k.tipo) {
            "rect" -> {
                val r = RectF(-aw / 2, -ah / 2, aw / 2, ah / 2); val radio = k.radio.toFloat()
                if (relleno(k, p.opacidad, ah)) c.drawRoundRect(r, radio, radio, pincel)
                if (borde(k, p.opacidad)) c.drawRoundRect(r, radio, radio, pincel)
            }
            "circulo" -> {
                if (relleno(k, p.opacidad, k.radio.toFloat() * 2)) c.drawCircle(0f, 0f, k.radio.toFloat(), pincel)
                if (borde(k, p.opacidad)) c.drawCircle(0f, 0f, k.radio.toFloat(), pincel)
            }
            "linea", "trazado" -> {
                val entero = camino(k)
                if (k.tipo == "trazado" && p.progreso >= 0.999 && relleno(k, p.opacidad, h)) c.drawPath(entero, pincel)
                if (borde(k, p.opacidad)) c.drawPath(if (p.progreso >= 0.999) entero else parcial(entero, p.progreso), pincel)
            }
            "texto" -> texto(c, k, p.opacidad, p.progreso)
            "imagen" -> imagen(c, k, p.opacidad)
        }
        c.restore()
    }

    private fun texto(c: Canvas, k: Capa, opacidad: Double, progreso: Double) {
        letra.reset(); letra.isAntiAlias = true
        letra.textSize = k.tam.toFloat()
        letra.typeface = Typeface.create(when (k.fuente) { "serif" -> Typeface.SERIF; "mono" -> Typeface.MONOSPACE; else -> Typeface.SANS_SERIF },
            if (k.peso == "negrita") Typeface.BOLD else Typeface.NORMAL)
        letra.color = conAlfa(k.relleno ?: Color.WHITE, opacidad)
        if (k.sombra) letra.setShadowLayer((k.tam * 0.10).toFloat(), 0f, (k.tam * 0.05).toFloat(), conAlfa(0xB0000000.toInt(), opacidad))
        // al escribirse, las letras que faltan ya ocupan su sitio (invisibles): así el texto no baila mientras aparece
        val escrito = SpannableString(k.texto)
        val vistas = (k.texto.length * progreso).roundToInt().coerceIn(0, k.texto.length)
        if (vistas < k.texto.length) escrito.setSpan(ForegroundColorSpan(Color.TRANSPARENT), vistas, k.texto.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val ancho = k.ancho.toInt().coerceAtLeast(10)
        val caja = StaticLayout.Builder.obtain(escrito, 0, escrito.length, letra, ancho)
            .setAlignment(when (k.alinea) { "izquierda" -> Layout.Alignment.ALIGN_NORMAL; "derecha" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_CENTER })
            .setLineSpacing(0f, 1.12f).setIncludePad(false).build()
        c.translate(-ancho / 2f, -caja.height / 2f)
        caja.draw(c)
    }

    private fun imagen(c: Canvas, k: Capa, opacidad: Double) {
        val bmp = imagenes.getOrPut("${k.medio}@${k.segundo}") { fotograma(k.medio, k.segundo) } ?: return
        val aw = k.ancho.toFloat(); val ah = k.alto.toFloat()
        val destino = RectF(-aw / 2, -ah / 2, aw / 2, ah / 2)
        val escala = if (k.encaje == "contener") minOf(aw / bmp.width, ah / bmp.height) else maxOf(aw / bmp.width, ah / bmp.height)
        val origen: Rect
        if (k.encaje == "contener") {
            origen = Rect(0, 0, bmp.width, bmp.height)
            destino.set(-bmp.width * escala / 2, -bmp.height * escala / 2, bmp.width * escala / 2, bmp.height * escala / 2)
        } else {       // cubrir: se recorta lo que sobra, centrado
            val sw = (aw / escala).roundToInt().coerceIn(1, bmp.width); val sh = (ah / escala).roundToInt().coerceIn(1, bmp.height)
            origen = Rect((bmp.width - sw) / 2, (bmp.height - sh) / 2, (bmp.width + sw) / 2, (bmp.height + sh) / 2)
        }
        pincel.reset(); pincel.isAntiAlias = true; pincel.isFilterBitmap = true; pincel.alpha = (opacidad * 255).roundToInt().coerceIn(0, 255)
        if (k.radio > 0) c.clipPath(Path().apply { addRoundRect(destino, k.radio.toFloat(), k.radio.toFloat(), Path.Direction.CW) })
        c.drawBitmap(bmp, origen, destino, pincel)
    }

    fun soltar() { imagenes.values.forEach { it?.recycle() }; imagenes.clear(); caminos.clear() }
}

/** Convierte los fotogramas pintados en un video MP4 (H.264), con el codificador del propio teléfono. Sin sonido. */
object Codificador {
    /**
     * @param cuadro pinta el fotograma número `i` en el lienzo que recibe.
     * @param sigue se consulta en cada fotograma; si devuelve false, se abandona.
     */
    fun codificar(destino: File, ancho: Int, alto: Int, fps: Int, cuadros: Int, cuadro: (Canvas, Int) -> Unit, progreso: (Int) -> Unit, sigue: () -> Boolean) {
        val formato = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ancho, alto).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (ancho.toLong() * alto * fps / 8).coerceIn(2_000_000, 12_000_000).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (e: Exception) { throw ErrorManos("este teléfono no tiene codificador de video") }
        var mux: MediaMuxer? = null
        var pista = -1; var escritos = 0L; var arrancado = false
        val info = MediaCodec.BufferInfo()
        val lienzo = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        val sobreLienzo = Canvas(lienzo)

        fun vaciar(hastaElFinal: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (hastaElFinal) 20_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!hastaElFinal) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { pista = mux!!.addTrack(codec.outputFormat); mux!!.start(); arrancado = true }
                    i >= 0 -> {
                        val datos = codec.getOutputBuffer(i)
                        if (datos != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && arrancado) {
                            // el codificador pone la hora del reloj; aquí cada fotograma recibe su instante exacto en el video
                            info.presentationTimeUs = escritos++ * 1_000_000L / fps
                            mux!!.writeSampleData(pista, datos, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            codec.configure(formato, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val superficie = codec.createInputSurface()
            codec.start()
            mux = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for (i in 0 until cuadros) {
                if (!sigue()) throw ErrorManos("se detuvo antes de terminar el video")
                cuadro(sobreLienzo, i)
                val c = superficie.lockHardwareCanvas()
                try { c.drawBitmap(lienzo, 0f, 0f, null) } finally { superficie.unlockCanvasAndPost(c) }
                vaciar(false)
                if (i % 10 == 0) progreso(i * 100 / cuadros)
            }
            codec.signalEndOfInputStream()
            vaciar(true)
            superficie.release()
            if (escritos == 0L) throw ErrorManos("el codificador de este teléfono no produjo ningún fotograma")
        } catch (e: ErrorManos) {
            throw e
        } catch (e: Exception) {
            throw ErrorManos("el teléfono no pudo codificar el video (${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)})")
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            runCatching { if (arrancado) mux?.stop() }; runCatching { mux?.release() }
            lienzo.recycle()
        }
    }
}
