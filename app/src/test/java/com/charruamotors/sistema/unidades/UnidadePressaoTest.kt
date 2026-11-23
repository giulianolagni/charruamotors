package com.charruamotors.sistema.unidades

import com.charruamotors.direcao.hud.FormatadorHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversão da pressão do Turbo/MAP (kPa → bar / psi) para o mostrador, a grade e o HUD. */
class UnidadePressaoTest {

    private val delta = 1e-6

    @Test
    fun `kPa nao converte`() {
        assertEquals(182.0, UnidadePressao.KPA.deKpa(182.0), delta)
    }

    @Test
    fun `kPa para bar divide por 100`() {
        assertEquals(1.0, UnidadePressao.BAR.deKpa(100.0), delta)
        assertEquals(1.82, UnidadePressao.BAR.deKpa(182.0), delta)
        assertEquals(1250.0, UnidadePressao.BAR.deKpa(125_000.0), delta)
    }

    @Test
    fun `kPa para psi usa 6,894757 kPa por psi`() {
        assertEquals(14.503774, UnidadePressao.PSI.deKpa(100.0), 1e-5)
        assertEquals(1.0, UnidadePressao.PSI.deKpa(6.894757293168361), delta)
        // Atmosfera padrão: 101,325 kPa ≈ 14,696 psi.
        assertEquals(14.6959, UnidadePressao.PSI.deKpa(101.325), 1e-4)
    }

    @Test
    fun `valor nulo continua nulo`() {
        assertNull(UnidadePressao.BAR.deKpa(null as Double?))
        assertEquals(0.5, UnidadePressao.BAR.deKpa(50.0 as Double?)!!, delta)
    }

    @Test
    fun `codigo salvo desconhecido volta para kPa`() {
        assertEquals(UnidadePressao.BAR, UnidadePressao.deCodigo("BAR"))
        assertEquals(UnidadePressao.PSI, UnidadePressao.deCodigo("psi"))
        assertEquals(UnidadePressao.KPA, UnidadePressao.deCodigo(null))
        assertEquals(UnidadePressao.KPA, UnidadePressao.deCodigo("atm"))
    }

    @Test
    fun `so PIDs em kPa sao convertidos na grade`() {
        assertTrue(UnidadePressao.ehPressao("kPa"))
        assertTrue(UnidadePressao.ehPressao("KPA"))
        assertFalse(UnidadePressao.ehPressao("°C"))
        assertFalse(UnidadePressao.ehPressao("rpm"))
        assertFalse(UnidadePressao.ehPressao(null))
    }

    @Test
    fun `casas decimais nunca ficam abaixo das do catalogo`() {
        assertEquals(0, UnidadePressao.KPA.casasPara(0))
        assertEquals(2, UnidadePressao.BAR.casasPara(0))
        assertEquals(1, UnidadePressao.PSI.casasPara(0))
        assertEquals(3, UnidadePressao.BAR.casasPara(3))
    }

    @Test
    fun `escala do mostrador equivale a de kPa`() {
        // A zona vermelha de 200 kPa (2 bar ≈ 29 psi) se mantém nas três unidades.
        assertEquals(UnidadePressao.BAR.deKpa(200.0), UnidadePressao.BAR.mostradorMap.zonaVermelha!!, delta)
        assertEquals(UnidadePressao.PSI.deKpa(200.0), UnidadePressao.PSI.mostradorMap.zonaVermelha!!, 0.1)
        UnidadePressao.entries.forEach { assertTrue(it.mostradorMap.maximo > it.mostradorMap.zonaVermelha!!) }
    }

    @Test
    fun `HUD mostra o turbo na unidade escolhida`() {
        val dados = DadosObd(mapOf(Pids.PRESSAO_COLETOR to 182.0))
        fun turbo(unidade: UnidadePressao, conectado: Boolean = true) =
            FormatadorHud.montar(dados, conectado, conversor = ConversorUnidades(SistemaUnidades.METRICO, unidade))
                .celula(SensorPainel.TURBO)!!

        assertEquals("182", FormatadorHud.montar(dados, conectado = true).celula(SensorPainel.TURBO)!!.valor)
        assertEquals("TURBO/MAP kPa", turbo(UnidadePressao.KPA).rotulo)
        assertEquals("1,82", turbo(UnidadePressao.BAR).valor)
        assertEquals("TURBO/MAP bar", turbo(UnidadePressao.BAR).rotulo)
        assertEquals("26,4", turbo(UnidadePressao.PSI).valor)
        assertEquals("TURBO/MAP psi", turbo(UnidadePressao.PSI).rotulo)
        assertEquals(FormatadorHud.SEM_VALOR, turbo(UnidadePressao.BAR, conectado = false).valor)
    }
}
