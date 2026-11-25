package com.charruamotors.sistema.atualizacao

import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/** Arquivo anexado à release (o `.apk` de instalação). */
data class AnexoRelease(val nome: String, val url: String, val tamanhoBytes: Long)

/** Release publicada no GitHub (pelo redirect de `/releases/latest` ou pela API). */
data class ReleaseGithub(
    val tag: String,
    val versao: VersaoSemantica,
    val titulo: String,
    val notas: String,
    val urlPagina: String,
    val apk: AnexoRelease?,
) {
    /** `v1.6`. */
    val versaoTexto: String get() = "v${versao.curta}"
}

/** O que a consulta à última release concluiu. */
sealed interface ResultadoVerificacao {
    /** O app já está na última versão publicada (ou numa mais nova, como um build de desenvolvimento). */
    data class Atualizado(val versaoAtual: String, val ultima: ReleaseGithub) : ResultadoVerificacao

    /** Há uma versão mais nova que a instalada. */
    data class NovaVersao(val versaoAtual: String, val release: ReleaseGithub) : ResultadoVerificacao

    /**
     * `404`/`401`: o repositório é privado (a API não o enxerga sem login) ou ainda não tem
     * release publicada. A página de Releases no navegador (com a conta logada) resolve.
     */
    data class Indisponivel(val codigoHttp: Int) : ResultadoVerificacao

    /** Sem internet (DNS/conexão recusada/timeout). */
    data object SemInternet : ResultadoVerificacao

    /** Outro erro (HTTP 403 de limite da API, 5xx, JSON inesperado...). */
    data class Falha(val mensagem: String) : ResultadoVerificacao
}

/**
 * Interpreta a resposta da API de Releases do GitHub. Objeto puro (só `org.json`), testável na JVM.
 */
object InterpretadorReleaseGithub {

    /**
     * [codigoHttp] e [corpo] da resposta a `/releases/latest`, comparados com o `versionName`
     * instalado ([versaoAtual]).
     */
    fun interpretar(codigoHttp: Int, corpo: String?, versaoAtual: String): ResultadoVerificacao = when (codigoHttp) {
        in 200..299 -> deCorpo(corpo, versaoAtual)
        401, 404 -> ResultadoVerificacao.Indisponivel(codigoHttp)
        403, 429 -> ResultadoVerificacao.Falha("o GitHub limitou as consultas (HTTP $codigoHttp); tente de novo mais tarde")
        else -> ResultadoVerificacao.Falha("o GitHub respondeu HTTP $codigoHttp")
    }

    private fun deCorpo(corpo: String?, versaoAtual: String): ResultadoVerificacao {
        val release = try {
            lerRelease(corpo.orEmpty())
        } catch (e: JSONException) {
            return ResultadoVerificacao.Falha("resposta inesperada da API do GitHub")
        } ?: return ResultadoVerificacao.Falha("a release não tem uma tag de versão (ex.: v1.6)")
        return comparar(release, versaoAtual)
    }

    /**
     * Campos usados: `tag_name`, `name`, `body`, `html_url` e `assets[].{name, browser_download_url, size}`.
     * O APK escolhido é o primeiro `.apk` com "release" no nome (ou o primeiro `.apk`).
     * @return null se a tag não for uma versão.
     * @throws JSONException se o corpo não for o JSON de uma release.
     */
    fun lerRelease(json: String): ReleaseGithub? {
        val o = JSONObject(json)
        val tag = o.getString("tag_name").trim()
        val versao = VersaoSemantica.deTexto(tag) ?: return null
        val anexos = o.optJSONArray("assets")
        val apks = (0 until (anexos?.length() ?: 0)).map { anexos!!.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("browser_download_url").startsWith("https://") }
            .map { AnexoRelease(it.getString("name"), it.getString("browser_download_url"), it.optLong("size", -1L)) }
        return ReleaseGithub(
            tag = tag,
            versao = versao,
            titulo = o.optString("name").takeUnless { o.isNull("name") }?.trim().orEmpty().ifEmpty { tag },
            notas = o.optString("body").takeUnless { o.isNull("body") }?.trim().orEmpty(),
            urlPagina = o.optString("html_url").ifEmpty { URL_RELEASES },
            apk = apks.firstOrNull { "release" in it.nome.lowercase() } ?: apks.firstOrNull(),
        )
    }

    /**
     * Notas para o diálogo: sem os comentários HTML e os marcadores de título do Markdown,
     * cortadas em [maximo] caracteres.
     */
    fun resumirNotas(notas: String, maximo: Int = 1_200): String {
        val limpo = notas
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.trimEnd().replace(Regex("^#{1,6}\\s*"), "").replace("**", "") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (limpo.length <= maximo) limpo else limpo.take(maximo).trimEnd() + "…"
    }

    // ---------------------------------------------------------------- redirect da página (v2.3.1)

    /**
     * Tag no endereço para onde `github.com/{dono}/{repo}/releases/latest` redireciona
     * (`.../releases/tag/v2.3.1`); null em qualquer outro endereço (portal cativo, `/releases` sem
     * release publicada...).
     */
    fun tagDoRedirecionamento(url: String): String? {
        val bruta = REGEX_URL_TAG.find(url.trim())?.groupValues?.get(1) ?: return null
        return runCatching { URLDecoder.decode(bruta, "UTF-8") }.getOrNull()
    }

    /**
     * Release montada só com a [tag] (sem a API): o APK pelo endereço direto
     * `releases/download/vX.Y.Z/CharruaOBDPro-X.Y.Z-release.apk`, sem notas nem tamanho.
     * Null se a tag não for uma versão.
     */
    fun releaseDaTag(tag: String): ReleaseGithub? {
        val versao = VersaoSemantica.deTexto(tag) ?: return null
        val nome = "CharruaOBDPro-${tag.removePrefix("v").removePrefix("V")}-release.apk"
        return ReleaseGithub(
            tag = tag,
            versao = versao,
            titulo = tag,
            notas = "",
            urlPagina = "$URL_RELEASES/tag/$tag",
            apk = AnexoRelease(nome, "$URL_RELEASES/download/$tag/$nome", -1L),
        )
    }

    /** O [resultado] de comparar a [release] com a versão instalada. */
    fun comparar(release: ReleaseGithub, versaoAtual: String): ResultadoVerificacao {
        val atual = VersaoSemantica.deTexto(versaoAtual)
        return if (atual == null || release.versao > atual) {
            ResultadoVerificacao.NovaVersao(versaoAtual, release)
        } else {
            ResultadoVerificacao.Atualizado(versaoAtual, release)
        }
    }

    private val REGEX_URL_TAG = Regex("^https://github\\.com/[^/]+/[^/]+/releases/tag/([^/?#]+)")

    const val DONO = "giulianolagni"
    const val REPOSITORIO = "charruamotors-pro"
    const val URL_API_ULTIMA = "https://api.github.com/repos/$DONO/$REPOSITORIO/releases/latest"
    const val URL_RELEASES = "https://github.com/$DONO/$REPOSITORIO/releases"
    const val URL_PAGINA_ULTIMA = "$URL_RELEASES/latest"
}
