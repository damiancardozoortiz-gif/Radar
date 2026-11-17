package com.radar.jarvis.nucleo

/** Un documento con el que JARVIS puede trabajar: un PDF o un texto que la persona adjuntó o dejó en su carpeta. */
data class Documento(val id: String, val nombre: String, val tipo: String, val paginas: Int, val origen: String) {
    fun linea() = "$id · «$nombre» · " + (if (tipo == "pdf") "PDF de $paginas páginas" else "texto") + " · $origen"
}

/** Los documentos del teléfono. En la app los lee Android (los PDF, página a página como imagen); en las pruebas, un falso. */
interface Documentos {
    fun lista(): List<Documento>
    /** El texto de un documento de texto, o null si es un PDF. */
    fun texto(id: String): String?
    /** Las páginas [desde, hasta] (desde 1) de un PDF, como JPEG. */
    fun paginas(id: String, desde: Int, hasta: Int): List<ByteArray>
}
