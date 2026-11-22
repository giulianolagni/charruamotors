package com.charruamotors.direcao.hud

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.ui.diagnostico.obd.ObdViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatação e alertas do HUD flutuante, e a ponte de telemetria com o ObdViewModel. */
class PainelHudTest {

    private val dieselRodando = DadosObd(
        mapOf(
            Pids.RPM to 2350.25,
            Pids.PRESSAO_COLETOR to 182.0,
            Pids.TEMPERATURA_MOTOR to 92.0,
            Pids.PRESSAO_RAIL to 125_000.0, // kPa
            Pids.TENSAO_BATERIA to 14.1,
        ),
    )

    @Test
    fun `formata rpm com milhar map em kPa temperatura e rail em bar`() {
        val painel = FormatadorHud.montar(dieselRodando, conectado = true)
        assertEquals("2.350", painel.rpm)
        assertEquals("182", painel.map)
        assertEquals("92", painel.temperatura)
        assertEquals(TipoQuartoValor.RAIL, painel.tipoQuarto)
        assertEquals("1.250", painel.quarto)
        assertFalse(painel.alertaTemperatura)
        assertTrue(painel.conectado)
    }

    @Test
    fun `sem rail o quarto mostrador vira a tensao da bateria`() {
        val flex = DadosObd(mapOf(Pids.RPM to 800.0, Pids.TENSAO_BATERIA to 12.64))
        val painel = FormatadorHud.montar(flex, conectado = true)
        assertEquals(TipoQuartoValor.TENSAO, painel.tipoQuarto)
        assertEquals("12,6", painel.quarto)
        assertEquals(FormatadorHud.SEM_VALOR, painel.map)
        assertEquals(FormatadorHud.SEM_VALOR, painel.temperatura)
    }

    @Test
    fun `alerta vermelho so acima de 105 graus`() {
        assertFalse(FormatadorHud.alertaTemperatura(null))
        assertFalse(FormatadorHud.alertaTemperatura(104.9))
        assertFalse(FormatadorHud.alertaTemperatura(105.0))
        assertTrue(FormatadorHud.alertaTemperatura(105.1))
        assertTrue(FormatadorHud.montar(DadosObd(mapOf(Pids.TEMPERATURA_MOTOR to 112.0)), conectado = true).alertaTemperatura)
    }

    @Test
    fun `desconectado mostra tracos e nao alerta mesmo com dado antigo`() {
        val quente = DadosObd(mapOf(Pids.TEMPERATURA_MOTOR to 118.0, Pids.RPM to 3000.0))
        val painel = FormatadorHud.montar(quente, conectado = false)
        assertEquals(FormatadorHud.SEM_VALOR, painel.rpm)
        assertEquals(FormatadorHud.SEM_VALOR, painel.temperatura)
        assertEquals(FormatadorHud.SEM_VALOR, painel.quarto)
        assertFalse(painel.alertaTemperatura)
        assertFalse(painel.conectado)
    }

    @Test
    fun `valor invalido nao aparece no hud`() {
        val painel = FormatadorHud.montar(DadosObd(mapOf(Pids.RPM to Double.NaN)), conectado = true)
        assertEquals(FormatadorHud.SEM_VALOR, painel.rpm)
    }

    @Test
    fun `telemetria compartilhada entrega o painel do ultimo instantaneo`() = runTest {
        val telemetria = TelemetriaCompartilhada()
        assertFalse(telemetria.painel.first().conectado)
        telemetria.publicar(dieselRodando, conectado = true)
        assertEquals("2.350", telemetria.painel.first().rpm)
        telemetria.definirHudAtivo(true)
        assertTrue(telemetria.hudAtivo.value)
    }

    @Test
    fun `polling continua em segundo plano so com o hud aberto`() {
        assertTrue(ObdViewModel.devePollar(telaVisivel = true, hudAtivo = false, conectado = true))
        assertTrue(ObdViewModel.devePollar(telaVisivel = false, hudAtivo = true, conectado = true))
        assertFalse(ObdViewModel.devePollar(telaVisivel = false, hudAtivo = false, conectado = true))
        assertFalse(ObdViewModel.devePollar(telaVisivel = true, hudAtivo = true, conectado = false))
    }
}
