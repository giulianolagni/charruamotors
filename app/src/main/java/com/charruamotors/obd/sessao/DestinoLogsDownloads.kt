package com.charruamotors.obd.sessao

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.charruamotors.obd.log.ArquivoLogAberto
import com.charruamotors.obd.log.ArquivoLogSalvo
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.ui.util.ExportadorCsv
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * Logs automáticos em `Downloads/CharruaOBD`, onde o Visualizador de Logs e o gerenciador de
 * arquivos os encontram:
 * - Android 10+: `MediaStore.Downloads` (sem permissão de armazenamento). O arquivo nasce já
 *   visível (sem `IS_PENDING`): se o celular reiniciar no meio da viagem, o CSV sincronizado até
 *   ali continua lá, em vez de ficar pendente e ser descartado pelo sistema.
 * - Android 8/9: pasta `Download` do app no armazenamento externo (exposta por `FileProvider`).
 */
class DestinoLogsDownloads(context: Context) : DestinoLogs {

    private val contexto = context.applicationContext

    override fun criar(nome: String, mime: String): ArquivoLogAberto =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) criarMediaStore(nome, mime) else criarNoApp(nome, mime)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun criarMediaStore(nome: String, mime: String): ArquivoLogAberto {
        val resolver = contexto.contentResolver
        val local = "${Environment.DIRECTORY_DOWNLOADS}/${ExportadorCsv.PASTA}"
        val valores = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, local)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
            ?: throw IOException("Não foi possível criar $nome em Downloads")
        val descritor = try {
            resolver.openFileDescriptor(uri, "w") ?: throw IOException("Não foi possível abrir $nome")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        // O MediaStore pode renomear em caso de conflito ("nome (1).csv").
        val nomeFinal = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: nome
        return Aberto(nomeFinal, mime, local, uri, descritor, FileOutputStream(descritor.fileDescriptor)) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private fun criarNoApp(nome: String, mime: String): ArquivoLogAberto {
        val pasta = contexto.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(contexto.filesDir, "logs")
        if (!pasta.exists() && !pasta.mkdirs()) throw IOException("Não foi possível criar a pasta ${pasta.path}")
        val arquivo = File(pasta, nome)
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}${ExportadorCsv.SUFIXO_AUTORIDADE}", arquivo)
        return Aberto(arquivo.name, mime, pasta.path, uri, null, FileOutputStream(arquivo)) { arquivo.delete() }
    }

    private class Aberto(
        override val nome: String,
        private val mime: String,
        private val local: String,
        private val uri: Uri,
        private val descritor: ParcelFileDescriptor?,
        private val saida: FileOutputStream,
        private val apagar: () -> Unit,
    ) : ArquivoLogAberto {

        override val writer: Writer = BufferedWriter(OutputStreamWriter(saida, Charsets.UTF_8), BUFFER)

        override fun sincronizar() {
            writer.flush()
            runCatching { saida.fd.sync() } // fsync: o trecho já gravado sobrevive a um desligamento brusco
        }

        override fun fechar(): ArquivoLogSalvo {
            try {
                sincronizar()
            } finally {
                runCatching { writer.close() }
                runCatching { descritor?.close() }
            }
            return ArquivoLogSalvo(nome, local, uri.toString(), mime)
        }

        override fun descartar() {
            runCatching { writer.close() }
            runCatching { descritor?.close() }
            apagar()
        }

        private companion object {
            const val BUFFER = 16 * 1024
        }
    }
}
