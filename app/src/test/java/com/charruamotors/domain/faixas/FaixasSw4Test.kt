package com.charruamotors.domain.faixas

import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.log.EstatisticasViagem
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1: faixas mínimas/máximas esperadas por veículo, com a predefinição da Hilux SW4 3.0 D-4D 2013 (1KD-FTV). */
class FaixasSw4Test {

    private val sw4 = PresetVeiculo.SW4_2013.faixas

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    private fun faixa(p: ParametroFaixa) = sw4[p]!!

    @Test
    fun `preset da SW4 2013 traz as faixas do 1KD-FTV`() {
        assertEquals(650.0 to 800.0, faixa(ParametroFaixa.RPM_LENTA).let { it.min to it.max })
        assertEquals(null to 4_200.0, faixa(ParametroFaixa.RPM_MAXIMO).let { it.min to it.max })
        assertEquals(80.0 to 98.0, faixa(ParametroFaixa.TEMP_AGUA).let { it.min to it.max })
        assertEquals(60.0 to 105.0, faixa(ParametroFaixa.TEMP_ATF).let { it.min to it.max })
        assertEquals(13.4 to 14.6, faixa(ParametroFaixa.TENSAO).let { it.min to it.max })
        assertEquals(280.0 to 2_000.0, faixa(ParametroFaixa.RAIL).let { it.min to it.max }) // v2.2.6: teto 2.000 bar
        assertEquals(280.0 to 600.0, faixa(ParametroFaixa.RAIL_LENTA).let { it.min to it.max })
        // 60 kPa (v2.1.1): em altitude a marcha lenta fica em 73–75 kPa e não pode acender o vermelho.
        assertEquals(60.0 to 215.0, faixa(ParametroFaixa.MAP_TURBO).let { it.min to it.max })
        assertEquals(-3.0 to 3.0, faixa(ParametroFaixa.BICOS).let { it.min to it.max })
        // v2.1.2: faixas dos sensores que estavam sem faixa na grade (carga, IAT, MAF, borboleta, baro, ECU).
        assertEquals(0.0 to 100.0, faixa(ParametroFaixa.CARGA_MOTOR).let { it.min to it.max })
        assertEquals(10.0 to 65.0, faixa(ParametroFaixa.TEMP_ADMISSAO).let { it.min to it.max })
        assertEquals(10.0 to 170.0, faixa(ParametroFaixa.MAF).let { it.min to it.max })
        assertEquals(0.0 to 100.0, faixa(ParametroFaixa.BORBOLETA).let { it.min to it.max })
        assertEquals(85.0 to 105.0, faixa(ParametroFaixa.PRESSAO_BARO).let { it.min to it.max })
        assertEquals(13.4 to 14.6, faixa(ParametroFaixa.TENSAO_ECU).let { it.min to it.max })
        assertEquals(15, sw4.faixas.size)
    }

    @Test
    fun `textos da ficha no formato brasileiro`() {
        assertEquals("650 – 800 rpm", faixa(ParametroFaixa.RPM_LENTA).texto)
        assertEquals("máx. 4.200 rpm", faixa(ParametroFaixa.RPM_MAXIMO).texto)
        assertEquals("13,4 – 14,6 V", faixa(ParametroFaixa.TENSAO).texto)
        assertEquals("280 – 2.000 bar", faixa(ParametroFaixa.RAIL).texto)
        assertEquals("-3,00 a +3,00 mm³/st", faixa(ParametroFaixa.BICOS).texto)
        assertTrue(sw4.linhasFicha().contains("Pressão Turbo (MAP): 60 – 215 kPa"))
    }

    @Test
    fun `reconhece a SW4 pelo cadastro`() {
        assertEquals(PresetVeiculo.SW4_2013, PresetVeiculo.detectar("Toyota", "Hilux SW4 3.0 D-4D", 2013))
        assertEquals(PresetVeiculo.SW4_2013, PresetVeiculo.detectar("TOYOTA", "SW4 1KD", 2018))
        assertEquals(PresetVeiculo.SW4_2013, PresetVeiculo.detectar("Toyota", "SW4 SRV", 2011)) // ano do 1KD-FTV
        assertNull(PresetVeiculo.detectar("Toyota", "SW4 2.8 GD", 2020)) // 1GD-FTV: outro motor
        assertNull(PresetVeiculo.detectar("Toyota", "Corolla 2.0", 2013))
        assertNull(PresetVeiculo.detectar(null, null, null))
    }

    @Test
    fun `veiculo sem preset usa as faixas genericas dos limites do cadastro`() {
        val gol = Veiculo(marca = "VW", modelo = "Gol 1.6", ano = 2015, placa = "ABC1234", kmAtual = 0,
            tipoCombustivel = TipoCombustivel.FLEX, tempMaxAguaC = 102, tensaoMinAlternadorV = 13.0)
        val f = FaixasVeiculo.paraVeiculo(gol)
        // v2.2: sem perfil exato, cai no genérico do catálogo pela motorização (Flex Aspirado), com os limites do cadastro.
        assertEquals("Genérico · Flex Aspirado (limites do cadastro)", f.perfil)
        assertEquals(102.0, f[ParametroFaixa.TEMP_AGUA]!!.max!!, 0.0)
        assertEquals(13.0, f[ParametroFaixa.TENSAO]!!.min!!, 0.0)
        assertTrue(f[ParametroFaixa.STFT] != null)
        // Diesel sem preset: sem as correções de combustível de ciclo Otto.
        assertNull(FaixasVeiculo.genericas(TipoCombustivel.DIESEL, LimitesProtecao())[ParametroFaixa.STFT])
        // A SW4 cadastrada é reconhecida.
        val sw4Cadastro = gol.copy(marca = "Toyota", modelo = "Hilux SW4 3.0", ano = 2013, tipoCombustivel = TipoCombustivel.DIESEL)
        assertEquals(PresetVeiculo.SW4_2013.faixas, FaixasVeiculo.paraVeiculo(sw4Cadastro))
    }

    @Test
    fun `marcha lenta so vale parado e sem acelerar`() {
        val q = Pids.TEMPERATURA_MOTOR to 85.0 // motor aquecido (v2.2.6)
        val lentaAlta = dados(Pids.RPM to 900.0, Pids.VELOCIDADE to 0.0, q)
        assertTrue(sw4.violacoes(lentaAlta).any { it.faixa.parametro == ParametroFaixa.RPM_LENTA && it.situacao == SituacaoFaixa.ACIMA })
        // Andando a 900 rpm não é marcha lenta; acelerando parado (pedal 30 %) também não.
        assertFalse(sw4.violacoes(dados(Pids.RPM to 900.0, Pids.VELOCIDADE to 20.0, q)).any { it.faixa.parametro == ParametroFaixa.RPM_LENTA })
        assertFalse(sw4.violacoes(dados(Pids.RPM to 900.0, Pids.VELOCIDADE to 0.0, "TOYOTA_PEDAL_D" to 30.0, q)).any { it.faixa.parametro == ParametroFaixa.RPM_LENTA })
        assertTrue(sw4.violacoes(dados(Pids.RPM to 700.0, Pids.VELOCIDADE to 0.0, q)).isEmpty())
    }

    @Test
    fun `RPM acima do maximo seguro`() {
        val v = sw4.violacoes(dados(Pids.RPM to 4_350.0, Pids.VELOCIDADE to 80.0))
        assertEquals(listOf(ParametroFaixa.RPM_MAXIMO), v.map { it.faixa.parametro })
        assertTrue(sw4.violacoes(dados(Pids.RPM to 4_200.0)).isEmpty()) // o limite é inclusivo
    }

    @Test
    fun `rail em bar e tensao so com o motor ligado`() {
        // 650 bar parado em lenta: dentro da faixa geral, acima da de marcha lenta (280–600).
        val railAlto = sw4.violacoes(dados(Pids.RPM to 700.0, Pids.VELOCIDADE to 0.0, Pids.PRESSAO_RAIL to 65_000.0, Pids.TEMPERATURA_MOTOR to 85.0))
        assertEquals(listOf(ParametroFaixa.RAIL_LENTA), railAlto.map { it.faixa.parametro })
        assertEquals(650.0, railAlto.single().valor, 1e-9)
        // Alternador baixo com o motor girando; com ele desligado a bateria em 12,5 V é normal.
        assertTrue(sw4.violacoes(dados(Pids.RPM to 750.0, Pids.TENSAO_BATERIA to 13.1)).any { it.faixa.parametro == ParametroFaixa.TENSAO })
        assertTrue(sw4.violacoes(dados(Pids.RPM to 0.0, Pids.TENSAO_BATERIA to 12.5)).isEmpty())
        assertTrue(sw4.violacoes(dados(Pids.RPM to 2_000.0, Pids.TENSAO_BATERIA to 14.8)).any { it.situacao == SituacaoFaixa.ACIMA })
    }

    @Test
    fun `temperaturas so acusam o acima ao vivo`() {
        assertTrue(sw4.violacoes(dados(Pids.RPM to 750.0, Pids.TEMPERATURA_MOTOR to 60.0, "TOYOTA_ATF_CAMBIO" to 30.0)).isEmpty())
        val quente = sw4.violacoes(dados(Pids.RPM to 2_000.0, Pids.TEMPERATURA_MOTOR to 101.0, "TOYOTA_ATF_CAMBIO" to 110.0))
        assertEquals(setOf(ParametroFaixa.TEMP_AGUA, ParametroFaixa.TEMP_ATF), quente.map { it.faixa.parametro }.toSet())
    }

    @Test
    fun `cada bico injetor e avaliado e destaca o proprio mostrador`() {
        val leitura = dados("TOYOTA_INJ1" to 0.45, "TOYOTA_INJ2" to 3.4, "TOYOTA_INJ3" to -3.2, "TOYOTA_INJ4" to 0.0)
        val v = sw4.violacoes(leitura)
        assertEquals(listOf("TOYOTA_INJ2", "TOYOTA_INJ3"), v.map { it.comando })
        assertTrue(sw4.foraDaFaixa(SensorPainel.BICO_2.comandos, leitura))
        assertFalse(sw4.foraDaFaixa(SensorPainel.BICO_1.comandos, leitura))
        assertTrue(sw4.foraDaFaixa(SensorPainel.TURBO.comandos, dados(Pids.RPM to 3_000.0, Pids.PRESSAO_COLETOR to 230.0)))
        assertFalse(sw4.foraDaFaixa(SensorPainel.TURBO.comandos, null))
    }

    @Test
    fun `relatorio da sessao resume as faixas`() {
        val e = EstatisticasViagem(faixas = sw4)
        e.registrar(dados(Pids.RPM to 700.0, Pids.VELOCIDADE to 0.0, Pids.PRESSAO_RAIL to 30_000.0, Pids.TENSAO_BATERIA to 14.1, Pids.TEMPERATURA_MOTOR to 85.0))
        e.registrar(dados(Pids.RPM to 900.0, Pids.VELOCIDADE to 0.0, Pids.PRESSAO_RAIL to 39_000.0, Pids.TENSAO_BATERIA to 14.2, Pids.TEMPERATURA_MOTOR to 85.0))
        e.registrar(dados(Pids.RPM to 2_500.0, Pids.VELOCIDADE to 80.0, Pids.PRESSAO_RAIL to 120_000.0, Pids.TENSAO_BATERIA to 14.0))
        val lenta = e.acompanhamentoFaixas.first { it.faixa.parametro == ParametroFaixa.RPM_LENTA }
        assertEquals(2, lenta.amostras) // só as duas paradas
        assertEquals(1, lenta.acima)
        assertEquals("acima em 50 % das leituras", lenta.situacao)
        val rail = e.acompanhamentoFaixas.first { it.faixa.parametro == ParametroFaixa.RAIL }
        assertEquals(1_200.0, rail.extremos.maximo!!, 1e-9)
        assertEquals("OK", rail.situacao)
        assertEquals("sem leitura", e.acompanhamentoFaixas.first { it.faixa.parametro == ParametroFaixa.BICOS }.situacao)
    }
}
