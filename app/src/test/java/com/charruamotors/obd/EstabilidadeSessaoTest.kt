package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Auditoria de estabilidade (v2.2.1): queda do socket no meio do polling, falhas não documentadas
 * da pilha Bluetooth, reconexão limpa e corridas entre desconectar, comandos em andamento e a
 * conexão seguinte — sempre terminando em estado coerente, sem exceção chegando à UI.
 */
class EstabilidadeSessaoTest {

    /**
     * ELM327 + ECU roteirizados. [segurar] deixa um comando esperando o adaptador até o [fechar]
     * (que entrega a resposta junto: o pior caso, a resposta chegando no instante do fechamento);
     * [falhaNoComando] lança a exceção dada; [aoEnviar] roda antes de cada resposta.
     */
    private class TransporteTeste(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var aberto = false
        var fechamentos = 0
        var segurar: String? = null
        var falhaNoComando: Pair<String, Exception>? = null
        var aoEnviar: suspend (String) -> Unit = {}
        private var pendente: CompletableDeferred<String>? = null

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            aoEnviar(comando)
            falhaNoComando?.let { (c, e) -> if (c == comando) throw e }
            if (comando == segurar) {
                val d = CompletableDeferred<String>()
                pendente = d
                return d.await()
            }
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
            fechamentos++
            pendente?.complete("OK\r\r")
            pendente = null
        }
    }

    private val dispositivo = DispositivoObd("ELM327", "00:11:22:33:44:55")

    private fun ecuPadrao() = mapOf(
        "ATZ" to "ELM327 v1.5\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "SEARCHING...\r41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
        "010D1" to "41 0D 5A\r\r",
        "010B1" to "41 0B 64\r\r",
    )

    /** Cliente que pega um transporte novo da [fila] a cada conexão (como o app faz com o RFCOMM). */
    private fun TestScope.cliente(vararg fila: TransporteTeste): Elm327BluetoothClient {
        val transportes = ArrayDeque(fila.toList())
        return Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { transportes.removeFirst() },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    // ---------------------------------------------------------------- queda no meio do polling

    @Test
    fun `queda do socket no meio do polling encerra em Erro, fecha o transporte e termina o fluxo`() = runTest {
        val t = TransporteTeste(ecuPadrao())
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        t.falhaNoComando = "010D1" to IOException("read failed, socket might closed or timeout, read ret: -1")

        val amostras = obd.lerTelemetria(intervaloMinimoMs = 0).toList() // termina sozinho, sem lançar

        assertTrue(amostras.isEmpty())
        val estado = obd.estado.value
        assertTrue("esperado Erro, veio $estado", estado is EstadoConexaoObd.Erro)
        assertFalse(t.aberto)
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }

    @Test
    fun `excecao nao documentada da pilha Bluetooth no polling vira Erro em vez de derrubar o app`() = runTest {
        val t = TransporteTeste(ecuPadrao())
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        // Alguns aparelhos lançam NPE no BluetoothSocket depois de uma queda de sinal.
        t.falhaNoComando = "010C1" to NullPointerException("Attempt to invoke virtual method on a null object reference")

        val r = runCatching { obd.lerTelemetria(intervaloMinimoMs = 0).toList() }

        assertTrue("o fluxo não pode lançar: ${r.exceptionOrNull()}", r.isSuccess)
        val estado = obd.estado.value as EstadoConexaoObd.Erro
        assertTrue(estado.mensagem, "Bluetooth" in estado.mensagem)
        assertFalse(t.aberto)
    }

    @Test
    fun `excecao no inicio da conexao vira falha com mensagem e libera a proxima tentativa`() = runTest {
        val t1 = TransporteTeste(ecuPadrao()).apply { falhaNoComando = "ATE0" to IllegalStateException("BluetoothSocket closed") }
        val t2 = TransporteTeste(ecuPadrao())
        val obd = cliente(t1, t2)

        val r = obd.conectar(dispositivo)
        assertTrue(r.isFailure)
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse(t1.aberto)

        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    // ---------------------------------------------------------------- reconexão limpa

    @Test
    fun `reconexao depois da queda refaz a inicializacao do zero e o polling volta a ler`() = runTest {
        val t1 = TransporteTeste(ecuPadrao())
        val t2 = TransporteTeste(ecuPadrao())
        val obd = cliente(t1, t2)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        t1.falhaNoComando = "010C1" to IOException("link caiu")
        obd.lerTelemetria(intervaloMinimoMs = 0).toList()
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)

        assertTrue(obd.conectar(dispositivo).isSuccess)

        assertEquals(t1.enviados.take(8), t2.enviados.take(8)) // mesma inicialização, nada herdado
        assertFalse("sem ATSH herdado da sessão anterior", t2.enviados.any { it.startsWith("ATSH") })
        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertEquals(1726, dados.rpm)
        assertEquals(1, t1.fechamentos)
    }

    @Test
    fun `resposta de comando da sessao encerrada nao vaza configuracao para a reconexao`() = runTest {
        val t1 = TransporteTeste(ecuPadrao()).apply { segurar = "ATSH7E1" }
        val t2 = TransporteTeste(ecuPadrao())
        val obd = cliente(t1, t2)
        assertTrue(obd.conectar(dispositivo).isSuccess)

        // Terminal: ATSH 7E1 esperando o adaptador quando o usuário desconecta e reconecta.
        val manual = async { obd.enviarComandoManual("ATSH7E1") }
        advanceUntilIdle()
        obd.desconectar()
        assertTrue(obd.conectar(dispositivo).isSuccess)

        assertTrue("o comando da sessão velha não pode ser dado como OK", manual.await().isFailure)
        // Sem a proteção, o "OK" atrasado marcaria o cabeçalho 7E1 na sessão nova e ela gastaria
        // um ATSH 7DF para "desfazer" algo que o adaptador recém-reiniciado nem tinha.
        assertFalse(t2.enviados.toString(), t2.enviados.any { it.startsWith("ATSH") })
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    @Test
    fun `desconectar no ultimo comando da inicializacao nao deixa Conectado sem canal`() = runTest {
        val t1 = TransporteTeste(ecuPadrao())
        val t2 = TransporteTeste(ecuPadrao())
        val obd = cliente(t1, t2)
        var bitmasks = 0
        // O 01001 sai duas vezes (sufixo e validação do ATST0C); o segundo é o último da inicialização.
        t1.aoEnviar = { if (it == "01001" && ++bitmasks == 2) obd.desconectar() }

        val r = obd.conectar(dispositivo)

        assertTrue(r.isFailure)
        assertEquals(EstadoConexaoObd.Desconectado, obd.estado.value)
        assertFalse(t1.aberto)
        // E a reconexão não é recusada com "Já existe uma conexão ativa".
        assertTrue(obd.conectar(dispositivo).isSuccess)
    }

    @Test
    fun `conectar duas vezes ao mesmo tempo abre um unico socket`() = runTest {
        val t1 = TransporteTeste(ecuPadrao())
        val t2 = TransporteTeste(ecuPadrao())
        val obd = cliente(t1, t2)

        val a = async { obd.conectar(dispositivo) }
        val b = async { obd.conectar(dispositivo) }
        val resultados = listOf(a.await(), b.await())

        assertEquals(1, resultados.count { it.isSuccess })
        assertTrue(resultados.single { it.isFailure }.exceptionOrNull()!!.message!!.contains("Já existe"))
        assertTrue(t1.aberto)
        assertTrue("o segundo transporte nem foi aberto", t2.enviados.isEmpty() && !t2.aberto)
    }

    // ---------------------------------------------------------------- respostas quebradas no polling

    @Test
    fun `BUFFER FULL e eco parcial no polling nao derrubam a sessao nem viram valor`() = runTest {
        val ecu = ecuPadrao() + mapOf(
            "010C1" to "41 0C 1A\rBUFFER FULL\r\r", // quadro cortado pelo buffer do clone
            "010D1" to "10D1\r41 0D 5A\r\r", // eco parcial (primeiro byte perdido) antes da resposta
        )
        val t = TransporteTeste(ecu)
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C", "010D")).first()

        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertFalse("RPM truncado não pode virar valor", "010C" in dados.valores)
        assertEquals(90.0, dados["010D"]!!, 0.0)
    }

    @Test
    fun `STOPPED no polling repete o comando uma vez e usa a resposta boa`() = runTest {
        var tentativas = 0
        val t = TransporteTeste(ecuPadrao())
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        t.aoEnviar = { c ->
            if (c == "010C1") t.respostas["010C1"] = if (++tentativas == 1) "STOPPED\r\r" else "41 0C 1A F8\r\r"
        }

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()

        assertEquals(2, tentativas)
        assertEquals(1726, dados.rpm)
    }

    @Test
    fun `UNABLE TO CONNECT e BUS INIT ERROR seguidos encerram por ECU muda, sem excecao`() = runTest {
        val t = TransporteTeste(ecuPadrao())
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        var n = 0
        t.aoEnviar = { c ->
            if (!c.startsWith("AT")) t.respostas[c] = if (n++ % 2 == 0) "UNABLE TO CONNECT\r\r" else "BUS INIT: ...ERROR\r\r"
        }

        val amostras = obd.lerDadosEmTempoReal(intervaloMs = 0, comandos = listOf("010C", "010D")).toList()

        assertEquals(Elm327BluetoothClient.MAX_CICLOS_SEM_ECU - 1, amostras.size)
        val estado = obd.estado.value as EstadoConexaoObd.Erro
        assertTrue(estado.mensagem, "ECU parou de responder" in estado.mensagem)
        assertFalse(t.aberto)
    }
}
