package com.radar.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.radar.jarvis.android.ServicioAccesibilidad
import com.radar.jarvis.android.Sesion
import com.radar.jarvis.nucleo.Ajustes
import com.radar.jarvis.ui.Estilo.dp

/** Ajustes: la clave de Claude, los permisos, el modelo, la voz, la memoria y el diario de lo que ha hecho. */
class AjustesActivity : Activity() {
    private lateinit var estadoAcc: TextView
    private lateinit var estadoMic: TextView
    private lateinit var estadoClave: TextView
    private lateinit var estadoGaleria: TextView
    private lateinit var estadoVoz: TextView
    private lateinit var puentes: LinearLayout
    private lateinit var estadoAvisar: TextView
    private lateinit var estadoAvisos: TextView
    private lateinit var estadoFuentes: TextView
    private lateinit var diario: TextView
    private val a get() = Sesion.almacen

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        val c = this
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 32)) }
        fun titulo(t: String) = col.addView(Estilo.texto(c, t, 11f, Estilo.CIAN, mono = true).apply { isAllCaps = true },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 26); bottomMargin = dp(c, 8) })
        fun nota(t: String) = Estilo.texto(c, t, 13f, Estilo.TENUE).also { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) }) }
        fun campo(pista: String, valor: String, tipo: Int, lineas: Int = 1) = EditText(c).apply {
            hint = pista; setText(valor); inputType = tipo; setTextColor(Estilo.TINTA); setHintTextColor(Estilo.TENUE); textSize = 15f
            background = Estilo.caja(c); setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12)); minLines = lineas
            col.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) })
        }
        fun boton(t: String, accion: () -> Unit) = col.addView(Estilo.boton(c, t, accion = accion), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) })
        fun lista(opciones: List<Pair<String, String>>, actual: String, alElegir: (String) -> Unit) = Spinner(c).apply {
            adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, opciones.map { it.second })
            setSelection(opciones.indexOfFirst { it.first == actual }.coerceAtLeast(0)); background = Estilo.caja(c)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { if (opciones[i].first != actual) alElegir(opciones[i].first) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            col.addView(this, LinearLayout.LayoutParams(-1, dp(c, 52)).apply { bottomMargin = dp(c, 8) })
        }
        fun interruptor(t: String, valor: Boolean, alCambiar: (Boolean) -> Unit) = col.addView(Switch(c).apply {
            text = t; isChecked = valor; setTextColor(Estilo.TINTA); textSize = 15f; minHeight = dp(c, 48)
            setOnCheckedChangeListener { _, v -> alCambiar(v); Sesion.reconfigurar() }
        }, LinearLayout.LayoutParams(-1, -2))

        col.addView(Estilo.texto(c, "Ajustes", 24f))

        titulo("1 · Clave de Claude")
        nota("JARVIS piensa con Claude a través de la API de Anthropic, que se paga por uso con una clave propia (no sirve la suscripción de Claude). Créala en platform.claude.com → API keys. Se guarda cifrada en este teléfono y solo se envía a api.anthropic.com.")
        val clave = campo("sk-ant-…", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        estadoClave = nota("")
        boton("Guardar clave") {
            val t = clave.text.toString().trim()
            if (t.isEmpty()) return@boton Toast.makeText(c, "Escribe la clave primero.", Toast.LENGTH_SHORT).show()
            a.clave = t; clave.setText(""); Sesion.reconfigurar(); pintar()
            Toast.makeText(c, "Clave guardada.", Toast.LENGTH_SHORT).show()
        }
        boton("Borrar clave") { a.clave = ""; Sesion.reconfigurar(); pintar() }

        titulo("2 · Permisos")
        estadoAcc = nota("")
        nota("Para que JARVIS vea la pantalla y la maneje. En la lista, entra en «JARVIS: manejar el teléfono» y actívalo. Si Android dice «ajuste restringido», ve a Ajustes → Aplicaciones → JARVIS → ⋮ → «Permitir ajustes restringidos» y vuelve a intentarlo.")
        boton("Abrir Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        estadoMic = nota("")
        boton("Permitir micrófono") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }

        titulo("Video")
        estadoGaleria = nota("")
        nota("Con este permiso JARVIS encuentra solo tus videos recientes («monta los tres últimos»). Sin él, adjúntalos con el botón + de la pantalla principal.")
        boton("Permitir ver mis videos") { requestPermissions(arrayOf(com.radar.jarvis.android.EstudioAndroid.PERMISO_GALERIA), 2) }
        nota("Oídos y voz (opcional). Con una clave de ElevenLabs (elevenlabs.io → API keys) entiende lo que se DICE en los videos y puede narrar un guion con voz. Sin ella decide solo por la imagen y no narra. Al transcribir, el sonido del video se envía a ElevenLabs; al narrar, el guion.")
        val claveVoz = campo("Clave de ElevenLabs", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        estadoVoz = nota("")
        boton("Guardar clave de ElevenLabs") {
            val t = claveVoz.text.toString().trim()
            if (t.isEmpty()) return@boton Toast.makeText(c, "Escribe la clave primero.", Toast.LENGTH_SHORT).show()
            a.claveVoz = t; claveVoz.setText(""); pintar()
        }
        boton("Borrar clave de ElevenLabs") { a.claveVoz = ""; pintar() }

        titulo("Cerebro")
        nota("Modelo. Los más capaces cuestan más por tarea; puedes cambiarlo cuando quieras.")
        lista(Ajustes.MODELOS, a.modelo) { a.modelo = it; Sesion.reconfigurar() }
        nota("Esfuerzo: cuánto piensa antes de actuar.")
        lista(Ajustes.ESFUERZOS, a.esfuerzo) { a.esfuerzo = it; Sesion.reconfigurar() }
        interruptor("Buscar en internet (pequeño coste por búsqueda)", a.web) { a.web = it }
        interruptor("Responder en voz alta", a.voz) { a.voz = it }

        titulo("Quién eres")
        nota("Cuéntale lo que deba saber siempre: tu nombre, a qué te dedicas, cómo quieres que te hable.")
        val quien = campo("Me llamo…", a.quien, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 3)
        boton("Guardar") { a.quien = quien.text.toString(); Sesion.reconfigurar(); Toast.makeText(c, "Guardado.", Toast.LENGTH_SHORT).show() }

        titulo("Autonomía")
        interruptor("Recordar solo lo importante de cada conversación", a.memoriaSola) { a.memoriaSola = it }
        interruptor("Crear rutinas por su cuenta cuando vea una costumbre", a.rutinasSolas) { a.rutinasSolas = it }
        nota("Fusible: lo máximo que pueden gastar en un día las tareas que lanza él solo, en dólares. Al llegar se detiene hasta mañana y te avisa. Lo que tú le pides no cuenta.")
        val fusible = campo("5", "%.2f".format(java.util.Locale.US, a.fusible), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        boton("Guardar límite") {
            val v = fusible.text.toString().replace(',', '.').toFloatOrNull()
            if (v == null) Toast.makeText(c, "Escribe un número, por ejemplo 5.", Toast.LENGTH_SHORT).show()
            else { a.fusible = v; Toast.makeText(c, "Límite: %.2f $ al día.".format(a.fusible), Toast.LENGTH_SHORT).show() }
        }
        estadoAvisar = nota("")
        boton("Permitir que JARVIS me avise") { if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4) }
        boton("Ver su mente: memoria, habilidades y rutinas") { startActivity(Intent(c, MenteActivity::class.java)) }

        titulo("Puentes · otros servicios")
        nota("Un puente conecta a JARVIS con un servicio de fuera (correo, documentos, tareas…) sin manejar la pantalla. Usa el estándar MCP: pega aquí la dirección del servidor MCP del servicio. Por ejemplo, Zapier (zapier.com/mcp) te da una sola dirección que abre miles de aplicaciones. Consultar lo hace solo; lo que envía, crea o borra espera tu toque.")
        puentes = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(puentes)
        val pNombre = campo("Nombre (p. ej. Zapier)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val pUrl = campo("https://…", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val pToken = campo("Credencial (si el servicio la pide)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        boton("Probar y añadir el puente") {
            val cfg = com.radar.jarvis.nucleo.PuenteConfig(pNombre.text.toString().trim(), pUrl.text.toString().trim(), pToken.text.toString().trim())
            if (cfg.nombre.length < 2 || !cfg.url.startsWith("https://")) return@boton Toast.makeText(c, "Pon un nombre y una dirección que empiece por https://", Toast.LENGTH_LONG).show()
            if (a.puentes.any { it.nombre.equals(cfg.nombre, true) }) return@boton Toast.makeText(c, "Ya hay un puente con ese nombre.", Toast.LENGTH_LONG).show()
            Toast.makeText(c, "Probando la conexión…", Toast.LENGTH_SHORT).show()
            Thread {
                val resultado = try { "ok" to com.radar.jarvis.nucleo.ClienteMcp(cfg).conectar() } catch (e: Exception) { (e.message ?: "no se pudo conectar") to emptyList() }
                runOnUiThread {
                    if (resultado.first == "ok") {
                        a.puentes = a.puentes + cfg; Sesion.reconfigurar()
                        pNombre.setText(""); pUrl.setText(""); pToken.setText("")
                        val l = resultado.second
                        Toast.makeText(c, "Conectado: ${l.size} herramientas (${l.count { it.soloLectura }} solo consultan).", Toast.LENGTH_LONG).show()
                        pintar()
                    } else Toast.makeText(c, "No se añadió: ${resultado.first}", Toast.LENGTH_LONG).show()
                }
            }.start()
        }

        titulo("Lo que sabe de tu día")
        nota("Todo apagado hasta que lo enciendas. Cada fuente necesita su interruptor Y el permiso de Android. Lo que lea de ellas se envía a Claude solo cuando lo necesite para una tarea.")
        interruptor("Notificaciones en vivo", a.fuenteAvisos) { a.fuenteAvisos = it; pintar() }
        estadoAvisos = nota("")
        boton("Dar acceso a notificaciones") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        interruptor("Agenda", a.fuenteAgenda) { a.fuenteAgenda = it; if (it) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 5); pintar() }
        interruptor("Contactos", a.fuenteContactos) { a.fuenteContactos = it; if (it) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 6); pintar() }
        interruptor("Ubicación", a.fuenteUbicacion) {
            a.fuenteUbicacion = it
            if (it) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            pintar()
        }
        estadoFuentes = nota("")
        nota("Para las rutinas «al llegar a un sitio», Android exige además permitir la ubicación «todo el tiempo»: Ajustes → Aplicaciones → JARVIS → Permisos → Ubicación.")

        titulo("Diario de acciones")
        nota("Todo lo que JARVIS ha hecho y los permisos que ha pedido, lo más reciente arriba.")
        diario = Estilo.texto(c, "", 11f, Estilo.TENUE).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(diario)

        val raiz = ScrollView(c).apply { setBackgroundColor(Estilo.FONDO); addView(col) }
        Estilo.respetarBordes(raiz)
        setContentView(raiz)
    }

    override fun onResume() { super.onResume(); pintar() }
    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) = pintar()

    private fun pintar() {
        estadoClave.text = if (a.hayClave) "✓ Hay una clave guardada." else "No hay ninguna clave guardada."
        estadoClave.setTextColor(if (a.hayClave) Estilo.MENTA else Estilo.AMBAR)
        estadoAcc.text = if (ServicioAccesibilidad.activo) "✓ Accesibilidad activada: JARVIS puede manejar el teléfono." else "Accesibilidad APAGADA: JARVIS solo puede conversar."
        estadoAcc.setTextColor(if (ServicioAccesibilidad.activo) Estilo.MENTA else Estilo.AMBAR)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        estadoMic.text = if (mic) "✓ Micrófono permitido." else "Micrófono sin permiso: solo podrás escribirle."
        estadoMic.setTextColor(if (mic) Estilo.MENTA else Estilo.AMBAR)
        val galeria = Sesion.estudio.puedeVerGaleria()
        estadoGaleria.text = if (galeria) "✓ JARVIS puede ver tus videos de la galería." else "JARVIS no ve tu galería: solo los videos que le adjuntes."
        estadoGaleria.setTextColor(if (galeria) Estilo.MENTA else Estilo.TENUE)
        estadoVoz.text = if (a.hayClaveVoz) "✓ Hay una clave de ElevenLabs guardada." else "Sin clave de ElevenLabs: JARVIS no oye los videos, solo los ve."
        estadoVoz.setTextColor(if (a.hayClaveVoz) Estilo.MENTA else Estilo.TENUE)
        puentes.removeAllViews()
        for (p in a.puentes) puentes.addView(Estilo.boton(this, "${p.nombre} · ${runCatching { java.net.URL(p.url).host }.getOrDefault("")}  ✕") {
            android.app.AlertDialog.Builder(this).setMessage("¿Quitar el puente «${p.nombre}»?")
                .setPositiveButton("Quitar") { _, _ -> a.puentes = a.puentes.filter { it.nombre != p.nombre }; Sesion.reconfigurar(); pintar() }
                .setNegativeButton("No", null).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(this@AjustesActivity, 8) })
        val f = Sesion.fuentes
        val puedeAvisar = com.radar.jarvis.android.Avisador.puede(this)
        estadoAvisar.text = if (puedeAvisar) "✓ JARVIS puede avisarte de lo que hace por iniciativa." else "JARVIS no puede ponerte notificaciones: no verás lo que hace por iniciativa si no abres la app."
        estadoAvisar.setTextColor(if (puedeAvisar) Estilo.MENTA else Estilo.AMBAR)
        estadoAvisos.text = if (!a.fuenteAvisos) "Apagado." else if (f.puedeAvisos()) "✓ JARVIS ve tus notificaciones." else "Encendido, pero falta darle el acceso a notificaciones."
        estadoAvisos.setTextColor(if (!a.fuenteAvisos) Estilo.TENUE else if (f.puedeAvisos()) Estilo.MENTA else Estilo.AMBAR)
        val faltan = buildList {
            if (a.fuenteAgenda && !f.puedeAgenda()) add("agenda")
            if (a.fuenteContactos && !f.puedeContactos()) add("contactos")
            if (a.fuenteUbicacion && !f.puedeUbicacion()) add("ubicación")
        }
        estadoFuentes.text = if (faltan.isEmpty()) "" else "Falta el permiso de Android para: ${faltan.joinToString()}. Apaga y enciende el interruptor para pedirlo otra vez."
        estadoFuentes.setTextColor(Estilo.AMBAR)
        diario.text = a.diario().ifEmpty { "Todavía no ha hecho nada." }
    }
}
