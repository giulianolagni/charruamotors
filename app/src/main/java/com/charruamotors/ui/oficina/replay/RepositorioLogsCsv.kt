package com.charruamotors.ui.oficina.replay

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.charruamotors.oficina.replay.LeitorCsvLog
import com.charruamotors.oficina.replay.LogCsv
import com.charruamotors.ui.util.ExportadorCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Log CSV disponível para o replay. */
data class ArquivoLogCsv(val uri: Uri, val nome: String, val tamanhoBytes: Long, val modificadoMs: Long)

/**
 * Encontra e lê os logs do Data Logger onde o [ExportadorCsv] os salva:
 * - Android 10+: `Downloads/CharruaOBD` pelo `MediaStore` (sem permissão: o app vê os arquivos que
 *   ele mesmo criou, inclusive depois de uma atualização por cima);
 * - Android 8/9: a pasta `Download` do app (e a interna `logs`, o fallback do exportador).
 * Qualquer outro CSV entra pelo seletor do sistema (Storage Access Framework).
 */
class RepositorioLogsCsv(context: Context) {

    private val contexto = context.applicationContext

    suspend fun listar(): List<ArquivoLogCsv> = withContext(Dispatchers.IO) {
        val encontrados = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listarMediaStore() else emptyList()
        (encontrados + listarPastasDoApp()).distinctBy { it.nome to it.tamanhoBytes }.sortedByDescending { it.modificadoMs }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listarMediaStore(): List<ArquivoLogCsv> {
        val colunas = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        val selecao = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val argumentos = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${ExportadorCsv.PASTA}%", "%.csv")
        val lista = mutableListOf<ArquivoLogCsv>()
        runCatching {
            contexto.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, colunas, selecao, argumentos, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nome = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val tamanho = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val data = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (c.moveToNext()) {
                    lista += ArquivoLogCsv(
                        uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(id)),
                        nome = c.getString(nome) ?: "log.csv",
                        tamanhoBytes = c.getLong(tamanho),
                        modificadoMs = c.getLong(data) * 1000, // DATE_MODIFIED vem em segundos
                    )
                }
            }
        }
        return lista
    }

    private fun listarPastasDoApp(): List<ArquivoLogCsv> =
        listOfNotNull(contexto.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), File(contexto.filesDir, "logs"))
            .flatMap { pasta -> pasta.listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }.orEmpty().toList() }
            .map { ArquivoLogCsv(Uri.fromFile(it), it.name, it.length(), it.lastModified()) }

    /** Nome de exibição de um documento escolhido pelo SAF (ou o último segmento da URI). */
    suspend fun nomeDe(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            contexto.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "log.csv"
    }

    /** Lê e interpreta o CSV (UTF-8, em streaming). */
    suspend fun abrir(uri: Uri): LogCsv = withContext(Dispatchers.IO) {
        val entrada = contexto.contentResolver.openInputStream(uri) ?: throw IOException("Arquivo indisponível")
        entrada.bufferedReader(Charsets.UTF_8).use { LeitorCsvLog.ler(it) }
    }
}
