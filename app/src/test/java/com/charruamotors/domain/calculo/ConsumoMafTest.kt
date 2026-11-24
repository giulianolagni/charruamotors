package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.log.EstatisticasViagem
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7: consumo real pelo MAF (g/s) e pela velocidade (km/h), em Diesel Common Rail (1KD-FTV) e
 * Gasolina/Flex: parado só L/h, em movimento também km/L, calibração por veículo e o resumo da viagem.
 */
class ConsumoMafTest {

    private val gasolina = ParametrosMotor(cilindradaLitros = 1.6, tipoCombustivel = TipoCombustivel.GASOLINA)
    private val flex = ParametrosMotor(cilindradaLitros = 1.6, tipoCombustivel = TipoCombustivel.FLEX)
    private val diesel = ParametrosMotor.deVeiculo("SW4 3.0 1KD-FTV", TipoCombustivel.DIESEL)

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    /** L/h esperado: ar (g/s) ÷ AFR × 3600 ÷ densidade (g/L). */
    private fun litrosPorHora(maf: Double, c: Combustivel) = maf / c.relacaoEstequiometrica * 3600.0 / c.densidadeGL

    // ---------------------------------------------------------------- gasolina / flex

    @Test
    fun `gasolina parada mostra so litros por hora`() {
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 2.5, Pids.VELOCIDADE to 0.0), gasolina)!!
        assertEquals(MetodoConsumo.MAF, c.metodo)
        assertEquals(litrosPorHora(2.5, Combustivel.GASOLINA_C), c.litrosPorHora, 1e-9)
        assertEquals(0.90, c.litrosPorHora, 0.01) // marcha lenta típica: ~0,9 L/h (E27 a 13,2:1)
        assertNull(c.kmPorLitro)
    }

    @Test
    fun `gasolina em movimento calcula km por litro`() {
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 18.0, Pids.VELOCIDADE to 80.0), gasolina)!!
        val lh = litrosPorHora(18.0, Combustivel.GASOLINA_C)
        assertEquals(lh, c.litrosPorHora, 1e-9)
        assertEquals(80.0 / lh, c.kmPorLitro!!, 1e-9)
        assertEquals(12.36, c.kmPorLitro!!, 0.1) // cruzeiro de um carro 1.6 flex: ~12 km/L
    }

    @Test
    fun `flex sem leitura de etanol assume gasolina comum e com etanol gasta mais`() {
        val base = dados(Pids.MAF to 14.0, Pids.VELOCIDADE to 80.0)
        val semEtanol = TelemetriaCalculada.consumoInstantaneo(base, flex)!!
        assertEquals(TelemetriaCalculada.consumoInstantaneo(base, gasolina)!!.litrosPorHora, semEtanol.litrosPorHora, 1e-9)
        val e100 = TelemetriaCalculada.consumoInstantaneo(DadosObd(base.valores + (Pids.PERCENTUAL_ETANOL to 100.0)), flex)!!
        assertTrue(e100.litrosPorHora > semEtanol.litrosPorHora)
    }

    @Test
    fun `gasolina corrige pelos ajustes de combustivel da ECU`() {
        val puro = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 14.0), gasolina)!!
        val rico = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 14.0, Pids.STFT to 5.0, Pids.LTFT to 5.0), gasolina)!!
        assertEquals(puro.litrosPorHora * 1.10, rico.litrosPorHora, 1e-9)
    }

    // ---------------------------------------------------------------- diesel Common Rail 1KD-FTV

    @Test
    fun `diesel common rail usa a relacao ar combustivel efetiva`() {
        assertEquals(Combustivel.DIESEL_COMMON_RAIL, diesel.combustivel())
        assertEquals(3.0, diesel.cilindradaLitros, 0.0)
    }

    @Test
    fun `diesel parado mostra so litros por hora pelo MAF`() {
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 8.0, Pids.VELOCIDADE to 0.0), diesel)!!
        assertEquals(MetodoConsumo.MAF, c.metodo)
        assertEquals(litrosPorHora(8.0, Combustivel.DIESEL_COMMON_RAIL), c.litrosPorHora, 1e-9)
        assertEquals(1.28, c.litrosPorHora, 0.01) // marcha lenta do 3.0 turbo diesel: ~1,3 L/h
        assertNull(c.kmPorLitro)
    }

    @Test
    fun `diesel em movimento calcula km por litro pelo MAF`() {
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 60.0, Pids.VELOCIDADE to 90.0), diesel)!!
        val lh = litrosPorHora(60.0, Combustivel.DIESEL_COMMON_RAIL)
        assertEquals(lh, c.litrosPorHora, 1e-9)
        assertEquals(90.0 / lh, c.kmPorLitro!!, 1e-9)
        assertEquals(9.4, c.kmPorLitro!!, 0.1) // SW4 diesel a 90 km/h: ~9-10 km/L
    }

    @Test
    fun `diesel ignora lambda e ajustes de combustivel que nao existem nele`() {
        val base = dados(Pids.MAF to 60.0)
        val comRuido = dados(Pids.MAF to 60.0, Pids.STFT to 20.0, Pids.LTFT to 20.0, Pids.LAMBDA_COMANDADO to 0.8)
        assertEquals(
            TelemetriaCalculada.consumoInstantaneo(base, diesel)!!.litrosPorHora,
            TelemetriaCalculada.consumoInstantaneo(comRuido, diesel)!!.litrosPorHora,
            1e-9,
        )
    }

    @Test
    fun `diesel prefere o MAF e cai no 015E sem MAF`() {
        val comAmbos = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 60.0, Pids.CONSUMO_ECU to 6.5), diesel)!!
        assertEquals(MetodoConsumo.MAF, comAmbos.metodo)
        val soEcu = TelemetriaCalculada.consumoInstantaneo(dados(Pids.CONSUMO_ECU to 6.5), diesel)!!
        assertEquals(MetodoConsumo.SENSOR_ECU, soEcu.metodo)
        assertNull(TelemetriaCalculada.consumoInstantaneo(dados(Pids.VELOCIDADE to 80.0), diesel))
    }

    // ---------------------------------------------------------------- calibração por veículo

    @Test
    fun `calibracao do veiculo multiplica o consumo estimado`() {
        val base = dados(Pids.MAF to 60.0, Pids.VELOCIDADE to 90.0)
        val padrao = TelemetriaCalculada.consumoInstantaneo(base, diesel)!!
        val ajustado = TelemetriaCalculada.consumoInstantaneo(base, ParametrosMotor.deVeiculo("SW4 3.0", TipoCombustivel.DIESEL, 1.25))!!
        assertEquals(padrao.litrosPorHora * 1.25, ajustado.litrosPorHora, 1e-9)
        assertEquals(padrao.kmPorLitro!! / 1.25, ajustado.kmPorLitro!!, 1e-9)
    }

    @Test
    fun `calibracao fora da faixa e recusada no motor e ignorada no cadastro`() {
        assertThrows(IllegalArgumentException::class.java) { ParametrosMotor(calibracao = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ParametrosMotor(calibracao = 9.0) }
        assertEquals(1.0, ParametrosMotor.deVeiculo("Gol 1.6", TipoCombustivel.FLEX, 9.0).calibracao, 0.0)
    }

    // ---------------------------------------------------------------- resumo da viagem

    @Test
    fun `resumo da viagem traz media e litros estimados pelo MAF`() {
        val estatisticas = EstatisticasViagem(diesel)
        // 1 h a 90 km/h com MAF constante de 60 g/s, uma amostra por segundo.
        val amostra = dados(Pids.MAF to 60.0, Pids.VELOCIDADE to 90.0)
        for (s in 0..3600) estatisticas.registrar(amostra, s * 1000L)
        val lh = litrosPorHora(60.0, Combustivel.DIESEL_COMMON_RAIL)
        val r = estatisticas.consumo
        assertEquals(90.0, r.distanciaKm, 1e-6)
        assertEquals(lh, r.litros, 1e-6)
        assertEquals(90.0 / lh, r.mediaKmPorLitro!!, 1e-6)
    }

    @Test
    fun `sem veiculo ou sem horario o resumo nao inventa consumo`() {
        val amostra = dados(Pids.MAF to 60.0, Pids.VELOCIDADE to 90.0)
        val semMotor = EstatisticasViagem()
        for (s in 0..60) semMotor.registrar(amostra, s * 1000L)
        assertEquals(0.0, semMotor.consumo.litros, 0.0)
        assertNull(semMotor.consumo.mediaKmPorLitro)

        val semHorario = EstatisticasViagem(diesel)
        semHorario.registrar(amostra)
        assertEquals(1, semHorario.amostras)
        assertNotNull(semHorario.rpm)
        assertEquals(0.0, semHorario.consumo.litros, 0.0)
    }
}
