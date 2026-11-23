package com.charruamotors.ui.oficina.replay

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.charruamotors.obd.log.ItemLog
import com.charruamotors.obd.log.OrganizadorLogs
import com.charruamotors.oficina.replay.LeitorCsvLog
import com.charruamotors.oficina.replay.LogCsv
import com.charruamotors.ui.util.ExportadorCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Log salvo disponível no visualizador: CSV de telemetria (replay) ou relatório `.txt` da sessão. */
data class ArquivoLogCsv(val uri: Uri, override val nome: String, val tamanhoBytes: Long, override val modificadoMs: Long) : ItemLog {
    /** Relatório de texto da caixa-preta (abre no diálogo, não no gráfico). */
    val texto: Boolean get() = nome.endsWith(".txt", ignoreCase = true)

    /** Gravado no Modo Simulado (`CharruaOBD_SIMULADO_...`). */
    val simulado: Boolean get() = OrganizadorLogs.ehSimulado(nome)
}

/** Trecho de um relatório `.txt` para exibir: [truncado] = o arquivo é maior que o lido. */
data class TextoLog(val conteudo: String, val truncado: Boolean)

/**
 * Encontra e lê os logs onde o [ExportadorCsv] e a sessão automática os salvam (`.csv` e `.txt`):
 * - Android 10+: `Downloads/CharruaOBD` pelo `MediaStore` (sem permissão: o app vê os arquivos que
 *   ele mesmo criou, inclusive depois de uma atualização por cima);
 * - Android 8/9: a pasta `Download` do app (e a interna `logs`, o fallback do exportador).
 * Qualquer outro CSV entra pelo seletor do sistema (Storage Access Framework).
 */
class RepositorioLogsCsv(context: Context) {

    private val contexto = context.applicationContext

    suspend fun listar(): List<ArquivoLogCsv> = withContext(Dispatchers.IO) {
        val encontrados = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listarMediaStore() else emptyList()
        // Mais recente (última viagem) no topo: horário do nome do arquivo, com a data de modificação de reserva.
        OrganizadorLogs.ordenar((encontrados + listarPastasDoApp()).distinctBy { it.nome to it.tamanhoBytes })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listarMediaStore(): List<ArquivoLogCsv> {
        val colunas = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        val selecao = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
            "(${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?)"
        val argumentos = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${ExportadorCsv.PASTA}%", "%.csv", "%.txt")
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
            .flatMap { pasta ->
                pasta.listFiles { f -> f.isFile && (f.name.endsWith(".csv", true) || f.name.endsWith(".txt", true)) }.orEmpty().toList()
            }
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

    /** Lê o começo do relatório `.txt` (o resumo vem primeiro; o log bruto pode ter megabytes). */
    suspend fun lerTexto(uri: Uri, limiteCaracteres: Int = LIMITE_TEXTO): TextoLog = withContext(Dispatchers.IO) {
        val entrada = contexto.contentResolver.openInputStream(uri) ?: throw IOException("Arquivo indisponível")
        entrada.bufferedReader(Charsets.UTF_8).use { leitor ->
            val buffer = CharArray(limiteCaracteres)
            var lidos = 0
            while (lidos < limiteCaracteres) {
                val n = leitor.read(buffer, lidos, limiteCaracteres - lidos)
                if (n < 0) break
                lidos += n
            }
            TextoLog(String(buffer, 0, lidos), truncado = lidos == limiteCaracteres && leitor.read() >= 0)
        }
    }

    /** URI que outro app pode ler (os `file://` das pastas do app passam pelo FileProvider). */
    fun uriCompartilhavel(arquivo: ArquivoLogCsv): Uri {
        val caminho = arquivo.uri.path
        if (arquivo.uri.scheme != "file" || caminho == null) return arquivo.uri
        return runCatching {
            FileProvider.getUriForFile(contexto, "${contexto.packageName}${ExportadorCsv.SUFIXO_AUTORIDADE}", File(caminho))
        }.getOrDefault(arquivo.uri)
    }

    companion object {
        /** ~200 mil caracteres no diálogo (o TextView fica lento com o log bruto de horas). */
        const val LIMITE_TEXTO = 200_000
    }
}
