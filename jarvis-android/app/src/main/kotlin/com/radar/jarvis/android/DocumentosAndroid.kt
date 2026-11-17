package com.radar.jarvis.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.radar.jarvis.nucleo.Documento
import com.radar.jarvis.nucleo.Documentos
import com.radar.jarvis.nucleo.ErrorManos
import java.io.ByteArrayOutputStream

/**
 * Los documentos que JARVIS puede leer: los que adjuntas con el botón + y los de la carpeta que elijas en Ajustes.
 * Solo esos: no busca por el resto del teléfono. Los PDF se leen con el lector de Android, página a página como imagen.
 */
class DocumentosAndroid(private val ctx: Context, private val almacen: Almacen) : Documentos {
    private data class Ficha(val doc: Documento, val uri: Uri)
    private val fichas = mutableMapOf<String, Ficha>()          // uri -> ficha, para que el id no cambie de una llamada a otra
    private var n = 0

    /** Guarda los documentos elegidos con el botón +. Devuelve cuántos sirven. */
    @Synchronized fun adjuntar(uris: List<Uri>): Int {
        val buenos = uris.filter { esDocumento(it) }
        for (u in buenos) runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (buenos.isNotEmpty()) almacen.docsAdjuntos = (buenos.map { it.toString() } + almacen.docsAdjuntos).distinct()
        return buenos.count { ficha(it, "adjunto") != null }
    }

    /** La carpeta elegida en Ajustes. */
    fun elegirCarpeta(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        almacen.carpetaDocs = uri.toString()
    }

    fun nombreCarpeta(): String = almacen.carpetaDocs.takeIf { it.isNotEmpty() }?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') ?: "elegida" } ?: ""

    fun esDocumento(u: Uri): Boolean = tipoDe(u).let { it == "application/pdf" || it.startsWith("text/") }

    private fun tipoDe(u: Uri): String = runCatching { ctx.contentResolver.getType(u) }.getOrNull().orEmpty().ifEmpty {
        val p = u.toString().lowercase()
        when { p.endsWith(".pdf") -> "application/pdf"; p.endsWith(".txt") || p.endsWith(".md") -> "text/plain"; else -> "" }
    }

    private fun nombreDe(uri: Uri): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "documento"

    @Synchronized private fun ficha(uri: Uri, origen: String): Ficha? {
        fichas[uri.toString()]?.let { return it }
        val tipo = tipoDe(uri)
        val paginas = if (tipo == "application/pdf") runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        }.getOrNull() ?: return null else if (tipo.startsWith("text/")) 0 else return null
        n++
        return Ficha(Documento("d$n", nombreDe(uri).take(80), if (paginas > 0 || tipo == "application/pdf") "pdf" else "texto", paginas, origen), uri)
            .also { fichas[uri.toString()] = it }
    }

    /** Los documentos de la carpeta (y de sus subcarpetas, un nivel), hasta 60. */
    private fun deCarpeta(): List<Uri> {
        val raiz = almacen.carpetaDocs.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) } ?: return emptyList()
        val salen = mutableListOf<Uri>()
        fun recorrer(padre: String, nivel: Int) {
            val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(raiz, padre)
            runCatching {
                ctx.contentResolver.query(hijos, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext() && salen.size < 60) {
                        val id = c.getString(0); val mime = c.getString(1).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (nivel < 1) recorrer(id, nivel + 1) }
                        else if (mime == "application/pdf" || mime.startsWith("text/")) salen += DocumentsContract.buildDocumentUriUsingTree(raiz, id)
                    }
                }
            }
        }
        runCatching { recorrer(DocumentsContract.getTreeDocumentId(raiz), 0) }
        return salen
    }

    override fun lista(): List<Documento> =
        (almacen.docsAdjuntos.map { Uri.parse(it) to "adjunto" } + deCarpeta().map { it to "carpeta" })
            .mapNotNull { (u, o) -> ficha(u, o)?.doc }.distinctBy { it.id }

    private fun uri(id: String): Uri {
        if (fichas.values.none { it.doc.id == id }) lista()
        return fichas.values.firstOrNull { it.doc.id == id }?.uri ?: throw ErrorManos("no existe el documento $id")
    }

    override fun texto(id: String): String? {
        val f = fichas.values.firstOrNull { it.doc.id == id } ?: run { uri(id); fichas.values.first { it.doc.id == id } }
        if (f.doc.tipo == "pdf") return null
        return ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().take(MAX_TEXTO).toByteArray().decodeToString() }
            ?: throw ErrorManos("no pude abrir «${f.doc.nombre}»")
    }

    override fun paginas(id: String, desde: Int, hasta: Int): List<ByteArray> {
        val u = uri(id)
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r") ?: throw ErrorManos("no pude abrir el documento")
        return pfd.use {
            PdfRenderer(it).use { pdf ->
                (desde..minOf(hasta, pdf.pageCount)).map { numero ->
                    pdf.openPage(numero - 1).use { pagina ->
                        val ancho = ANCHO
                        val alto = (ancho.toLong() * pagina.height / pagina.width.coerceAtLeast(1)).toInt().coerceIn(200, 2400)
                        val bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pagina.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 80, o); bmp.recycle() }.toByteArray()
                    }
                }
            }
        }
    }

    private companion object {
        const val ANCHO = 1240
        const val MAX_TEXTO = 400_000
    }
}
