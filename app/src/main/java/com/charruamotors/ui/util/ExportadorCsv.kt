package com.charruamotors.ui.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer

/** Arquivo exportado: [uri] compartilhável e [local] legível para o usuário (ex.: "Downloads/CharruaOBD"). */
data class ArquivoExportado(val uri: Uri, val nome: String, val local: String)

/**
 * Salva arquivos de texto (CSV do Data Logger) onde o usuário encontra e pode compartilhar:
 * - Android 10+: pasta pública `Downloads/CharruaOBD` via `MediaStore` (sem permissão de armazenamento);
 * - Android 8/9: pasta `Download` do app no armazenamento externo, exposta por `FileProvider`
 *   (a pasta pública exigiria `WRITE_EXTERNAL_STORAGE`).
 */
class ExportadorCsv(context: Context) {

    private val contexto = context.applicationContext

    /**
     * Cria o arquivo [nome] e escreve o conteúdo em streaming via [escrever] (UTF-8), fora da main thread.
     * @throws IOException se o arquivo não puder ser criado ou gravado.
     */
    suspend fun salvar(nome: String, escrever: (Writer) -> Unit): ArquivoExportado = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) salvarEmDownloads(nome, escrever) else salvarNoApp(nome, escrever)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun salvarEmDownloads(nome: String, escrever: (Writer) -> Unit): ArquivoExportado {
        val resolver = contexto.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, MIME_CSV)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PASTA")
            put(MediaStore.Downloads.IS_PENDING, 1) // invisível para outros apps até terminar de gravar
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: throw IOException("Não foi possível criar o arquivo em Downloads")
        try {
            val saida = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo")
            saida.bufferedWriter(Charsets.UTF_8).use(escrever)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) } // não deixa arquivo pela metade
            throw if (e is IOException) e else IOException(e.message, e)
        }
        return ArquivoExportado(uri, nome, "${Environment.DIRECTORY_DOWNLOADS}/$PASTA")
    }

    private fun salvarNoApp(nome: String, escrever: (Writer) -> Unit): ArquivoExportado {
        val pasta = contexto.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(contexto.filesDir, "logs")
        if (!pasta.exists() && !pasta.mkdirs()) throw IOException("Não foi possível criar a pasta ${pasta.path}")
        val arquivo = File(pasta, nome)
        try {
            arquivo.bufferedWriter(Charsets.UTF_8).use(escrever)
        } catch (e: Exception) {
            arquivo.delete()
            throw if (e is IOException) e else IOException(e.message, e)
        }
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}$SUFIXO_AUTORIDADE", arquivo)
        return ArquivoExportado(uri, nome, pasta.path)
    }

    companion object {
        const val MIME_CSV = "text/csv"
        const val PASTA = "CharruaOBD"

        /** Autoridade do FileProvider declarado no manifesto (`${applicationId}.arquivos`). */
        const val SUFIXO_AUTORIDADE = ".arquivos"

        /** Intent de compartilhamento (e-mail, Drive, WhatsApp...) com permissão de leitura temporária. */
        fun intentCompartilhar(arquivo: ArquivoExportado, titulo: String, mime: String = MIME_CSV): Intent {
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, arquivo.uri)
                putExtra(Intent.EXTRA_SUBJECT, arquivo.nome)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(envio, titulo)
        }
    }
}
