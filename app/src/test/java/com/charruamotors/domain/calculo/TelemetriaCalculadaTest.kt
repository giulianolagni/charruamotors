package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetriaCalculadaTest {

    private val gasolina = ParametrosMotor(cilindradaLitros = 1.6, tipoCombustivel = TipoCombustivel.GASOLINA)

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    // ---------------------------------------------------------------- combustíveis

    @Test
    fun `gasolina C tem AFR e densidade de E27`() {
        val c = Combustivel.GASOLINA_C
        assertEquals(13.2, c.relacaoEstequiometrica, 1e-9) // referência do Modo Flex (v2.1)
        assertEquals(758.6, c.densidadeGL, 0.1)
        assertEquals(Combustivel.GASOLINA_PURA, Combustivel.mistura(0.0))
        assertEquals(Combustivel.ETANOL_HIDRATADO, Combustivel.mistura(1.0))
        assertEquals(Combustivel.ETANOL_HIDRATADO, Combustivel.mistura(1.7)) // saturado
    }

    // ---------------------------------------------------------------- consumo por MAF

    @Test
    fun `consumo por MAF em L por hora e km por litro`() {
        // 10 g/s de ar / 13,2 → 0,758 g/s de gasolina C → × 3600 / 758,6 g/L = 3,60 L/h
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0, Pids.VELOCIDADE to 60.0), gasolina)!!
        assertEquals(MetodoConsumo.MAF, c.metodo)
        assertEquals(3.595, c.litrosPorHora, 0.005)
        assertEquals(60.0 / 3.595, c.kmPorLitro!!, 0.03)
    }

    @Test
    fun `etanol gasta mais litros com a mesma massa de ar`() {
        val etanol = gasolina.copy(tipoCombustivel = TipoCombustivel.ETANOL)
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0), etanol)!!
        assertEquals(10.0 / 9.0 * 3600 / 809.0, c.litrosPorHora, 1e-9) // 4,94 L/h
    }

    @Test
    fun `flex usa o percentual de etanol informado pela ECU e sem ele assume E27`() {
        val flex = gasolina.copy(tipoCombustivel = TipoCombustivel.FLEX)
        val e100 = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0, Pids.PERCENTUAL_ETANOL to 100.0), flex)!!
        assertEquals(10.0 / 9.0 * 3600 / 809.0, e100.litrosPorHora, 1e-9)
        val semPid = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0), flex)!!
        assertEquals(3.595, semPid.litrosPorHora, 0.005)
    }

    @Test
    fun `ajustes de combustivel e lambda comandado corrigem o consumo`() {
        val base = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0), gasolina)!!.litrosPorHora
        val comTrims = TelemetriaCalculada.consumoInstantaneo(
            dados(Pids.MAF to 10.0, Pids.STFT to 4.0, Pids.LTFT to 6.0), gasolina,
        )!!.litrosPorHora
        assertEquals(base * 1.10, comTrims, 1e-9) // ECU injetando 10 % a mais

        val enriquecido = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0, Pids.LAMBDA_COMANDADO to 0.88), gasolina)!!
        assertEquals(base / 0.88, enriquecido.litrosPorHora, 1e-9) // plena carga: mistura rica
        val lambdaAbsurdo = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 10.0, Pids.LAMBDA_COMANDADO to 0.1), gasolina)!!
        assertEquals(base, lambdaAbsurdo.litrosPorHora, 1e-9) // leitura implausível é ignorada
    }

    @Test
    fun `parado nao tem km por litro mas tem L por hora`() {
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.MAF to 2.5, Pids.VELOCIDADE to 0.0), gasolina)!!
        assertNull(c.kmPorLitro)
        assertTrue(c.litrosPorHora > 0.5)
        assertNull(TelemetriaCalculada.kmPorLitro(2.0, 1.0))
        assertNull(TelemetriaCalculada.kmPorLitro(null, 1.0))
        assertNull(TelemetriaCalculada.kmPorLitro(80.0, 0.0)) // corte de combustível
        assertEquals(20.0, TelemetriaCalculada.kmPorLitro(80.0, 4.0)!!, 1e-9)
    }

    // ---------------------------------------------------------------- fallback Speed-Density

    @Test
    fun `speed density calcula a massa de ar pela lei dos gases`() {
        // 3000 rpm / 120 · 1,6 L · 0,85 · 100 kPa / (0,287 · 300 K) = 39,49 g/s
        val ar = TelemetriaCalculada.massaArSpeedDensity(3000.0, 100.0, 26.85, gasolina)!!
        assertEquals(39.49, ar, 0.01)
        // Dobrar a cilindrada dobra o ar; ar mais quente é menos denso.
        val dobro = TelemetriaCalculada.massaArSpeedDensity(3000.0, 100.0, 26.85, gasolina.copy(cilindradaLitros = 3.2))!!
        assertEquals(2 * ar, dobro, 1e-9)
        assertTrue(TelemetriaCalculada.massaArSpeedDensity(3000.0, 100.0, 60.0, gasolina)!! < ar)
    }

    @Test
    fun `sem MAF o consumo cai automaticamente para speed density`() {
        val semMaf = dados(Pids.RPM to 3000.0, Pids.PRESSAO_COLETOR to 100.0, Pids.TEMPERATURA_AR_ADMISSAO to 26.85, Pids.VELOCIDADE to 100.0)
        val c = TelemetriaCalculada.consumoInstantaneo(semMaf, gasolina)!!
        assertEquals(MetodoConsumo.SPEED_DENSITY, c.metodo)
        assertEquals(39.49 / 13.2 * 3600 / 758.63, c.litrosPorHora, 0.02) // ≈ 14,2 L/h em aceleração plena

        // Com MAF presente, ele tem prioridade sobre a estimativa.
        val comMaf = TelemetriaCalculada.consumoInstantaneo(DadosObd(semMaf.valores + (Pids.MAF to 10.0)), gasolina)!!
        assertEquals(MetodoConsumo.MAF, comMaf.metodo)
    }

    @Test
    fun `sem MAF e sem algum dado do speed density nao ha consumo`() {
        assertNull(TelemetriaCalculada.consumoInstantaneo(dados(Pids.RPM to 3000.0, Pids.PRESSAO_COLETOR to 100.0), gasolina))
        assertNull(TelemetriaCalculada.consumoInstantaneo(dados(), gasolina))
        assertNull(TelemetriaCalculada.massaArSpeedDensity(3000.0, 100.0, -300.0, gasolina)) // abaixo do zero absoluto
        assertNull(TelemetriaCalculada.massaArSpeedDensity(-1.0, 100.0, 20.0, gasolina))
    }

    @Test
    fun `diesel sem MAF so usa o consumo informado pela ECU`() {
        val diesel = ParametrosMotor(2.8, tipoCombustivel = TipoCombustivel.DIESEL)
        // Sem MAF e sem 015E não há como estimar (o Speed-Density não vale no diesel).
        assertNull(TelemetriaCalculada.consumoInstantaneo(dados(Pids.RPM to 2000.0, Pids.PRESSAO_COLETOR to 150.0, Pids.TEMPERATURA_AR_ADMISSAO to 30.0), diesel))
        val c = TelemetriaCalculada.consumoInstantaneo(dados(Pids.CONSUMO_ECU to 6.5, Pids.VELOCIDADE to 90.0), diesel)!!
        assertEquals(MetodoConsumo.SENSOR_ECU, c.metodo)
        assertEquals(6.5, c.litrosPorHora, 0.0)
        assertEquals(90.0 / 6.5, c.kmPorLitro!!, 1e-9)
    }

    // ---------------------------------------------------------------- parâmetros do motor

    @Test
    fun `cilindrada e lida do modelo cadastrado`() {
        assertEquals(1.6, ParametrosMotor.deVeiculo("Gol 1.6 MSI", TipoCombustivel.FLEX).cilindradaLitros, 0.0)
        assertEquals(2.8, ParametrosMotor.deVeiculo("Hilux 2.8 D-4D", TipoCombustivel.DIESEL).cilindradaLitros, 0.0)
        assertEquals(1.0, ParametrosMotor.deVeiculo("Uno Way 1,0 Fire", null).cilindradaLitros, 0.0)
        assertEquals(2.0, ParametrosMotor.deVeiculo("Golf GTI 2.0 350TSI", null).cilindradaLitros, 0.0)
    }

    @Test
    fun `modelo sem cilindrada usa o padrao`() {
        listOf("Onix", "Gol G5 2012", "Civic 20.5", "Motor 9.9", null).forEach {
            assertEquals("$it", ParametrosMotor.CILINDRADA_PADRAO_L, ParametrosMotor.deVeiculo(it, null).cilindradaLitros, 0.0)
        }
        assertEquals(TipoCombustivel.FLEX, ParametrosMotor.deVeiculo(null, null).tipoCombustivel)
        assertThrows(IllegalArgumentException::class.java) { ParametrosMotor(cilindradaLitros = 0.1) }
    }

    // ---------------------------------------------------------------- computador de bordo

    @Test
    fun `computador de bordo integra distancia litros e media da viagem`() {
        val bordo = ComputadorBordo()
        val consumo = ConsumoInstantaneo(6.0, 10.0, MetodoConsumo.MAF)
        // 1 h a 60 km/h e 6 L/h, uma leitura por segundo
        for (s in 0..3600) bordo.registrar(consumo, 60.0, s * 1000L)
        val r = bordo.resumo
        assertEquals(60.0, r.distanciaKm, 1e-6)
        assertEquals(6.0, r.litros, 1e-6)
        assertEquals(10.0, r.mediaKmPorLitro!!, 1e-6)
        assertEquals(3600.0, r.segundos, 1e-6)
    }

    @Test
    fun `lacunas longas entre leituras contam no maximo o limite`() {
        val bordo = ComputadorBordo(maxIntervaloMs = 5_000)
        val consumo = ConsumoInstantaneo(3.6, null, MetodoConsumo.MAF)
        bordo.registrar(consumo, 36.0, 0) // primeira leitura: só marca o instante
        assertEquals(0.0, bordo.resumo.distanciaKm, 0.0)
        bordo.registrar(consumo, 36.0, 60_000) // aba pausada por 1 min: conta só 5 s
        assertEquals(0.05, bordo.resumo.distanciaKm, 1e-9)
        assertEquals(0.005, bordo.resumo.litros, 1e-9)
        assertNull(bordo.resumo.mediaKmPorLitro) // distância ainda pequena demais para média
    }

    @Test
    fun `sem consumo conta a distancia e zerar recomeca a viagem`() {
        val bordo = ComputadorBordo()
        bordo.registrar(null, 72.0, 0)
        bordo.registrar(null, 72.0, 1_000)
        assertEquals(0.02, bordo.resumo.distanciaKm, 1e-9)
        assertEquals(0.0, bordo.resumo.litros, 0.0)
        bordo.zerar()
        assertEquals(ResumoViagem(), bordo.resumo)
        bordo.registrar(null, 72.0, 500) // relógio "voltou": nada é integrado
        assertEquals(0.0, bordo.resumo.distanciaKm, 0.0)
    }
}
