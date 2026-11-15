package com.radar.jarvis.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.radar.jarvis.android.Sesion
import com.radar.jarvis.nucleo.Habilidad
import com.radar.jarvis.nucleo.Recuerdo
import com.radar.jarvis.nucleo.Rutina
import com.radar.jarvis.ui.Estilo.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La mente de JARVIS, a la vista: cómo le va, qué hace por iniciativa, qué sabe hacer y qué recuerda.
 * Todo se puede corregir o borrar desde aquí.
 */
class MenteActivity : Activity() {
    private lateinit var col: LinearLayout
    private val mente get() = Sesion.mente
    private val fecha = SimpleDateFormat("d MMM yyyy", Locale("es"))

    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 40)) }
        val raiz = ScrollView(this).apply { setBackgroundColor(Estilo.FONDO); addView(col) }
        Estilo.respetarBordes(raiz)
        setContentView(raiz)
    }

    override fun onResume() { super.onResume(); pintar() }

    private fun titulo(t: String) = col.addView(Estilo.texto(this, t, 11f, Estilo.CIAN, mono = true).apply { isAllCaps = true },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(this@MenteActivity, 26); bottomMargin = dp(this@MenteActivity, 8) })
    private fun nota(t: String, color: Int = Estilo.TENUE) =
        col.addView(Estilo.texto(this, t, 13f, color), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(this@MenteActivity, 8) })

    /** Una ficha que se puede tocar. */
    private fun ficha(principal: String, secundario: String, alTocar: () -> Unit) = col.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Estilo.caja(context); setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        addView(Estilo.texto(context, principal, 15f))
        if (secundario.isNotEmpty()) addView(Estilo.texto(context, secundario, 12f, Estilo.TENUE))
        setOnClickListener { alTocar() }
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(this@MenteActivity, 8) })

    private fun pintar() {
        col.removeAllViews()
        col.addView(Estilo.texto(this, "Mente", 24f))
        nota("Lo que JARVIS aprende solo. Toca cualquier cosa para verla, corregirla o borrarla.")

        // ---- cómo le va
        titulo("Cómo le va")
        val ahora = System.currentTimeMillis()
        val semana = mente.panel(ahora - 7 * 86_400_000L)
        val total = mente.panel(0)
        fun linea(p: com.radar.jarvis.nucleo.Panel) = if (p.tareas == 0) "sin tareas todavía" else
            "${p.tareas} tareas · ${p.logradas} logradas (${p.logradas * 100 / p.tareas} %) · te gustaron ${p.gustaron}, no ${p.noGustaron} · " +
                "${p.solas} por iniciativa · unos ${"%.2f".format(p.coste)} $ · ${p.msMedio / 1000} s de media"
        nota("Últimos 7 días: ${linea(semana)}", Estilo.TINTA)
        nota("Desde el principio: ${linea(total)}")
        nota("Hoy, por iniciativa: unos ${"%.2f".format(mente.gastoSoloHoy(ahora))} $ de un límite de ${"%.2f".format(Sesion.almacen.fusible)} $. " +
            "El coste es una estimación por arriba, con precios de lista.")

        // ---- rutinas
        titulo("Rutinas · lo que hace por iniciativa")
        val rutinas = mente.rutinas()
        if (rutinas.isEmpty()) nota("Todavía no hay ninguna. Las crea él cuando ve una costumbre, o se las pides: «cada mañana a las 8 resume mis notificaciones».")
        for (r in rutinas) ficha((if (r.activa) "" else "(apagada) ") + r.nombre,
            "${r.disparador.frase()} · ${r.nivel} · creada por ${if (r.creadaPor == "jarvis") "JARVIS" else "ti"} · ha saltado ${r.veces} veces") { verRutina(r) }

        // ---- habilidades
        titulo("Habilidades · lo que sabe hacer")
        val habs = mente.habilidades()
        if (habs.isEmpty()) nota("Todavía no hay ninguna. Las aprende de tareas que salen bien, o se las enseñas: «aprende a…».")
        for (h in habs) ficha((if (h.enRevision) "(en revisión) " else "") + h.nombre,
            "${h.cuando.ifEmpty { "sin condición" }} · ${h.origen} · salió bien ${h.exitos}, mal ${h.fallos}") { verHabilidad(h) }
        col.addView(Estilo.boton(this, "Importar habilidades de un archivo") { elegirArchivo() }, LinearLayout.LayoutParams(-1, -2))

        // ---- biblioteca
        titulo("Biblioteca · lo que ha estudiado")
        Sesion.almacen.estudios.actual()?.let { p ->
            val falta = p.capitulos.firstOrNull { it.estado == "pendiente" }
            nota(when (p.estado) {
                "en_curso" -> "Estudiando " + p.resumen() + (if (falta != null) ". Ahora: ${falta.numero}. ${falta.titulo}" else "") +
                    if (Sesion.estudiando) "." else ". En espera: sigue solo cuando haya conexión."
                else -> "Último estudio: ${p.resumen()}."
            }, if (p.estado == "en_curso") Estilo.MENTA else Estilo.TENUE)
            if (p.estado == "en_curso") col.addView(Estilo.boton(this, "Detener el estudio", tinta = Estilo.AMBAR, borde = Estilo.AMBAR) {
                confirmar("¿Detener el estudio de «${p.tomo}»? Lo ya escrito se queda.") { Sesion.almacen.estudios.detener(); Sesion.latido(); pintar() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(this@MenteActivity, 8) })
        }
        val tomos = Sesion.almacen.biblioteca.tomos()
        if (tomos.isEmpty()) nota("Todavía no ha estudiado nada. Pídeselo: «estudia física cuántica y escríbete un tomo».")
        for (t in tomos) ficha(t.titulo, "${t.capitulos.size} capítulos · ${t.capitulos.sumOf { it.letras } / 1000} mil letras · ${t.descripcion}") { verTomo(t) }

        val dudas = Sesion.almacen.biblioteca.pendientes()
        if (dudas.isNotEmpty()) {
            titulo("Biblioteca · datos por contrastar (${dudas.size})")
            nota("Datos que JARVIS escribió sin poder comprobarlos del todo. Pídele: «repasa los datos pendientes».")
            val titulos = tomos.associate { it.id to it.titulo }
            for (d in dudas.take(60)) ficha(d.dato, "«${titulos[d.tomo] ?: d.tomo}», capítulo ${d.capitulo}" + if (d.motivo.isNotEmpty()) " · ${d.motivo}" else "") {
                confirmar("¿Quitar este dato de la lista de pendientes?") { Sesion.almacen.biblioteca.verificar(d.id); pintar() }
            }
        }

        // ---- memoria
        val recuerdos = mente.recuerdos()
        for ((capa, nombre) in listOf("perfil" to "Memoria · quién eres", "hecho" to "Memoria · tu mundo", "leccion" to "Memoria · lecciones", "episodio" to "Memoria · lo que ha hecho")) {
            val l = recuerdos.filter { it.capa == capa }.sortedByDescending { it.fecha }
            titulo("$nombre (${l.size})")
            if (l.isEmpty()) nota("Nada todavía.")
            for (r in l.take(if (capa == "episodio") 40 else 200)) ficha(r.texto, "${fecha.format(Date(r.fecha))} · ${r.origen}") { verRecuerdo(r) }
        }
        if (recuerdos.isNotEmpty()) col.addView(Estilo.boton(this, "Borrar toda la memoria", tinta = Estilo.ROJO, borde = Estilo.ROJO) {
            confirmar("¿Borrar todo lo que recuerda? No se puede deshacer.") { mente.borrarMemoria(); pintar() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(this@MenteActivity, 12) })
    }

    private fun confirmar(pregunta: String, hacer: () -> Unit) =
        AlertDialog.Builder(this).setMessage(pregunta).setPositiveButton("Sí") { _, _ -> hacer() }.setNegativeButton("No", null).show()

    private fun verRecuerdo(r: Recuerdo) {
        val campo = EditText(this).apply { setText(r.texto); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        AlertDialog.Builder(this).setTitle("Recuerdo").setView(campo)
            .setPositiveButton("Guardar") { _, _ ->
                if (!mente.editarRecuerdo(r.id, campo.text.toString())) Toast.makeText(this, "No se guardó: está vacío o parece una contraseña.", Toast.LENGTH_LONG).show()
                pintar()
            }
            .setNeutralButton("Borrar") { _, _ -> mente.borrarRecuerdo(r.id); pintar() }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun verHabilidad(h: Habilidad) = AlertDialog.Builder(this).setTitle(h.nombre)
        .setMessage("Cuándo: ${h.cuando.ifEmpty { "—" }}\n\n${h.pasos}" + if (h.enRevision) "\n\nEstá en revisión porque ha fallado varias veces: JARVIS no la usa. Enséñasela de nuevo o bórrala." else "")
        .setNeutralButton("Borrar") { _, _ -> mente.borrarHabilidad(h.id); pintar() }.setNegativeButton("Cerrar", null).show()

    private fun verRutina(r: Rutina) = AlertDialog.Builder(this).setTitle(r.nombre)
        .setMessage("Cuándo: ${r.disparador.frase()}\nNivel: ${r.nivel}\n\nQué hace: ${r.encargo}\n\nEnviar, pagar, comprar, borrar, publicar o llamar sigue esperando tu toque, también aquí.")
        .setPositiveButton(if (r.activa) "Apagar" else "Encender") { _, _ -> mente.activarRutina(r.id, !r.activa); Sesion.programarDespertador(); pintar() }
        .setNeutralButton("Borrar") { _, _ -> mente.borrarRutina(r.id); Sesion.programarDespertador(); pintar() }
        .setNegativeButton("Cerrar", null).show()

    private fun verTomo(t: com.radar.jarvis.nucleo.Tomo) {
        val b = Sesion.almacen.biblioteca
        AlertDialog.Builder(this).setTitle(t.titulo)
            .setItems(t.capitulos.map { "${it.numero}. ${it.titulo}" }.toTypedArray()) { _, i ->
                val c = t.capitulos[i]
                AlertDialog.Builder(this).setTitle("${c.numero}. ${c.titulo}")
                    .setMessage((b.leer(t, c.numero) ?: "(vacío)") + if (c.fuentes.isNotEmpty()) "\n\nFuentes:\n" + c.fuentes.joinToString("\n") { "· $it" } else "\n\n(sin fuentes)")
                    .setNegativeButton("Cerrar", null).show()
            }
            .setNeutralButton("Borrar el tomo") { _, _ -> confirmar("¿Borrar «${t.titulo}» entero?") { b.borrar(t.id); pintar() } }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun elegirArchivo() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), 3)
        } catch (e: Exception) { Toast.makeText(this, "Este teléfono no tiene selector de archivos.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        val uri = datos?.data ?: return
        if (codigo != 3 || resultado != RESULT_OK) return
        val texto = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().take(200_000).toByteArray().decodeToString() } }.getOrNull().orEmpty()
        val n = mente.importar(texto)
        Toast.makeText(this, if (n == 0) "No encontré habilidades. Formato: «# Nombre», «cuando: …» y los pasos debajo." else "$n habilidades importadas.", Toast.LENGTH_LONG).show()
        pintar()
    }
}
