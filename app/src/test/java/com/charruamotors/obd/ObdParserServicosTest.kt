package com.charruamotors.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Respostas dos serviços de montadora: negativas `7F`, positivas 21/22 e falhas `13` / `18` / `19` / `14`. */
class ObdParserServicosTest {

    // ---------------------------------------------------------------- respostas negativas e positivas

    @Test
    fun `7F0111 e reconhecido como resposta negativa ao modo 01`() {
        val bruto = "SEARCHING...\r7F0111\r7F0111\r\r"
        assertEquals("11", ObdParser.respostaNegativa(bruto, "01", "0100"))
        assertEquals("serviceNotSupported", ObdParser.descricaoNrc("11"))
        assertNull(ObdParser.respostaNegativa("41 00 BE 1F A8 13\r", "01"))
        assertNull("responsePending não é recusa", ObdParser.respostaNegativa("7F 01 78\r", "01"))
        assertEquals("31", ObdParser.respostaNegativa("7F 01 31\r", "01"))
    }

    @Test
    fun `resposta positiva dos servicos 21 e 22 confere o identificador`() {
        assertTrue(ObdParser.respostaPositiva("02B\r0:6101800064A0\r1:7B1A2C3D4E5F60\r\r", "2101"))
        assertTrue(ObdParser.respostaPositiva("610C0C3B\r", "210C"))
        assertFalse(ObdParser.respostaPositiva("6104AABB\r", "2101"))
        assertFalse(ObdParser.respostaPositiva("7F2112\r", "2104"))
        assertTrue(ObdParser.respostaPositiva("62F40C1AF8\r", "22F40C"))
        assertFalse(ObdParser.respostaPositiva("62F40D3C\r", "22F40C"))
        assertTrue(ObdParser.respostaPositiva("4300\r", "03"))
    }

    @Test
    fun `descricao do protocolo vem do ATDP`() {
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", ObdParser.parseDescricaoProtocolo("AUTO, ISO 15765-4 (CAN 11/500)\r\r"))
        assertNull(ObdParser.parseDescricaoProtocolo("?\r\r"))
    }

    // ---------------------------------------------------------------- modo 21: serviço 13 (53) e 18 (58)

    @Test
    fun `servico 13 sem falhas`() {
        assertEquals(emptyList<String>(), ok(ObdParser.parseDtcsKwp13("5300\r\r")))
        assertEquals(emptyList<String>(), ok(ObdParser.parseDtcsKwp13("53 00\r\r>")))
    }

    @Test
    fun `servico 13 com quantidade e pares de bytes`() {
        assertEquals(listOf("P0102", "P0304"), ok(ObdParser.parseDtcsKwp13("530201020304\r\r")))
        assertEquals(listOf("P0102", "P0304"), ok(ObdParser.parseDtcsKwp13("53 02 01 02 03 04\r\r")))
        assertEquals("C e U pelos 2 bits altos", listOf("C0123", "U0100"), ok(ObdParser.parseDtcsKwp13("53024123C100\r")))
    }

    @Test
    fun `servico 13 respeita a quantidade e ignora o preenchimento`() {
        assertEquals(listOf("P0102"), ok(ObdParser.parseDtcsKwp13("53010102000000\r")))
    }

    @Test
    fun `servico 13 recusado e NO DATA`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsKwp13("7F1311\r"))
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsKwp13("NO DATA\r\r"))
        assertEquals(RespostaObd.SemConexao, ObdParser.parseDtcsKwp13("CAN ERROR\r\r"))
    }

    @Test
    fun `DTCs KWP 18 00 FF 00 sao decodificados`() {
        assertEquals(listOf("P0121"), ok(ObdParser.parseDtcsKwp("5801012160\r")))
        assertEquals(emptyList<String>(), ok(ObdParser.parseDtcsKwp("5800\r")))
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsKwp("7F1811\r"))
    }

    // ---------------------------------------------------------------- modo 22: UDS 19 02 FF (59 02)

    @Test
    fun `UDS 1902 extrai os DTCs de 3 bytes`() {
        assertEquals(listOf("P0102", "P0430"), ok(ObdParser.parseDtcsUds("5902FF01021F08043000AF\r\r")))
        assertEquals(emptyList<String>(), ok(ObdParser.parseDtcsUds("5902FF\r\r")))
    }

    @Test
    fun `UDS 1902 multi-frame ISO-TP`() {
        val bruto = "00F\r0:5902FF01021F\r1:08043000AF0000\r\r"
        assertEquals(listOf("P0102", "P0430"), ok(ObdParser.parseDtcsUds(bruto)))
    }

    @Test
    fun `UDS 1902 recusado`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsUds("7F1911\r"))
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsUds("7F 19 78\r7F 19 31\r"))
    }

    @Test
    fun `parser por servico escolhe o formato pelo comando`() {
        assertEquals(listOf("P0133"), ok(ObdParser.parseDtcsServico("43 01 01 33\r", "03")))
        assertEquals(listOf("P0102"), ok(ObdParser.parseDtcsServico("53010102\r", "13")))
        assertEquals(listOf("P0121"), ok(ObdParser.parseDtcsServico("5801012160\r", "1800FF00")))
        assertEquals(listOf("P0102"), ok(ObdParser.parseDtcsServico("5902FF01021F08\r", "1902FF")))
    }

    // ---------------------------------------------------------------- limpeza: 04 (44) e 14 (54)

    @Test
    fun `limpeza confirma pelo servico do comando`() {
        assertEquals(RespostaObd.Ok(Unit), ObdParser.parseLimpezaDtcs("44\r\r"))
        assertEquals(RespostaObd.Ok(Unit), ObdParser.parseLimpezaDtcs("54\r\r", "14"))
        assertEquals(RespostaObd.Ok(Unit), ObdParser.parseLimpezaDtcs("54FF00\r\r", "14FF00"))
        assertEquals(RespostaObd.Ok(Unit), ObdParser.parseLimpezaDtcs("54\r\r", "14FFFFFF"))
        assertFalse("44 não confirma um 14", ObdParser.parseLimpezaDtcs("44\r\r", "14") is RespostaObd.Ok)
    }

    @Test
    fun `limpeza recusada pela ECU`() {
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseLimpezaDtcs("7F1422\r", "14"))
        assertEquals("22", ObdParser.respostaNegativa("7F1422\r", "14"))
        assertEquals(RespostaObd.SemDados, ObdParser.parseLimpezaDtcs("NO DATA\r", "14"))
    }

    private fun ok(r: RespostaObd<List<String>>): List<String> {
        assertTrue("esperava Ok, veio $r", r is RespostaObd.Ok)
        return (r as RespostaObd.Ok).valor
    }
}
