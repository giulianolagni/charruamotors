package com.charruamotors.obd.transporte

import com.charruamotors.obd.ObdTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Leitura do ELM327 até o prompt com tempo virtual: respostas em pedaços, linha K lenta,
 * adaptador mudo, lixo sem fim, resposta atrasada de um comando que estourou o prazo e link perdido.
 */
class CanalElm327Test {

    /**
     * Simula o lado do adaptador: cada comando recebido (até o `\r`) agenda os pedaços da resposta
     * em instantes virtuais. `read()` sem bytes disponíveis falha o teste — o canal nunca pode
     * ficar bloqueado num `read()` (fechar o socket não o destravaria de forma confiável).
     */
    private class AdaptadorSimulado(private val agora: () -> Long) {
        /** Respostas por comando: lista de (atraso em ms desde o envio, texto). */
        val roteiro = mutableMapOf<String, List<Pair<Long, String>>>()
        val recebidos = mutableListOf<String>()
        var fechado = false
        private val pendentes = ArrayDeque<Pair<Long, Byte>>()
        private val comando = StringBuilder()

        /** Agenda bytes "soltos" (resposta atrasada, resíduo) num instante absoluto. */
        fun agendar(instanteMs: Long, texto: String) {
            texto.toByteArray(Charsets.US_ASCII).forEach { pendentes.addLast(instanteMs to it) }
        }

        val entrada = object : InputStream() {
            override fun available(): Int {
                if (fechado) throw IOException("socket closed")
                return pendentes.count { it.first <= agora() }
            }

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val disponiveis = available()
                if (disponiveis == 0) fail("read() bloqueante chamado sem bytes disponíveis")
                val n = minOf(len, disponiveis)
                repeat(n) { b[off + it] = pendentes.removeFirst().second }
                return n
            }
        }

        val saida = object : OutputStream() {
            override fun write(b: Int) {
                if (fechado) throw IOException("socket closed")
                val c = b.toChar()
                if (c != '\r') {
                    comando.append(c)
                    return
                }
                val cmd = comando.toString()
                comando.clear()
                recebidos += cmd
                val inicio = agora()
                roteiro[cmd].orEmpty().forEach { (atraso, texto) -> agendar(inicio + atraso, texto) }
            }
        }
    }

    private fun TestScope.cenario(): Pair<AdaptadorSimulado, CanalElm327> {
        val relogio = { testScheduler.currentTime }
        val adaptador = AdaptadorSimulado(relogio)
        return adaptador to CanalElm327(adaptador.entrada, adaptador.saida, relogio)
    }

    @Test
    fun `resposta multi linha em pedacos chega inteira ate o prompt`() = runTest {
        val (elm, canal) = cenario()
        elm.roteiro["0100"] = listOf(
            40L to "SEARCHING...\r",
            900L to "41 00 BE 1F",
            905L to " A8 13\r",
            910L to "\r>",
        )
        assertEquals("SEARCHING...\r41 00 BE 1F A8 13\r\r", canal.enviar("0100", 3_000))
        assertEquals(listOf("0100"), elm.recebidos)
        assertFalse(canal.dessincronizado)
    }

    @Test
    fun `timeout conta da inatividade e a linha K lenta nao estoura`() = runTest {
        val (elm, canal) = cenario()
        // ISO 9141 5 baud: os pontos do BUS INIT saem aos poucos; total de 5,5 s com prazo de 3 s.
        elm.roteiro["0100"] = listOf(
            100L to "BUS INIT: .",
            2_000L to ".",
            4_000L to ".OK\r",
            5_500L to "41 00 BE 3E B8 10\r\r>",
        )
        val r = canal.enviar("0100", 3_000)
        assertTrue(r, r.endsWith("41 00 BE 3E B8 10\r\r"))
        assertTrue(testScheduler.currentTime >= 5_500)
    }

    @Test
    fun `adaptador mudo estoura no prazo e marca o canal para ressincronizar`() = runTest {
        val (_, canal) = cenario()
        val inicio = testScheduler.currentTime
        val e = runCatching { canal.enviar("010C1", 3_000) }.exceptionOrNull()
        assertTrue(e is ObdTimeoutException)
        val decorrido = testScheduler.currentTime - inicio
        assertTrue("estourou em $decorrido ms", decorrido in 3_000..3_100)
        assertTrue(canal.dessincronizado)
    }

    @Test
    fun `bytes sem fim sem prompt nao viram laco infinito`() = runTest {
        val (elm, canal) = cenario()
        // Lixo a cada 500 ms por 60 s (ex.: dispositivo que não é ELM327): para no teto de 2× o prazo.
        elm.roteiro["ATZ"] = (1..120).map { it * 500L to "x" }
        val inicio = testScheduler.currentTime
        assertTrue(runCatching { canal.enviar("ATZ", 3_000) }.exceptionOrNull() is ObdTimeoutException)
        val decorrido = testScheduler.currentTime - inicio
        assertTrue("parou em $decorrido ms", decorrido in 6_000..6_600)
    }

    @Test
    fun `enxurrada de dados para no limite de tamanho`() = runTest {
        val (elm, canal) = cenario()
        elm.roteiro["ATMA"] = listOf(10L to "7E8 03 41 0C 1A F8\r".repeat(400)) // ~7 kB sem prompt
        val e = runCatching { canal.enviar("ATMA", 3_000) }.exceptionOrNull()
        assertTrue(e is ObdTimeoutException)
        assertTrue(canal.dessincronizado)
    }

    @Test
    fun `resposta atrasada do comando que estourou nao vira resposta do proximo`() = runTest {
        val (elm, canal) = cenario()
        // 010C1 só responde depois do prazo; quando o 010D1 é enviado, a resposta velha está chegando.
        elm.roteiro["010C1"] = listOf(3_150L to "41 0C 1A F8\r\r>")
        elm.roteiro["010D1"] = listOf(60L to "41 0D 5A\r\r>")
        assertTrue(runCatching { canal.enviar("010C1", 3_000) }.exceptionOrNull() is ObdTimeoutException)

        assertEquals("41 0D 5A\r\r", canal.enviar("010D1", 3_000))
        assertFalse(canal.dessincronizado)
    }

    @Test
    fun `adaptador ainda ocupado depois da ressincronia e interrompido com STOPPED`() = runTest {
        val (elm, canal) = cenario()
        assertTrue(runCatching { canal.enviar("0100", 1_000) }.exceptionOrNull() is ObdTimeoutException)
        // O ELM327 ainda está buscando o protocolo: o próximo comando o interrompe.
        elm.roteiro["010C1"] = listOf(20L to "STOPPED\r\r>")
        assertEquals("STOPPED\r\r", canal.enviar("010C1", 3_000))
        assertTrue("STOPPED pode deixar um '?' atrasado", canal.dessincronizado)
    }

    @Test
    fun `residuo depois do prompt e descartado antes do proximo comando`() = runTest {
        val (elm, canal) = cenario()
        // Clone que manda um prompt duplicado: o segundo ">" não pode encerrar a próxima leitura.
        elm.roteiro["ATE0"] = listOf(10L to "OK\r\r>\r>")
        elm.roteiro["ATL0"] = listOf(30L to "OK\r\r>")
        assertEquals("OK\r\r", canal.enviar("ATE0", 3_000))
        testScheduler.advanceTimeBy(50)
        assertEquals("OK\r\r", canal.enviar("ATL0", 3_000))
    }

    @Test
    fun `socket fechado durante a espera vira IOException na hora`() = runTest {
        val (elm, canal) = cenario()
        elm.roteiro["010C1"] = listOf(5_000L to "41 0C 1A F8\r\r>")
        // ELM327 puxado da porta / desconectar pela UI: o socket fecha com o comando esperando.
        val inicio = testScheduler.currentTime
        launch {
            delay(200)
            elm.fechado = true
        }
        val e = runCatching { canal.enviar("010C1", 3_000) }.exceptionOrNull()
        assertTrue("esperado IOException, veio $e", e is IOException)
        assertTrue(testScheduler.currentTime - inicio < 300)
    }

    @Test
    fun `envio com o canal ja fechado falha com IOException`() = runTest {
        val (elm, canal) = cenario()
        elm.fechado = true
        assertTrue(runCatching { canal.enviar("ATRV", 3_000) }.exceptionOrNull() is IOException)
    }

    // ---------------------------------------------------------------- v2.2.1: auditoria de estabilidade

    @Test
    fun `comando cancelado no meio nao deixa a resposta atrasada para o proximo`() = runTest {
        val (elm, canal) = cenario()
        // Tela fechada / prazo do perfil estourado com o 21D9 esperando o câmbio responder.
        elm.roteiro["21D9"] = listOf(150L to "61 D9 00 80 52\r\r>")
        elm.roteiro["010C1"] = listOf(80L to "41 0C 1A F8\r\r>")
        val pedido = launch { canal.enviar("21D9", 3_000) }
        testScheduler.advanceTimeBy(100)
        pedido.cancel()
        assertTrue("o cancelamento deixa o canal marcado para ressincronizar", canal.dessincronizado)

        // Sem a ressincronia, o 010C1 leria o "61 D9..." atrasado e cada comando seguinte
        // passaria a receber a resposta do anterior.
        assertEquals("41 0C 1A F8\r\r", canal.enviar("010C1", 3_000))
        assertFalse(canal.dessincronizado)
    }

    @Test
    fun `read devolvendo zero com bytes anunciados conta como silencio e estoura no prazo`() = runTest {
        val relogio = { testScheduler.currentTime }
        // Pilha Bluetooth "meio morta" depois de uma queda de sinal: available() > 0, read() = 0.
        val entrada = object : InputStream() {
            override fun available(): Int = 4
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        val saida = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        val canal = CanalElm327(entrada, saida, relogio)
        val inicio = testScheduler.currentTime

        val e = runCatching { canal.enviar("010C1", 1_000) }.exceptionOrNull()

        assertTrue("esperado timeout, veio $e", e is ObdTimeoutException)
        val decorrido = testScheduler.currentTime - inicio
        assertTrue("estourou em $decorrido ms (laço sem pausa não avança o relógio)", decorrido in 1_000..1_100)
    }

    @Test
    fun `eco parcial e BUFFER FULL chegam inteiros ate o prompt para o parser decidir`() = runTest {
        val (elm, canal) = cenario()
        elm.roteiro["010C1"] = listOf(10L to "10C1\r41 0C 1A", 30L to "\rBUFFER FULL\r\r>")
        assertEquals("10C1\r41 0C 1A\rBUFFER FULL\r\r", canal.enviar("010C1", 3_000))
        assertFalse(canal.dessincronizado)
    }
}
