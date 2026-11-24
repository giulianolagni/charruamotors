package com.charruamotors.direcao.painel

import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.obd.DadosObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPainelTest {

    private fun dados(vararg v: Pair<String, Double>) = DadosObd(mapOf(*v))

    @Test
    fun `alavanca em D com marcha engatada mostra a marcha grande e o D pequeno`() {
        val d = dados("TOYOTA_ALAVANCA" to 3.0, "TOYOTA_MARCHA" to 4.0)
        assertEquals(IndicadorMarcha("4ª", "D"), ClusterPainel.indicadorMarcha(d))
    }

    @Test
    fun `P R e N mostram so a alavanca e o 255 parado nao vira marcha`() {
        assertEquals(IndicadorMarcha("P"), ClusterPainel.indicadorMarcha(dados("TOYOTA_ALAVANCA" to 0.0, "TOYOTA_MARCHA" to 255.0)))
        assertEquals(IndicadorMarcha("R"), ClusterPainel.indicadorMarcha(dados("TOYOTA_ALAVANCA" to 1.0, "TOYOTA_MARCHA" to 1.0)))
        assertEquals(IndicadorMarcha("D"), ClusterPainel.indicadorMarcha(dados("TOYOTA_ALAVANCA" to 3.0, "TOYOTA_MARCHA" to 255.0)))
    }

    @Test
    fun `sem alavanca usa a marcha e sem nada mostra o traco`() {
        assertEquals(IndicadorMarcha("2ª"), ClusterPainel.indicadorMarcha(dados("TOYOTA_MARCHA" to 2.0)))
        assertEquals(IndicadorMarcha("—"), ClusterPainel.indicadorMarcha(null))
        assertEquals(IndicadorMarcha("—"), ClusterPainel.indicadorMarcha(dados("010C" to 800.0)))
    }

    @Test
    fun `quarta bolinha automatica prefere o ATF do cambio e cai no oleo`() {
        assertEquals(SensorPainel.TEMP_CAMBIO, ClusterPainel.oleoOuAtf(dados("TOYOTA_ATF_CAMBIO" to 80.0, "015C" to 90.0)))
        assertEquals(SensorPainel.TEMP_OLEO, ClusterPainel.oleoOuAtf(dados("015C" to 90.0)))
        assertEquals(SensorPainel.TEMP_CAMBIO, ClusterPainel.oleoOuAtf(null))
        // Padrão das 4 bolinhas: água, óleo/ATF, turbo/MAP, bateria.
        val d = dados("015C" to 90.0)
        val sensores = (0 until ClusterPainel.BOLINHAS).map { ClusterPainel.sensorDaBolinha(it, null, d) }
        assertEquals(listOf(SensorPainel.TEMP_MOTOR, SensorPainel.TEMP_OLEO, SensorPainel.TURBO, SensorPainel.TENSAO), sensores)
        assertEquals(SensorPainel.CARGA, ClusterPainel.sensorDaBolinha(0, SensorPainel.CARGA, d))
    }

    @Test
    fun `toque troca para o proximo sensor com leitura e da a volta`() {
        val d = dados("0105" to 90.0, "TOYOTA_ATF_CAMBIO" to 70.0, "ATRV" to 14.0)
        assertEquals(SensorPainel.TEMP_CAMBIO, ClusterPainel.proximo(SensorPainel.TEMP_MOTOR, d))
        assertEquals(SensorPainel.TENSAO, ClusterPainel.proximo(SensorPainel.TEMP_CAMBIO, d)) // pula óleo e turbo (sem leitura)
        assertEquals(SensorPainel.TEMP_MOTOR, ClusterPainel.proximo(SensorPainel.TENSAO, d))
        // Sem nenhuma leitura: simplesmente o seguinte.
        assertEquals(SensorPainel.TEMP_CAMBIO, ClusterPainel.proximo(SensorPainel.TEMP_MOTOR, null))
    }

    @Test
    fun `avisos de temperatura e bateria fora da faixa`() {
        assertTrue(ClusterPainel.alertas(null).isEmpty())
        assertTrue(ClusterPainel.alertas(dados("0105" to 90.0, "TOYOTA_ATF_CAMBIO" to 80.0, "ATRV" to 14.1)).isEmpty())
        val textos = ClusterPainel.alertas(
            dados("0105" to 108.0, "TOYOTA_ATF_CAMBIO" to 125.0, "ATRV" to 11.5),
        ).map { it.texto }
        assertEquals(listOf("ÁGUA QUENTE", "ATF QUENTE", "BATERIA BAIXA"), textos)
        assertEquals(listOf("BATERIA ALTA"), ClusterPainel.alertas(dados("ATRV" to 15.6)).map { it.texto })
    }

    @Test
    fun `agua e ATF seguem os limites do veiculo e o alternador so vale com o motor girando`() {
        val limites = LimitesProtecao(aguaMaxC = 98.0, atfMaxC = 105.0, tensaoMinV = 13.2)
        // 100 °C já passa dos 98 °C do cadastro (antes só avisava em 105 °C); ATF 106 passa dos 105.
        assertEquals(
            listOf("ÁGUA QUENTE", "ATF QUENTE", "ALTERNADOR BAIXO"),
            ClusterPainel.alertas(dados("0105" to 100.0, "TOYOTA_ATF_CAMBIO" to 106.0, "010C" to 900.0, "ATRV" to 12.8), limites).map { it.texto },
        )
        // Motor desligado (sem RPM): 12,8 V é só bateria em repouso, sem alerta do alternador.
        assertTrue(ClusterPainel.alertas(dados("ATRV" to 12.8), limites).isEmpty())
        // Limite maior no cadastro: 100 °C deixa de alertar.
        assertTrue(ClusterPainel.alertas(dados("0105" to 100.0), limites.copy(aguaMaxC = 105.0)).isEmpty())
    }
}
