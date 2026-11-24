package com.charruamotors.direcao.hud

import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.EstiloHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatação, tamanhos, sensores, estilos e alertas do HUD flutuante, e a ponte de telemetria. */
class PainelHudTest {

    private val dieselRodando = DadosObd(
        mapOf(
            Pids.RPM to 2350.25,
            Pids.PRESSAO_COLETOR to 182.0,
            Pids.TEMPERATURA_MOTOR to 92.0,
            Pids.PRESSAO_RAIL to 125_000.0, // kPa
            Pids.TENSAO_BATERIA to 14.1,
            Pids.VELOCIDADE to 100.0,
        ),
    )

    private val imperial = ConversorUnidades(SistemaUnidades.IMPERIAL)

    @Test
    fun `expandido padrao formata rpm com milhar map em kPa temperatura e rail em bar`() {
        val painel = FormatadorHud.montar(dieselRodando, conectado = true)
        assertEquals(listOf(SensorPainel.RPM, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR, SensorPainel.RAIL), painel.celulas.map { it.sensor })
        assertEquals("2.350", painel.celula(SensorPainel.RPM)!!.valor)
        assertEquals("RPM", painel.celula(SensorPainel.RPM)!!.rotulo)
        assertEquals("182", painel.celula(SensorPainel.TURBO)!!.valor)
        assertEquals("92", painel.celula(SensorPainel.TEMP_MOTOR)!!.valor)
        assertEquals("MOTOR °C", painel.celula(SensorPainel.TEMP_MOTOR)!!.rotulo)
        assertEquals("1.250", painel.celula(SensorPainel.RAIL)!!.valor)
        assertEquals("RAIL bar", painel.celula(SensorPainel.RAIL)!!.rotulo)
        assertFalse(painel.alertaTemperatura)
        assertTrue(painel.conectado)
    }

    @Test
    fun `minimizado mostra so RPM e Turbo mesmo com outros sensores escolhidos`() {
        val config = ConfigHud(TamanhoHud.MINIMIZADO, listOf(SensorPainel.VELOCIDADE, SensorPainel.TENSAO))
        val painel = FormatadorHud.montar(dieselRodando, conectado = true, config)
        assertEquals(listOf(SensorPainel.RPM, SensorPainel.TURBO), painel.celulas.map { it.sensor })
        assertEquals(TamanhoHud.MINIMIZADO, painel.tamanho)
    }

    @Test
    fun `expandido respeita os sensores fixados sem repetir e no maximo 4`() {
        val escolhidos = listOf(
            SensorPainel.VELOCIDADE, SensorPainel.VELOCIDADE, SensorPainel.TENSAO,
            SensorPainel.CARGA, SensorPainel.RPM, SensorPainel.MAF,
        )
        val config = ConfigHud(TamanhoHud.EXPANDIDO, escolhidos, EstiloHud.BARRAS)
        assertEquals(listOf(SensorPainel.VELOCIDADE, SensorPainel.TENSAO, SensorPainel.CARGA, SensorPainel.RPM), config.sensoresVisiveis)
        val painel = FormatadorHud.montar(dieselRodando, conectado = true, config)
        assertEquals("14,1", painel.celula(SensorPainel.TENSAO)!!.valor)
        assertEquals(EstiloHud.BARRAS, painel.estilo)
        // Barra: fração da escala (100 km/h em 0–240).
        assertEquals(100f / 240f, painel.celula(SensorPainel.VELOCIDADE)!!.fracao!!, 1e-4f)
    }

    @Test
    fun `alternar sensor no expandido nunca esvazia nem passa de 4`() {
        var config = ConfigHud(sensores = listOf(SensorPainel.RPM))
        config = config.alternarSensor(SensorPainel.RPM)
        assertEquals(listOf(SensorPainel.RPM), config.sensores)
        config = config.alternarSensor(SensorPainel.TURBO).alternarSensor(SensorPainel.TENSAO).alternarSensor(SensorPainel.CARGA)
        assertEquals(4, config.sensores.size)
        assertEquals(config, config.alternarSensor(SensorPainel.MAF))
        assertEquals(3, config.alternarSensor(SensorPainel.TURBO).sensores.size)
    }

    @Test
    fun `sem rail a celula do rail vira a tensao da bateria`() {
        val flex = DadosObd(mapOf(Pids.RPM to 800.0, Pids.TENSAO_BATERIA to 12.64))
        val painel = FormatadorHud.montar(flex, conectado = true)
        assertNull(painel.celula(SensorPainel.RAIL))
        assertEquals("12,6", painel.celula(SensorPainel.TENSAO)!!.valor)
        assertEquals("BATERIA V", painel.celula(SensorPainel.TENSAO)!!.rotulo)
        assertEquals(FormatadorHud.SEM_VALOR, painel.celula(SensorPainel.TURBO)!!.valor)
        assertEquals(FormatadorHud.SEM_VALOR, painel.celula(SensorPainel.TEMP_MOTOR)!!.valor)
    }

    @Test
    fun `imperial converte velocidade temperatura turbo e rail no HUD`() {
        val config = ConfigHud(sensores = listOf(SensorPainel.VELOCIDADE, SensorPainel.TEMP_MOTOR, SensorPainel.TURBO, SensorPainel.RAIL))
        val painel = FormatadorHud.montar(dieselRodando, conectado = true, config, imperial)
        assertEquals("62", painel.celula(SensorPainel.VELOCIDADE)!!.valor)
        assertEquals("VELOC. mph", painel.celula(SensorPainel.VELOCIDADE)!!.rotulo)
        assertEquals("198", painel.celula(SensorPainel.TEMP_MOTOR)!!.valor) // 92 °C = 197,6 °F
        assertEquals("MOTOR °F", painel.celula(SensorPainel.TEMP_MOTOR)!!.rotulo)
        assertEquals("26,4", painel.celula(SensorPainel.TURBO)!!.valor)
        assertEquals("18.130", painel.celula(SensorPainel.RAIL)!!.valor) // 1.250 bar ≈ 18.130 psi
        assertEquals("RAIL psi", painel.celula(SensorPainel.RAIL)!!.rotulo)
    }

    @Test
    fun `alerta vermelho so acima de 105 graus e tambem no imperial`() {
        assertFalse(FormatadorHud.alertaTemperatura(null))
        assertFalse(FormatadorHud.alertaTemperatura(104.9))
        assertFalse(FormatadorHud.alertaTemperatura(105.0))
        assertTrue(FormatadorHud.alertaTemperatura(105.1))
        val quente = DadosObd(mapOf(Pids.TEMPERATURA_MOTOR to 112.0))
        assertTrue(FormatadorHud.montar(quente, conectado = true).alertaTemperatura)
        assertTrue(FormatadorHud.montar(quente, conectado = true, conversor = imperial).alertaTemperatura)
    }

    @Test
    fun `desconectado mostra tracos e nao alerta mesmo com dado antigo`() {
        val quente = DadosObd(mapOf(Pids.TEMPERATURA_MOTOR to 118.0, Pids.RPM to 3000.0))
        val painel = FormatadorHud.montar(quente, conectado = false)
        assertTrue(painel.celulas.all { it.valor == FormatadorHud.SEM_VALOR && it.fracao == null })
        assertFalse(painel.alertaTemperatura)
        assertFalse(painel.conectado)
    }

    @Test
    fun `valor invalido nao aparece no hud`() {
        val painel = FormatadorHud.montar(DadosObd(mapOf(Pids.RPM to Double.NaN)), conectado = true)
        assertEquals(FormatadorHud.SEM_VALOR, painel.celula(SensorPainel.RPM)!!.valor)
    }

    @Test
    fun `telemetria compartilhada entrega o painel do ultimo instantaneo e reage a configuracao`() = runTest {
        val config = MutableStateFlow(ConfigHud())
        val conversor = MutableStateFlow(ConversorUnidades.PADRAO)
        val telemetria = TelemetriaCompartilhada(conversor, config)
        assertFalse(telemetria.painel.first().conectado)
        telemetria.publicar(dieselRodando, conectado = true)
        assertEquals("2.350", telemetria.painel.first().celula(SensorPainel.RPM)!!.valor)
        config.value = ConfigHud(TamanhoHud.MINIMIZADO)
        assertEquals(2, telemetria.painel.first().celulas.size)
        conversor.value = imperial
        assertEquals("TURBO/MAP psi", telemetria.painel.first().celula(SensorPainel.TURBO)!!.rotulo)
        telemetria.definirHudAtivo(true)
        assertTrue(telemetria.hudAtivo.value)
    }
}
