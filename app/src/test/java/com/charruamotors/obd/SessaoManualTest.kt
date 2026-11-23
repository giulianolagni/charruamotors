package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import com.charruamotors.oficina.bateria.EtapaBateria
import com.charruamotors.oficina.bateria.LeituraTensao
import com.charruamotors.oficina.bateria.TesteBateria
import com.charruamotors.oficina.bateria.VereditoAlternador
import com.charruamotors.oficina.bateria.VereditoPartida
import com.charruamotors.oficina.bateria.VereditoRepouso
import com.charruamotors.oficina.varredura.ConfigVarredura
import com.charruamotors.oficina.varredura.TipoRespostaPid
import com.charruamotors.oficina.varredura.VarreduraPids
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Sessão manual das Ferramentas de Oficina: pausa do polling (inclusive o do HUD), comandos brutos
 * sem `ATSH` automático e a restauração do adaptador no fim — no cliente ELM327 real (transporte
 * roteirizado) e no simulado (curva de bateria e ECU Hilux para o terminal e a varredura).
 */
class SessaoManualTest {

    private class TransporteRoteirizado(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        var aberto = false
        var timeoutNoComando: String? = null

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            if (comando == timeoutNoComando) throw ObdTimeoutException("O scanner não respondeu ao comando $comando")
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("ELM327", "00:11:22:33:44:55")

    private fun ecu() = mutableMapOf(
        "ATZ" to "ELM327 v1.5\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
        "010D1" to "41 0D 5A\r\r",
        "01101" to "41 10 01 F4\r\r",
        "2182" to "618200003D62\r\r",
    )

    private fun TestScope.cliente(t: ObdTransport) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private suspend fun TestScope.conectado(): Pair<TransporteRoteirizado, Elm327BluetoothClient> {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t)
        assertTrue(obd.conectar(dispositivo).isSuccess)
        t.enviados.clear()
        return t to obd
    }

    // ---------------------------------------------------------------- cliente ELM327 real

    @Test
    fun `sessao manual pausa a telemetria e a retoma no fim sem fechar o socket`() = runTest {
        val (t, obd) = conectado()
        val leituras = mutableListOf<DadosObd>()
        backgroundScope.launch { obd.lerTelemetria(intervaloMinimoMs = 100).collect { leituras += it } }
        advanceTimeBy(1_000)
        assertTrue(leituras.isNotEmpty())

        assertTrue(obd.iniciarSessaoManual().isSuccess)
        assertTrue(obd.sessaoManualAtiva.value)
        advanceTimeBy(50) // o ciclo que estava em andamento termina e emite
        val pausadas = leituras.size
        t.enviados.clear()
        advanceTimeBy(3_000)
        assertEquals("nenhum ciclo de polling durante a sessão", pausadas, leituras.size)
        assertTrue(t.enviados.isEmpty())

        assertEquals("12.6V\r\r", obd.enviarComandoManual("ATRV").getOrThrow().resposta)
        assertEquals(listOf("ATRV"), t.enviados)

        obd.encerrarSessaoManual()
        assertFalse(obd.sessaoManualAtiva.value)
        advanceTimeBy(1_000)
        assertTrue("o polling voltou", leituras.size > pausadas)
        assertTrue(t.aberto)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    @Test
    fun `comando manual vai cru e o ATSH manual e desfeito ao encerrar`() = runTest {
        val (t, obd) = conectado()
        obd.iniciarSessaoManual()

        assertTrue("OK" in obd.enviarComandoManual("atsh 7e1").getOrThrow().resposta)
        val r = obd.enviarComandoManual("2182").getOrThrow()
        assertEquals("618200003D62\r\r", r.resposta)
        assertFalse(r.semPrompt)
        assertTrue(r.latenciaMs >= 0)
        // Sem ATSH automático entre os dois: o 2182 saiu com o cabeçalho que o usuário pôs.
        assertEquals(listOf("ATSH 7E1", "2182"), t.enviados)

        obd.encerrarSessaoManual()
        assertEquals("volta ao cabeçalho funcional da entrada", listOf("ATSH 7E1", "2182", "ATSH7DF"), t.enviados)
    }

    @Test
    fun `sem mudanca de cabecalho nem de configuracao o encerramento nao envia nada`() = runTest {
        val (t, obd) = conectado()
        obd.iniciarSessaoManual()
        obd.enviarComandoManual("ATRV")
        obd.enviarComandoManual("ATDP")
        obd.encerrarSessaoManual()
        assertEquals(listOf("ATRV", "ATDP"), t.enviados)
    }

    @Test
    fun `ATH1 manual faz reaplicar a configuracao e o cabecalho no fim`() = runTest {
        val (t, obd) = conectado()
        obd.iniciarSessaoManual()
        obd.enviarComandoManual("ATH1")
        obd.encerrarSessaoManual()
        assertEquals(listOf("ATH1", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATSH7DF"), t.enviados)
    }

    @Test
    fun `LV RESET na partida nao derruba a sessao e a configuracao e reaplicada no fim`() = runTest {
        val (t, obd) = conectado()
        t.respostas["ATRV"] = "LV RESET\r\r"
        obd.iniciarSessaoManual()
        val r = obd.enviarComandoManual("ATRV", registrar = false).getOrThrow()
        assertEquals(LeituraTensao.LvReset, LeituraTensao.deResposta(r.resposta))
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)

        obd.encerrarSessaoManual()
        assertEquals(listOf("ATRV", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATSH7DF"), t.enviados)
        assertTrue(obd.registroComunicacao().any { (it as? com.charruamotors.obd.log.EntradaRegistro.Evento)?.texto?.contains("LV RESET") == true })
    }

    @Test
    fun `timeout de comando manual vira semPrompt sem perder a conexao`() = runTest {
        val (t, obd) = conectado()
        t.timeoutNoComando = "2101"
        obd.iniciarSessaoManual()
        val r = obd.enviarComandoManual("2101").getOrThrow()
        assertTrue(r.semPrompt)
        assertEquals("", r.resposta)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
    }

    @Test
    fun `modo monitor e comandos invalidos sao recusados sem chegar ao adaptador`() = runTest {
        val (t, obd) = conectado()
        assertTrue(obd.enviarComandoManual("ATMA").isFailure)
        assertTrue(obd.enviarComandoManual("AT LP").isFailure)
        assertTrue(obd.enviarComandoManual("   ").isFailure)
        assertTrue(obd.enviarComandoManual("01" + "0".repeat(60)).isFailure)
        assertTrue(obd.enviarComandoManual("ATRV\rATZ").isFailure)
        assertTrue(t.enviados.isEmpty())
    }

    @Test
    fun `sessao manual exige conexao e desconectar a libera`() = runTest {
        val desconectado = cliente(TransporteRoteirizado(ecu()))
        assertTrue(desconectado.iniciarSessaoManual().isFailure)
        assertTrue(desconectado.enviarComandoManual("ATRV").isFailure)

        val (_, obd) = conectado()
        obd.iniciarSessaoManual()
        obd.desconectar()
        assertFalse(obd.sessaoManualAtiva.value)
    }

    @Test
    fun `normalizacao do comando manual`() {
        assertEquals("ATSH 7E0", ObdClient.normalizarComandoManual("  atsh 7e0 "))
        assertEquals(null, ObdClient.normalizarComandoManual(""))
        assertEquals(null, ObdClient.normalizarComandoManual("2101\n"+"13"))
    }

    // ---------------------------------------------------------------- cliente simulado

    private fun TestScope.fake() = ObdClientFake(
        random = Random(7),
        latenciaMs = 0,
        latenciaComandoMs = 50,
        relogioMs = { testScheduler.currentTime },
    )

    @Test
    fun `fake simula a partida com vale de cerca de 10,3 V um LV RESET e o alternador assumindo`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        obd.iniciarSessaoManual()
        obd.simularBateria(FaseBateriaSimulada.PARTIDA)
        val leituras = List(120) { LeituraTensao.deResposta(obd.enviarComandoManual("ATRV", registrar = false).getOrThrow().resposta) }

        assertEquals(1, leituras.count { it == LeituraTensao.LvReset })
        val volts = leituras.filterIsInstance<LeituraTensao.Valida>().map { it.volts }
        assertEquals(12.5, volts.first(), 0.05)
        assertEquals(10.3, volts.min(), 0.11)
        assertEquals(14.2, volts.last(), 0.11)
    }

    @Test
    fun `teste de bateria completo com o fake da laudo saudavel e alternador normal`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        obd.iniciarSessaoManual()
        val teste = TesteBateria()

        suspend fun capturar(fase: FaseBateriaSimulada) {
            obd.simularBateria(fase)
            teste.iniciarCaptura(testScheduler.currentTime)
            var amostras = 0
            while (teste.capturando && amostras++ < 1_000) {
                val r = obd.enviarComandoManual("ATRV", registrar = false).getOrThrow()
                teste.registrar(LeituraTensao.deResposta(r.resposta, r.semPrompt), testScheduler.currentTime)
            }
        }

        capturar(FaseBateriaSimulada.REPOUSO)
        assertEquals(EtapaBateria.PARTIDA, teste.etapa)
        capturar(FaseBateriaSimulada.PARTIDA)
        assertEquals(EtapaBateria.ALTERNADOR_LENTA, teste.etapa)
        capturar(FaseBateriaSimulada.MARCHA_LENTA)
        capturar(FaseBateriaSimulada.CARGA)

        assertTrue(teste.concluido)
        assertEquals(VereditoRepouso.SAUDAVEL, teste.resultadoRepouso!!.veredito)
        assertEquals(12.5, teste.resultadoRepouso!!.tensao, 0.05)
        val partida = teste.resultadoPartida!!
        assertEquals(VereditoPartida.OK, partida.veredito)
        assertEquals(10.3, partida.vMin, 0.11)
        assertEquals(1, partida.lvResets)
        assertTrue(partida.motorPegou)
        assertEquals(VereditoAlternador.NORMAL, teste.resultadoLenta!!.veredito)
        assertEquals(14.2, teste.resultadoLenta!!.media, 0.1)
        assertEquals(VereditoAlternador.NORMAL, teste.resultadoCarga!!.veredito)
    }

    @Test
    fun `fake responde como uma Hilux no servico 21 do motor e do cambio`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        obd.iniciarSessaoManual()
        suspend fun cmd(c: String) = obd.enviarComandoManual(c).getOrThrow().resposta

        assertEquals("A6\r\r", cmd("ATDPN"))
        assertTrue("OK" in cmd("ATSH 7E0"))
        // 10 bytes não cabem num quadro CAN: multi-frame com ATH0, como no adaptador real.
        assertEquals("00A\r0:611381A47ECA\r1:873A7F88\r\r", cmd("2113"))
        assertTrue(cmd("2101").startsWith("012\r0:6101")) // multi-frame CAN com ATH0
        assertEquals("7F2131\r\r", cmd("21A0"))
        assertEquals("7F2211\r\r", cmd("22F40C"))
        // 3 DTCs (P0300, P0171, P0420) = 8 bytes: multi-frame.
        assertEquals("008\r0:530303000171\r1:0420\r\r", cmd("13"))
        assertTrue("OK" in cmd("ATSH 7E1"))
        assertTrue(cmd("2182").startsWith("6182"))
        assertTrue("OK" in cmd("ATSH 7E2"))
        assertEquals("NO DATA\r\r", cmd("2101"))
        assertEquals("?\r\r", cmd("21G1"))
    }

    @Test
    fun `varredura Toyota Motor no fake encontra os identificadores do motor`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        obd.iniciarSessaoManual()
        val resultado = VarreduraPids.executar(VarreduraPids.PRESETS[0].config, enviar = { obd.enviarComandoManual(it, registrar = false) })

        assertTrue(resultado.completa)
        assertEquals(256, resultado.resultados.size)
        val esperados = (EcuSimulada.IDS_MOTOR + setOf(0x01, 0x05, 0x0C, 0x13, 0x49, 0x4A)).map { "21%02X".format(it) }.toSet()
        assertEquals(esperados, resultado.positivas.map { it.comando }.toSet())
        assertTrue(resultado.resultados.filterNot { it.respondeu }.all { it.tipo == TipoRespostaPid.NEGATIVA && it.nrc == "31" })

        val cambio = VarreduraPids.executar(ConfigVarredura("7E2", "21", 0x00, 0x0F), enviar = { obd.enviarComandoManual(it) })
        assertTrue(cambio.resultados.all { it.tipo == TipoRespostaPid.SEM_DADOS })
    }

    @Test
    fun `fake pausa a telemetria na sessao e restaura o cabecalho ao encerrar`() = runTest {
        val obd = fake()
        obd.conectar(dispositivo)
        val leituras = mutableListOf<DadosObd>()
        backgroundScope.launch { obd.lerTelemetria(intervaloMinimoMs = 100).collect { leituras += it } }
        advanceTimeBy(1_000)
        obd.iniciarSessaoManual()
        advanceTimeBy(150)
        val pausadas = leituras.size
        obd.enviarComandoManual("ATSH 7E1")
        advanceTimeBy(2_000)
        assertEquals(pausadas, leituras.size)

        obd.encerrarSessaoManual()
        advanceTimeBy(1_000)
        assertTrue(leituras.size > pausadas)
        obd.iniciarSessaoManual()
        // De volta ao funcional 7DF: o serviço 21 só atende o endereço físico.
        assertEquals("NO DATA\r\r", obd.enviarComandoManual("2113").getOrThrow().resposta)
    }
}
