package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Cenários que só aparecem com hardware de verdade: clone que ignora o primeiro comando, linha K
 * que falha na primeira inicialização, CAN ERROR passageiro, ignição desligada no meio do polling,
 * `STOPPED`, `LV RESET` na partida, adaptador que trava e desconectar com um comando preso.
 */
class Elm327HardwareRealTest {

    /** ELM327 + ECU roteirizados; [interceptar] troca a resposta (ou lança) comando a comando. */
    private class TransporteFalso(val ecu: MutableMap<String, String>) : ObdTransport {
        val enviados = mutableListOf<String>()
        var aberto = false
        var interceptar: (String) -> String? = { null }

        /** Comando que fica preso até [fechar] (adaptador travado / link morto sem aviso). */
        var travarEm: String? = null
        val travou = CompletableDeferred<Unit>()
        private val liberar = CompletableDeferred<Unit>()

        /** `conectar()` só volta quando [fechar] for chamado (scanner fora de alcance). */
        var conexaoLenta = false

        override suspend fun conectar() {
            if (conexaoLenta) {
                travou.complete(Unit)
                liberar.await()
                throw IOException("socket closed")
            }
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            if (comando == travarEm) {
                travou.complete(Unit)
                liberar.await()
                throw IOException("socket closed")
            }
            interceptar(comando)?.let { return it }
            return ecu[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
            liberar.complete(Unit)
        }
    }

    private val dispositivo = DispositivoObd("OBDII", "00:11:22:33:44:55")

    /** Mesma ECU do `Elm327TelemetriaTest` (sem MAP: a borboleta entra na fila rápida). */
    private fun ecu() = mutableMapOf(
        "ATZ" to "\r\rELM327 v1.5\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "SEARCHING...\r41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
        "010D1" to "41 0D 5A\r\r",
        "01101" to "41 10 01 F4\r\r",
        "01111" to "41 11 33\r\r",
        "010C" to "41 0C 0F A0\r\r",
    )

    private fun TestScope.cliente(t: ObdTransport) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun timeout(): Nothing = throw ObdTimeoutException("sem prompt")

    private fun Elm327BluetoothClient.comandosRegistrados() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Comando>()

    private fun Elm327BluetoothClient.eventos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }

    // ---------------------------------------------------------------- inicialização

    @Test
    fun `inicializacao vai inteira para o registro de campo com protocolo e tensao`() = runTest {
        val obd = cliente(TransporteFalso(ecu()))
        assertTrue(obd.conectar(dispositivo).isSuccess)

        val trocas = obd.comandosRegistrados().associateBy { it.comando }
        assertEquals("\r\rELM327 v1.5\r\r", trocas["ATZ"]!!.resposta)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)\r\r", trocas["ATDP"]!!.resposta)
        assertEquals("12.6V\r\r", trocas["ATRV"]!!.resposta)
        assertTrue(trocas["0100"]!!.resposta!!.contains("41 00 BE 1F A8 13"))
        assertTrue(obd.eventos().any { "sufixo de resposta única: sim" in it })
    }

    @Test
    fun `polling so registra as falhas e nao as leituras boas`() = runTest {
        val e = ecu().apply { put("010D1", "NO DATA\r\r") }
        val obd = cliente(TransporteFalso(e))
        obd.conectar(dispositivo)
        val antes = obd.comandosRegistrados().size

        obd.lerTelemetria(250).take(3).toList()
        val novos = obd.comandosRegistrados().drop(antes)
        // Os rápidos que responderam não aparecem; a velocidade muda aparece em todo ciclo.
        assertTrue(novos.none { it.comando in setOf("010C1", "01101") })
        assertEquals(3, novos.count { it.comando == "010D1" && it.resposta == "NO DATA\r\r" })
        assertTrue(novos.all { ObdParser.erroElm(it.resposta.orEmpty()) != null })
    }

    @Test
    fun `clone que ignora o primeiro ATZ conecta na segunda tentativa`() = runTest {
        val t = TransporteFalso(ecu())
        var atz = 0
        t.interceptar = { if (it == "ATZ" && atz++ == 0) timeout() else null }
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals(2, t.enviados.count { it == "ATZ" })
        assertTrue(obd.eventos().any { "ATZ" in it })
    }

    @Test
    fun `dispositivo que nao e ELM327 falha com mensagem clara`() = runTest {
        val t = TransporteFalso(ecu())
        t.interceptar = { if (it == "ATZ") timeout() else null }
        val obd = cliente(t)
        val r = obd.conectar(dispositivo)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("ATZ"))
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse(t.aberto)
    }

    @Test
    fun `ATZ com bytes nulos e controle ainda identifica o ELM327 sem enviar ATI`() = runTest {
        val t = TransporteFalso(ecu().apply { put("ATZ", "\u0000\u0000\r\u0007EL\u0000M327 v1.5\u0000\r\r") })
        // "EL\u0000M327" vira "EL M327" ao trocar controle por espaço: o ATI confirma.
        t.interceptar = { if (it == "ATI") "ELM327 v1.5\r\r" else null }
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)

        val t2 = TransporteFalso(ecu().apply { put("ATZ", "\u0000\u0000\r\rELM327 v1.5\u0000\r\r") })
        val obd2 = cliente(t2)
        assertTrue(obd2.conectar(dispositivo).isSuccess)
        assertFalse("ATI" in t2.enviados)
    }

    @Test
    fun `ATZ sem ELM mas ATI de adaptador compativel conecta`() = runTest {
        for (ati in listOf("OBDII v1.5\r\r", "STN1110 v4.0\r\r", "VGATE iCar\r\r", "v2.1\r\r")) {
            val t = TransporteFalso(ecu().apply { put("ATZ", "\r\r?\r\r") })
            t.interceptar = { if (it == "ATI") ati else null }
            val obd = cliente(t)
            assertTrue(ati, obd.conectar(dispositivo).isSuccess)
            assertTrue("ATI" in t.enviados)
        }
    }

    @Test
    fun `ATZ e ATI irreconheciveis mas ATE0 com OK aceita a inicializacao`() = runTest {
        val t = TransporteFalso(ecu().apply { put("ATZ", "xyz\r\r"); put("ATI", "???\r\r") })
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertTrue(obd.eventos().any { "ATE0 respondeu OK" in it })
    }

    @Test
    fun `sem identificacao e sem OK no ATE0 falha e o registro guarda a resposta bruta do ATZ`() = runTest {
        val t = TransporteFalso(ecu().apply { put("ATZ", "\u0000zz\r\r"); put("ATI", "???\r\r"); put("ATE0", "?\r\r") })
        val obd = cliente(t)
        val r = obd.conectar(dispositivo)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("ELM327"))
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        val atz = obd.comandosRegistrados().first { it.comando == "ATZ" }
        assertEquals("\u0000zz\r\r", atz.resposta)
        assertTrue(obd.comandosRegistrados().any { it.comando == "ATI" })
        assertTrue(obd.eventos().any { "ATZ=\"\\x00zz\\r\\r\"" in it })
    }

    @Test
    fun `linha K que falha no primeiro BUS INIT conecta na nova busca`() = runTest {
        val t = TransporteFalso(ecu())
        var buscas = 0
        t.interceptar = {
            if (it == "0100") {
                if (buscas++ == 0) "SEARCHING...\rBUS INIT: ...ERROR\r\r" else "SEARCHING...\rBUS INIT: ...OK\r41 00 BE 1F A8 13\r\r"
            } else {
                null
            }
        }
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        assertEquals(2, t.enviados.count { it == "0100" })
        assertTrue("010C" in obd.pidsSuportados.value)
    }

    @Test
    fun `linha K sem comunicacao explica ignicao e protocolo da montadora`() = runTest {
        val t = TransporteFalso(ecu().apply { put("0100", "SEARCHING...\rBUS INIT: ...ERROR\r\r") })
        val obd = cliente(t)
        val mensagem = obd.conectar(dispositivo).exceptionOrNull()!!.message!!
        assertTrue(mensagem, "BUS INIT: ERROR" in mensagem && "ignição" in mensagem && "OBD-BR2" in mensagem)
        assertEquals(2, t.enviados.count { it == "0100" })
        assertFalse(t.aberto)
    }

    // ---------------------------------------------------------------- erros durante o polling

    @Test
    fun `CAN ERROR passageiro em todos os PIDs nao derruba a sessao`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        var ciclos = 0
        t.interceptar = { cmd ->
            if (cmd == "010C1") ciclos++
            if (!cmd.startsWith("AT") && ciclos <= 3) "CAN ERROR\r\r" else null
        }
        val leituras = obd.lerTelemetria(250).take(6).toList()
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertEquals(null, leituras[0].rpm)
        assertEquals(1726, leituras.last().rpm)
    }

    @Test
    fun `ignicao desligada encerra a sessao depois de varios ciclos sem ECU`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.interceptar = { if (it.startsWith("AT")) null else "NO DATA\r\r" } // ATRV continua respondendo

        val leituras = obd.lerTelemetria(250).toList()
        assertEquals(Elm327BluetoothClient.MAX_CICLOS_SEM_ECU - 1, leituras.size)
        val erro = obd.estado.value as EstadoConexaoObd.Erro
        assertTrue(erro.mensagem, "ignição" in erro.mensagem && "NO DATA" in erro.mensagem)
        assertFalse(t.aberto)
    }

    @Test
    fun `STOPPED repete o comando uma vez`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()
        var primeira = true
        t.interceptar = { if (it == "010C1" && primeira) "STOPPED\r\r".also { primeira = false } else null }

        val dados = obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertEquals(listOf("010C1", "010C1"), t.enviados)
        assertEquals(1726, dados.rpm)
    }

    @Test
    fun `LV RESET na partida reaplica a configuracao do adaptador`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()
        var primeira = true
        t.interceptar = {
            if (it == "010C1" && primeira) "\r\rLV RESET\r\rELM327 v1.5\r\r".also { primeira = false } else null
        }

        obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertEquals(listOf("010C1", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2"), t.enviados)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        assertTrue(obd.eventos().any { "reiniciou" in it })
    }

    @Test
    fun `timeout liga o respiro de 15 ms entre comandos pelo resto da sessao`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        val instantes = mutableListOf<Pair<String, Long>>()
        var primeira = true
        t.interceptar = {
            instantes += it to testScheduler.currentTime
            if (it == "010D1" && primeira) {
                primeira = false
                timeout()
            }
            null
        }

        obd.lerTelemetria(intervaloMinimoMs = 1).take(2).toList()

        val iTimeout = instantes.indexOfFirst { it.first == "010D1" }
        val antes = instantes.take(iTimeout + 1).zipWithNext { a, b -> b.second - a.second }
        assertTrue("sem pausa antes do erro: $antes", antes.all { it == 0L })
        // Do timeout em diante, todo comando espera o respiro (fora a pausa entre ciclos).
        val depois = instantes.drop(iTimeout).zipWithNext { a, b -> b.second - a.second }
        assertTrue("respiro depois do erro: $depois", depois.all { it >= Elm327BluetoothClient.INTERVALO_SEGURO_MS - 5 })
        assertTrue(obd.eventos().any { "Respiro de 15 ms" in it })
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    @Test
    fun `um timeout isolado nao derruba mas tres seguidos sim`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        var primeira = true
        t.interceptar = { if (it == "010D1" && primeira) timeout().also { primeira = false } else null }
        obd.lerTelemetria(250).take(3).toList()
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)

        t.enviados.clear()
        t.interceptar = { timeout() } // adaptador travou
        assertTrue(obd.lerTelemetria(250).toList().isEmpty())
        assertEquals(Elm327BluetoothClient.MAX_TIMEOUTS_SEGUIDOS, t.enviados.size)
        assertTrue((obd.estado.value as EstadoConexaoObd.Erro).mensagem.contains("parou de responder"))
    }

    @Test
    fun `interrogacao isolada nao desliga o sufixo se o comando puro tambem falhar`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        var primeira = true
        t.interceptar = {
            when {
                it == "010C1" && primeira -> "?\r\r".also { primeira = false }
                it == "010C" -> "?\r\r" // resíduo de um comando interrompido, não falta de suporte
                else -> null
            }
        }
        obd.lerDadosEmTempoReal(comandos = listOf("010C")).first()
        assertTrue(obd.usaSufixoRespostaUnica)
    }

    // ---------------------------------------------------------------- desconexão abrupta

    @Test
    fun `desconectar com comando travado destrava na hora sem virar Erro`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.travarEm = "010D1"

        val coleta = async { obd.lerTelemetria(250).toList() }
        t.travou.await()
        runCurrent()
        obd.desconectar()
        coleta.await() // o fluxo termina: nenhuma thread fica presa no read()

        assertEquals(EstadoConexaoObd.Desconectado, obd.estado.value)
        assertTrue(obd.pidsSuportados.value.isEmpty())
        assertTrue(obd.eventos().none { it.startsWith("Conexão perdida") })
        assertFalse(t.aberto)
    }

    @Test
    fun `cancelar durante a conexao volta a Desconectado e nao a Erro`() = runTest {
        val t = TransporteFalso(ecu()).apply { conexaoLenta = true }
        val obd = cliente(t)

        val tentativa = async { obd.conectar(dispositivo) }
        t.travou.await()
        assertEquals(EstadoConexaoObd.Conectando, obd.estado.value)
        obd.desconectar()

        assertTrue(tentativa.await().isFailure)
        assertEquals(EstadoConexaoObd.Desconectado, obd.estado.value)
        assertTrue(obd.eventos().none { it.startsWith("Conexão perdida") })
    }

    @Test
    fun `link perdido no meio da telemetria vira Erro e fecha o canal`() = runTest {
        val t = TransporteFalso(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        var ciclos = 0
        t.interceptar = { if (it == "010C1" && ++ciclos == 3) throw IOException("Broken pipe") else null }

        val leituras = obd.lerTelemetria(250).toList()
        assertEquals(2, leituras.size)
        assertTrue((obd.estado.value as EstadoConexaoObd.Erro).mensagem.contains("Bluetooth"))
        assertFalse(t.aberto)
        assertTrue(obd.comandosRegistrados().any { it.falha?.startsWith("LINK PERDIDO") == true })
    }
}
