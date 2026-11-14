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
    private lateinit var memoria: TextView
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

        titulo("Memoria")
        memoria = nota("")
        boton("Borrar la memoria") { a.borrarMemoria(); Sesion.reconfigurar(); pintar() }

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
        memoria.text = a.todo().joinToString("\n") { "· $it" }.ifEmpty { "Todavía no le has pedido que recuerde nada." }
        diario.text = a.diario().ifEmpty { "Todavía no ha hecho nada." }
    }
}
