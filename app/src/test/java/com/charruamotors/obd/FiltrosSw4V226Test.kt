package com.charruamotors.obd

import com.charruamotors.domain.calculo.AlertasProtecao
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.calculo.TipoAlertaProtecao
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.faixas.SituacaoFaixa
import com.charruamotors.obd.log.CabecalhoSessao
import com.charruamotors.obd.log.EstatisticasViagem
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.obd.log.ResumoSessao
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * v2.2.6: filtros de alertas/faixas com o motor desligado ou frio, `ATRV` com o bug do ELM327 v2.1,
 * alavanca A750F, rail de 2.000 bar, boost com o barômetro real e estados distintos no relatório.
 */
class FiltrosSw4V226Test {

    private val sw4 = PresetVeiculo.SW4_2013.faixas

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    private fun parametros(d: DadosObd) = sw4.violacoes(d).map { it.faixa.parametro }.toSet()

    // ---------------------------------------------------------------- ATRV

    @Test
    fun `ATRV com dois pontos do ELM327 v2_1 vira 10 V`() {
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parseTensao(":.0V\r\r"))
        assertEquals(RespostaObd.Ok(10.4), ObdParser.parseTensao(":.4V\r\r>"))
        assertEquals(RespostaObd.Ok(11.2), ObdParser.parseTensao(";.2V\r\r"))
        // Normais seguem iguais (9,9 → 10,0 sem o bug).
        assertEquals(RespostaObd.Ok(9.9), ObdParser.parseTensao("9.9V\r\r"))
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parseTensao("10.0V\r\r"))
        assertEquals(RespostaObd.Ok(12.6), ObdParser.parseTensao("ATRV\r12.6V\r\r"))
        // Pelo catálogo (polling do ATRV) também.
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parsePid(":.0V\r\r", CatalogoTeste.pid("ATRV")))
        // Só o primeiro caractere seguido de ponto é corrigido.
        assertEquals("10.0V", ObdParser.corrigirDezenaAtrv(":.0V"))
        assertEquals("1:0", ObdParser.corrigirDezenaAtrv("1:0"))
        assertEquals("12.6V", ObdParser.corrigirDezenaAtrv("12.6V"))
    }

    // ---------------------------------------------------------------- RPM ≥ 400

    @Test
    fun `alternador tensao ECU rail MAP e MAF so com RPM a partir de 400`() {
        val desligado = dados(
            Pids.RPM to 399.0, Pids.TENSAO_BATERIA to 12.1, "TOYOTA_TENSAO_ECU" to 12.0, Pids.PRESSAO_RAIL to 0.0,
            Pids.PRESSAO_COLETOR to 40.0, Pids.MAF to 0.0,
        )
        val alvo = setOf(ParametroFaixa.TENSAO, ParametroFaixa.TENSAO_ECU, ParametroFaixa.RAIL, ParametroFaixa.MAP_TURBO, ParametroFaixa.MAF)
        assertTrue(parametros(desligado).intersect(alvo).isEmpty())
        assertTrue(AlertasProtecao.avaliar(desligado, LimitesProtecao(tensaoMinV = 13.4)).isEmpty())

        val ligado = DadosObd(desligado.valores + (Pids.RPM to 400.0))
        assertEquals(alvo, parametros(ligado).intersect(alvo))
        assertEquals(listOf(TipoAlertaProtecao.TENSAO), AlertasProtecao.avaliar(ligado, LimitesProtecao(tensaoMinV = 13.4)).map { it.tipo })

        // Sem RPM no instantâneo: nada disso é avaliado.
        assertTrue(parametros(DadosObd(desligado.valores - Pids.RPM)).intersect(alvo).isEmpty())
    }

    @Test
    fun `marcha lenta e rail de lenta so com o motor aquecido`() {
        val lenta = mapOf(Pids.RPM to 900.0, Pids.VELOCIDADE to 0.0, Pids.PRESSAO_RAIL to 65_000.0)
        val frio = parametros(DadosObd(lenta + (Pids.TEMPERATURA_MOTOR to 64.0)))
        assertFalse(ParametroFaixa.RPM_LENTA in frio)
        assertFalse(ParametroFaixa.RAIL_LENTA in frio)
        // Sem temperatura lida também não dá para saber: não avalia.
        assertFalse(ParametroFaixa.RPM_LENTA in parametros(DadosObd(lenta)))
        val quente = parametros(DadosObd(lenta + (Pids.TEMPERATURA_MOTOR to 65.0)))
        assertTrue(ParametroFaixa.RPM_LENTA in quente)
        assertTrue(ParametroFaixa.RAIL_LENTA in quente)
    }

    // ---------------------------------------------------------------- rail e alavanca

    @Test
    fun `rail da SW4 2013 vai ate 2000 bar`() {
        val rail = sw4[ParametroFaixa.RAIL]!!
        assertEquals(2_000.0, rail.max!!, 0.0)
        assertTrue(sw4.violacoes(dados(Pids.RPM to 3_000.0, Pids.PRESSAO_RAIL to 195_000.0)).none { it.faixa.parametro == ParametroFaixa.RAIL })
        val acima = sw4.violacoes(dados(Pids.RPM to 3_000.0, Pids.PRESSAO_RAIL to 205_000.0)).single { it.faixa.parametro == ParametroFaixa.RAIL }
        assertEquals(SituacaoFaixa.ACIMA, acima.situacao)
    }

    @Test
    fun `bytes C D do 7E1 21D9 mapeiam a alavanca A750F`() {
        val def = CatalogoTeste.estendido["TOYOTA_ALAVANCA"]!!
        fun posicao(cd: String): String? {
            val r = ObdParser.parsePid("61D90000${cd}42E000000000\r\r", def) as RespostaObd.Ok
            return def.rotulos[r.valor.toInt()]
        }
        assertEquals("P/N", posicao("0000"))
        assertEquals("R", posicao("0400"))
        assertEquals("D", posicao("0004"))
        assertEquals("4", posicao("0006"))
        assertEquals("3", posicao("0001"))
        assertEquals("2", posicao("0200"))
        assertEquals("L", posicao("0300"))
        assertEquals("M", posicao("0505")) // par não mapeado
    }

    @Test
    fun `alavanca do motor sai quando a do cambio responde`() {
        val cambio = CatalogoTeste.estendido["TOYOTA_ALAVANCA"]!!
        val ecm = CatalogoTeste.estendido["TOYOTA_ALAVANCA_ECM"]!!
        val atf = CatalogoTeste.estendido["TOYOTA_ATF_CAMBIO"]!!
        assertEquals(listOf(atf, cambio), Elm327BluetoothClient.filtrarAlavancaRedundante(listOf(atf, ecm, cambio)))
        // Sem a do 7E1, a do 7E0 fica (único jeito de ler a alavanca).
        assertEquals(listOf(atf, ecm), Elm327BluetoothClient.filtrarAlavancaRedundante(listOf(atf, ecm)))
    }

    // ---------------------------------------------------------------- relatório

    @Test
    fun `boost do resumo desconta o barometro real`() {
        assertEquals(0.99, RelatorioSessaoTxt.boostBar(200.0), 1e-9) // sem barômetro: 101 kPa
        assertEquals(1.10, RelatorioSessaoTxt.boostBar(200.0, 90.0), 1e-9)
        assertEquals("200 kPa (≈ +1,10 bar de turbo, barômetro 90 kPa)", RelatorioSessaoTxt.descreverMap(200.0, 90.0))
        assertEquals("200 kPa (≈ +0,99 bar de turbo)", RelatorioSessaoTxt.descreverMap(200.0))
        assertEquals("95 kPa", RelatorioSessaoTxt.descreverMap(95.0, 92.0))

        // Na viagem: o barômetro Toyota (2133) vale sobre o 0133; o do pico do MAP é o usado.
        val e = EstatisticasViagem()
        e.registrar(dados(Pids.PRESSAO_COLETOR to 100.0))
        assertEquals(null, e.barometroBoost)
        e.registrar(dados(Pids.PRESSAO_COLETOR to 180.0, "TOYOTA_BARO" to 88.0, "0133" to 95.0))
        e.registrar(dados(Pids.PRESSAO_COLETOR to 120.0, "TOYOTA_BARO" to 92.0))
        assertEquals(88.0, e.barometroBoost!!, 0.0)
        // Lixo (0 / 255) não entra.
        val so0133 = EstatisticasViagem()
        so0133.registrar(dados(Pids.PRESSAO_COLETOR to 150.0, "TOYOTA_BARO" to 255.0, "0133" to 93.0))
        assertEquals(93.0, so0133.barometroBoost!!, 0.0)
    }

    private fun relatorio(e: EstatisticasViagem, colunas: List<DefinicaoPid>): String {
        val r = ResumoSessao(
            cabecalho = CabecalhoSessao(null, null, null, "Teste", null, 0L),
            fimMs = 60_000L,
            conectou = false,
            motivoFim = "fim",
            protocolo = null,
            camada = null,
            protocoloForcado = null,
            tensaoInicial = null,
            dtcs = null,
            estatisticas = e,
            arquivoCsv = null,
            amostrasCsv = 0,
            avisoCsv = null,
            colunas = colunas,
        )
        return StringBuilder().also { RelatorioSessaoTxt.escreverResumo(r, it, ZoneOffset.UTC) }.toString()
    }

    @Test
    fun `relatorio lista os estados distintos da alavanca e da marcha e o boost real`() {
        val alavanca = CatalogoTeste.estendido["TOYOTA_ALAVANCA"]!!
        val e = EstatisticasViagem()
        // P/N (7) → D (3) → 4 (8) → D → R (1): a ordem é a da primeira vez.
        listOf(7.0, 3.0, 8.0, 3.0, 1.0).zip(listOf(255.0, 1.0, 13.0, 2.0, 254.0)).forEach { (a, m) ->
            e.registrar(dados("TOYOTA_ALAVANCA" to a, CambioA750f.CHAVE_MARCHA to m, Pids.PRESSAO_COLETOR to 190.0, "TOYOTA_BARO" to 90.0))
        }
        val txt = relatorio(e, listOf(alavanca, DefinicaoPid.MARCHA_ESTIMADA_A750F))
        val linhaAlavanca = txt.lines().single { "[TOYOTA_ALAVANCA]" in it }
        assertTrue(linhaAlavanca, "estados observados: P/N, D, 4, R (5 leituras)" in linhaAlavanca)
        val linhaMarcha = txt.lines().single { "[TOYOTA_MARCHA]" in it }
        assertTrue(linhaMarcha, "estados observados: Parado, 1ª, 3ª L, 2ª, R" in linhaMarcha)
        assertFalse(txt.contains("estados de "))
        assertTrue(txt, txt.contains("190 kPa (≈ +1,00 bar de turbo, barômetro 90 kPa)"))
    }

    @Test
    fun `secao MARCHAS separa a reduzida`() {
        val e = EstatisticasViagem()
        listOf(1.0, 2.0, 2.0, 11.0, 12.0, 12.0, 12.0, 255.0).forEach { e.registrar(dados(CambioA750f.CHAVE_MARCHA to it)) }
        val txt = relatorio(e, emptyList())
        val esperado = "MARCHAS\n" +
            "  1ª      : 1 amostras (12,5 %)\n" +
            "  2ª      : 2 amostras (25,0 %)\n" +
            "  Parado  : 1 amostras (12,5 %)\n" +
            "  Reduzida (4L):\n" +
            "  1ª L    : 1 amostras (12,5 %)\n" +
            "  2ª L    : 3 amostras (37,5 %)\n"
        assertTrue(txt, txt.contains(esperado))
    }
}
