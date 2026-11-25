package com.charruamotors.domain.faixas

import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.SensorPainel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.1.5: faixas esperadas do Cruze 1.4 Turbo Flex 2018 (LE2) e o layout rápido "Turbo Flex (Cruze)". */
class FaixasCruzeTest {

    private val cruze = PresetVeiculo.CRUZE_2018

    private fun faixa(p: ParametroFaixa) = cruze.faixas[p]!!.let { it.min to it.max }

    @Test
    fun `preset do Cruze 2018 traz as faixas do LE2`() {
        assertEquals(600.0 to 850.0, faixa(ParametroFaixa.RPM_LENTA))
        // v2.2.9: redline do LE2 em 6.500 rpm e boost de até ~1,6 bar (MAP 260 kPa) no 1.4T.
        assertEquals(null to 6_500.0, faixa(ParametroFaixa.RPM_MAXIMO))
        assertEquals(85.0 to 108.0, faixa(ParametroFaixa.TEMP_AGUA))
        assertEquals(60.0 to 105.0, faixa(ParametroFaixa.TEMP_ATF))
        assertEquals(25.0 to 260.0, faixa(ParametroFaixa.MAP_TURBO))
        assertEquals(30.0 to 200.0, faixa(ParametroFaixa.RAIL))
        assertEquals(12.6 to 14.8, faixa(ParametroFaixa.TENSAO))
        assertEquals(-10.0 to 10.0, faixa(ParametroFaixa.STFT))
        assertEquals(-10.0 to 10.0, faixa(ParametroFaixa.LTFT))
        assertEquals(0.0 to 100.0, faixa(ParametroFaixa.CARGA_MOTOR))
        assertEquals(10.0 to 65.0, faixa(ParametroFaixa.TEMP_ADMISSAO))
    }

    @Test
    fun `textos da ficha do Cruze`() {
        assertEquals("600 – 850 rpm", cruze.faixas[ParametroFaixa.RPM_LENTA]!!.texto)
        assertEquals("12,6 – 14,8 V", cruze.faixas[ParametroFaixa.TENSAO]!!.texto)
        assertTrue(cruze.faixas.linhasFicha().contains("Pressão Turbo (MAP): 25 – 260 kPa"))
    }

    @Test
    fun `layout Turbo Flex monta os 10 mostradores na ordem pedida`() {
        val config = ConfigPainel().aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX)
        assertEquals(
            listOf(
                SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.RELACAO_AF,
                SensorPainel.ETANOL, SensorPainel.SONDA_LAMBDA, SensorPainel.RAIL, SensorPainel.TEMP_MOTOR,
                SensorPainel.TEMP_CAMBIO, SensorPainel.MARCHA_ATUAL,
            ),
            config.mostradores.map { it.sensor },
        )
    }

    @Test
    fun `layout rapido mantem o estilo atual e volta ao padrao`() {
        val digital = ConfigPainel().aplicarLayout(ConfigPainel.LAYOUT_PADRAO).aplicarEstiloATodos(EstiloMostrador.DIGITAL)
        val turbo = digital.aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX)
        assertTrue(turbo.mostradores.all { it.estilo == EstiloMostrador.DIGITAL })
        assertEquals(ConfigPainel.MOSTRADORES_PADRAO.map { it.sensor }, turbo.aplicarLayout(ConfigPainel.LAYOUT_PADRAO).mostradores.map { it.sensor })
    }
}
