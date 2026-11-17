package com.radar.jarvis.nucleo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Un puente hacia un servicio externo: la dirección de su servidor MCP y, si la pide, su credencial. */
data class PuenteConfig(val nombre: String, val url: String, val token: String = "")

/** Una herramienta que ofrece un puente. `soloLectura`: consultar no cambia nada, así que no necesita tu sí. */
data class HerramientaPuente(
    val puente: String, val nombre: String, val alias: String, val descripcion: String,
    val esquema: Map<String, Any?>, val soloLectura: Boolean,
)

/**
 * Cliente mínimo del protocolo MCP (Model Context Protocol) sobre HTTP: el estándar con el que servicios como
 * Zapier, Notion o GitHub ofrecen sus herramientas a los asistentes.
 *
 * Las llamadas salen de ESTE teléfono, no de los servidores de Claude: así cada herramienta pasa por el freno
 * antes de ejecutarse. Solo habla https, salvo con servidores de este mismo teléfono (127.0.0.1 o localhost),
 * como el taller de Termux, cuyo tráfico no sale del teléfono.
 */
class ClienteMcp(private val cfg: PuenteConfig, private val permitirHttp: Boolean = false) {
    private var sesion: String? = null
    private var n = 0

    private fun rpc(metodo: String, params: JSONObject?, espera: Boolean = true): JSONObject? {
        if (!cfg.url.startsWith("https://") && !esLocal(cfg.url) && !(permitirHttp && cfg.url.startsWith("http://")))
            throw ErrorManos("la dirección del puente debe empezar por https:// (o ser http://127.0.0.1, este mismo teléfono)")
        val id = ++n
        val cuerpo = JSONObject().put("jsonrpc", "2.0").put("method", metodo)
        if (espera) cuerpo.put("id", id)
        if (params != null) cuerpo.put("params", params)
        val con = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", VERSION)
            if (cfg.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.token}")
            sesion?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            con.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            val codigo = con.responseCode
            con.getHeaderField("Mcp-Session-Id")?.let { sesion = it }
            if (codigo == 401 || codigo == 403) throw ErrorManos("el puente «${cfg.nombre}» rechazó la credencial (código $codigo)")
            if (codigo !in 200..299) throw ErrorManos("el puente «${cfg.nombre}» respondió con el código $codigo")
            if (!espera) return null
            val texto = con.inputStream.bufferedReader().readText()
            val tipo = con.contentType.orEmpty()
            val mensajes = if ("text/event-stream" in tipo)
                texto.lineSequence().filter { it.startsWith("data:") }.mapNotNull { runCatching { JSONObject(it.removePrefix("data:").trim()) }.getOrNull() }.toList()
            else listOf(JSONObject(texto))
            val m = mensajes.firstOrNull { it.optInt("id", -1) == id } ?: throw ErrorManos("el puente «${cfg.nombre}» no contestó a la petición")
            m.optJSONObject("error")?.let { throw ErrorManos("el puente «${cfg.nombre}» dio un error: ${it.optString("message").take(200)}") }
            return m.optJSONObject("result") ?: JSONObject()
        } catch (e: ErrorManos) {
            throw e
        } catch (e: Exception) {
            throw ErrorManos("no hubo conexión con el puente «${cfg.nombre}» (${e.javaClass.simpleName})")
        } finally { con.disconnect() }
    }

    /** Saluda al servidor y devuelve las herramientas que ofrece. */
    fun conectar(): List<HerramientaPuente> {
        sesion = null
        rpc("initialize", JSONObject().put("protocolVersion", VERSION).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "JARVIS").put("version", "2.1")))
        rpc("notifications/initialized", null, espera = false)
        val salida = mutableListOf<HerramientaPuente>()
        var cursor: String? = null
        do {
            val r = rpc("tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } })!!
            val a = r.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                val nombre = t.optString("name")
                if (nombre.isEmpty()) continue
                val soloLectura = t.optJSONObject("annotations")?.let { an ->
                    if (an.has("readOnlyHint")) an.optBoolean("readOnlyHint") else null
                } ?: Puentes.pareceLectura(nombre)
                salida += HerramientaPuente(cfg.nombre, nombre, Puentes.alias(cfg.nombre, nombre), t.optString("description").take(900),
                    Puentes.aMapa(t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object")), soloLectura)
            }
            cursor = r.optString("nextCursor").takeIf { it.isNotEmpty() }
        } while (cursor != null && salida.size < Puentes.MAX_POR_PUENTE)
        return salida.take(Puentes.MAX_POR_PUENTE)
    }

    /** Ejecuta una herramienta del puente. Devuelve (texto, esError). */
    fun llamar(nombre: String, args: Map<String, Any?>): Pair<String, Boolean> {
        if (sesion == null && n == 0) conectar()
        val r = rpc("tools/call", JSONObject().put("name", nombre).put("arguments", JSONObject(args)))!!
        val partes = r.optJSONArray("content")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> if (c.optString("type") == "text") c.optString("text") else "(${c.optString("type")} que no se puede mostrar)" } }
        } ?: emptyList()
        val texto = partes.joinToString("\n").ifBlank { r.optJSONObject("structuredContent")?.toString() ?: "(sin respuesta)" }
        return texto.take(Puentes.MAX_RESPUESTA) to r.optBoolean("isError", false)
    }

    companion object {
        private const val VERSION = "2025-06-18"
        /** ¿Es un servidor de este mismo teléfono por http? (el taller de Termux). Ninguna otra dirección puede ir sin cifrar. */
        fun esLocal(url: String): Boolean = Regex("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$").matches(url.trim())
    }
}

/** Todos los puentes que tiene JARVIS. Se conectan la primera vez que hacen falta y se recuerdan mientras la app vive. */
class Puentes(private val configs: List<PuenteConfig>, private val permitirHttp: Boolean = false) {
    private val clientes = configs.associate { it.nombre to ClienteMcp(it, permitirHttp) }
    private var herramientas: List<HerramientaPuente>? = null
    /** Los puentes que no se pudieron conectar, con el motivo: se le cuenta a la persona. */
    var fallos: List<String> = emptyList()
        private set

    val hay get() = configs.isNotEmpty()

    suspend fun herramientas(): List<HerramientaPuente> = herramientas ?: withContext(Dispatchers.IO) {
        val malos = mutableListOf<String>()
        val l = configs.flatMap { c ->
            try { clientes.getValue(c.nombre).conectar() } catch (e: ErrorManos) { malos += e.message.orEmpty(); emptyList() }
        }.take(MAX_TOTAL)
        fallos = malos
        if (malos.isEmpty()) herramientas = l          // si alguno falló, se vuelve a intentar en el próximo mensaje
        l
    }

    fun buscar(alias: String): HerramientaPuente? = herramientas?.firstOrNull { it.alias == alias }

    suspend fun llamar(h: HerramientaPuente, args: Map<String, Any?>): Pair<String, Boolean> =
        withContext(Dispatchers.IO) { clientes.getValue(h.puente).llamar(h.nombre, args) }

    companion object {
        const val MAX_POR_PUENTE = 40
        const val MAX_TOTAL = 80
        const val MAX_RESPUESTA = 12_000
        private val ESCRIBE = setOf("send", "create", "add", "update", "delete", "remove", "write", "post", "publish", "upload", "insert", "move", "archive",
            "reply", "forward", "invite", "pay", "buy", "book", "cancel", "set", "edit", "rename", "share", "merge", "close", "run", "execute", "trigger",
            "make", "new", "append", "replace", "patch", "put", "draft", "schedule", "assign", "mark", "star", "label", "copy", "import", "export", "sync",
            "enviar", "crear", "borrar", "eliminar", "actualizar", "publicar", "subir", "editar", "mover", "responder", "pagar", "comprar", "reservar")
        private val LEE = setOf("get", "list", "search", "find", "read", "fetch", "query", "lookup", "view", "show", "describe", "count", "check", "retrieve",
            "buscar", "leer", "listar", "ver", "consultar", "obtener")

        /**
         * Sin más datos, una herramienta solo se considera de lectura si alguna palabra de su nombre lo dice claro
         * y ninguna dice lo contrario. Ante la duda, pide permiso.
         */
        fun pareceLectura(nombre: String): Boolean {
            val palabras = nombre.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            return palabras.any { it in LEE } && palabras.none { it in ESCRIBE }
        }

        /** El nombre con el que el agente ve la herramienta: p_<puente>__<herramienta>, solo con letras, números y guiones. */
        fun alias(puente: String, herramienta: String): String {
            fun limpio(s: String) = Freno.norm(s).replace(Regex("[^a-z0-9_-]+"), "_").trim('_')
            return ("p_" + limpio(puente).take(16) + "__" + limpio(herramienta)).take(64)
        }

        fun aMapa(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { k -> aValor(o.opt(k)) }
        private fun aValor(v: Any?): Any? = when (v) {
            is JSONObject -> aMapa(v)
            is JSONArray -> (0 until v.length()).map { aValor(v.opt(it)) }
            JSONObject.NULL -> null
            else -> v
        }
    }
}
