package com.charruamotors.obd.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.concurrent.thread

class RegistroComunicacaoTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `buffer circular guarda so as ultimas entradas`() {
        var agora = 0L
        val registro = RegistroComunicacao(capacidade = 3, relogio = { agora++ })
        (1..5).forEach { registro.evento("e$it") }
        assertEquals(listOf("e3", "e4", "e5"), registro.entradas().map { (it as EntradaRegistro.Evento).texto })
        registro.limpar()
        assertTrue(registro.entradas().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { RegistroComunicacao(capacidade = 0) }
    }

    @Test
    fun `formato mostra a resposta bruta com controles visiveis`() {
        val registro = RegistroComunicacao(relogio = { 45_296_120L }) // 12:34:56.120 UTC
        registro.comando("ATZ", "\r\rELM327 v1.5\r\r", 812)
        registro.comando("0100", null, 20_000, "TIMEOUT: O scanner não respondeu ao comando 0100")
        registro.evento("Protocolo: ISO 9141-2")
        val linhas = RegistroComunicacao.formatar(registro.entradas(), utc).lines()
        assertEquals("12:34:56.120  ATZ → \"\\r\\rELM327 v1.5\\r\\r\" (812 ms)", linhas[0])
        assertEquals("12:34:56.120  0100 → TIMEOUT: O scanner não respondeu ao comando 0100 (20000 ms)", linhas[1])
        assertEquals("12:34:56.120  ● Protocolo: ISO 9141-2", linhas[2])
    }

    @Test
    fun `bytes de controle e fora do ASCII aparecem em hexadecimal`() {
        assertEquals("\\x00\\x00410C\\n\\xFF", RegistroComunicacao.visivel("\u0000\u0000410C\nÿ"))
    }

    @Test
    fun `gravacao de varias threads nao perde nem corrompe entradas`() {
        val registro = RegistroComunicacao(capacidade = 10_000)
        val threads = (1..4).map { n -> thread { repeat(1_000) { registro.comando("01$n", "NO DATA", 1) } } }
        // A UI lendo enquanto o polling grava (não pode lançar ConcurrentModificationException).
        repeat(200) { registro.entradas().size }
        threads.forEach { it.join() }
        assertEquals(4_000, registro.entradas().size)
    }
}
