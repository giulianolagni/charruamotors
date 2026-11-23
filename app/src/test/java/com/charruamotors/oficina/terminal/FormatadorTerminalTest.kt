package com.charruamotors.oficina.terminal

import com.charruamotors.obd.RespostaBruta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class FormatadorTerminalTest {

    private val zona = ZoneOffset.UTC
    private val instante = 1_763_800_205_123L // 08:30:05.123 UTC

    @Test
    fun `comando latencia e uma linha por quadro da resposta`() {
        val entrada = EntradaTerminal.de(RespostaBruta("2101", "012\r0:6101007D873C\r1:8012008B641F00\r\r", 42), instante)
        val linhas = FormatadorTerminal.formatar(entrada, zona).lines()
        assertTrue(linhas[0], linhas[0].startsWith("08:30:05.123  > 2101"))
        assertTrue(linhas[0], linhas[0].endsWith("42 ms"))
        assertEquals(listOf("012", "0:6101007D873C", "1:8012008B641F00"), linhas.drop(1).map { it.trim() })
    }

    @Test
    fun `timeout erro e resposta vazia ficam explicitos`() {
        val timeout = FormatadorTerminal.formatar(EntradaTerminal.de(RespostaBruta("0100", "", 2001, semPrompt = true), instante), zona)
        assertTrue(timeout, "TIMEOUT" in timeout)
        val erro = FormatadorTerminal.formatar(EntradaTerminal.falha("ATMA", "não é permitido", instante), zona)
        assertTrue(erro, "ERRO: não é permitido" in erro)
        val vazia = FormatadorTerminal.formatar(EntradaTerminal.de(RespostaBruta("ATE0", "\r\r", 12), instante), zona)
        assertTrue(vazia, "(resposta vazia)" in vazia)
    }

    @Test
    fun `remove bytes nulos e o prompt da resposta`() {
        assertEquals(listOf("12.4V"), FormatadorTerminal.linhasResposta("\u000012.4V\r\r>"))
    }

    @Test
    fun `varias entradas em ordem`() {
        val texto = FormatadorTerminal.formatar(
            listOf(
                EntradaTerminal.de(RespostaBruta("ATSH 7E1", "OK\r\r", 20), instante),
                EntradaTerminal.de(RespostaBruta("2182", "618200003D62\r\r", 35), instante + 100),
            ),
            zona,
        )
        assertTrue(texto.indexOf("ATSH 7E1") < texto.indexOf("2182"))
        assertTrue("618200003D62" in texto)
    }
}
