package com.charruamotors.obd.pid

import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.TelemetriaCalculada
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.EscalonadorPolling
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `cascata_conexao.json` e o mapeamento semântico (`chave`) que liga 01xx, 21xx e 22F4xx. */
class CatalogoCascataTest {

    private val padrao = CatalogoTeste.padrao
    private val cascata = CatalogoTeste.cascata

    // ---------------------------------------------------------------- asset

    @Test
    fun `asset tem a camada 2 na ordem ATSP6 7 5 3`() {
        assertEquals(listOf("ATSP6", "ATSP7", "ATSP5", "ATSP3"), cascata.protocolosFisicos.map { it.comando })
        assertEquals(listOf(true, false, false, false), cascata.protocolosFisicos.map { it.ehCan11Bits })
    }

    @Test
    fun `asset tem o modo 21 e depois o modo 22 no motor`() {
        val (m21, m22) = cascata.modosServico
        assertEquals(ModoComunicacaoEcu.MODO_21, m21.tipo)
        assertEquals(ModoComunicacaoEcu.MODO_22, m22.tipo)
        assertEquals("Modo 21 (M-OBD / KWP)", m21.nome)
        assertEquals("Modo 22 (UDS)", m22.nome)
        assertEquals(listOf("7E0", "7E0"), cascata.modosServico.map { it.cabecalho })
        assertEquals(
            listOf("210C", "210D", "210B", "2105", "2104", "210F", "2110", "2111", "2123", "215C"),
            m21.pids.map { it.estendida!!.comando },
        )
        assertEquals(
            listOf("22F40C", "22F40D", "22F40B", "22F405", "22F404", "22F40F", "22F410", "22F411"),
            m22.pids.map { it.estendida!!.comando },
        )
        assertEquals(listOf("13", "1800FF00"), m21.dtc.ler)
        assertEquals(listOf("14", "14FF00"), m21.dtc.limpar)
        assertEquals(listOf("1902FF"), m22.dtc.ler)
        assertEquals(listOf("14FFFFFF"), m22.dtc.limpar)
    }

    @Test
    fun `a marca decide a ordem dos modos`() {
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara("Toyota Hilux").map { it.id })
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara(null).map { it.id })
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara("Honda").map { it.id })
        assertEquals(listOf("MODO_22", "MODO_21"), cascata.modosPara("VW Gol").map { it.id })
        assertEquals(listOf("MODO_22", "MODO_21"), cascata.modosPara("Jeep Renegade").map { it.id })
        assertEquals(listOf("MODO_22", "MODO_21"), cascata.modosPara("Chevrolet").map { it.id })
    }

    // ---------------------------------------------------------------- mapeamento semântico

    @Test
    fun `toda chave semantica tem um unico PID canonico no catalogo padrao`() {
        ChaveSemantica.entries.forEach { chave ->
            val canonico = padrao[chave]
            assertNotNull("$chave sem PID canônico", canonico)
            assertEquals(chave, canonico!!.chave)
        }
    }

    @Test
    fun `as chaves batem com os PIDs dos mostradores grafico computador e filas`() {
        assertEquals(Pids.RPM, padrao[ChaveSemantica.RPM]!!.comando)
        assertEquals(Pids.VELOCIDADE, padrao[ChaveSemantica.VELOCIDADE]!!.comando)
        assertEquals(Pids.PRESSAO_COLETOR, padrao[ChaveSemantica.MAP]!!.comando)
        assertEquals(Pids.TEMPERATURA_MOTOR, padrao[ChaveSemantica.TEMP_MOTOR]!!.comando)
        assertEquals(Pids.CARGA_MOTOR, padrao[ChaveSemantica.CARGA]!!.comando)
        assertEquals(Pids.MAF, padrao[ChaveSemantica.MAF]!!.comando)
        assertEquals(Pids.BORBOLETA, padrao[ChaveSemantica.BORBOLETA]!!.comando)
        assertEquals(Pids.TEMPERATURA_AR_ADMISSAO, padrao[ChaveSemantica.IAT]!!.comando)
        assertEquals(Pids.TENSAO_BATERIA, padrao[ChaveSemantica.TENSAO]!!.comando)
        assertEquals("0123", padrao[ChaveSemantica.RAIL]!!.comando)
        assertEquals("015C", padrao[ChaveSemantica.TEMP_OLEO]!!.comando)
    }

    @Test
    fun `espelhos herdam a formula e a faixa do PID canonico e moram na mesma chave`() {
        cascata.modosServico.flatMap { it.pids }.forEach { espelho ->
            val canonico = padrao[espelho.chave!!]!!
            assertTrue(espelho.ehEspelho)
            assertEquals(canonico.comando, espelho.comando)
            assertEquals(canonico.formula, espelho.formula)
            assertEquals(canonico.bytes, espelho.bytes)
            assertEquals(canonico.min, espelho.min, 0.0)
            assertEquals(canonico.max, espelho.max, 0.0)
            assertEquals(canonico.nome, espelho.nome)
        }
    }

    @Test
    fun `o mesmo RPM chega igual por 010C 210C e 22F40C`() {
        val m21 = cascata.modosServico.first { it.tipo == ModoComunicacaoEcu.MODO_21 }
        val m22 = cascata.modosServico.first { it.tipo == ModoComunicacaoEcu.MODO_22 }
        val viaPadrao = ObdParser.parsePid("410C0C3B\r", padrao[Pids.RPM]!!)
        val via21 = ObdParser.parsePid("610C0C3B\r", m21.pids.first { it.chave == ChaveSemantica.RPM })
        val via22 = ObdParser.parsePid("62F40C0C3B\r", m22.pids.first { it.chave == ChaveSemantica.RPM })
        assertEquals(RespostaObd.Ok(782.75), viaPadrao)
        assertEquals(viaPadrao, via21)
        assertEquals(viaPadrao, via22)
    }

    @Test
    fun `fila rapida e computador de bordo funcionam com os sensores vindos dos espelhos`() {
        val m22 = cascata.modosServico.first { it.tipo == ModoComunicacaoEcu.MODO_22 }
        val escalonador = EscalonadorPolling()
        escalonador.atualizarSuportados(listOf("ATRV") + m22.pids.map { it.comando })
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE, Pids.MAF), escalonador.filaRapida)
        assertTrue(Pids.TEMPERATURA_MOTOR in escalonador.filaLenta && Pids.BORBOLETA in escalonador.filaLenta)

        // MAF 22F410 + velocidade 22F40D no mesmo lugar do 0110/010D: consumo pelo MAF.
        val dados = DadosObd(mapOf(Pids.RPM to 2000.0, Pids.VELOCIDADE to 80.0, Pids.MAF to 10.0))
        val consumo = TelemetriaCalculada.consumoInstantaneo(dados, ParametrosMotor())
        assertNotNull(consumo)
        assertTrue(consumo!!.kmPorLitro!! > 0)
    }

    // ---------------------------------------------------------------- validação do JSON

    @Test
    fun `chave sem PID canonico e rejeitada`() {
        val semRail = CatalogoPids(padrao.pids.filter { it.chave != ChaveSemantica.RAIL })
        val e = assertThrows(IllegalArgumentException::class.java) { CatalogoCascata.deJson(asset(), semRail) }
        assertTrue(e.message!!, "RAIL" in e.message!!)
    }

    @Test
    fun `chave semantica desconhecida e rejeitada`() {
        val json = """{"modosServico":[{"id":"X","nome":"X","cabecalho":"7E0","servico":"21",
            "dtc":{"ler":"13","limpar":["14"]},"pids":[{"identificador":"0C","chave":"TURBO"}]}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { CatalogoCascata.deJson(json, padrao) }
        assertTrue(e.message!!, "TURBO" in e.message!!)
    }

    @Test
    fun `espelho pode sobrescrever a formula`() {
        val json = """{"modosServico":[{"id":"X","nome":"X","cabecalho":"7E0","servico":"21",
            "dtc":{"ler":"13","limpar":["14"]},
            "pids":[{"identificador":"40","chave":"RPM","bytes":2,"formula":"256*A+B","max":8000}]}]}"""
        val rpm = CatalogoCascata.deJson(json, padrao).modosServico.single().pids.single()
        assertEquals(RespostaObd.Ok(3000.0), ObdParser.parsePid("61400BB8\r", rpm))
        assertEquals(Pids.RPM, rpm.comando)
        assertEquals("7E0 · 2140", rpm.rotuloComando)
    }

    @Test
    fun `comandos de DTC invalidos sao rejeitados`() {
        val json = """{"modosServico":[{"id":"X","nome":"X","cabecalho":"7E0","servico":"22",
            "dtc":{"ler":"0902","limpar":["14FFFFFF"]},"pids":[{"identificador":"F40C","chave":"RPM"}]}]}"""
        assertThrows(IllegalArgumentException::class.java) { CatalogoCascata.deJson(json, padrao) }
    }

    @Test
    fun `chave semantica duplicada no catalogo padrao e rejeitada`() {
        val dup = padrao.pids.map { if (it.comando == "0145") it.copy(chave = ChaveSemantica.BORBOLETA) else it }
        assertThrows(IllegalArgumentException::class.java) { CatalogoPids(dup) }
    }

    private fun asset(): String =
        listOf("src/main/assets", "app/src/main/assets").map { java.io.File(it, CatalogoCascata.ARQUIVO_PADRAO) }
            .first { it.isFile }.readText()
}
