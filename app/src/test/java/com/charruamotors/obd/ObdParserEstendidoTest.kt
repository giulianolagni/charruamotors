package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Formula
import com.charruamotors.obd.pid.RequisicaoEstendida
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Respostas dos serviços de montadora (21 Toyota / 22 UDS) e do protocolo ativo (`ATDPN`). */
class ObdParserEstendidoTest {

    private fun def(id: String, servico: String, identificador: String, formula: String, bytes: Int, min: Double = -40.0, max: Double = 215.0) =
        DefinicaoPid(id, id, "°C", bytes, Formula.compilar(formula), min, max, estendida = RequisicaoEstendida("7E0", servico, identificador))

    private val oleoVw = def("VW_OLEO", "22", "11BD", "A-40", 1)
    private val atf = CatalogoTeste.estendido["TOYOTA_ATF"]!!

    private fun RespostaObd<Double>.valor() = (this as RespostaObd.Ok).valor

    @Test
    fun `UDS 22 em quadro unico`() {
        assertEquals(90.0, ObdParser.parsePid("62 11 BD 82\r\r>", oleoVw).valor(), 0.0)
        assertEquals(90.0, ObdParser.parsePid("2211BD\r6211BD82\r>", oleoVw).valor(), 0.0) // eco + sem espaços
    }

    @Test
    fun `servico 21 Toyota com dados em C e D`() {
        // ((256·0x45 + 0x1C) · 0,07 − 400) / 10 = (17692 · 0,07 − 400) / 10 = 83,8 °C
        assertEquals(83.84, ObdParser.parsePid("61 82 00 00 45 1C\r>", atf).valor(), 0.01)
    }

    @Test
    fun `resposta multi frame ISO-TP e unida`() {
        val inj1 = CatalogoTeste.estendido["TOYOTA_INJ1"]!!
        val inj3 = CatalogoTeste.estendido["TOYOTA_INJ3"]!!
        val bruto = "00D\r0: 61 AD 00 E6 00 E1\r1: 01 52 00 00 00 00 00\r\r>"
        assertEquals(230 / 512.0, ObdParser.parsePid(bruto, inj1).valor(), 1e-9) // 0x00E6 / 512 = +0,45
        assertEquals(338 / 512.0, ObdParser.parsePid(bruto, inj3).valor(), 1e-9) // 0x0152 / 512 = +0,66
        // signed short: 0xFF38 = −200 → −0,39 mm³/st
        assertEquals(-200 / 512.0, ObdParser.parsePid("61 AD FF 38 00 00 00 00 00 00\r>", inj1).valor(), 1e-9)
    }

    @Test
    fun `resposta negativa 7F significa PID nao suportado`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("7F 22 31\r>", oleoVw)) // requestOutOfRange
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("7F 21 12\r>", atf)) // subFunctionNotSupported
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("NO DATA\r>", oleoVw))
    }

    @Test
    fun `NRC 78 resposta pendente aguarda a resposta positiva`() {
        assertEquals(90.0, ObdParser.parsePid("7F 22 78\r62 11 BD 82\r>", oleoVw).valor(), 0.0)
        assertTrue(ObdParser.parsePid("7F 22 78\r>", oleoVw) !is RespostaObd.Ok)
    }

    @Test
    fun `resposta de outro identificador truncada ou fora da faixa e invalida`() {
        assertTrue(ObdParser.parsePid("62 11 BE 82\r>", oleoVw) is RespostaObd.Invalido)
        assertTrue(ObdParser.parsePid("61 82 00\r>", atf) is RespostaObd.Invalido) // faltam C e D
        val estreito = def("VW_X", "22", "2029", "A", 1, min = 0.0, max = 10.0)
        assertTrue(ObdParser.parsePid("62 20 29 FF\r>", estreito) is RespostaObd.Invalido)
    }

    @Test
    fun `PID padrao nao passa pelo parser estendido`() {
        assertThrows(IllegalArgumentException::class.java) { ObdParser.parsePidEstendido("41 0C 1A F8", CatalogoTeste.pid("010C")) }
    }

    @Test
    fun `protocolo ativo do ATDPN`() {
        assertEquals(6, ObdParser.parseProtocolo("A6\r\r>"))
        assertEquals(8, ObdParser.parseProtocolo("8\r>"))
        assertEquals(3, ObdParser.parseProtocolo("ATDPN\rA3\r>"))
        assertEquals(12, ObdParser.parseProtocolo("C"))
        assertNull(ObdParser.parseProtocolo("?\r>"))
        assertTrue(6 in ObdParser.PROTOCOLOS_CAN_11_BITS && 8 in ObdParser.PROTOCOLOS_CAN_11_BITS)
        assertTrue(7 !in ObdParser.PROTOCOLOS_CAN_11_BITS) // CAN 29 bits: cabeçalho de 11 bits não serve
    }
}
