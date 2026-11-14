package com.radar.jarvis.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Estudio
import com.radar.jarvis.nucleo.Medio
import com.radar.jarvis.nucleo.Montaje
import com.radar.jarvis.nucleo.Plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * El estudio de video en el teléfono.
 *
 * - Ver: saca fotogramas con el propio Android y los junta en una hoja de contactos.
 * - Oír: separa el audio y lo transcribe con ElevenLabs (opcional, con tu clave).
 * - Montar: corta, une, rotula y pone música con Media3 Transformer, la librería de edición de Google.
 *
 * Nunca toca los originales: cada montaje es un archivo nuevo en Películas/JARVIS.
 */
@OptIn(UnstableApi::class)
class EstudioAndroid(private val ctx: Context, private val almacen: Almacen) : Estudio {
    private class Ficha(val medio: Medio, val uri: Uri)

    private val fichas = LinkedHashMap<String, Ficha>()      // por dirección del archivo
    private val adjuntos = mutableListOf<Uri>()
    private val montajes = mutableListOf<Uri>()
    private var n = 0
    private val principal = Handler(Looper.getMainLooper())

    /** El último video que montó JARVIS, para el botón «ver el video». */
    @Volatile var ultimoMontaje: Uri? = null
        private set

    // ------------------------------------------------------------ qué hay
    fun puedeVerGaleria(): Boolean = ctx.checkSelfPermission(PERMISO_GALERIA) == PackageManager.PERMISSION_GRANTED

    /** Lo que la persona elige con el botón «adjuntar». Devuelve cuántos sirvieron. */
    @Synchronized fun adjuntar(uris: List<Uri>): Int {
        var buenos = 0
        for (u in uris) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (ficha(u, "adjunto") != null) { adjuntos.remove(u); adjuntos.add(0, u); buenos++ }
        }
        return buenos
    }

    private fun nombreDe(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "archivo"

    @Synchronized private fun ficha(uri: Uri, origen: String, fecha: String = ""): Ficha? {
        fichas[uri.toString()]?.let { return it }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val esVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val giro = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (giro == 90 || giro == 270) w = h.also { h = w }          // grabado en vertical
            n++
            Ficha(Medio((if (esVideo) "v" else "a") + n, nombreDe(uri).take(80), if (esVideo) "video" else "audio", dur, w, h, fecha, origen), uri)
                .also { fichas[uri.toString()] = it }
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    private fun deLaGaleria(): List<Ficha> {
        if (!puedeVerGaleria()) return emptyList()
        val salida = mutableListOf<Ficha>()
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale("es"))
        runCatching {
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED), null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { c ->
                while (c.moveToNext() && salida.size < MAX_GALERIA) {
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                    ficha(uri, "galería", fmt.format(Date(c.getLong(1) * 1000)))?.let { salida += it }
                }
            }
        }
        return salida
    }

    @Synchronized override fun medios(): List<Medio> {
        val vistos = LinkedHashMap<String, Medio>()
        for (u in montajes.asReversed()) fichas[u.toString()]?.let { vistos[it.medio.id] = it.medio }
        for (u in adjuntos) fichas[u.toString()]?.let { vistos.putIfAbsent(it.medio.id, it.medio) }
        for (f in deLaGaleria()) vistos.putIfAbsent(f.medio.id, f.medio)
        return vistos.values.toList()
    }

    @Synchronized private fun porId(id: String): Ficha =
        fichas.values.firstOrNull { it.medio.id == id } ?: throw ErrorManos("no existe el medio «$id»")

    // ------------------------------------------------------------ ver
    override fun hojaDeContactos(id: String, desdeMs: Long, hastaMs: Long, cuadros: Int): ByteArray {
        val f = porId(id)
        val m = f.medio
        val cols = if (cuadros <= 1) 1 else if (cuadros <= 4) 2 else if (cuadros <= 9) 3 else 4
        val filas = (cuadros + cols - 1) / cols
        val vertical = m.alto > m.ancho
        val cw = if (vertical) 270 else 400
        val ch = if (m.ancho > 0) (cw.toFloat() * m.alto / m.ancho).toInt().coerceIn(120, 600) else cw * 9 / 16
        val hoja = Bitmap.createBitmap(cols * cw, filas * ch, Bitmap.Config.RGB_565)
        val lienzo = Canvas(hoja).apply { drawColor(Color.BLACK) }
        val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
        val fondo = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        val fino = hastaMs - desdeMs < 30_000          // tramo corto: se enseñan las décimas
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, f.uri)
            for (i in 0 until cuadros) {
                val t = desdeMs + (hastaMs - desdeMs) * (2 * i + 1) / (2 * cuadros)
                val crudo = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, cw, ch)
                else r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                val x = i % cols * cw
                val y = i / cols * ch
                if (crudo != null) {
                    val e = minOf(cw.toFloat() / crudo.width, ch.toFloat() / crudo.height)
                    val w = (crudo.width * e).toInt(); val h = (crudo.height * e).toInt()
                    lienzo.drawBitmap(crudo, null, Rect(x + (cw - w) / 2, y + (ch - h) / 2, x + (cw + w) / 2, y + (ch + h) / 2), null)
                    crudo.recycle()
                }
                val marca = Montaje.reloj(t) + if (fino) ".${t % 1000 / 100}" else ""
                lienzo.drawRect(x.toFloat(), y.toFloat(), x + tinta.measureText(marca) + 16, y + 36f, fondo)
                lienzo.drawText(marca, x + 8f, y + 27f, tinta)
            }
        } catch (e: Exception) {
            throw ErrorManos("no pude sacar fotogramas de $id (${e.javaClass.simpleName})")
        } finally { runCatching { r.release() } }
        return ByteArrayOutputStream().also { hoja.compress(Bitmap.CompressFormat.JPEG, 72, it); hoja.recycle() }.toByteArray()
    }

    // ------------------------------------------------------------ oír
    /** Copia solo la pista de audio a un archivo pequeño, para no subir el video entero. */
    private fun soloAudio(uri: Uri): File {
        val ex = MediaExtractor()
        val destino = File(ctx.cacheDir, "audio-${System.currentTimeMillis()}.m4a")
        try {
            ex.setDataSource(ctx, uri, null)
            val pista = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ErrorManos("este video no tiene sonido")
            ex.selectTrack(pista)
            val mux = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val salida = mux.addTrack(ex.getTrackFormat(pista))
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val leido = ex.readSampleData(buf, 0)
                if (leido < 0) break
                info.set(0, leido, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(salida, buf, info)
                ex.advance()
            }
            mux.stop(); mux.release()
            return destino
        } catch (e: ErrorManos) {
            destino.delete(); throw e
        } catch (e: Exception) {
            destino.delete(); throw ErrorManos("no pude separar el sonido de este video (${e.javaClass.simpleName})")
        } finally { ex.release() }
    }

    override suspend fun transcribir(id: String): String = withContext(Dispatchers.IO) {
        val clave = almacen.claveVoz
        if (clave.isBlank()) throw ErrorManos("para oír los videos hace falta una clave de ElevenLabs, que la persona pone en Ajustes de JARVIS. " +
            "Sin ella trabaja solo con la imagen y díselo")
        val audio = soloAudio(porId(id).uri)
        try {
            if (audio.length() > 80L * 1024 * 1024) throw ErrorManos("el sonido de este video es demasiado largo para transcribirlo")
            val borde = "jarvis" + System.nanoTime()
            val con = (URL("https://api.elevenlabs.io/v1/speech-to-text").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 180_000
                setRequestProperty("xi-api-key", clave)
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$borde")
                setChunkedStreamingMode(64 * 1024)
            }
            con.outputStream.use { o ->
                o.write("--$borde\r\nContent-Disposition: form-data; name=\"model_id\"\r\n\r\nscribe_v1\r\n".toByteArray())
                o.write("--$borde\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                audio.inputStream().use { it.copyTo(o) }
                o.write("\r\n--$borde--\r\n".toByteArray())
            }
            val codigo = con.responseCode
            val cuerpo = (if (codigo in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (codigo == 401) throw ErrorManos("la clave de ElevenLabs no es válida")
            if (codigo !in 200..299) throw ErrorManos("ElevenLabs no pudo transcribir (código $codigo)")
            enLineas(JSONObject(cuerpo))
        } catch (e: ErrorManos) {
            throw e
        } catch (e: Exception) {
            throw ErrorManos("no hubo conexión con ElevenLabs para transcribir (${e.javaClass.simpleName})")
        } finally { audio.delete() }
    }

    /** Las palabras con su tiempo, agrupadas en frases: «[0:12] texto». */
    private fun enLineas(j: JSONObject): String {
        val palabras = j.optJSONArray("words") ?: return j.optString("text").ifBlank { "(no se oye a nadie hablar)" }
        val lineas = mutableListOf<String>()
        val frase = StringBuilder()
        var inicio = -1.0; var fin = 0.0
        fun cerrar() {
            if (frase.isNotBlank()) lineas += "[${Montaje.reloj((inicio * 1000).toLong())}–${Montaje.reloj((fin * 1000).toLong())}] ${frase.toString().trim()}"
            frase.clear(); inicio = -1.0
        }
        for (i in 0 until palabras.length()) {
            val p = palabras.getJSONObject(i)
            if (p.optString("type") == "audio_event") continue
            val a = p.optDouble("start", fin); val b = p.optDouble("end", a)
            if (p.optString("type") == "word") {
                if (inicio >= 0 && (a - fin > 0.9 || a - inicio > 10)) cerrar()
                if (inicio < 0) inicio = a
                fin = b
            }
            frase.append(p.optString("text"))
        }
        cerrar()
        return lineas.joinToString("\n").ifBlank { "(no se oye a nadie hablar)" }
    }

    // ------------------------------------------------------------ narrar y compartir
    private fun pedir(url: String, cuerpo: ByteArray?, clave: String, acepta: String): Pair<Int, ByteArray> {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (cuerpo == null) "GET" else "POST"; connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("xi-api-key", clave); setRequestProperty("Accept", acepta)
            if (cuerpo != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (cuerpo != null) con.outputStream.use { it.write(cuerpo) }
        val codigo = con.responseCode
        return codigo to ((if (codigo in 200..299) con.inputStream else con.errorStream)?.readBytes() ?: ByteArray(0))
    }

    override suspend fun narrar(texto: String): Medio = withContext(Dispatchers.IO) {
        val clave = almacen.claveVoz
        if (clave.isBlank()) throw ErrorManos("para narrar hace falta una clave de ElevenLabs, que la persona pone en Ajustes de JARVIS")
        try {
            // la voz: la primera de la cuenta de la persona
            val (c1, voces) = pedir("https://api.elevenlabs.io/v1/voices", null, clave, "application/json")
            if (c1 == 401) throw ErrorManos("la clave de ElevenLabs no es válida")
            if (c1 !in 200..299) throw ErrorManos("ElevenLabs no dio la lista de voces (código $c1)")
            val voz = JSONObject(String(voces)).optJSONArray("voices")?.optJSONObject(0)?.optString("voice_id").orEmpty()
            if (voz.isEmpty()) throw ErrorManos("la cuenta de ElevenLabs no tiene ninguna voz")
            val cuerpo = JSONObject().put("text", texto).put("model_id", "eleven_multilingual_v2").toString().toByteArray()
            val (c2, audio) = pedir("https://api.elevenlabs.io/v1/text-to-speech/$voz?output_format=mp3_44100_128", cuerpo, clave, "audio/mpeg")
            if (c2 !in 200..299 || audio.size < 1000) throw ErrorManos("ElevenLabs no pudo generar la voz (código $c2)" +
                if (c2 == 401 || c2 == 402) ": revisa la clave o el saldo de la cuenta" else "")
            val carpeta = File(ctx.filesDir, "narraciones").apply { mkdirs() }
            carpeta.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }      // se guardan las 20 últimas
            val archivo = File(carpeta, "narracion-${System.currentTimeMillis()}.mp3").apply { writeBytes(audio) }
            val uri = Uri.fromFile(archivo)
            val f = ficha(uri, "narración") ?: throw ErrorManos("la narración se generó pero no se puede leer")
            synchronized(this@EstudioAndroid) { adjuntos.add(0, uri) }
            f.medio
        } catch (e: ErrorManos) {
            throw e
        } catch (e: Exception) {
            throw ErrorManos("no hubo conexión con ElevenLabs para narrar (${e.javaClass.simpleName})")
        }
    }

    override fun compartir(id: String, app: String) {
        val f = porId(id)
        if (f.medio.tipo != "video") throw ErrorManos("«$id» no es un video")
        if (f.uri.scheme != "content") throw ErrorManos("ese video no se puede compartir desde aquí; está en Películas/JARVIS")
        val enviar = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (app.isNotBlank()) {
            val q = com.radar.jarvis.nucleo.Freno.norm(app).replace(" ", "")
            val destino = ctx.packageManager.queryIntentActivities(enviar, 0).firstOrNull {
                q in com.radar.jarvis.nucleo.Freno.norm(it.loadLabel(ctx.packageManager).toString()).replace(" ", "") ||
                    q in it.activityInfo.packageName.lowercase() ||
                    (q == "tiktok" && it.activityInfo.packageName.let { p -> "musically" in p || "ugc.trill" in p })
            } ?: throw ErrorManos("no encuentro «$app» entre las aplicaciones que aceptan videos. ¿Está instalada?")
            enviar.setClassName(destino.activityInfo.packageName, destino.activityInfo.name)
            ctx.grantUriPermission(destino.activityInfo.packageName, f.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(enviar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ctx.startActivity(Intent.createChooser(enviar, "Compartir el video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    // ------------------------------------------------------------ montar
    private fun rotulo(texto: String, plan: Plan): TextOverlay {
        // líneas de unas 26 letras, cortando entre palabras
        val lineas = mutableListOf(StringBuilder())
        for (palabra in texto.split(Regex("\\s+"))) {
            if (lineas.last().isNotEmpty() && lineas.last().length + palabra.length > 26) lineas += StringBuilder()
            lineas.last().append(if (lineas.last().isEmpty()) "" else " ").append(palabra)
        }
        val t = SpannableString(lineas.joinToString("\n") { " $it " })
        val tam = (minOf(plan.ancho, plan.alto) / 15).coerceAtLeast(24)
        t.setSpan(AbsoluteSizeSpan(tam), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(ForegroundColorSpan(Color.WHITE), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(StyleSpan(Typeface.BOLD), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // abajo, en el tercio inferior, centrado
        return TextOverlay.createStaticTextOverlay(t, OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.62f).build())
    }

    private fun composicion(plan: Plan): Composition {
        val encaje = if (plan.recortar) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val trozos = plan.cortes.map { c ->
            val item = MediaItem.Builder().setUri(porId(c.id).uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.desdeMs).setEndPositionMs(c.hastaMs).build()).build()
            val efectos = mutableListOf<Effect>(Presentation.createForWidthAndHeight(plan.ancho, plan.alto, encaje))
            if (c.texto.isNotEmpty()) efectos += OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(rotulo(c.texto, plan)))
            EditedMediaItem.Builder(item).setRemoveAudio(c.sinSonido).setEffects(Effects(emptyList(), efectos)).build()
        }
        val secuencias = mutableListOf(EditedMediaItemSequence(trozos))
        plan.musicaId?.let { id ->
            val volumen = ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2).scaleBy(plan.volumenMusica))
                putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(plan.volumenMusica))
            }
            val musica = EditedMediaItem.Builder(MediaItem.fromUri(porId(id).uri)).setRemoveVideo(true)
                .setEffects(Effects(listOf(volumen), emptyList())).build()
            secuencias += EditedMediaItemSequence(listOf(musica), /* isLooping = */ true)     // se repite hasta que acaba el video
        }
        plan.narracionId?.let { id ->
            // la voz suena una vez, a volumen completo, desde el principio
            secuencias += EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(porId(id).uri)).setRemoveVideo(true).build()))
        }
        val b = Composition.Builder(secuencias)
        // sin música, si algún trozo va sin sonido se fuerza una pista de audio para que el resto no se quede mudo
        if (plan.musicaId == null && plan.narracionId == null && plan.cortes.any { it.sinSonido } && !plan.cortes.all { it.sinSonido }) b.experimentalSetForceAudioTrack(true)
        return b.build()
    }

    override suspend fun montar(plan: Plan, progreso: (Int) -> Unit): Medio {
        val temporal = File(ctx.cacheDir, "montaje-${System.currentTimeMillis()}.mp4")
        try {
            withContext(Dispatchers.Main) {          // Media3 exige crear y manejar el exportador en un hilo con bucle de mensajes
                suspendCancellableCoroutine<Unit> { cont ->
                    var exportador: Transformer? = null
                    val pulso = object : Runnable {
                        val caja = ProgressHolder()
                        override fun run() {
                            val t = exportador ?: return
                            if (!cont.isActive) return
                            if (t.getProgress(caja) == Transformer.PROGRESS_STATE_AVAILABLE) progreso(caja.progress)
                            principal.postDelayed(this, 700)
                        }
                    }
                    val t = Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                principal.removeCallbacks(pulso)
                                if (cont.isActive) cont.resume(Unit)
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                principal.removeCallbacks(pulso)
                                if (cont.isActive) cont.resumeWithException(ErrorManos(
                                    "el teléfono no pudo exportar el video (${exportException.errorCodeName}). Prueba con menos trozos, sin rótulos o en formato original"))
                            }
                        }).build()
                    exportador = t
                    cont.invokeOnCancellation { principal.post { principal.removeCallbacks(pulso); runCatching { t.cancel() } } }
                    try {
                        t.start(composicion(plan), temporal.absolutePath)
                        principal.postDelayed(pulso, 700)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is ErrorManos) e else ErrorManos("no se pudo empezar a exportar (${e.javaClass.simpleName}: ${e.message?.take(80)})"))
                    }
                }
            }
            progreso(100)
            return withContext(Dispatchers.IO) { guardar(temporal, plan.nombre) }
        } finally { temporal.delete() }
    }

    /** Deja el montaje donde lo veas: en la galería, carpeta Películas/JARVIS. */
    private fun guardar(archivo: File, nombre: String): Medio {
        val titulo = "$nombre ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())}.mp4"
        val uri: Uri = if (Build.VERSION.SDK_INT >= 29) {
            val datos = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, titulo); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/JARVIS"); put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val res = ctx.contentResolver
            val u = res.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), datos)
                ?: throw ErrorManos("el teléfono no dejó guardar el video en la galería")
            res.openOutputStream(u)?.use { o -> archivo.inputStream().use { it.copyTo(o) } } ?: throw ErrorManos("no pude escribir el video")
            res.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            u
        } else {
            // Android 8 y 9: sin permiso de escritura, el video se queda en la carpeta propia de la app
            val carpeta = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            Uri.fromFile(File(carpeta, titulo).also { archivo.copyTo(it, overwrite = true) })
        }
        val f = ficha(uri, "montaje", SimpleDateFormat("d MMM HH:mm", Locale("es")).format(Date()))
            ?: throw ErrorManos("el video se exportó pero no se puede leer; puede haber quedado dañado")
        synchronized(this) { montajes += uri }
        ultimoMontaje = uri
        return f.medio
    }

    companion object {
        private const val MAX_GALERIA = 25
        val PERMISO_GALERIA: String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
