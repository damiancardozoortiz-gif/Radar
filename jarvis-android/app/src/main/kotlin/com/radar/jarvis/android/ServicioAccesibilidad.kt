package com.radar.jarvis.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.radar.jarvis.nucleo.Elemento
import com.radar.jarvis.nucleo.ErrorManos
import com.radar.jarvis.nucleo.Freno
import com.radar.jarvis.nucleo.Manos
import com.radar.jarvis.nucleo.Vista
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Las manos y los ojos de JARVIS: un servicio de accesibilidad de Android.
 *
 * Lee la pantalla como una lista numerada de elementos y toca, escribe y abre aplicaciones. Lo tienes que
 * activar tú en Ajustes → Accesibilidad; mientras está apagado, JARVIS solo conversa. Nunca maneja su propia
 * ventana: así no puede pulsar su propio botón de Aprobar ni cambiarse los ajustes.
 */
class ServicioAccesibilidad : AccessibilityService(), Manos {
    private var nodos: List<AccessibilityNodeInfo> = emptyList()
    private var burbuja: Burbuja? = null

    override fun onServiceConnected() {
        instancia = this
        burbuja = Burbuja(this).also { it.mostrar() }
        Sesion.avisar()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        burbuja?.quitar(); burbuja = null
        if (instancia === this) instancia = null
        Sesion.avisar()
        super.onDestroy()
    }

    // ------------------------------------------------------------ ver
    override fun noDisponible(): String? = null

    private fun raiz(): AccessibilityNodeInfo =
        rootInActiveWindow ?: throw ErrorManos("no puedo leer esta pantalla (está protegida o cambiando); espera un momento y vuelve a mirar")

    private fun esMia(n: AccessibilityNodeInfo) = n.packageName?.toString() == packageName

    override fun mirar(): Vista {
        val raiz = raiz()
        if (esMia(raiz)) {
            nodos = emptyList()
            return Vista("JARVIS (tu propia ventana: no se maneja; abre la aplicación que necesites)", emptyList())
        }
        val elementos = mutableListOf<Elemento>()
        val lista = mutableListOf<AccessibilityNodeInfo>()
        val caja = Rect()

        fun textos(n: AccessibilityNodeInfo, fuera: MutableList<String>, fondo: Int) {
            if (fuera.size >= 4 || fondo > 12) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t !in fuera) fuera += t
            for (i in 0 until n.childCount) n.getChild(i)?.let { textos(it, fuera, fondo + 1) }
        }

        fun visitar(n: AccessibilityNodeInfo, dentroPulsable: Boolean, fondo: Int) {
            if (elementos.size >= MAX_ELEMENTOS || fondo > 60) return
            val pulsable = n.isClickable || n.isCheckable || n.isLongClickable
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(caja)
                val campo = n.isEditable
                val texto = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val grande = caja.width() > 4 && caja.height() > 4
                if (grande && (pulsable || campo || ((texto.isNotEmpty() || desc.isNotEmpty()) && !dentroPulsable))) {
                    val nombre = when {
                        n.isPassword -> desc.ifEmpty { n.hintText?.toString().orEmpty() }.ifEmpty { "contraseña" }
                        texto.isNotEmpty() -> texto
                        desc.isNotEmpty() -> desc
                        else -> mutableListOf<String>().also { textos(n, it, 0) }.joinToString(" · ")
                            .ifEmpty { n.viewIdResourceName?.substringAfterLast('/').orEmpty() }
                    }
                    elementos += Elemento(
                        tipo = if (campo) "campo" else if (n.isCheckable) "casilla" else if (pulsable) "botón" else "texto",
                        nombre = nombre.take(120), x1 = caja.left, y1 = caja.top, x2 = caja.right, y2 = caja.bottom,
                        paquete = n.packageName?.toString().orEmpty(), password = n.isPassword, marcada = n.isChecked,
                        enfocado = n.isFocused, desplazable = n.isScrollable, activo = n.isEnabled,
                        valor = if (campo && !n.isPassword && !n.isShowingHintText) texto else "")
                    lista += n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visitar(it, dentroPulsable || pulsable, fondo + 1) }
        }

        visitar(raiz, false, 0)
        nodos = lista
        return Vista(raiz.packageName?.toString().orEmpty(), elementos)
    }

    override fun tamano(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            b.width() to b.height()
        } else m.widthPixels to m.heightPixels
    }

    override fun captura(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val listo = CountDownLatch(1)
        var jpeg: ByteArray? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val duro = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val bmp = duro?.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    if (bmp != null) {
                        val escala = minOf(1f, ALTO_CAPTURA / bmp.height.toFloat())
                        val chico = if (escala < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * escala).toInt(), (bmp.height * escala).toInt(), true) else bmp
                        jpeg = ByteArrayOutputStream().also { chico.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                    }
                } catch (e: Exception) { /* sin captura: se trabaja con la lista */ }
                listo.countDown()
            }
            override fun onFailure(codigo: Int) = listo.countDown()
        })
        listo.await(6, TimeUnit.SECONDS)
        return jpeg
    }

    // ------------------------------------------------------------ actuar
    private fun gesto(camino: Path, ms: Long) {
        if (rootInActiveWindow?.let { esMia(it) } == true) throw ErrorManos("JARVIS no maneja su propia ventana; abre antes la aplicación que necesites")
        val listo = CountDownLatch(1)
        var hecho = false
        burbuja?.dejarPasarToques(true)      // el dedo de JARVIS no debe chocar con su propia burbuja
        try {
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(camino, 0, ms)).build()
            val enviado = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { hecho = true; listo.countDown() }
                override fun onCancelled(d: GestureDescription?) = listo.countDown()
            }, null)
            if (!enviado || !listo.await(ms + 4000, TimeUnit.MILLISECONDS) || !hecho) throw ErrorManos("el teléfono no aceptó el gesto")
        } finally {
            burbuja?.dejarPasarToques(false)
        }
    }

    override fun tocarPunto(x: Int, y: Int, largo: Boolean) {
        val (w, h) = tamano()
        if (x !in 0..w || y !in 0..h) throw ErrorManos("el punto ($x, $y) está fuera de la pantalla ($w×$h)")
        gesto(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, if (largo) 700 else 60)
    }

    override fun tocarElemento(indice: Int, largo: Boolean) {
        val n = nodos.getOrNull(indice) ?: throw ErrorManos("ese elemento ya no está; vuelve a mirar la pantalla")
        if (!n.refresh()) throw ErrorManos("la pantalla cambió; vuelve a mirarla")
        val accion = if (largo) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val puede = if (largo) n.isLongClickable else n.isClickable
        if (n.isEditable) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (puede && n.performAction(accion)) return
        val c = Rect().also { n.getBoundsInScreen(it) }     // si el elemento no admite la acción, se toca con el dedo en su centro
        tocarPunto(c.centerX(), c.centerY(), largo)
    }

    private fun campoConCursor(): AccessibilityNodeInfo =
        raiz().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: throw ErrorManos("ningún campo tiene el cursor; toca primero el campo")

    override fun escribir(texto: String) {
        val campo = campoConCursor()
        if (campo.isPassword) throw ErrorManos("JARVIS no escribe contraseñas")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto) }
        if (!campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw ErrorManos("este campo no dejó escribir")
    }

    override fun intro() {
        val ok = Build.VERSION.SDK_INT >= 30 &&
            campoConCursor().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!ok) throw ErrorManos("este campo no acepta Intro; toca el botón de enviar o de buscar que haya en pantalla")
    }

    override fun deslizar(direccion: String) {
        val (w, h) = tamano()
        val cx = w / 2f; val cy = h / 2f
        val p = when (direccion) {
            "arriba" -> floatArrayOf(cx, h * 0.72f, cx, h * 0.28f)         // el dedo sube: se ve lo de más abajo
            "abajo" -> floatArrayOf(cx, h * 0.30f, cx, h * 0.74f)
            "izquierda" -> floatArrayOf(w * 0.82f, cy, w * 0.18f, cy)
            "derecha" -> floatArrayOf(w * 0.18f, cy, w * 0.82f, cy)
            else -> throw ErrorManos("dirección desconocida")
        }
        gesto(Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]) }, 320)
    }

    override fun tecla(nombre: String) {
        val accion = when (nombre) {
            "atras" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recientes" -> GLOBAL_ACTION_RECENTS
            "notificaciones" -> GLOBAL_ACTION_NOTIFICATIONS
            "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw ErrorManos("tecla desconocida")
        }
        if (!performGlobalAction(accion)) throw ErrorManos("el teléfono no aceptó la tecla $nombre")
    }

    // ------------------------------------------------------------ aplicaciones
    private fun lanzables(): List<ResolveInfo> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }

    override fun apps(): List<Pair<String, String>> =
        lanzables().map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }.distinct().sortedBy { it.first.lowercase() }

    override fun resolverApp(nombre: String): String {
        val q = Freno.norm(nombre).trim()
        val todas = apps()
        todas.firstOrNull { it.second == nombre.trim() }?.let { return it.second }
        val exactas = todas.filter { Freno.norm(it.first) == q }
        if (exactas.isNotEmpty()) return exactas[0].second
        val parecidas = todas.filter { Freno.norm(it.first).startsWith(q) }.ifEmpty { todas.filter { q in Freno.norm(it.first) } }
            .ifEmpty { todas.filter { q.replace(" ", "") in it.second.lowercase() } }
        return when {
            parecidas.isEmpty() -> throw ErrorManos("no hay ninguna aplicación instalada parecida a «$nombre». Usa la herramienta apps para ver las que hay")
            parecidas.size == 1 -> parecidas[0].second
            else -> parecidas.minBy { it.first.length }.second
        }
    }

    override fun abrirApp(nombre: String): String {
        val paquete = resolverApp(nombre)
        val intent = packageManager.getLaunchIntentForPackage(paquete) ?: throw ErrorManos("$paquete no se puede abrir")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return paquete
    }

    override fun abrirUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            throw ErrorManos("no hay navegador para abrir esa dirección")
        }
    }

    companion object {
        private const val MAX_ELEMENTOS = 120
        private const val ALTO_CAPTURA = 1280f

        @Volatile var instancia: ServicioAccesibilidad? = null
            private set

        val activo get() = instancia != null

        /** Las manos que usa el agente: el servicio si está encendido; si no, la explicación de por qué no. */
        val manos: Manos = object : Manos {
            private fun s() = instancia ?: throw ErrorManos("el servicio de accesibilidad de JARVIS está apagado")
            override fun noDisponible() =
                if (instancia == null) "el permiso de accesibilidad de JARVIS está apagado (se activa en Ajustes de JARVIS)" else null
            override fun mirar() = s().mirar()
            override fun tocarElemento(indice: Int, largo: Boolean) = s().tocarElemento(indice, largo)
            override fun tocarPunto(x: Int, y: Int, largo: Boolean) = s().tocarPunto(x, y, largo)
            override fun escribir(texto: String) = s().escribir(texto)
            override fun intro() = s().intro()
            override fun deslizar(direccion: String) = s().deslizar(direccion)
            override fun tecla(nombre: String) = s().tecla(nombre)
            override fun abrirApp(nombre: String) = s().abrirApp(nombre)
            override fun apps() = s().apps()
            override fun resolverApp(nombre: String) = s().resolverApp(nombre)
            override fun abrirUrl(url: String) = s().abrirUrl(url)
            override fun captura() = s().captura()
            override fun tamano() = s().tamano()
        }
    }
}
