package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Respostas como saem de adaptadores ELM327 reais (e clones) em CAN e na linha K: progresso da
 * busca de protocolo, quadros corrompidos, mensagens de erro do datasheet e lixo de baixo nível.
 */
class ObdParserErrosReaisTest {

    private fun <T> RespostaObd<T>.valor(): T = (this as RespostaObd.Ok<T>).valor

    private val rpm = CatalogoTeste.pid("010C")

    // ---------------------------------------------------------------- com e sem espaços (ATS0/ATS1)

    @Test
    fun `mesma resposta com espacos sem espacos e com eco e line feed`() {
        listOf(
            "41 0C 1A F8\r\r>",
            "410C1AF8\r\r>",
            "010C1\r410C1AF8\r\r>", // ATE0 falhou: eco presente
            "010C1\r\n41 0C 1A F8\r\n\r\n>", // ATL0 falhou: \r\n
            "\u0000\u0000410C1AF8\r\r>", // clones que mandam bytes nulos antes
        ).forEach { assertEquals(it, 1726.0, ObdParser.parsePid(it, rpm, "010C1").valor(), 0.0) }
    }

    @Test
    fun `DTCs e VIN sem espacos (ATS0)`() {
        assertEquals(listOf("P0133", "P0171"), ObdParser.parseDtcs("4302013301710000\r\r>").valor())
        val vin = "014\r0:4902013942575A\r1:5A5A3337375654\r2:30303432353100\r\r>"
        assertEquals("9BWZZZ377VT004251", ObdParser.parseVin(vin).valor())
    }

    // ---------------------------------------------------------------- linha K (ISO 9141 / KWP 5 baud)

    @Test
    fun `BUS INIT OK com pontos e descartado e a resposta vale`() {
        assertEquals(1726.0, ObdParser.parsePid("BUS INIT: ...OK\r41 0C 1A F8\r\r>", rpm).valor(), 0.0)
        assertEquals(1726.0, ObdParser.parsePid("BUS INIT: OK\r41 0C 1A F8\r>", rpm).valor(), 0.0)
        // Bitmask na primeira busca: SEARCHING... e BUS INIT juntos
        val bitmask = ObdParser.parsePidsSuportados("SEARCHING...\rBUS INIT: ...OK\r41 00 BE 3E B8 10\r\r>")
        assertTrue(0x0C in bitmask.valor())
    }

    @Test
    fun `progresso na mesma linha dos dados tambem e removido`() {
        assertEquals(1726.0, ObdParser.parsePid("SEARCHING...41 0C 1A F8\r>", rpm).valor(), 0.0)
    }

    @Test
    fun `BUS INIT com erro e falta de comunicacao com a ECU`() {
        assertEquals(RespostaObd.SemConexao, ObdParser.parsePidsSuportados("SEARCHING...\rBUS INIT: ...ERROR\r\r>"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parsePidsSuportados("BUS INIT: ERROR\r>"))
        assertEquals("BUS INIT: ERROR", ObdParser.erroElm("SEARCHING...\rBUS INIT: ...ERROR\r\r>"))
    }

    // ---------------------------------------------------------------- quadros corrompidos

    @Test
    fun `quadro com DATA ERROR nao e aceito como valor`() {
        assertTrue(ObdParser.parsePid("41 0C 1A F8 <DATA ERROR\r\r>", rpm) is RespostaObd.Invalido)
        assertTrue(ObdParser.parsePid("41 0C 1A <RX ERROR\r\r>", rpm) is RespostaObd.Invalido)
        // Com duas ECUs, a resposta boa da outra ainda vale.
        assertEquals(1000.0, ObdParser.parsePid("41 0C 1A F8 <DATA ERROR\r41 0C 0F A0\r\r>", rpm).valor(), 0.0)
    }

    @Test
    fun `multi frame com quadro corrompido nao vira VIN`() {
        val bruto = "014\r0:4902013942575A\r1:5A5A3337 <DATA ERROR\r2:30303432353100\r\r>"
        assertTrue(ObdParser.parseVin(bruto) !is RespostaObd.Ok)
    }

    @Test
    fun `bytes fora do ASCII imprimivel sao descartados`() {
        assertEquals(12.6, ObdParser.parseTensao("ÿ12.6V\u0080\r>").valor(), 0.0)
    }

    // ---------------------------------------------------------------- mensagens do datasheet

    @Test
    fun `erros que indicam ECU ou barramento sem comunicacao`() {
        listOf(
            "UNABLE TO CONNECT\r\r>", "CAN ERROR\r\r>", "BUS ERROR\r\r>", "FB ERROR\r\r>",
            "LV RESET\r\r>", "ACT ALERT\r\r>", "LP ALERT\r\r>", "ERR94\r\r>",
        ).forEach { assertEquals(it, RespostaObd.SemConexao, ObdParser.parsePid(it, rpm)) }
    }

    @Test
    fun `falhas passageiras sao invalidas e NO DATA e sem dados`() {
        listOf("STOPPED\r\r>", "BUFFER FULL\r\r>", "BUS BUSY\r\r>", "DATA ERROR\r\r>", "<RX ERROR\r\r>")
            .forEach { assertTrue(it, ObdParser.parsePid(it, rpm) is RespostaObd.Invalido) }
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("NO DATA\r\r>", rpm))
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("SEARCHING...\rNO DATA\r\r>", rpm))
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parsePid("?\r\r>", rpm))
    }

    @Test
    fun `erroElm extrai a mensagem para o usuario e o registro`() {
        assertEquals("NO DATA", ObdParser.erroElm("SEARCHING...\rNO DATA\r\r"))
        assertEquals("CAN ERROR", ObdParser.erroElm("CAN ERROR\r\r"))
        assertEquals("UNABLE TO CONNECT", ObdParser.erroElm("SEARCHING...\rUNABLE TO CONNECT\r\r"))
        assertEquals("?", ObdParser.erroElm("?\r\r"))
        assertEquals("ERR94", ObdParser.erroElm("ERR94\r"))
        assertEquals("<DATA ERROR", ObdParser.erroElm("41 0C 1A F8 <DATA ERROR\r"))
        assertNull(ObdParser.erroElm("41 0C 1A F8\r\r"))
        assertNull(ObdParser.erroElm("OK\r\r"))
        assertNull(ObdParser.erroElm("ELM327 v1.5\r\r"))
    }

    @Test
    fun `STOPPED e reinicio do adaptador sao detectados`() {
        assertTrue(ObdParser.interrompido("STOPPED\r\r"))
        assertTrue(ObdParser.interrompido("010C1\rSTOPPED\r\r"))
        assertFalse(ObdParser.interrompido("41 0C 1A F8\r\r"))

        assertTrue(ObdParser.adaptadorReiniciou("LV RESET\r\r"))
        assertTrue(ObdParser.adaptadorReiniciou("\r\rELM327 v2.1\r\r"))
        assertFalse(ObdParser.adaptadorReiniciou("41 0C 1A F8\r\r"))
        assertFalse(ObdParser.adaptadorReiniciou("NO DATA\r\r"))
    }

    @Test
    fun `protocolo da linha K no ATDPN`() {
        assertEquals(3, ObdParser.parseProtocolo("A3\r\r>")) // ISO 9141-2
        assertEquals(4, ObdParser.parseProtocolo("A4\r\r>")) // KWP2000 5 baud
        assertFalse(3 in ObdParser.PROTOCOLOS_CAN_11_BITS)
    }
}
