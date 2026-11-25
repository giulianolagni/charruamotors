package com.charruamotors.sistema.atualizacao

import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor

/**
 * Última release encontrada, guardada por [VALIDADE_MS] para a verificação automática não consultar
 * o GitHub a cada abertura do app (v2.3.1). Só respostas com release são guardadas: uma falha tenta
 * de novo na próxima abertura. Puro (JVM).
 */
class CacheVerificacao(
    private val armazenamento: ArmazenamentoChaveValor,
    private val relogio: () -> Long = System::currentTimeMillis,
) {
    /** A release guardada, se ainda válida; null = vencida, ausente ou relógio atrasado. */
    fun ler(): ReleaseGithub? {
        val momento = armazenamento.lerTexto(MOMENTO)?.toLongOrNull() ?: return null
        if (relogio() - momento !in 0 until VALIDADE_MS) return null
        val tag = armazenamento.lerTexto(TAG) ?: return null
        val versao = VersaoSemantica.deTexto(tag) ?: return null
        val nome = armazenamento.lerTexto(APK_NOME)
        val url = armazenamento.lerTexto(APK_URL)
        return ReleaseGithub(
            tag = tag,
            versao = versao,
            titulo = armazenamento.lerTexto(TITULO) ?: tag,
            notas = armazenamento.lerTexto(NOTAS).orEmpty(),
            urlPagina = armazenamento.lerTexto(PAGINA) ?: InterpretadorReleaseGithub.URL_RELEASES,
            apk = if (nome != null && url != null) {
                AnexoRelease(nome, url, armazenamento.lerTexto(APK_TAMANHO)?.toLongOrNull() ?: -1L)
            } else {
                null
            },
        )
    }

    fun gravar(release: ReleaseGithub) = armazenamento.gravar(
        mapOf(
            MOMENTO to relogio().toString(),
            TAG to release.tag,
            TITULO to release.titulo,
            NOTAS to release.notas,
            PAGINA to release.urlPagina,
            APK_NOME to release.apk?.nome,
            APK_URL to release.apk?.url,
            APK_TAMANHO to release.apk?.tamanhoBytes?.toString(),
        ),
    )

    companion object {
        const val ARQUIVO = "atualizacao"
        const val VALIDADE_MS = 6L * 60 * 60 * 1000

        private const val MOMENTO = "momento"
        private const val TAG = "tag"
        private const val TITULO = "titulo"
        private const val NOTAS = "notas"
        private const val PAGINA = "pagina"
        private const val APK_NOME = "apk_nome"
        private const val APK_URL = "apk_url"
        private const val APK_TAMANHO = "apk_tamanho"
    }
}
