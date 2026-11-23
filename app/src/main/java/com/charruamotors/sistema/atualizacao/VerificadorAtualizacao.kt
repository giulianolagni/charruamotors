package com.charruamotors.sistema.atualizacao

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Resposta HTTP mínima (código e corpo), para a consulta ser injetável nos testes. */
data class RespostaHttp(val codigo: Int, val corpo: String?)

/**
 * Consulta a última GitHub Release do app (`/releases/latest`) em [Dispatchers.IO] e a compara
 * com o `versionName` instalado. Nunca lança exceção: sem internet, `404/401` (repositório
 * privado) ou qualquer erro viram um [ResultadoVerificacao].
 */
class VerificadorAtualizacao(
    private val versaoAtual: String,
    private val urlApi: String = InterpretadorReleaseGithub.URL_API_ULTIMA,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val buscar: (String) -> RespostaHttp = ::buscarHttp,
) {

    suspend fun verificar(): ResultadoVerificacao = withContext(dispatcher) {
        try {
            val resposta = buscar(urlApi)
            InterpretadorReleaseGithub.interpretar(resposta.codigo, resposta.corpo, versaoAtual)
        } catch (e: IOException) {
            if (e.semInternet()) ResultadoVerificacao.SemInternet else ResultadoVerificacao.Falha(e.message ?: "falha de rede")
        } catch (e: SecurityException) {
            ResultadoVerificacao.Falha("sem permissão de acesso à internet")
        }
    }

    companion object {
        private const val TIMEOUT_CONEXAO_MS = 8_000
        private const val TIMEOUT_LEITURA_MS = 10_000

        /** Agente genérico (sem dados do usuário), exigido pela API do GitHub. */
        private const val USER_AGENT = "CharruaOBDPro-Atualizador"

        private fun IOException.semInternet() =
            this is UnknownHostException || this is ConnectException || this is SocketTimeoutException || this is SSLException

        /** GET simples com os cabeçalhos recomendados pela API REST do GitHub. */
        fun buscarHttp(url: String): RespostaHttp {
            val conexao = URL(url).openConnection() as HttpURLConnection
            try {
                conexao.connectTimeout = TIMEOUT_CONEXAO_MS
                conexao.readTimeout = TIMEOUT_LEITURA_MS
                conexao.setRequestProperty("Accept", "application/vnd.github+json")
                conexao.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conexao.setRequestProperty("User-Agent", USER_AGENT)
                val codigo = conexao.responseCode
                val fluxo = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
                val corpo = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                return RespostaHttp(codigo, corpo)
            } finally {
                conexao.disconnect()
            }
        }

        /**
         * Baixa o APK de [url] para [destino] (via `.parcial`, renomeado só no fim), publicando
         * o progresso em [aoProgredir] (bytes baixados, total ou -1 se desconhecido). Segue os
         * redirecionamentos do GitHub para o armazenamento dos anexos. Cancelável entre blocos.
         * @throws IOException se o download falhar ou vier vazio.
         */
        suspend fun baixarApk(
            url: String,
            destino: File,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            aoProgredir: (Long, Long) -> Unit = { _, _ -> },
        ): File = withContext(dispatcher) {
            destino.parentFile?.mkdirs()
            val parcial = File(destino.parentFile, destino.name + ".parcial")
            var endereco = url
            var conexao: HttpURLConnection? = null
            try {
                // Redirecionamentos manuais: o HttpURLConnection não segue entre hosts diferentes em todo Android.
                for (tentativa in 0..MAX_REDIRECIONAMENTOS) {
                    val c = URL(endereco).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = TIMEOUT_CONEXAO_MS
                    c.readTimeout = TIMEOUT_LEITURA_MS * 3
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.setRequestProperty("Accept", "application/octet-stream")
                    val codigo = c.responseCode
                    if (codigo !in 300..399) {
                        if (codigo !in 200..299) {
                            c.disconnect()
                            throw IOException("download recusado (HTTP $codigo)")
                        }
                        conexao = c
                        break
                    }
                    val proximo = c.getHeaderField("Location")
                    c.disconnect()
                    if (proximo.isNullOrBlank() || !proximo.startsWith("https://")) throw IOException("redirecionamento inválido")
                    endereco = proximo
                }
                val c = conexao ?: throw IOException("redirecionamentos demais")
                val total = c.contentLengthLong
                var baixados = 0L
                c.inputStream.use { entrada ->
                    parcial.outputStream().use { saida ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        aoProgredir(0, total)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val lidos = entrada.read(buffer)
                            if (lidos < 0) break
                            saida.write(buffer, 0, lidos)
                            baixados += lidos
                            aoProgredir(baixados, total)
                        }
                    }
                }
                if (baixados == 0L || (total > 0 && baixados != total)) throw IOException("download incompleto ($baixados de $total bytes)")
                destino.delete()
                if (!parcial.renameTo(destino)) throw IOException("não foi possível salvar ${destino.name}")
                destino
            } finally {
                conexao?.disconnect()
                parcial.delete()
            }
        }

        private const val MAX_REDIRECIONAMENTOS = 5
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Estado da sessão do app (vida do processo): depois de "Depois", a verificação automática não
 * mostra mais o diálogo até o app ser reaberto do zero.
 */
object SessaoAtualizacao {
    @Volatile var adiadaNestaSessao: Boolean = false

    /** Evita dois diálogos de nova versão ao mesmo tempo (ex.: duas telas principais na pilha). */
    @Volatile var dialogoAberto: Boolean = false
}
