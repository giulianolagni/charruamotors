package com.charruamotors.domain.perfis

import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.CambioGm6T
import com.charruamotors.domain.faixas.FaixaEsperada
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.ParametroFaixa.BICOS
import com.charruamotors.domain.faixas.ParametroFaixa.BORBOLETA
import com.charruamotors.domain.faixas.ParametroFaixa.CARGA_MOTOR
import com.charruamotors.domain.faixas.ParametroFaixa.LTFT
import com.charruamotors.domain.faixas.ParametroFaixa.MAF
import com.charruamotors.domain.faixas.ParametroFaixa.MAP_TURBO
import com.charruamotors.domain.faixas.ParametroFaixa.PRESSAO_BARO
import com.charruamotors.domain.faixas.ParametroFaixa.RAIL
import com.charruamotors.domain.faixas.ParametroFaixa.RAIL_LENTA
import com.charruamotors.domain.faixas.ParametroFaixa.RPM_LENTA
import com.charruamotors.domain.faixas.ParametroFaixa.RPM_MAXIMO
import com.charruamotors.domain.faixas.ParametroFaixa.STFT
import com.charruamotors.domain.faixas.ParametroFaixa.TEMP_ADMISSAO
import com.charruamotors.domain.faixas.ParametroFaixa.TEMP_AGUA
import com.charruamotors.domain.faixas.ParametroFaixa.TEMP_ATF
import com.charruamotors.domain.faixas.ParametroFaixa.TENSAO
import com.charruamotors.domain.faixas.ParametroFaixa.TENSAO_ECU
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.2: a SW4 2013 e o Cruze 1.4T saíram do enum para o catálogo JSON sem mudar nada. As listas
 * abaixo são cópia literal do `PresetVeiculo` da v2.1.5 (mesma ordem, mesmos limites).
 */
class CompatibilidadeV215Test {

    private val sw4V215 = FaixasVeiculo(
        "Toyota Hilux SW4 3.0 D-4D 2013 (1KD-FTV)",
        listOf(
            FaixaEsperada(RPM_LENTA, 650.0, 800.0),
            FaixaEsperada(RPM_MAXIMO, null, 4_200.0),
            FaixaEsperada(TEMP_AGUA, 80.0, 98.0),
            FaixaEsperada(TEMP_ATF, 60.0, 105.0),
            FaixaEsperada(TENSAO, 13.4, 14.6),
            FaixaEsperada(RAIL, 280.0, 1_800.0),
            FaixaEsperada(RAIL_LENTA, 280.0, 600.0),
            FaixaEsperada(MAP_TURBO, 60.0, 215.0),
            FaixaEsperada(BICOS, -3.0, 3.0),
            FaixaEsperada(CARGA_MOTOR, 0.0, 100.0),
            FaixaEsperada(TEMP_ADMISSAO, 10.0, 65.0),
            FaixaEsperada(MAF, 10.0, 170.0),
            FaixaEsperada(BORBOLETA, 0.0, 100.0),
            FaixaEsperada(PRESSAO_BARO, 85.0, 105.0),
            FaixaEsperada(TENSAO_ECU, 13.4, 14.6),
        ),
    )

    private val cruzeV215 = FaixasVeiculo(
        "Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)",
        listOf(
            FaixaEsperada(RPM_LENTA, 600.0, 850.0),
            FaixaEsperada(RPM_MAXIMO, null, 6_000.0),
            FaixaEsperada(TEMP_AGUA, 85.0, 108.0),
            FaixaEsperada(TEMP_ATF, 60.0, 105.0),
            FaixaEsperada(TENSAO, 12.6, 14.8),
            FaixaEsperada(RAIL, 30.0, 200.0),
            FaixaEsperada(RAIL_LENTA, 30.0, 60.0),
            FaixaEsperada(MAP_TURBO, 25.0, 215.0),
            FaixaEsperada(STFT, -10.0, 10.0),
            FaixaEsperada(LTFT, -10.0, 10.0),
            FaixaEsperada(CARGA_MOTOR, 0.0, 100.0),
            FaixaEsperada(TEMP_ADMISSAO, 10.0, 65.0),
        ),
    )

    @Test
    fun `SW4 2013 com exatamente as faixas e os dados da v2_1_5`() {
        val p = PresetVeiculo.SW4_2013
        assertEquals(sw4V215, p.faixas)
        assertEquals("Toyota", p.marca)
        assertEquals("Hilux SW4 3.0 D-4D", p.modelo)
        assertEquals(2013, p.ano)
        assertEquals(TipoCombustivel.DIESEL, p.combustivel)
        assertEquals("1KD-FTV", p.motor)
        assertEquals("ATSP6", p.protocolo)
        assertEquals("ISO 15765-4 CAN 11 bits / 500 kbps (M-OBD, KWP modo 21)", p.descricaoProtocolo)
        assertEquals("7E0", p.ecuMotor)
        assertEquals("7E1", p.ecuCambio)
        assertEquals("21", p.servicoMontadora)
        assertEquals(98, p.limiteAguaC)
        assertEquals(105, p.limiteAtfC)
        assertEquals(13.4, p.tensaoMinV, 0.0)
        assertEquals(StatusPerfil.VALIDADO, p.status)
        assertEquals("ECU do motor 7E0 · TCM do câmbio 7E1 · serviço 21", p.linhasRede.last())
    }

    @Test
    fun `Cruze 1_4T com exatamente as faixas e os dados da v2_1_5`() {
        val p = PresetVeiculo.CRUZE_2018
        assertEquals(cruzeV215, p.faixas)
        assertEquals("Chevrolet", p.marca)
        assertEquals("Cruze 1.4 Turbo Flex", p.modelo)
        assertEquals(2018, p.ano)
        assertEquals(TipoCombustivel.FLEX, p.combustivel)
        assertEquals("LE2", p.motor)
        assertEquals("ATSP6", p.protocolo)
        assertEquals("ISO 15765-4 CAN 11 bits / 500 kbps (UDS ISO 14229)", p.descricaoProtocolo)
        assertEquals("7E0", p.ecuMotor)
        assertEquals("7E1", p.ecuCambio)
        assertEquals(listOf("7E1", "7E2"), p.perfilCambio.cabecalhos)
        assertEquals("22", p.servicoMontadora)
        assertEquals(108, p.limiteAguaC)
        assertEquals(105, p.limiteAtfC)
        assertEquals(12.6, p.tensaoMinV, 0.0)
        assertEquals(StatusPerfil.BASE, p.status)
    }

    @Test
    fun `detecao pelo cadastro igual a da v2_1_5`() {
        val sw4 = PresetVeiculo.SW4_2013
        val cruze = PresetVeiculo.CRUZE_2018
        assertEquals(sw4, PresetVeiculo.detectar("Toyota", "Hilux SW4 3.0 D-4D", 2013))
        assertEquals(sw4, PresetVeiculo.detectar("TOYOTA", "SW4 1KD", 2018))
        assertEquals(sw4, PresetVeiculo.detectar("Toyota", "SW4 SRV", 2011))
        assertEquals(sw4, PresetVeiculo.detectar("Toyota", "Hilux 3,0", 2019))
        assertEquals(sw4, PresetVeiculo.detectar("Toyota", "Hilux CD", 2005))
        assertNull(PresetVeiculo.detectar("Toyota", "Hilux CD", 2016))
        assertNull(PresetVeiculo.detectar("Toyota", "SW4 2.8 GD", 2020))
        assertNull(PresetVeiculo.detectar("Toyota", "Corolla 2.0", 2013))
        assertEquals(cruze, PresetVeiculo.detectar("Chevrolet", "Cruze 1.4 Turbo Flex", 2018))
        assertEquals(cruze, PresetVeiculo.detectar("GM", "Cruze LTZ", 2019))
        assertEquals(cruze, PresetVeiculo.detectar("Chevrolet / GM", "Cruze LT", 2017))
        assertEquals(cruze, PresetVeiculo.detectar("General Motors", "Cruze LE2", 2012))
        assertNull(PresetVeiculo.detectar("Chevrolet", "Cruze 1.8", 2013))
        assertNull(PresetVeiculo.detectar("Chevrolet", "Onix 1.0", 2018))
        assertNull(PresetVeiculo.detectar(null, null, null))
    }

    @Test
    fun `cadastros salvos no Room continuam reconhecidos sem migracao`() {
        val sw4 = Veiculo(marca = "Toyota", modelo = "Hilux SW4 3.0", ano = 2013, placa = "ABC1234", kmAtual = 0,
            tipoCombustivel = TipoCombustivel.DIESEL, tempMaxAguaC = 95)
        // O perfil exato manda nas faixas, como na v2.1.5 (o limite do cadastro só vale nos genéricos).
        assertEquals(sw4V215, FaixasVeiculo.paraVeiculo(sw4))
        val cruze = sw4.copy(marca = "Chevrolet", modelo = "Cruze LTZ 1.4", ano = 2018, tipoCombustivel = TipoCombustivel.FLEX)
        assertEquals(cruzeV215, FaixasVeiculo.paraVeiculo(cruze))
    }

    @Test
    fun `marcha pelas razoes do JSON igual a das regras da A750F e do 6T35`() {
        val a750f = PresetVeiculo.SW4_2013.perfilCambio
        val gm = PresetVeiculo.CRUZE_2018.perfilCambio
        val velocidades = listOf(4.9, 5.0, 10.0, 23.0, 47.0, 80.0, 121.0)
        for (v in velocidades) {
            var rpm = 0.0
            while (rpm <= 6_500.0) {
                assertEquals("A750F $rpm/$v", CambioA750f.marchaPorRazao(rpm, v), a750f.marchaPorRazao(rpm, v))
                assertEquals("6T35 $rpm/$v", CambioGm6T.marchaPorRazao(rpm, v), gm.marchaPorRazao(rpm, v))
                rpm += 7.0
            }
        }
        // Fronteiras exatas das regras (≥ 85 = 1ª na A750F; ≥ 115 = 1ª no 6T35).
        assertEquals(1, a750f.marchaPorRazao(850.0, 10.0))
        assertEquals(2, a750f.marchaPorRazao(849.0, 10.0))
        assertEquals(5, a750f.marchaPorRazao(259.0, 10.0))
        assertEquals(1, gm.marchaPorRazao(1_150.0, 10.0))
        assertEquals(6, gm.marchaPorRazao(269.0, 10.0))
        assertNull(gm.marchaPorRazao(null, 50.0))
        assertNull(gm.marchaPorRazao(2_000.0, Double.NaN))
    }
}
