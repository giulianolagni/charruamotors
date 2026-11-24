package com.charruamotors.obd.log

import java.io.IOException
import java.io.Writer

/** Arquivo de log já fechado e visível para o usuário. */
data class ArquivoLogSalvo(
    val nome: String,
    /** Pasta legível para o usuário (ex.: `Download/CharruaOBD`). */
    val local: String,
    /** URI (`content://` ou `file://`) para abrir/compartilhar; no teste, só um identificador. */
    val uri: String,
    val mime: String,
)

/**
 * Arquivo de log aberto para escrita incremental. [sincronizar] descarrega o buffer e força a
 * gravação no armazenamento (fsync), para o trajeto sobreviver a uma queda brusca do app ou do
 * celular; [fechar] faz o mesmo e libera o arquivo.
 */
interface ArquivoLogAberto {
    val nome: String
    val writer: Writer

    @Throws(IOException::class)
    fun sincronizar()

    @Throws(IOException::class)
    fun fechar(): ArquivoLogSalvo

    /** Fecha e apaga (falha antes de haver conteúdo útil). Nunca lança. */
    fun descartar()
}

/**
 * Onde a sessão grava os logs automáticos. No app: `Downloads/CharruaOBD` (MediaStore no
 * Android 10+); nos testes JVM: memória.
 */
fun interface DestinoLogs {
    /** Cria o arquivo [nome] (tipo [mime]) já visível, aberto para escrita em streaming. */
    @Throws(IOException::class)
    fun criar(nome: String, mime: String): ArquivoLogAberto

    companion object {
        const val MIME_CSV = "text/csv"
        const val MIME_TXT = "text/plain"
    }
}
