package com.charruamotors.sistema.atualizacao

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class InterpretadorReleaseGithubTest {

    /** Trecho real (reduzido) de `GET /repos/{dono}/{repo}/releases/latest`. */
    private fun json(tag: String = "v1.6", assets: String = APK_ASSET, body: String = "\"## Novidades\\n\\n- Atualizador automático\\n- Sensores SW4\"") = """
        {
          "url": "https://api.github.com/repos/giulianolagni/charruamotors-pro/releases/1",
          "html_url": "https://github.com/giulianolagni/charruamotors-pro/releases/tag/$tag",
          "tag_name": "$tag",
          "name": "Charrua OBD Pro $tag",
          "draft": false,
          "prerelease": false,
          "body": $body,
          "assets": [$assets]
        }
    """.trimIndent()

    @Test
    fun `release mais nova com APK anexado vira NovaVersao`() {
        val r = InterpretadorReleaseGithub.interpretar(200, json(), "1.5")
        assertTrue(r is ResultadoVerificacao.NovaVersao)
        val release = (r as ResultadoVerificacao.NovaVersao).release
        assertEquals("v1.6", release.tag)
        assertEquals(VersaoSemantica(1, 6), release.versao)
        assertEquals("v1.6", release.versaoTexto)
        assertEquals("Charrua OBD Pro v1.6", release.titulo)
        assertTrue(release.notas.contains("Sensores SW4"))
        assertEquals("https://github.com/giulianolagni/charruamotors-pro/releases/tag/v1.6", release.urlPagina)
        val apk = release.apk!!
        assertEquals("CharruaOBDPro-1.6-release.apk", apk.nome)
        assertEquals(
            "https://github.com/giulianolagni/charruamotors-pro/releases/download/v1.6/CharruaOBDPro-1.6-release.apk",
            apk.url,
        )
        assertEquals(7_340_032L, apk.tamanhoBytes)
        assertEquals("1.5", r.versaoAtual)
    }

    @Test
    fun `mesma versao ou mais antiga vira Atualizado`() {
        val igual = InterpretadorReleaseGithub.interpretar(200, json("v1.5"), "1.5")
        assertTrue(igual is ResultadoVerificacao.Atualizado)
        assertEquals("v1.5", (igual as ResultadoVerificacao.Atualizado).ultima.versaoTexto)
        assertTrue(InterpretadorReleaseGithub.interpretar(200, json("v1.4"), "1.5") is ResultadoVerificacao.Atualizado)
        // Patch novo conta como atualização.
        assertTrue(InterpretadorReleaseGithub.interpretar(200, json("v1.5.1"), "1.5") is ResultadoVerificacao.NovaVersao)
    }

    @Test
    fun `404 e 401 de repositorio privado viram Indisponivel com o codigo`() {
        val corpo404 = """{"message":"Not Found","documentation_url":"https://docs.github.com/rest/releases/releases#get-the-latest-release","status":"404"}"""
        val r404 = InterpretadorReleaseGithub.interpretar(404, corpo404, "1.5")
        assertEquals(ResultadoVerificacao.Indisponivel(404), r404)
        val r401 = InterpretadorReleaseGithub.interpretar(401, """{"message":"Bad credentials"}""", "1.5")
        assertEquals(ResultadoVerificacao.Indisponivel(401), r401)
        assertEquals(
            "https://github.com/giulianolagni/charruamotors-pro/releases",
            InterpretadorReleaseGithub.URL_RELEASES,
        )
        assertEquals(
            "https://api.github.com/repos/giulianolagni/charruamotors-pro/releases/latest",
            InterpretadorReleaseGithub.URL_API_ULTIMA,
        )
    }

    @Test
    fun `limite da API, erro do servidor e JSON quebrado viram Falha`() {
        val limite = InterpretadorReleaseGithub.interpretar(403, """{"message":"API rate limit exceeded"}""", "1.5")
        assertTrue((limite as ResultadoVerificacao.Falha).mensagem.contains("403"))
        assertTrue(InterpretadorReleaseGithub.interpretar(502, null, "1.5") is ResultadoVerificacao.Falha)
        assertTrue(InterpretadorReleaseGithub.interpretar(200, "<html>proxy</html>", "1.5") is ResultadoVerificacao.Falha)
        assertTrue(InterpretadorReleaseGithub.interpretar(200, """{"name":"sem tag"}""", "1.5") is ResultadoVerificacao.Falha)
        assertTrue(InterpretadorReleaseGithub.interpretar(200, json("nightly"), "1.5") is ResultadoVerificacao.Falha)
    }

    @Test
    fun `sem APK anexado a release continua valida para abrir no GitHub`() {
        val r = InterpretadorReleaseGithub.interpretar(200, json(assets = ""), "1.5") as ResultadoVerificacao.NovaVersao
        assertNull(r.release.apk)
        val soZip = """{"name":"fonte.zip","browser_download_url":"https://github.com/x/fonte.zip","size":10}"""
        assertNull(InterpretadorReleaseGithub.lerRelease(json(assets = soZip))!!.apk)
    }

    @Test
    fun `entre varios anexos prefere o APK de release`() {
        val debug = """{"name":"CharruaOBDPro-1.6-debug.apk","browser_download_url":"https://github.com/x/debug.apk","size":1}"""
        val release = InterpretadorReleaseGithub.lerRelease(json(assets = "$debug,$APK_ASSET"))!!
        assertEquals("CharruaOBDPro-1.6-release.apk", release.apk!!.nome)
        // URL sem https é ignorada (o download exige TLS).
        val inseguro = """{"name":"a.apk","browser_download_url":"http://exemplo/a.apk","size":1}"""
        assertNull(InterpretadorReleaseGithub.lerRelease(json(assets = inseguro))!!.apk)
    }

    @Test
    fun `body e name nulos nao quebram a leitura`() {
        val release = InterpretadorReleaseGithub.lerRelease(
            """{"tag_name":"v1.7","name":null,"body":null,"html_url":"https://github.com/r/v1.7","assets":[]}""",
        )!!
        assertEquals("v1.7", release.titulo)
        assertEquals("", release.notas)
    }

    @Test
    fun `notas resumidas sem marcadores de titulo e comentarios`() {
        val notas = "<!-- gerado -->\n## Novidades\n\n\n\n- **Atualizador**\n### Correções\n- bug"
        assertEquals("Novidades\n\n- Atualizador\nCorreções\n- bug", InterpretadorReleaseGithub.resumirNotas(notas))
        val longa = "x".repeat(2_000)
        val resumo = InterpretadorReleaseGithub.resumirNotas(longa, maximo = 100)
        assertEquals(101, resumo.length)
        assertTrue(resumo.endsWith("…"))
    }

    // ---------------------------------------------------------------- verificador (rede injetada)

    @Test
    fun `verificador consulta a URL da API e interpreta a resposta`() = runTest {
        val urls = mutableListOf<String>()
        val verificador = VerificadorAtualizacao("1.5", dispatcher = StandardTestDispatcher(testScheduler)) { url ->
            urls += url
            RespostaHttp(200, json("v1.6"))
        }
        assertTrue(verificador.verificar() is ResultadoVerificacao.NovaVersao)
        assertEquals(listOf(InterpretadorReleaseGithub.URL_API_ULTIMA), urls)
    }

    @Test
    fun `verificador sem internet nunca lanca excecao`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        fun com(erro: IOException) = VerificadorAtualizacao("1.5", dispatcher = d) { throw erro }
        assertEquals(ResultadoVerificacao.SemInternet, com(UnknownHostException("api.github.com")).verificar())
        assertEquals(ResultadoVerificacao.SemInternet, com(SocketTimeoutException("timeout")).verificar())
        val outra = com(IOException("conexão reiniciada")).verificar()
        assertTrue(outra is ResultadoVerificacao.Falha)
        assertFalse(VerificadorAtualizacao("1.5", dispatcher = d) { RespostaHttp(404, null) }.verificar() is ResultadoVerificacao.Falha)
    }

    private companion object {
        const val APK_ASSET = """{
            "name": "CharruaOBDPro-1.6-release.apk",
            "content_type": "application/vnd.android.package-archive",
            "size": 7340032,
            "browser_download_url": "https://github.com/giulianolagni/charruamotors-pro/releases/download/v1.6/CharruaOBDPro-1.6-release.apk"
        }"""
    }
}
