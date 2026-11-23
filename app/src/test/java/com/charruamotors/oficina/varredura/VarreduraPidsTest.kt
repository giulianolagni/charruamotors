package com.charruamotors.oficina.varredura

import com.charruamotors.obd.ObdException
import com.charruamotors.obd.RespostaBruta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VarreduraPidsTest {

    private fun resposta(comando: String, bruto: String, semPrompt: Boolean = false) = RespostaBruta(comando, bruto, 40, semPrompt)

    // ---------------------------------------------------------------- gerador de faixas

    @Test
    fun `faixa 00 a FF do servico 21 gera 256 comandos em ordem`() {
        val comandos = VarreduraPids.comandos(ConfigVarredura("7E0", "21", 0x00, 0xFF))
        assertEquals(256, comandos.size)
        assertEquals("2100", comandos.first())
        assertEquals("2101", comandos[1])
        assertEquals("21FF", comandos.last())
    }

    @Test
    fun `servico 22 usa DIDs de 4 digitos`() {
        val config = VarreduraPids.configurar("7e0", "22", "f40c", "F40F").getOrThrow()
        assertEquals(listOf("22F40C", "22F40D", "22F40E", "22F40F"), VarreduraPids.comandos(config))
        assertEquals("7E0 · 22F40C–22F40F", config.descricao)
    }

    @Test
    fun `presets Toyota varrem motor e cambio no servico 21`() {
        val (motor, cambio) = VarreduraPids.PRESETS
        assertEquals("Toyota Motor (7E0 · 2100–21FF)", motor.nome)
        assertEquals(ConfigVarredura("7E0", "21", 0x00, 0xFF), motor.config)
        assertEquals(ConfigVarredura("7E1", "21", 0x00, 0xFF), cambio.config)
        assertEquals("7E1 · 2100–21FF", cambio.config.descricao)
    }

    @Test
    fun `configuracao invalida e recusada com mensagem`() {
        fun erro(c: String, s: String, i: String, f: String) = VarreduraPids.configurar(c, s, i, f).exceptionOrNull()?.message
        assertTrue(erro("7G0", "21", "00", "FF")!!.contains("Cabeçalho"))
        assertTrue(erro("7E0", "2", "00", "FF")!!.contains("Serviço"))
        assertTrue(erro("7E0", "21", "zz", "FF")!!.contains("Início"))
        assertTrue(erro("7E0", "21", "00", "")!!.contains("Fim"))
        assertTrue(erro("7E0", "21", "80", "10")!!.contains("menor"))
        assertTrue(erro("7E0", "21", "00", "1FF")!!.contains("limite"))
        assertNull(erro("7E0", "21", "10", "10"))
    }

    // ---------------------------------------------------------------- classificação das respostas

    @Test
    fun `resposta positiva de quadro unico traz os bytes brutos`() {
        val r = VarreduraPids.classificar("2113", resposta("2113", "611381A47ECA873A7F88\r\r"))
        assertEquals(TipoRespostaPid.POSITIVA, r.tipo)
        assertEquals("61 13 81 A4 7E CA 87 3A 7F 88", r.bytesHex)
        assertEquals(8, r.dados(2).size)
        assertTrue(r.respondeu)
    }

    @Test
    fun `resposta multi-frame CAN e unida antes de classificar`() {
        val bruto = "012\r0:6101007D873C\r1:8012008B641F00\r2:00A213C4070000\r\r"
        val r = VarreduraPids.classificar("2101", resposta("2101", bruto))
        assertEquals(TipoRespostaPid.POSITIVA, r.tipo)
        assertEquals(0x61, r.bytes[0])
        assertEquals(0x01, r.bytes[1])
        assertTrue(r.bytes.size >= 18)
    }

    @Test
    fun `negativa 7F NO DATA timeout e erro do adaptador`() {
        val negativa = VarreduraPids.classificar("2150", resposta("2150", "7F2131\r\r"))
        assertEquals(TipoRespostaPid.NEGATIVA, negativa.tipo)
        assertEquals("31", negativa.nrc)

        assertEquals(TipoRespostaPid.SEM_DADOS, VarreduraPids.classificar("2101", resposta("2101", "NO DATA\r\r")).tipo)

        val timeout = VarreduraPids.classificar("2101", resposta("2101", "", semPrompt = true))
        assertEquals(TipoRespostaPid.ERRO, timeout.tipo)
        assertEquals("TIMEOUT", timeout.erro)

        assertEquals(TipoRespostaPid.ERRO, VarreduraPids.classificar("2101", resposta("2101", "CAN ERROR\r\r")).tipo)
        assertEquals(TipoRespostaPid.ERRO, VarreduraPids.classificar("2101", resposta("2101", "?\r\r")).tipo)
    }

    @Test
    fun `resposta positiva de outro identificador nao conta`() {
        // Resposta atrasada do comando anterior (2101) chegando no 2102.
        assertFalse(VarreduraPids.classificar("2102", resposta("2102", "6101AABB\r\r")).respondeu)
    }

    @Test
    fun `filtro oculta NO DATA 7F e erros salvo com mostrar tudo`() {
        val resultados = listOf(
            VarreduraPids.classificar("2101", resposta("2101", "6101AA\r\r")),
            VarreduraPids.classificar("2102", resposta("2102", "7F2131\r\r")),
            VarreduraPids.classificar("2103", resposta("2103", "NO DATA\r\r")),
            VarreduraPids.classificar("2104", resposta("2104", "", semPrompt = true)),
        )
        assertEquals(listOf("2101"), VarreduraPids.filtrar(resultados, mostrarTudo = false).map { it.comando })
        assertEquals(4, VarreduraPids.filtrar(resultados, mostrarTudo = true).size)
    }

    // ---------------------------------------------------------------- execução

    @Test
    fun `executa ATSH e a faixa em sequencia com progresso`() = runTest {
        val enviados = mutableListOf<String>()
        val progressos = mutableListOf<ProgressoVarredura>()
        val config = ConfigVarredura("7E1", "21", 0x80, 0x83)
        val resultado = VarreduraPids.executar(config, enviar = { c ->
            enviados += c
            Result.success(
                when (c) {
                    "ATSH 7E1" -> resposta(c, "OK\r\r")
                    "2182" -> resposta(c, "618200003D62\r\r")
                    else -> resposta(c, "7F2131\r\r")
                },
            )
        }) { p, _ -> progressos += p }

        assertEquals(listOf("ATSH 7E1", "2180", "2181", "2182", "2183"), enviados)
        assertTrue(resultado.completa)
        assertEquals(listOf("2182"), resultado.positivas.map { it.comando })
        assertEquals(ProgressoVarredura(4, 4, 1, null), progressos.last())
        assertEquals("2180", progressos.first().atual)
    }

    @Test
    fun `parar atende entre comandos e devolve o parcial`() = runTest {
        var enviados = 0
        val resultado = VarreduraPids.executar(
            ConfigVarredura("7E0", "21", 0x00, 0xFF),
            enviar = { c ->
                enviados++
                Result.success(resposta(c, if (c.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"))
            },
            deveParar = { enviados > 10 },
        )
        assertEquals(10, resultado.resultados.size) // ATSH + 10 comandos, e o 11º não sai
        assertEquals(VarreduraPids.PARADA_USUARIO, resultado.motivoParada)
        assertFalse(resultado.completa)
    }

    @Test
    fun `timeouts seguidos ou ATSH recusado interrompem a varredura`() = runTest {
        val mudo = VarreduraPids.executar(ConfigVarredura("7E0", "21", 0x00, 0xFF), enviar = { c ->
            Result.success(if (c.startsWith("AT")) resposta(c, "OK\r\r") else resposta(c, "", semPrompt = true))
        })
        assertEquals(VarreduraPids.MAX_TIMEOUTS_SEGUIDOS, mudo.resultados.size)
        assertTrue(mudo.motivoParada!!.contains("sem resposta"))

        val recusado = VarreduraPids.executar(ConfigVarredura("7E0", "21", 0x00, 0x01), enviar = { c -> Result.success(resposta(c, "?\r\r")) })
        assertTrue(recusado.resultados.isEmpty())
        assertTrue(recusado.motivoParada!!.contains("ATSH 7E0"))

        val caiu = VarreduraPids.executar(ConfigVarredura("7E0", "21", 0x00, 0x01), enviar = { c ->
            if (c.startsWith("AT")) Result.success(resposta(c, "OK\r\r")) else Result.failure(ObdException("Conexão com o scanner perdida"))
        })
        assertEquals("Conexão com o scanner perdida", caiu.motivoParada)
    }

    // ---------------------------------------------------------------- relatório

    @Test
    fun `relatorio lista os PIDs que responderam com os bytes e resume as negativas`() {
        val config = ConfigVarredura("7E0", "21", 0x01, 0x03)
        val resultado = ResultadoVarredura(
            config,
            listOf(
                VarreduraPids.classificar("2101", resposta("2101", "6101AABBCC\r\r")),
                VarreduraPids.classificar("2102", resposta("2102", "7F2131\r\r")),
                VarreduraPids.classificar("2103", resposta("2103", "7F2131\r\r")),
            ),
            total = 3,
        )
        val texto = RelatorioVarredura.gerar(resultado, LocalDateTime.of(2026, 11, 22, 10, 15), veiculo = "Hilux SW4", protocolo = "ISO 15765-4 (CAN 11/500)")
        assertTrue(texto, texto.startsWith("VARREDURA DE PIDs"))
        assertTrue(texto, "22/11/2026 10:15 · Hilux SW4" in texto)
        assertTrue(texto, "Faixa: 7E0 · 2101–2103" in texto)
        assertTrue(texto, "responderam: 1" in texto)
        assertTrue(texto, "2101 → 61 01 AA BB CC  [3 bytes de dados]" in texto)
        assertTrue(texto, "7F 21 31" in texto && "×2" in texto)
        assertFalse(texto, "2102 →" in texto)
    }
}
