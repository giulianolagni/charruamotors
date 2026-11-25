package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.2.9: Multi-PID em lote, prontidão (`0101`), contadores do checkup, CVN, Modo 06 e os PIDs do Cruze. */
class ObdParserV229Test {

    private fun pid(c: String) = CatalogoTeste.pid(c)

    private val rapidos = listOf(pid("010C"), pid("010D"), pid("010B"))

    // ---------------------------------------------------------------- Multi-PID em lote

    @Test
    fun `comando e tamanho da resposta do lote`() {
        assertEquals("010C0D0B", ObdParser.comandoLote(rapidos))
        assertEquals(8, ObdParser.bytesRespostaLote(rapidos)) // 41 + 0C A B + 0D A + 0B A
    }

    @Test
    fun `lote em quadro unico e em multi-frame com preenchimento`() {
        val dois = listOf(pid("010C"), pid("010D"))
        val unico = ObdParser.parseLote("410C1AF80D32\r\r>", dois)
        assertEquals(RespostaObd.Ok(1726.0), unico[pid("010C")])
        assertEquals(RespostaObd.Ok(50.0), unico[pid("010D")])

        val multi = ObdParser.parseLote("008\r0:410C1AF80D32\r1:0B650000000000\r\r>", rapidos)
        assertEquals(RespostaObd.Ok(1726.0), multi[pid("010C")])
        assertEquals(RespostaObd.Ok(50.0), multi[pid("010D")])
        assertEquals(RespostaObd.Ok(101.0), multi[pid("010B")])
        // A ECU pode responder em outra ordem: cada PID é achado pelo próprio byte.
        val ordem = ObdParser.parseLote("410B65 0D32 0C1AF8", rapidos)
        assertTrue(ordem.values.all { it is RespostaObd.Ok })
    }

    @Test
    fun `lote com espacos, eco e duas ECUs fica com a primeira resposta de cada PID`() {
        val r = ObdParser.parseLote("010C0D0B\r41 0C 1A F8 0D 32 0B 65\r41 0D 33\r\r>", rapidos)
        assertEquals(RespostaObd.Ok(50.0), r[pid("010D")]) // o 0D do TCM (51) não sobrescreve o do motor
        assertEquals(RespostaObd.Ok(101.0), r[pid("010B")])
    }

    @Test
    fun `lote truncado, recusado ou sem dados nao inventa valores`() {
        val truncado = ObdParser.parseLote("410C1AF80D32", rapidos)
        assertEquals(RespostaObd.Ok(1726.0), truncado[pid("010C")])
        assertTrue(truncado[pid("010B")] is RespostaObd.Invalido)

        val meioDoPid = ObdParser.parseLote("410C1A", listOf(pid("010C"), pid("010D")))
        assertTrue(meioDoPid.values.all { it is RespostaObd.Invalido })

        assertTrue(ObdParser.parseLote("?\r\r", rapidos).values.all { it == RespostaObd.ComandoInvalido })
        assertTrue(ObdParser.parseLote("NO DATA\r\r", rapidos).values.all { it == RespostaObd.SemDados })
        assertTrue(ObdParser.parseLote("CAN ERROR\r\r", rapidos).values.all { it == RespostaObd.SemConexao })
        assertTrue(ObdParser.parseLote("BUFFER FULL\r\r", rapidos).values.all { it is RespostaObd.Invalido })
        assertTrue(ObdParser.parseLote("7F0112\r\r", rapidos).values.none { it is RespostaObd.Ok })
    }

    // ---------------------------------------------------------------- 0101 (prontidão)

    @Test
    fun `0101 gasolina flex com MIL apagada e EVAP pendente`() {
        // A = 00 (MIL apagada, 0 DTCs); B = 07 (contínuos suportados e completos, centelha);
        // C = 65 (catalisador, EVAP, sonda O2 e aquecedor); D = 04 (EVAP incompleto).
        val p = (ObdParser.parseProntidao("41 01 00 07 65 04\r\r>") as RespostaObd.Ok).valor
        assertFalse(p.milAcesa)
        assertEquals(0, p.quantidadeDtcs)
        assertFalse(p.diesel)
        val estado = p.monitores.associate { it.nome to it.estado }
        assertEquals(EstadoMonitor.PRONTO, estado["Falha de ignição (misfire)"])
        assertEquals(EstadoMonitor.PRONTO, estado["Catalisador"])
        assertEquals(EstadoMonitor.PENDENTE, estado["Sistema evaporativo (EVAP)"])
        assertEquals(EstadoMonitor.PRONTO, estado["Sonda O₂"])
        assertEquals(EstadoMonitor.NAO_APLICAVEL, estado["Ar secundário"])
        assertEquals(EstadoMonitor.NAO_APLICAVEL, estado["EGR / VVT"])
        assertEquals(listOf("Sistema evaporativo (EVAP)"), p.pendentes.map { it.nome })
        assertEquals(7, p.suportados.size)
    }

    @Test
    fun `0101 com MIL acesa, codigos e monitores do diesel`() {
        // A = 83 (MIL acesa, 3 DTCs); B = 0F|10 → 1F: diesel (bit 3), misfire incompleto (bit 4).
        val p = (ObdParser.parseProntidao("41 01 83 1F E1 A0") as RespostaObd.Ok).valor
        assertTrue(p.milAcesa)
        assertEquals(3, p.quantidadeDtcs)
        assertTrue(p.diesel)
        val estado = p.monitores.associate { it.nome to it.estado }
        assertEquals(EstadoMonitor.PENDENTE, estado["Falha de ignição (misfire)"])
        assertEquals(EstadoMonitor.PRONTO, estado["Sistema de combustível"])
        assertEquals(EstadoMonitor.PRONTO, estado["Catalisador NMHC"]) // C bit 0, D bit 0 = 0
        assertEquals(EstadoMonitor.PENDENTE, estado["Sensor de gases de escape"]) // C bit 5, D bit 5
        assertEquals(EstadoMonitor.PENDENTE, estado["EGR / VVT"]) // C bit 7, D bit 7
        assertEquals(EstadoMonitor.PRONTO, estado["Filtro de partículas (DPF)"]) // C bit 6, D bit 6 = 0
        assertFalse(p.monitores.any { it.nome == "Catalisador" }) // nomes da gasolina não aparecem no diesel
    }

    @Test
    fun `0101 sem resposta ou truncado`() {
        assertEquals(RespostaObd.SemDados, ObdParser.parseProntidao("NO DATA\r\r"))
        assertTrue(ObdParser.parseProntidao("410100") is RespostaObd.Invalido)
    }

    // ---------------------------------------------------------------- contadores (0121, 0130, 0131, 014D, 014E)

    @Test
    fun `contadores do checkup`() {
        assertEquals(RespostaObd.Ok(1234), ObdParser.parseContador("413104D2\r\r", "31", 2))
        assertEquals(RespostaObd.Ok(0), ObdParser.parseContador("41210000", "21", 2))
        assertEquals(RespostaObd.Ok(45), ObdParser.parseContador("41302D", "30", 1))
        assertEquals(RespostaObd.Ok(789), ObdParser.parseContador("414E0315", "4E", 2))
        assertEquals(RespostaObd.Ok(65535), ObdParser.parseContador("414DFFFF", "4D", 2))
        assertEquals(RespostaObd.SemDados, ObdParser.parseContador("NO DATA", "4E", 2))
        // Os mesmos PIDs no catálogo (CSV/relatório), com a fórmula do JSON.
        assertEquals(RespostaObd.Ok(1234.0), ObdParser.parsePid("413104D2", pid("0131")))
        assertEquals(RespostaObd.Ok(45.0), ObdParser.parsePid("41302D", pid("0130")))
        assertEquals(RespostaObd.Ok(789.0), ObdParser.parsePid("414E0315", pid("014E")))
        assertEquals(RespostaObd.Ok(0.0), ObdParser.parsePid("414D0000", pid("014D")))
    }

    // ---------------------------------------------------------------- PIDs do Cruze no catálogo

    @Test
    fun `trims, etanol, catalisador e pedal E do catalogo`() {
        assertEquals(RespostaObd.Ok(0.0), ObdParser.parsePid("410680", pid("0106")))
        assertEquals(-6.25, (ObdParser.parsePid("410678", pid("0106")) as RespostaObd.Ok).valor, 1e-9)
        assertEquals(3.125, (ObdParser.parsePid("410784", pid("0107")) as RespostaObd.Ok).valor, 1e-9)
        assertEquals(27.06, (ObdParser.parsePid("415245", pid("0152")) as RespostaObd.Ok).valor, 0.01)
        assertEquals(RespostaObd.Ok(10.0), ObdParser.parsePid("410E94", pid("010E")))
        // 013C: (256·A + B)/10 − 40 → 0x1194 = 4500 → 410 °C.
        assertEquals(RespostaObd.Ok(410.0), ObdParser.parsePid("413C1194", pid("013C")))
        assertEquals(50.2, (ObdParser.parsePid("414A80", pid("014A")) as RespostaObd.Ok).valor, 0.01)
        assertEquals(RespostaObd.Ok(1.0), ObdParser.parsePid("41448000", pid("0144")))
    }

    // ---------------------------------------------------------------- CVN (0906)

    @Test
    fun `CVN em quadro unico, multi-frame e sem resposta`() {
        assertEquals(RespostaObd.Ok("5A3C910E"), ObdParser.parseCvn("4906015A3C910E\r\r"))
        assertEquals(
            RespostaObd.Ok("5A3C910E / 01020304"),
            ObdParser.parseCvn("00B\r0:4906025A3C91\r1:0E010203040000\r\r"),
        )
        assertEquals(RespostaObd.SemDados, ObdParser.parseCvn("NO DATA"))
        assertTrue(ObdParser.parseCvn("490601") is RespostaObd.Invalido)
    }

    // ---------------------------------------------------------------- Modo 06

    @Test
    fun `modo 06 por cilindro com EWMA e ciclo atual`() {
        // 46 | A4 0B 24 0001 0000 FFFF | A4 0C 24 0003 0000 FFFF (19 bytes, 3 quadros)
        val bruto = "013\r0:46A40B240001\r1:0000FFFFA40C24\r2:00030000FFFF00\r\r"
        val r = (ObdParser.parseModo06(bruto, 0xA4) as RespostaObd.Ok).valor
        assertEquals(listOf(0x0B, 0x0C), r.map { it.tid })
        assertEquals(1, r.first { it.tid == 0x0B }.valor)
        assertEquals(3, r.first { it.tid == 0x0C }.valor)
        assertEquals(0x24, r.first().uasid)
        assertEquals(0xFFFF, r.first().maximo)
    }

    @Test
    fun `modo 06 parcial, recusado ou sem dados`() {
        // Só o TID 0B chegou inteiro: o registro cortado é descartado.
        val parcial = (ObdParser.parseModo06("46A20B2400020000FFFFA20C24", 0xA2) as RespostaObd.Ok).valor
        assertEquals(listOf(0x0B), parcial.map { it.tid })
        assertTrue(ObdParser.parseModo06("46A20B24", 0xA2) is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemDados, ObdParser.parseModo06("7F0612", 0xA2))
        assertEquals(RespostaObd.SemDados, ObdParser.parseModo06("NO DATA", 0xA3))
    }
}
