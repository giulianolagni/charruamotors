package com.charruamotors.direcao.hud

import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.EstimadorCambioGm6T
import com.charruamotors.domain.faixas.FaixaEsperada
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.EscalonadorPolling
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.2.9: HUD Esportivo com a escala de turbo pelo teto de boost do perfil (SW4 x Cruze x aspirado). */
class HudEsportivoCruzeTest {

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    private val sw4 = PresetVeiculo.SW4_2013.faixas
    private val cruze = PresetVeiculo.CRUZE_2018.faixas

    private fun rotulos(e: EscalaTurbo) = e.rotulos.map { Math.round(it * 10) / 10.0 }

    @Test
    fun `turbo da SW4 continua 0,0 a 1,2 e o Cruze 1_4T vai ate 1,6`() {
        val escalaSw4 = EscalaTurbo.para(sw4[ParametroFaixa.MAP_TURBO]!!.max)
        assertEquals(listOf(0.0, 0.4, 0.8, 1.2), rotulos(escalaSw4))
        assertEquals(1.25, escalaSw4.redlineBar, 1e-9)
        assertFalse(escalaSw4.aspirado)

        val escalaCruze = EscalaTurbo.para(cruze[ParametroFaixa.MAP_TURBO]!!.max)
        assertEquals(listOf(0.0, 0.4, 0.8, 1.2, 1.6), rotulos(escalaCruze))
        assertEquals(1.65, escalaCruze.redlineBar, 1e-9)
        assertFalse(escalaCruze.aspirado)
        // 1,5 bar de boost no Cruze fica dentro do arco, antes do vermelho.
        assertTrue(escalaCruze.fracao(1.5) < escalaCruze.fracaoRedline)
        assertTrue(escalaSw4.fracao(1.5) >= escalaSw4.fracaoRedline)
    }

    @Test
    fun `aspirado fica com o ponteiro travado em 0,0`() {
        // MAP máximo do generico_flex_aspirado.json (105 kPa ≈ 1 atm).
        val aspirado = FaixasVeiculo("Flex aspirado", listOf(FaixaEsperada(ParametroFaixa.MAP_TURBO, 20.0, 105.0)))
        val calibrador = CalibradorPedal()

        val e = FormatadorHudEsportivo.montar(dados(Pids.PRESSAO_COLETOR to 104.0, "0133" to 98.0), true, aspirado, calibrador)

        assertTrue(e.escalaTurbo.aspirado)
        assertEquals(0.0, e.boostBar, 0.0)
        // No turbo o mesmo MAP já mostra pressão positiva.
        val t = FormatadorHudEsportivo.montar(dados(Pids.PRESSAO_COLETOR to 250.0, "0133" to 98.0), true, cruze, calibrador)
        assertEquals(1.52, t.boostBar, 1e-9)
    }

    @Test
    fun `Cruze escala o conta-giros ate 8 com o redline de 6_500`() {
        val e = FormatadorHudEsportivo.montar(dados(Pids.RPM to 6_000.0), true, cruze, CalibradorPedal())
        assertEquals(EscalaRpm(6_500.0, 8), e.escalaRpm)
        assertTrue(e.shiftLight) // 6.000 ≥ 90 % de 6.500
        assertEquals(6, EscalaRpm.para(sw4[ParametroFaixa.RPM_MAXIMO]!!.max).fimMil) // SW4 igual
    }

    @Test
    fun `marchas 1 a 6 do 6T35 sem sufixo L e N parado sem alavanca`() {
        val estimador = EstimadorCambioGm6T()
        val marchas = listOf(10.0 to 1_500.0, 30.0 to 2_500.0, 50.0 to 2_800.0, 70.0 to 2_800.0, 90.0 to 2_800.0, 110.0 to 2_000.0)
            .map { (kmh, rpm) -> FormatadorHudEsportivo.marcha(estimador.aplicar(dados(Pids.RPM to rpm, Pids.VELOCIDADE to kmh))) }
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), marchas)
        assertTrue(marchas.none { it.endsWith("L") })
        val parado = estimador.aplicar(dados(Pids.RPM to 800.0, Pids.VELOCIDADE to 0.0))
        assertEquals(CambioA750f.MARCHA_PARADO.toDouble(), parado[CambioA750f.CHAVE_MARCHA]!!, 0.0)
        assertEquals("N", FormatadorHudEsportivo.marcha(parado))
    }

    @Test
    fun `arco PED do Cruze pelo 0149 e a borboleta como reserva`() {
        val calibrador = CalibradorPedal()
        FormatadorHudEsportivo.montar(dados(Pids.PEDAL_ACELERADOR to 14.0, Pids.BORBOLETA to 90.0), true, cruze, calibrador)
        val pisado = FormatadorHudEsportivo.montar(dados(Pids.PEDAL_ACELERADOR to 54.0, Pids.BORBOLETA to 10.0), true, cruze, calibrador)
        assertEquals(1f, pisado.pedal!!, 1e-6f) // a borboleta foi ignorada: o 0149 manda
        assertEquals(14.0, calibrador.pedalMin!!, 0.0)

        val semPedal = CalibradorPedal()
        FormatadorHudEsportivo.montar(dados(Pids.BORBOLETA to 15.0), true, cruze, semPedal)
        assertEquals(15.0, semPedal.pedalMin!!, 0.0)
    }

    @Test
    fun `escalonador poe o pedal na fila rapida so com o lote ativo`() {
        val base = Pids.candidatosFilaRapida(consumoPorMaf = true)
        val e = EscalonadorPolling(candidatosRapidos = base)
        val suportados = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE, Pids.MAF, Pids.PEDAL_ACELERADOR, Pids.TEMPERATURA_MOTOR)
        e.atualizarSuportados(suportados)
        assertFalse(Pids.PEDAL_ACELERADOR in e.filaRapida)

        e.trocarCandidatosRapidos(base + Pids.PEDAL_ACELERADOR)
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.VELOCIDADE, Pids.MAF, Pids.PEDAL_ACELERADOR), e.filaRapida)
        assertEquals(listOf(Pids.TEMPERATURA_MOTOR), e.filaLenta)

        e.trocarCandidatosRapidos(base) // lote desligado: o pedal volta à fila lenta
        assertEquals(listOf(Pids.PEDAL_ACELERADOR, Pids.TEMPERATURA_MOTOR), e.filaLenta)
    }
}
