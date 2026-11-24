package com.charruamotors.obd

import com.charruamotors.obd.pid.CatalogoTeste
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

    // --- bitmasks de PIDs suportados (0100 / 0120 / 0140) ---

    @Test
    fun `bitmask 0100 decodifica os PIDs 01 a 20`() {
        // Exemplo da SAE J1979: BE 1F A8 13
        val esperado = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20)
        assertEquals(esperado, ObdParser.parsePidsSuportados("41 00 BE 1F A8 13\r>").valor())
    }

    @Test
    fun `bitmask 0120 usa a base 0x20`() {
        // 80 02 20 01 → bits 0, 14, 18 e 31 da faixa 21..40
        val r = ObdParser.parsePidsSuportados("41 20 80 02 20 01\r>", base = 0x20).valor()
        assertEquals(setOf(0x21, 0x2F, 0x33, 0x40), r)
        assertTrue("0x40 indica suporte à faixa 0140", 0x40 in r)
    }

    @Test
    fun `bitmask 0140 sem suporte a proxima faixa`() {
        // Sem espaços (ATS0): 44 00 00 10 → PIDs 42, 46 e 5C; bit de 0x60 desligado
        val r = ObdParser.parsePidsSuportados("414044000010\r>", base = 0x40).valor()
        assertEquals(setOf(0x42, 0x46, 0x5C), r)
    }

    @Test
    fun `bitmask une respostas de varias ECUs`() {
        // Motor suporta 01..08 (FF), transmissão suporta 0D (00 08)
        val r = ObdParser.parsePidsSuportados("41 00 FF 00 00 00\r41 00 00 08 00 00\r>").valor()
        assertEquals((0x01..0x08).toSet() + 0x0D, r)
    }

    @Test
    fun `bitmask aceita a resposta ao comando com sufixo de resposta unica`() {
        assertEquals(setOf(0x01), ObdParser.parsePidsSuportados("01001\r41 00 80 00 00 00\r>", comando = "01001").valor())
    }

    @Test
    fun `bitmask zerado e bitmask de outra faixa`() {
        assertEquals(emptySet<Int>(), ObdParser.parsePidsSuportados("41 00 00 00 00 00\r>").valor())
        assertTrue(ObdParser.parsePidsSuportados("41 00 BE 1F A8 13\r>", base = 0x20) is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemDados, ObdParser.parsePidsSuportados("NO DATA\r>", base = 0x40))
    }

    @Test
    fun `decodificar bitmask bit a bit`() {
        assertEquals(setOf(0x01), ObdParser.decodificarBitmask(0x00, listOf(0x80, 0, 0, 0)))
        assertEquals(setOf(0x20), ObdParser.decodificarBitmask(0x00, listOf(0, 0, 0, 0x01)))
        assertEquals((0x41..0x60).toSet(), ObdParser.decodificarBitmask(0x40, listOf(0xFF, 0xFF, 0xFF, 0xFF)))
    }

    // --- PID genérico do catálogo ---

    @Test
    fun `pid generico aplica a formula do catalogo`() {
        assertEquals(1726.0, ObdParser.parsePid("41 0C 1A F8\r>", CatalogoTeste.pid("010C")).valor(), 0.0)
        assertEquals(5.0, ObdParser.parsePid("41 10 01 F4\r>", CatalogoTeste.pid("0110")).valor(), 1e-9)
        assertEquals(95.0, ObdParser.parsePid("41 5C 87\r>", CatalogoTeste.pid("015C")).valor(), 0.0)
        assertEquals(50_000.0, ObdParser.parsePid("41 23 13 88\r>", CatalogoTeste.pid("0123")).valor(), 0.0)
    }

    @Test
    fun `pid generico com sufixo de resposta unica no eco`() {
        val rpm = CatalogoTeste.pid("010C")
        assertEquals(1000.0, ObdParser.parsePid("010C1\r41 0C 0F A0\r>", rpm, "010C1").valor(), 0.0)
    }

    @Test
    fun `pid generico de comando AT`() {
        assertEquals(12.6, ObdParser.parsePid("12.6V\r>", CatalogoTeste.pid("ATRV")).valor(), 0.0)
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("NO DATA\r>", CatalogoTeste.pid("ATRV")))
        assertTrue(ObdParser.parsePid("99.9V", CatalogoTeste.pid("ATRV")) is RespostaObd.Invalido)
    }

    @Test
    fun `pid generico truncado ou de outro PID e invalido e erros sao preservados`() {
        val maf = CatalogoTeste.pid("0110")
        assertTrue(ObdParser.parsePid("41 10 01\r>", maf) is RespostaObd.Invalido)
        assertTrue(ObdParser.parsePid("41 0C 1A F8\r>", maf) is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemDados, ObdParser.parsePid("NO DATA\r>", maf))
        assertEquals(RespostaObd.SemConexao, ObdParser.parsePid("CAN ERROR\r>", maf))
    }

    // --- Freeze Frame (modo 02) ---

    @Test
    fun `freeze frame de rpm velocidade temperatura e carga`() {
        assertEquals(2350.0, ObdParser.parseFreezeFrame("42 0C 00 24 B8\r>", CatalogoTeste.pid("010C")).valor(), 0.0)
        assertEquals(62.0, ObdParser.parseFreezeFrame("42 0D 00 3E\r>", CatalogoTeste.pid("010D")).valor(), 0.0)
        assertEquals(97.0, ObdParser.parseFreezeFrame("42 05 00 89\r>", CatalogoTeste.pid("0105")).valor(), 0.0)
        assertEquals(78.4, ObdParser.parseFreezeFrame("42 04 00 C8\r>", CatalogoTeste.pid("0104")).valor(), 0.05)
    }

    @Test
    fun `comando do freeze frame inclui o numero do quadro`() {
        assertEquals("020C00", ObdParser.comandoFreezeFrame(CatalogoTeste.pid("010C")))
        assertEquals("020D00", ObdParser.comandoFreezeFrame(CatalogoTeste.pid("010D")))
        assertEquals("020501", ObdParser.comandoFreezeFrame(CatalogoTeste.pid("0105"), quadro = 1))
    }

    @Test
    fun `freeze frame ignora eco e nao aceita resposta do modo 01 nem de outro quadro`() {
        val rpm = CatalogoTeste.pid("010C")
        assertEquals(1000.0, ObdParser.parseFreezeFrame("020C00\r42 0C 00 0F A0\r>", rpm).valor(), 0.0)
        assertTrue(ObdParser.parseFreezeFrame("41 0C 0F A0\r>", rpm) is RespostaObd.Invalido)
        assertTrue(ObdParser.parseFreezeFrame("42 0C 01 0F A0\r>", rpm) is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemDados, ObdParser.parseFreezeFrame("NO DATA\r>", rpm))
    }

    @Test
    fun `dtc que disparou o freeze frame`() {
        assertEquals("P0300", ObdParser.parseDtcFreezeFrame("42 02 00 03 00\r>").valor())
        assertEquals(null, ObdParser.parseDtcFreezeFrame("42 02 00 00 00\r>").valor()) // nenhum quadro
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcFreezeFrame("NO DATA\r>"))
    }

    // --- VIN (modo 09, PID 02) ---

    @Test
    fun `vin CAN multi frame`() {
        val bruto = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r\r>"
        assertEquals("1D4GP00R55B123456", ObdParser.parseVin(bruto).valor())
    }

    @Test
    fun `vin ISO 9141 e KWP em cinco linhas com padding`() {
        val bruto = "49 02 01 00 00 00 31\r49 02 02 44 34 47 50\r49 02 03 30 30 52 35\r" +
            "49 02 04 35 42 31 32\r49 02 05 33 34 35 36\r>"
        assertEquals("1D4GP00R55B123456", ObdParser.parseVin(bruto).valor())
    }

    @Test
    fun `vin com linhas fora de ordem sem espacos e com eco`() {
        val bruto = "0902\r490203303052 35\r4902010000 0031\r4902053334 3536\r49020244344750\r4902043542 3132\r>"
        assertEquals("1D4GP00R55B123456", ObdParser.parseVin(bruto).valor())
    }

    @Test
    fun `vin brasileiro`() {
        val ascii = "9BWZZZ377VT004251".map { "%02X".format(it.code) }
        val bruto = "014\r0: 49 02 01 ${ascii.take(3).joinToString(" ")}\r" +
            "1: ${ascii.subList(3, 10).joinToString(" ")}\r2: ${ascii.subList(10, 17).joinToString(" ")}\r>"
        assertEquals("9BWZZZ377VT004251", ObdParser.parseVin(bruto).valor())
    }

    @Test
    fun `vin incompleto ou com caracteres proibidos e invalido`() {
        assertTrue(ObdParser.parseVin("49 02 01 00 00 00 31\r49 02 02 44 34 47 50\r>") is RespostaObd.Invalido)
        // 'O' (0x4F) não existe em VIN (ISO 3779)
        val comO = "014\r0: 49 02 01 4F 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r>"
        assertTrue(ObdParser.parseVin(comO) is RespostaObd.Invalido)
        assertEquals(RespostaObd.SemDados, ObdParser.parseVin("NO DATA\r>"))
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseVin("?\r>"))
    }

    // --- DTCs pendentes (modo 07) e permanentes (modo 0A) ---

    @Test
    fun `pendentes CAN com byte de quantidade`() {
        assertEquals(listOf("P0133", "P0420"), ObdParser.parseDtcsPendentes("47 02 01 33 04 20\r>").valor())
    }

    @Test
    fun `pendentes ISO com padding e eco`() {
        assertEquals(listOf("P0171"), ObdParser.parseDtcsPendentes("07\r47 01 71 00 00 00 00\r>").valor())
    }

    @Test
    fun `pendentes multi frame e sem codigos`() {
        val bruto = "00A\r0: 47 04 01 33\r1: 03 00 40 35\r2: 80 01 00 00\r>"
        assertEquals(listOf("P0133", "P0300", "C0035", "B0001"), ObdParser.parseDtcsPendentes(bruto).valor())
        assertEquals(emptyList<String>(), ObdParser.parseDtcsPendentes("47 00\r>").valor())
        assertEquals(RespostaObd.SemDados, ObdParser.parseDtcsPendentes("NO DATA\r>"))
    }

    @Test
    fun `permanentes modo 0A`() {
        assertEquals(listOf("P0420"), ObdParser.parseDtcsPermanentes("4A 01 04 20\r>").valor())
        assertEquals(listOf("P0300", "U0100"), ObdParser.parseDtcsPermanentes("0A\r4A 03 00 C1 00 00 00\r>").valor())
        assertEquals(emptyList<String>(), ObdParser.parseDtcsPermanentes("4A 00\r>").valor())
        assertEquals(RespostaObd.ComandoInvalido, ObdParser.parseDtcsPermanentes("?\r>"))
    }

    @Test
    fun `cada modo so aceita o proprio prefixo de resposta`() {
        val armazenados = "43 01 01 33 00 00 00 00\r>"
        assertTrue(ObdParser.parseDtcsPendentes(armazenados) is RespostaObd.Invalido)
        assertTrue(ObdParser.parseDtcsPermanentes(armazenados) is RespostaObd.Invalido)
        assertTrue(ObdParser.parseDtcs("47 01 01 33\r>") is RespostaObd.Invalido)
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
