package com.charruamotors.sistema.atualizacao

import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** v2.3.1: atualizador pelo redirect de `/releases/latest` (sem o limite da API) e cache de 6 horas. */
class AtualizadorV231Test {

    private class Memoria : ArmazenamentoChaveValor {
        val mapa = mutableMapOf<String, Any>()
        override fun lerTexto(chave: String) = mapa[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean) = mapa[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) = valores.forEach { (c, v) -> if (v == null) mapa.remove(c) else mapa[c] = v }
    }

    private val pagina = InterpretadorReleaseGithub.URL_PAGINA_ULTIMA
    private val urlTag = "https://github.com/giulianolagni/charruamotors-pro/releases/tag/v2.4.0"
    private val urls = mutableListOf<String>()
    private var agora = 1_000_000L
    private val memoria = Memoria()

    private fun TestScope.verificador(cache: Boolean = false, buscar: (String) -> RespostaHttp) = VerificadorAtualizacao(
        "2.3.1",
        dispatcher = StandardTestDispatcher(testScheduler),
        cache = if (cache) CacheVerificacao(memoria) { agora } else null,
    ) { url ->
        urls += url
        buscar(url)
    }

    private fun redirect(location: String) = RespostaHttp(302, null, location)

    @Test
    fun `redirect da pagina vira a URL direta do APK sem tocar na API`() = runTest {
        val r = verificador { redirect(urlTag) }.verificar() as ResultadoVerificacao.NovaVersao

        assertEquals(listOf(pagina), urls)
        assertEquals("v2.4.0", r.release.tag)
        assertEquals("CharruaOBDPro-2.4.0-release.apk", r.release.apk!!.nome)
        assertEquals(
            "https://github.com/giulianolagni/charruamotors-pro/releases/download/v2.4.0/CharruaOBDPro-2.4.0-release.apk",
            r.release.apk!!.url,
        )
    }

    @Test
    fun `segue redirects intermediarios e relativos ate a tag`() = runTest {
        val outro = "https://github.com/outro/repo/releases/latest"
        val r = verificador { url -> if (url == pagina) redirect(outro) else redirect("/outro/repo/releases/tag/v2.3.1") }.verificar()

        assertTrue(r is ResultadoVerificacao.Atualizado)
        assertEquals(listOf(pagina, outro), urls)
    }

    @Test
    fun `sem tag no redirect ou com erro na pagina cai na API`() = runTest {
        val api = InterpretadorReleaseGithub.URL_API_ULTIMA
        val semRelease = verificador { url ->
            when (url) {
                pagina -> redirect("https://github.com/giulianolagni/charruamotors-pro/releases")
                api -> RespostaHttp(403, "{}")
                else -> RespostaHttp(200, null)
            }
        }.verificar()

        assertTrue((semRelease as ResultadoVerificacao.Falha).mensagem.contains("403"))
        assertEquals(api, urls.last())
        assertEquals(ResultadoVerificacao.SemInternet, verificador { throw UnknownHostException() }.verificar())
    }

    @Test
    fun `tag so e aceita em endereco do github`() {
        assertEquals("v2.4.0", InterpretadorReleaseGithub.tagDoRedirecionamento(urlTag))
        assertNull(InterpretadorReleaseGithub.tagDoRedirecionamento("https://portal.exemplo/releases/tag/v9.9"))
        assertNull(InterpretadorReleaseGithub.releaseDaTag("nightly"))
    }

    @Test
    fun `automatica usa o cache por 6 horas e a manual ignora`() = runTest {
        val v = verificador(cache = true) { redirect(urlTag) }
        v.verificar(forcar = false)
        assertEquals(1, urls.size)

        agora += CacheVerificacao.VALIDADE_MS - 1
        val doCache = v.verificar(forcar = false) as ResultadoVerificacao.NovaVersao
        assertEquals(1, urls.size)
        assertEquals("CharruaOBDPro-2.4.0-release.apk", doCache.release.apk!!.nome)

        v.verificar(forcar = true)
        assertEquals(2, urls.size)

        agora += CacheVerificacao.VALIDADE_MS
        v.verificar(forcar = false)
        assertEquals(3, urls.size)
    }

    @Test
    fun `falha nao entra no cache`() = runTest {
        val v = verificador(cache = true) { RespostaHttp(500, null) }
        v.verificar(forcar = false)
        v.verificar(forcar = false)

        assertTrue(memoria.mapa.isEmpty())
        assertEquals(4, urls.size) // página + API, duas vezes
    }

    @Test
    fun `agente de usuario generico`() {
        assertEquals("CharruaOBDPro", VerificadorAtualizacao.USER_AGENT)
    }
}
