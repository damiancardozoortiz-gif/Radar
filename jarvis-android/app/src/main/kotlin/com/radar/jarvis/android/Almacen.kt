package com.radar.jarvis.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.radar.jarvis.nucleo.Ajustes
import com.radar.jarvis.nucleo.Caja
import com.radar.jarvis.nucleo.Mente
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lo que JARVIS guarda en el teléfono.
 *
 * - La mente (memoria, habilidades, rutinas, evaluaciones): archivos en mente/. Entran en la copia de seguridad de
 *   Android, que los sube a tu Google Drive.
 * - Los ajustes: también entran en la copia.
 * - Las claves (Claude y ElevenLabs): cifradas con una llave del almacén seguro de Android, en un archivo aparte que
 *   NO entra en la copia. Solo se usan para hablar con api.anthropic.com y api.elevenlabs.io.
 */
class Almacen(ctx: Context) : Caja {
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val secretos = ctx.getSharedPreferences("jarvis_secretos", Context.MODE_PRIVATE)
    private val carpeta = File(ctx.filesDir, "mente").apply { mkdirs() }
    private val diario = File(ctx.filesDir, "registro.txt")

    val mente = Mente(this)
    val biblioteca = com.radar.jarvis.nucleo.Biblioteca(this)

    init {
        // versiones anteriores guardaban las claves junto a los ajustes: se mudan al archivo que no entra en la copia
        for (k in listOf("clave", "clave_voz")) prefs.getString(k, null)?.let { v ->
            secretos.edit().putString(k, v).apply(); prefs.edit().remove(k).apply()
        }
        // y la memoria era una lista suelta: pasa a ser recuerdos
        prefs.getString("memoria", null)?.let { vieja ->
            runCatching { JSONArray(vieja).let { a -> for (i in 0 until a.length()) mente.recordar("hecho", a.getString(i).substringAfter(": "), "versión anterior") } }
            prefs.edit().remove("memoria").apply()
        }
    }

    // ---------- la mente, en archivos ----------
    @Synchronized override fun leer(nombre: String): String = try { File(carpeta, "$nombre.json").readText() } catch (e: Exception) { "" }

    @Synchronized override fun escribir(nombre: String, texto: String) {
        try {
            val tmp = File(carpeta, "$nombre.json.tmp")
            tmp.writeText(texto)
            if (!tmp.renameTo(File(carpeta, "$nombre.json"))) File(carpeta, "$nombre.json").writeText(texto)   // se escribe aparte y se cambia: nunca queda a medias
        } catch (e: Exception) { /* sin sitio en el teléfono: la mente no se actualiza, pero nada se rompe */ }
    }

    // ---------- claves, cifradas ----------
    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun leerSecreto(nombre: String): String = try {
        val crudo = Base64.decode(secretos.getString(nombre, "") ?: "", Base64.NO_WRAP)
        if (crudo.size < 13) "" else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, crudo, 0, 12))
            String(c.doFinal(crudo, 12, crudo.size - 12), Charsets.UTF_8)
        }
    } catch (e: Exception) { "" }

    private fun guardarSecreto(nombre: String, v: String) {
        if (v.isBlank()) { secretos.edit().remove(nombre).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        secretos.edit().putString(nombre, Base64.encodeToString(c.iv + c.doFinal(v.trim().toByteArray()), Base64.NO_WRAP)).apply()
    }

    /** La clave de la API de Claude. */
    var clave: String
        get() = leerSecreto("clave")
        set(v) = guardarSecreto("clave", v)
    val hayClave get() = !secretos.getString("clave", "").isNullOrEmpty()

    /** La clave de ElevenLabs, opcional: para oír los videos y para narrar. */
    var claveVoz: String
        get() = leerSecreto("clave_voz")
        set(v) = guardarSecreto("clave_voz", v)
    val hayClaveVoz get() = !secretos.getString("clave_voz", "").isNullOrEmpty()

    /** Los puentes hacia otros servicios. Sus direcciones suelen llevar la credencial dentro: se guardan cifradas, como las claves. */
    var puentes: List<com.radar.jarvis.nucleo.PuenteConfig>
        get() = try {
            JSONArray(leerSecreto("puentes").ifBlank { "[]" }).let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { o -> com.radar.jarvis.nucleo.PuenteConfig(o.getString("nombre"), o.getString("url"), o.optString("token")) } }
            }
        } catch (e: Exception) { emptyList() }
        set(v) = guardarSecreto("puentes", if (v.isEmpty()) "" else JSONArray(v.map { org.json.JSONObject().put("nombre", it.nombre).put("url", it.url).put("token", it.token) }).toString())

    // ---------- ajustes ----------
    private fun texto(k: String, d: String) = prefs.getString(k, d)!!
    private fun si(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun pon(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()

    var modelo: String get() = texto("modelo", Ajustes.MODELO_POR_DEFECTO); set(v) = prefs.edit().putString("modelo", v).apply()
    var esfuerzo: String get() = texto("esfuerzo", "medium"); set(v) = prefs.edit().putString("esfuerzo", v).apply()
    var voz: Boolean get() = si("voz", true); set(v) = pon("voz", v)
    var web: Boolean get() = si("web", true); set(v) = pon("web", v)
    var quien: String get() = texto("quien", ""); set(v) = prefs.edit().putString("quien", v.trim()).apply()

    /** Autonomía. */
    var memoriaSola: Boolean get() = si("memoria_sola", true); set(v) = pon("memoria_sola", v)
    var rutinasSolas: Boolean get() = si("rutinas_solas", true); set(v) = pon("rutinas_solas", v)
    /** El fusible: dólares al día que pueden gastar las tareas que JARVIS lanza solo. Al llegar, se detiene y avisa. */
    var fusible: Float get() = prefs.getFloat("fusible", 5f); set(v) = prefs.edit().putFloat("fusible", v.coerceIn(0.5f, 500f)).apply()

    /** Lo que sabe de tu día. Todo apagado hasta que lo enciendas. */
    var fuenteAvisos: Boolean get() = si("fuente_avisos", false); set(v) = pon("fuente_avisos", v)
    var fuenteAgenda: Boolean get() = si("fuente_agenda", false); set(v) = pon("fuente_agenda", v)
    var fuenteContactos: Boolean get() = si("fuente_contactos", false); set(v) = pon("fuente_contactos", v)
    var fuenteUbicacion: Boolean get() = si("fuente_ubicacion", false); set(v) = pon("fuente_ubicacion", v)

    fun ajustes() = Ajustes(clave, modelo, esfuerzo, web, quien)

    // ---------- diario de acciones ----------
    @Synchronized fun apuntar(linea: String) {
        try {
            if (diario.length() > 400_000) diario.writeText(diario.readLines().takeLast(1500).joinToString("\n") + "\n")
            diario.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + linea.replace("\n", " ").take(300) + "\n")
        } catch (e: Exception) { /* el diario nunca debe parar una tarea */ }
    }

    fun diario(lineas: Int = 200): String = try { diario.readLines().takeLast(lineas).reversed().joinToString("\n") } catch (e: Exception) { "" }

    private companion object { const val ALIAS = "jarvis_clave_api" }
}
