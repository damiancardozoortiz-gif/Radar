package com.radar.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.radar.jarvis.R
import com.radar.jarvis.android.Estado
import com.radar.jarvis.android.ServicioAccesibilidad
import com.radar.jarvis.android.Sesion
import com.radar.jarvis.ui.Estilo.dp

/** La pantalla de JARVIS: el orbe, lo que dice, los pasos de lo que hace y la barra para hablarle o escribirle. */
class MainActivity : Activity() {
    private lateinit var orbe: OrbView
    private lateinit var etiqueta: TextView
    private lateinit var subtitulo: TextView
    private lateinit var detalle: TextView
    private lateinit var aviso: TextView
    private lateinit var pasos: LinearLayout
    private lateinit var deslizador: ScrollView
    private lateinit var tarjeta: LinearLayout
    private lateinit var tarjetaAccion: TextView
    private lateinit var tarjetaMotivo: TextView
    private lateinit var campo: EditText
    private lateinit var btnPausa: TextView
    private lateinit var btnDetener: TextView
    private lateinit var btnVer: android.widget.Button
    private val alCambiar: () -> Unit = { pintar() }

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        val c = this
        val raiz = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Estilo.FONDO) }
        Estilo.respetarBordes(raiz)

        // ---- barra de arriba
        btnDetener = enlace("detener", Estilo.AMBAR) { Sesion.detener() }
        btnPausa = enlace("pausa", Estilo.ROJO) { Sesion.ponerPausa(!Sesion.pausa) }
        raiz.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 18), dp(c, 10), dp(c, 10), dp(c, 4))
            addView(Estilo.texto(c, "J.A.R.V.I.S.", 13f, Estilo.TINTA, mono = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(btnDetener); addView(btnPausa)
            addView(enlace("nueva", Estilo.TENUE) { Sesion.nuevaConversacion() })
            addView(enlace("ajustes", Estilo.CIAN) { startActivity(Intent(c, AjustesActivity::class.java)) })
        })

        // ---- lo que falta por configurar
        aviso = Estilo.texto(c, "", 13f, Estilo.AMBAR).apply {
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10)); background = Estilo.caja(c, Color.parseColor("#331A1204"), Color.parseColor("#88FFB84D"))
            setOnClickListener { startActivity(Intent(c, AjustesActivity::class.java)) }
        }
        raiz.addView(aviso, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 16), dp(c, 4), dp(c, 16), 0) })

        // ---- el orbe y lo que dice
        orbe = OrbView(c).apply { fuenteNivel = { Sesion.nivelVoz() }; setOnClickListener { alTocarOrbe() } }
        raiz.addView(orbe, LinearLayout.LayoutParams(-1, 0, 1.25f))
        etiqueta = Estilo.texto(c, "en espera", 11f, Estilo.CIAN, mono = true).apply { gravity = Gravity.CENTER; isAllCaps = true }
        raiz.addView(etiqueta, LinearLayout.LayoutParams(-1, -2))
        subtitulo = Estilo.texto(c, "", 19f).apply { gravity = Gravity.CENTER; setPadding(dp(c, 24), dp(c, 8), dp(c, 24), dp(c, 8)); setLineSpacing(0f, 1.15f) }
        raiz.addView(subtitulo, LinearLayout.LayoutParams(-1, -2))

        // ---- la tarjeta de permiso
        tarjetaAccion = Estilo.texto(c, "", 19f)
        tarjetaMotivo = Estilo.texto(c, "", 13f, Color.parseColor("#D8C7A6"))
        tarjeta = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14))
            background = Estilo.caja(c, Color.parseColor("#F21A1204"), Color.parseColor("#B3FFB84D"), 14)
            addView(Estilo.texto(c, "NECESITO TU SÍ", 11f, Estilo.AMBAR, mono = true))
            addView(tarjetaAccion, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 6) })
            addView(tarjetaMotivo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 4) })
            addView(LinearLayout(c).apply {
                setPadding(0, dp(c, 12), 0, 0)
                addView(Estilo.boton(c, "Aprobar", Estilo.AMBAR, Color.parseColor("#1A1000"), Estilo.AMBAR) { Sesion.decidir(true) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(c, 10) })
                addView(Estilo.boton(c, "Rechazar") { Sesion.decidir(false) }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(Estilo.texto(c, "Solo vale tu toque: decir «sí» no aprueba nada.", 11f, Estilo.TENUE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 8) })
        }
        raiz.addView(tarjeta, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 16), 0, dp(c, 16), dp(c, 8)) })

        // ---- los pasos y el detalle
        btnVer = Estilo.boton(c, "Ver el último video", android.graphics.Color.parseColor("#225EFFC4"), Estilo.MENTA, Estilo.MENTA) { verVideo() }
        pasos = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        detalle = Estilo.texto(c, "", 14f, Color.parseColor("#B9CDD9")).apply { setTextIsSelectable(true); setPadding(0, dp(c, 8), 0, dp(c, 8)) }
        deslizador = ScrollView(c).apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(c, 20), 0, dp(c, 20), 0)
                addView(pasos); addView(detalle)
                addView(btnVer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 6); bottomMargin = dp(c, 12) })
            })
        }
        raiz.addView(deslizador, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- la barra de abajo
        campo = EditText(c).apply {
            hint = "Dime qué hago…"; setTextColor(Estilo.TINTA); setHintTextColor(Estilo.TENUE); textSize = 16f
            background = Estilo.caja(c, Color.parseColor("#D9060D16"), Estilo.LINEA, 24)
            setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12)); maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, accion, _ -> if (accion == EditorInfo.IME_ACTION_SEND) { mandar(); true } else false }
        }
        val mic = ImageButton(c).apply {
            setImageResource(R.drawable.ic_mic); contentDescription = "Hablar"
            background = Estilo.caja(c, Color.parseColor("#CC08121E"), Estilo.LINEA, 26)
            setOnClickListener { alTocarOrbe() }
        }
        val mas = Estilo.texto(c, "+", 26f, Estilo.CIAN).apply {
            gravity = Gravity.CENTER; contentDescription = "Adjuntar videos o música"
            background = Estilo.caja(c, Color.parseColor("#CC08121E"), Estilo.LINEA, 26)
            setOnClickListener { elegirArchivos() }
        }
        raiz.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 12))
            addView(mas, LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)).apply { marginEnd = dp(c, 8) })
            addView(mic, LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)).apply { marginEnd = dp(c, 8) })
            addView(campo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(c, 8) })
            addView(Estilo.boton(c, "Mandar", Color.parseColor("#225EE4FF"), Estilo.CIAN, Estilo.CIAN) { mandar() })
        })
        setContentView(raiz)
    }

    private fun enlace(t: String, color: Int, accion: () -> Unit) = Estilo.texto(this, t, 13f, color).apply {
        setPadding(dp(context, 10), dp(context, 12), dp(context, 10), dp(context, 12)); minWidth = dp(context, 48)
        gravity = Gravity.CENTER; setOnClickListener { accion() }
    }

    private fun mandar() {
        val t = campo.text.toString().trim()
        if (t.isEmpty()) return
        campo.setText("")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(campo.windowToken, 0)
        Sesion.enviar(t)
    }

    /** Abre el selector de archivos del teléfono para elegir videos y música. No hace falta ningún permiso. */
    private fun elegirArchivos() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 2)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Este teléfono no tiene selector de archivos.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        if (codigo != 2 || resultado != RESULT_OK || datos == null) return
        val uris = buildList {
            datos.clipData?.let { cd -> for (i in 0 until cd.itemCount) add(cd.getItemAt(i).uri) }
            datos.data?.let { if (it !in this) add(it) }
        }
        if (uris.isNotEmpty()) Sesion.adjuntar(uris)
    }

    private fun verVideo() {
        val uri = Sesion.estudio.ultimoMontaje ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Está en la galería, carpeta Películas/JARVIS.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun alTocarOrbe() {
        if (Sesion.estado == Estado.HABLANDO) return Sesion.detener("")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        Sesion.escucharVoz()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        if (codigo == 1 && resultados.firstOrNull() == PackageManager.PERMISSION_GRANTED) Sesion.escucharVoz()
    }

    override fun onResume() {
        super.onResume()
        Sesion.enPrimerPlano = true
        Sesion.observar(alCambiar)
        Sesion.avisar()
    }

    override fun onPause() {
        Sesion.enPrimerPlano = false
        Sesion.dejarDeObservar(alCambiar)
        Sesion.avisar()
        super.onPause()
    }

    private fun pintar() {
        val e = Sesion.estado
        orbe.estado = e
        etiqueta.text = e.etiqueta
        etiqueta.setTextColor(when (e) {
            Estado.ACTUANDO -> Estilo.MENTA; Estado.ESPERA_SI -> Estilo.AMBAR; Estado.PAUSA -> Estilo.ROJO
            Estado.PENSANDO -> Color.parseColor("#96AAFF"); else -> Estilo.CIAN
        })
        subtitulo.text = Sesion.subtitulo.ifEmpty { if (e == Estado.ESPERA) "Toca el orbe y háblame, o escríbeme abajo." else "" }
        btnPausa.text = if (Sesion.pausa) "quitar pausa" else "pausa"
        btnDetener.visibility = if (Sesion.ocupado) View.VISIBLE else View.GONE

        val faltan = buildList {
            if (!Sesion.almacen.hayClave) add("poner tu clave de la API de Claude")
            if (!ServicioAccesibilidad.activo) add("activar el permiso de accesibilidad para que pueda manejar el teléfono")
        }
        aviso.visibility = if (faltan.isEmpty()) View.GONE else View.VISIBLE
        aviso.text = "Falta " + faltan.joinToString(" y ") + ". Toca aquí."

        val p = Sesion.peticion
        tarjeta.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) {
            tarjetaAccion.text = p.accion
            tarjetaMotivo.text = "En ${p.donde.ifEmpty { "el teléfono" }} · ${p.motivo}." + if (p.detalle.isNotEmpty()) "\n${p.detalle}" else ""
        }

        pasos.removeAllViews()
        val copia = synchronized(Sesion.pasos) { Sesion.pasos.toList() }
        for (s in copia) {
            val (marca, color) = when (s.estado) {
                "en curso" -> "●" to Estilo.MENTA; "hecho" -> "✓" to Estilo.MENTA; "fallo" -> "✕" to Estilo.ROJO; else -> "·" to Estilo.TENUE
            }
            pasos.addView(LinearLayout(this).apply {
                setPadding(0, dp(context, 7), 0, dp(context, 7))
                addView(Estilo.texto(context, marca, 14f, color), LinearLayout.LayoutParams(dp(context, 22), -2))
                addView(Estilo.texto(context, s.texto + if (s.detalle.isNotEmpty()) "\n${s.detalle}" else "", 14f,
                    if (s.estado == "fallo") Estilo.ROJO else if (s.estado == "nota") Estilo.TENUE else Color.parseColor("#B9CDD9")),
                    LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        val hayVideo = Sesion.estudio.ultimoMontaje?.scheme == "content"
        btnVer.visibility = if (hayVideo && !Sesion.ocupado) View.VISIBLE else View.GONE
        detalle.text = Sesion.detalle
        detalle.visibility = if (Sesion.detalle.isEmpty()) View.GONE else View.VISIBLE
        if (Sesion.ocupado) deslizador.post { deslizador.fullScroll(View.FOCUS_DOWN) }
    }
}
