package com.radar.jarvis.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.radar.jarvis.nucleo.Ajustes
import com.radar.jarvis.nucleo.Memoria
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
 * Lo que JARVIS guarda en el teléfono: los ajustes, la memoria y el diario de acciones.
 *
 * La clave de la API se guarda CIFRADA con una llave que vive en el almacén seguro de Android (Keystore) y no
 * puede salir del teléfono. Solo se usa para hablar con api.anthropic.com.
 */
class Almacen(ctx: Context) : Memoria {
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val diario = File(ctx.filesDir, "registro.txt")

    // ---------- clave de la API, cifrada ----------
    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    var clave: String
        get() = try {
            val crudo = Base64.decode(prefs.getString("clave", "") ?: "", Base64.NO_WRAP)
            if (crudo.size < 13) "" else {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, crudo, 0, 12))
                String(c.doFinal(crudo, 12, crudo.size - 12), Charsets.UTF_8)
            }
        } catch (e: Exception) { "" }
        set(v) {
            if (v.isBlank()) { prefs.edit().remove("clave").apply(); return }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, llave())
            prefs.edit().putString("clave", Base64.encodeToString(c.iv + c.doFinal(v.trim().toByteArray()), Base64.NO_WRAP)).apply()
        }

    val hayClave get() = !prefs.getString("clave", "").isNullOrEmpty()

    // ---------- ajustes ----------
    var modelo: String
        get() = prefs.getString("modelo", Ajustes.MODELO_POR_DEFECTO)!!
        set(v) = prefs.edit().putString("modelo", v).apply()
    var esfuerzo: String
        get() = prefs.getString("esfuerzo", "medium")!!
        set(v) = prefs.edit().putString("esfuerzo", v).apply()
    var voz: Boolean
        get() = prefs.getBoolean("voz", true)
        set(v) = prefs.edit().putBoolean("voz", v).apply()
    var web: Boolean
        get() = prefs.getBoolean("web", true)
        set(v) = prefs.edit().putBoolean("web", v).apply()
    var quien: String
        get() = prefs.getString("quien", "")!!
        set(v) = prefs.edit().putString("quien", v.trim()).apply()

    fun ajustes() = Ajustes(clave, modelo, esfuerzo, web, quien)

    // ---------- memoria ----------
    override fun todo(): List<String> = try {
        JSONArray(prefs.getString("memoria", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (e: Exception) { emptyList() }

    override fun guardar(texto: String) {
        val t = texto.trim().take(600)
        if (t.isEmpty()) return
        val dia = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit().putString("memoria", JSONArray(todo() + "$dia: $t").toString()).apply()
    }

    fun borrarMemoria() = prefs.edit().remove("memoria").apply()

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
