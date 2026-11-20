package com.charruamotors.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    private fun <T> RespostaObd<T>.valor(): T = (this as RespostaObd.Ok<T>).valor

    // --- RPM (010C) ---

    @Test
    fun `rpm decodifica 256A mais B dividido por 4`() {
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8\r\r>").valor()) // 0x1AF8 = 6904 / 4
    }

    @Test
    fun `rpm sem espacos e em minusculas`() {
        assertEquals(1726, ObdParser.parseRpm("410c1af8>").valor())
    }

    @Test
    fun `rpm ignora eco do comando e searching`() {
        val bruto = "010C\rSEARCHING...\r41 0C 0F A0\r\r>"
        assertEquals(1000, ObdParser.parseRpm(bruto).valor())
    }

    @Test
    fun `rpm com resposta de duas ECUs usa a primeira`() {
        assertEquals(1000, ObdParser.parseRpm("41 0C 0F A0\r41 0C 1F 40\r>").valor())
    }

    @Test
    fun `rpm ignora caracteres de lixo e controle`() {
        assertEquals(1000, ObdParser.parseRpm("\u0000\u0007\r41 0C 0F A0\u0000\r>").valor())
    }

    @Test
    fun `rpm truncado e invalido`() {
        assertTrue(ObdParser.parseRpm("41 0C 1A\r>") is RespostaObd.Invalido)
    }

    // --- velocidade (010D) / temperatura (0105) / carga (0104) ---

    @Test
    fun `velocidade e o byte A`() {
        assertEquals(90, ObdParser.parseVelocidade("41 0D 5A\r>").valor())
        assertEquals(0, ObdParser.parseVelocidade("41 0D 00\r>").valor())
    }

    @Test
    fun `temperatura subtrai 40`() {
        assertEquals(50, ObdParser.parseTemperaturaMotor("41 05 5A\r>").valor())
        assertEquals(-40, ObdParser.parseTemperaturaMotor("41 05 00\r>").valor())
    }

    @Test
    fun `carga do motor em porcentagem`() {
        assertEquals(100.0, ObdParser.parseCargaMotor("41 04 FF\r>").valor(), 0.001)
        assertEquals(50.2, ObdParser.parseCargaMotor("41 04 80\r>").valor(), 0.05)
        assertEquals(0.0, ObdParser.parseCargaMotor("41 04 00\r>").valor(), 0.001)
    }

    @Test
    fun `resposta de outro pid nao e aceita`() {
        assertTrue(ObdParser.parseVelocidade("41 0C 1A F8\r>") is RespostaObd.Invalido)
    }

    // --- tensão (ATRV) ---

    @Test
    fun `tensao da bateria`() {
        assertEquals(12.6, ObdParser.parseTensao("12.6V\r\r>").valor(), 0.001)
        assertEquals(14.2, ObdParser.parseTensao("ATRV\r14.2V\r>").valor(), 0.001)
        assertEquals(13.8, ObdParser.parseTensao("13.8 V").valor(), 0.001)
        assertEquals(12.4, ObdParser.parseTensao("12,4V").valor(), 0.001)
    }

    @Test
    fun `tensao fora da faixa ou lixo e invalida`() {
        assertTrue(ObdParser.parseTensao("99.9V") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseTensao("0.0V") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseTensao("@#%&") is RespostaObd.Invalido)
    }

    // --- erros do adaptador ---

    @Test
    fun `NO DATA`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseRpm("NO DATA\r\r>"))
        assertEquals(RespostaObd.SemDados, ObdParser.parseVelocidade("010D\rno data\r>"))
        assertEquals(RespostaObd.SemDados, ObdParser.parseTensao("NO DATA"))
    }

    @Test
    fun `ponto de interrogacao significa comando nao entendido`() {
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseRpm("?\r\r>"))
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseDtcs("?\r>"))
    }

    @Test
    fun `UNABLE TO CONNECT e erros de barramento`() {
        assertEquals(RespostaObd.SemConexao, ObdParser.parseRpm("SEARCHING...\rUNABLE TO CONNECT\r>"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parseVelocidade("CAN ERROR\r>"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parseDtcs("BUS INIT: ...ERROR\r>"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parsePidsSuportados("UNABLE TO CONNECT"))
    }

    @Test
    fun `resposta vazia ou lixo puro e invalida`() {
        assertTrue(ObdParser.parseRpm("") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseRpm(">") is RespostaObd.Invalido)
        assertTrue(ObdParser.parseRpm("\u0000\u0001\u0002") is RespostaObd.Invalido)
    }

    @Test
    fun `PIDs suportados`() {
        assertTrue(ObdParser.parsePidsSuportados("41 00 BE 3F A8 13\r>") is RespostaObd.Ok)
        assertTrue(ObdParser.parsePidsSuportados("41 00 BE\r>") is RespostaObd.Invalido)
    }

    // --- DTCs (modo 03) ---

    @Test
    fun `dtc decodifica letra e digitos`() {
        assertEquals("P0133", ObdParser.decodificarDtc(0x01, 0x33))
        assertEquals("P0300", ObdParser.decodificarDtc(0x03, 0x00))
        assertEquals("C0035", ObdParser.decodificarDtc(0x40, 0x35))
        assertEquals("B0001", ObdParser.decodificarDtc(0x80, 0x01))
        assertEquals("U0100", ObdParser.decodificarDtc(0xC1, 0x00))
        assertEquals("P1A2F", ObdParser.decodificarDtc(0x1A, 0x2F))
    }

    @Test
    fun `dtc protocolo sem contador com padding`() {
        // ISO 9141/KWP: 3 pares por linha, preenchidos com 00 00
        val r = ObdParser.parseDtcs("43 01 33 03 00 00 00\r>").valor()
        assertEquals(listOf("P0133", "P0300"), r)
    }

    @Test
    fun `dtc CAN com byte de quantidade`() {
        val r = ObdParser.parseDtcs("43 02 01 33 04 20 00 00\r>").valor()
        assertEquals(listOf("P0133", "P0420"), r)
    }

    @Test
    fun `dtc sem codigos armazenados`() {
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00 00 00 00 00 00\r>").valor())
        assertEquals(emptyList<String>(), ObdParser.parseDtcs("43 00\r>").valor())
    }

    @Test
    fun `dtc de varias ECUs sem duplicar`() {
        val r = ObdParser.parseDtcs("43 01 03 00 00 00 00 00\r43 02 03 00 C1 00 00 00\r>").valor()
        assertEquals(listOf("P0300", "U0100"), r)
    }

    @Test
    fun `dtc multi frame CAN`() {
        val bruto = "00A\r0: 43 04 01 33\r1: 03 00 40 35\r2: 80 01 00 00\r>"
        val r = ObdParser.parseDtcs(bruto).valor()
        assertEquals(listOf("P0133", "P0300", "C0035", "B0001"), r)
    }

    @Test
    fun `dtc ignora eco`() {
        assertEquals(listOf("P0171"), ObdParser.parseDtcs("03\r43 01 01 71 00 00 00 00\r>").valor())
    }

    @Test
    fun `dtc NO DATA e erro`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcs("NO DATA\r>"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parseDtcs("UNABLE TO CONNECT\r>"))
    }

    // --- limpeza (modo 04) ---

    @Test
    fun `limpeza confirmada com 44`() {
        assertTrue(ObdParser.parseLimpezaDtcs("44\r\r>") is RespostaObd.Ok)
        assertTrue(ObdParser.parseLimpezaDtcs("04\r44\r>") is RespostaObd.Ok)
    }

    @Test
    fun `limpeza sem confirmacao`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseLimpezaDtcs("NO DATA\r>"))
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseLimpezaDtcs("?\r>"))
        assertTrue(ObdParser.parseLimpezaDtcs("04\r>") is RespostaObd.Invalido)
    }

    // --- descrições ---

    @Test
    fun `descricao de codigo conhecido em portugues`() {
        assertTrue(DescricaoDtc.descricaoDe("P0300").contains("ignição"))
        assertTrue(DescricaoDtc.descricaoDe("p0171").contains("pobre"))
    }

    @Test
    fun `descricao generica por sistema para codigo desconhecido`() {
        assertTrue(DescricaoDtc.descricaoDe("P0999").contains("trem de força"))
        assertTrue(DescricaoDtc.descricaoDe("C0999").contains("chassi"))
        assertTrue(DescricaoDtc.descricaoDe("B0999").contains("carroceria"))
        assertTrue(DescricaoDtc.descricaoDe("U0999").contains("rede"))
        assertTrue(DescricaoDtc.descricaoDe("P1999").contains("fabricante"))
        assertEquals("Código desconhecido", DescricaoDtc.descricaoDe("X123"))
    }
}
