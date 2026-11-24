package com.charruamotors.direcao.painel

import com.charruamotors.obd.DadosObd
import com.charruamotors.oficina.bateria.LaudoBateria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7.1: nomes com unidade, sem chips duplicados, seções e laudo de bateria. */
class LimpezaSensoresTest {
    @Test
    fun `titulos sao unicos e trazem a unidade`() {
        val titulos = SensorPainel.entries.map { it.titulo }
        assertEquals(titulos.size, titulos.distinct().size)
        assertEquals("Temp. Água (°C)", SensorPainel.TEMP_MOTOR.titulo)
        assertEquals("Pressão Rail (bar)", SensorPainel.RAIL.titulo)
        assertEquals("Alavanca (P/R/D)", SensorPainel.MARCHA_ALAVANCA.titulo)
        assertEquals("Marcha (1ª–6ª)", SensorPainel.MARCHA_ATUAL.titulo)
        assertEquals(1, titulos.count { it.startsWith("Consumo") })
    }

    @Test
    fun `codigo antigo CONSUMO migra para CONSUMO_KML`() {
        assertEquals(SensorPainel.CONSUMO_KML, SensorPainel.deCodigo("CONSUMO"))
    }

    @Test
    fun `agrupados cobre todos os sensores uma unica vez e isola os nao suportados`() {
        val normal = SensorPainel.agrupados()
        assertEquals(listOf("Principal", "Temperaturas", "Injeção & Diesel", "Mistura & Flex", "Câmbio & Chassi", "Elétrica"), normal.map { it.first })
        assertEquals(SensorPainel.entries.size, normal.sumOf { it.second.size })

        val fora = setOf(SensorPainel.TRACAO_4X4, SensorPainel.VSC, SensorPainel.TEMP_OLEO)
        val secoes = SensorPainel.agrupados(fora)
        assertEquals(SensorPainel.TITULO_NAO_SUPORTADOS, secoes.last().first)
        assertEquals(fora, secoes.last().second.toSet())
        assertEquals(SensorPainel.entries.size, secoes.sumOf { it.second.size })
        assertFalse(secoes.dropLast(1).any { s -> s.second.any { it in fora } })
    }

    @Test
    fun `historico so condena sensor apos varios ciclos sem valor`() {
        HistoricoSensores.limpar()
        val dados = DadosObd(mapOf("010C" to 800.0))
        repeat(HistoricoSensores.CICLOS_ATE_DESISTIR - 1) { HistoricoSensores.registrar(dados) }
        assertTrue(HistoricoSensores.naoSuportados().isEmpty())
        HistoricoSensores.registrar(dados)
        assertTrue(SensorPainel.TRACAO_4X4 in HistoricoSensores.naoSuportados())
        assertFalse(SensorPainel.RPM in HistoricoSensores.naoSuportados())
        assertFalse(SensorPainel.CONSUMO_KML in HistoricoSensores.naoSuportados())
        HistoricoSensores.limpar()
    }

    @Test
    fun `queda de bateria abaixo de uma amostra vira queda rapida`() {
        assertEquals("Queda rápida (< 0,3 s); motor pegou", LaudoBateria.textoQueda(0, true))
        assertEquals("Queda rápida (< 0,3 s); motor não pegou no tempo do teste", LaudoBateria.textoQueda(120, false))
        assertEquals("Queda de 1,2 s; motor pegou", LaudoBateria.textoQueda(1200, true))
    }

    @Test
    fun `titulo da marcha acompanha o maximo de marchas do cambio ativo`() {
        val catalogo = com.charruamotors.domain.perfis.CatalogoPerfis.carregar(com.charruamotors.domain.perfis.FontePerfis.doClasspath())
        fun titulo(id: String) = SensorPainel.MARCHA_ATUAL.tituloPara(catalogo.cambios[id])
        assertEquals("Marcha (1ª–5ª)", titulo("toyota_a750f"))
        assertEquals("Marcha (1ª–6ª)", titulo("gm_6t35"))
        assertEquals("Marcha (1ª–6ª)", titulo("aisin_6m"))
        assertEquals("Marcha", titulo("cvt"))
        assertEquals("Marcha", titulo("manual"))
        assertEquals("Marcha", SensorPainel.MARCHA_ATUAL.tituloPara(null))
        assertEquals("Temp. Água (°C)", SensorPainel.TEMP_MOTOR.tituloPara(catalogo.cambios["gm_6t35"]))
    }

    @Test
    fun `nota dos modos 07 e 0A so aparece no modo 21`() {
        assertTrue((com.charruamotors.obd.ModoComunicacaoEcu.MODO_21).avisaModos07e0A)
        assertFalse((com.charruamotors.obd.ModoComunicacaoEcu.MODO_22).avisaModos07e0A)
        assertFalse((com.charruamotors.obd.ModoComunicacaoEcu.OBD2_PADRAO).avisaModos07e0A)
    }
}
