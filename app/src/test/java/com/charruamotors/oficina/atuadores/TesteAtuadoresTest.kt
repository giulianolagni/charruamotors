package com.charruamotors.oficina.atuadores

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.EcuSimulada
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.RespostaBruta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** v2.1: máquina de comandos do Teste de Atuadores (UDS 2F), interpretação das respostas e NRC. */
class TesteAtuadoresTest {

    /** Adaptador roteirizado: responde pelo comando e anota o que foi enviado. */
    private class Roteiro(private val respostas: Map<String, String>, private val padrao: String = "NO DATA\r\r") {
        val enviados = mutableListOf<String>()
        suspend fun enviar(c: String): RespostaBruta {
            enviados += c
            val r = respostas[c] ?: if (c.startsWith("AT")) "OK\r\r" else padrao
            return RespostaBruta(c, r, 5)
        }
    }

    private val gm = MapeamentoAtuadores.GM_CRUZE

    private fun roteiroGm(rpm: String = "410C0000") = Roteiro(
        mapOf(
            "1003" to "5003003201F4\r\r",
            "1001" to "5001003201F4\r\r",
            "3E00" to "7E00\r\r",
            "010C" to "$rpm\r\r",
            "2F01010301" to "6F01010301\r\r",
            "2F010100" to "6F010100\r\r",
            "2F01210301" to "7F2F33\r\r",
        ),
    )

    // ---------------------------------------------------------------- interpretação UDS

    @Test
    fun `interpreta positiva, negativa, pendente e sem resposta`() {
        assertEquals(listOf(0x6F, 0x01, 0x01, 0x03, 0x01), (InterpretadorUds.interpretar("6F01010301\r\r>", 0x2F) as RespostaUds.Positiva).bytes)
        val seed = InterpretadorUds.interpretar("7F 2F 33\r\r", 0x2F) as RespostaUds.Negativa
        assertEquals(0x33, seed.nrc)
        assertEquals(Nrc.SEGURANCA_NEGADA, seed.conhecido)
        assertEquals("7F 2F 33 · securityAccessDenied", seed.rotulo)
        assertTrue(seed.explicacao.contains("Seed/Key"))
        // 7F 2F 78 (pendente) seguido da resposta final positiva.
        assertTrue(InterpretadorUds.interpretar("7F2F78\r6F01010301\r\r", 0x2F) is RespostaUds.Positiva)
        assertTrue(InterpretadorUds.interpretar("NO DATA\r\r", 0x2F) is RespostaUds.SemResposta)
        assertTrue(InterpretadorUds.interpretar("", 0x2F, semPrompt = true) is RespostaUds.SemResposta)
        assertTrue(InterpretadorUds.interpretar("?\r\r", 0x2F) is RespostaUds.Invalida)
        // Com cabeçalhos (ATH1): 7E8 + PCI.
        assertEquals(0x11, (InterpretadorUds.interpretar("7E8 03 7F 2F 11\r\r", 0x2F) as RespostaUds.Negativa).nrc)
        assertTrue(InterpretadorUds.interpretar("7E8065003003201F4\r", 0x10) is RespostaUds.Positiva)
    }

    @Test
    fun `NRC desconhecido ainda e descrito`() {
        assertTrue(Nrc.descrever(0xF3).contains("0xF3"))
        assertTrue(Nrc.descrever(0x11).startsWith("serviceNotSupported"))
        assertEquals("7F 2F 22 · conditionsNotCorrect", Nrc.CONDICOES_INCORRETAS.rotulo(0x2F))
    }

    @Test
    fun `comando 2F ativa com 03 e devolve com 00`() {
        val c = gm[Atuador.ELETROVENTILADOR_ALTA]!!
        assertEquals("2F01020302", c.ativar)
        assertEquals("2F010200", c.devolver)
        val custom = ComandoAtuador.customizado("2f 0104 03 80", "7e0")!!
        assertEquals("2F01040380", custom.ativar)
        assertEquals("7E0", custom.cabecalho)
        assertEquals("2F010403", ComandoAtuador.customizado("010403", "7E0")!!.ativar)
        assertNull(ComandoAtuador.customizado("2F 01", "7E0"))
        assertNull(ComandoAtuador.customizado("2F 0G01 03", "7E0"))
        assertNull(ComandoAtuador.customizado("2F 0101 03 01", "9XX"))
    }

    @Test
    fun `todos os atuadores tem DID na tabela GM e a ECU simulada os reconhece`() {
        Atuador.entries.forEach { a -> assertTrue("$a sem DID", gm[a] != null) }
        val dids = gm.comandos.values.map { it.did.toInt(16) }.toSet()
        assertEquals(EcuSimulada.DIDS_ATUADORES_GM, dids)
        assertTrue(EcuSimulada.DIDS_SEED_KEY_GM.all { d -> gm.comandos.filterKeys { it.grupo == GrupoAtuador.IGNICAO }.values.any { it.did.toInt(16) == d } })
        assertEquals(MapeamentoAtuadores.GM_CRUZE, MapeamentoAtuadores.paraMarca("Chevrolet"))
        assertEquals(MapeamentoAtuadores.GENERICO, MapeamentoAtuadores.paraMarca("Toyota"))
    }

    // ---------------------------------------------------------------- máquina de comandos

    @Test
    fun `prepara a sessao estendida e ativa a bomba com o motor parado`() = runTest {
        val r = roteiroGm()
        val t = TesteAtuadores(r::enviar, esperar = {})
        assertEquals(ResultadoSessao.Estendida, t.preparar("7E0"))
        assertEquals(EtapaAtuadores.PRONTA, t.etapa)
        val res = t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL])
        assertTrue(res.toString(), res is ResultadoAtuador.Sucesso)
        assertEquals(listOf("ATSH7E0", "1003", "010C", "2F01010301", "3E00", "2F010100"), r.enviados)
        t.encerrar()
        assertEquals("1001", r.enviados.last())
        assertEquals(EtapaAtuadores.ENCERRADA, t.etapa)
    }

    @Test
    fun `item critico com motor ligado e bloqueado sem enviar 2F`() = runTest {
        val r = roteiroGm(rpm = "410C0C80") // 800 rpm
        val t = TesteAtuadores(r::enviar, esperar = {})
        t.preparar("7E0")
        val res = t.executar(Atuador.BICO_1, gm[Atuador.BICO_1]) as ResultadoAtuador.BloqueadoMotor
        assertEquals(800.0, res.rpm!!, 0.0)
        assertFalse(r.enviados.any { it.startsWith("2F") })
    }

    @Test
    fun `sem RPM da ECU vale o ultimo conhecido e sem nenhum o critico fica bloqueado`() = runTest {
        val semRpm = Roteiro(mapOf("1003" to "5003003201F4\r\r", "010C" to "7F0111\r\r"))
        val t = TesteAtuadores(semRpm::enviar, rpmConhecido = { null }, esperar = {})
        t.preparar("7E0")
        assertTrue(t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL]) is ResultadoAtuador.BloqueadoMotor)
        val comPolling = TesteAtuadores(semRpm::enviar, rpmConhecido = { 0.0 }, esperar = {})
        comPolling.preparar("7E0")
        assertFalse(comPolling.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL]) is ResultadoAtuador.BloqueadoMotor)
    }

    @Test
    fun `item nao critico roda com o motor ligado`() = runTest {
        val r = roteiroGm(rpm = "410C0C80")
        val t = TesteAtuadores(r::enviar, esperar = {})
        t.preparar("7E0")
        val res = t.executar(Atuador.ELETROVENTILADOR_BAIXA, gm[Atuador.ELETROVENTILADOR_BAIXA])
        // O roteiro não conhece o DID 0102: NO DATA → sem resposta, mas o controle é devolvido.
        assertTrue(res is ResultadoAtuador.SemResposta)
        assertFalse(r.enviados.contains("010C"))
        assertEquals("2F010200", r.enviados.last())
    }

    @Test
    fun `Seed Key negado vira resultado recusado com explicacao`() = runTest {
        val r = roteiroGm()
        val t = TesteAtuadores(r::enviar, esperar = {})
        t.preparar("7E0")
        val res = t.executar(Atuador.BOBINA_1, gm[Atuador.BOBINA_1]) as ResultadoAtuador.Recusado
        assertEquals(0x33, res.negativa.nrc)
        assertTrue(res.negativa.explicacao.contains("Seed/Key"))
        assertEquals(EtapaAtuadores.PRONTA, t.etapa) // a sessão segue utilizável
        assertEquals("2F012100", r.enviados.last())
    }

    @Test
    fun `pulso longo envia Tester Present a cada 2 s`() = runTest {
        val r = roteiroGm()
        val t = TesteAtuadores(r::enviar, esperar = {})
        t.preparar("7E0")
        t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL], duracaoMs = 7_000)
        // 7 s em fatias de 2 s: 3E 00 depois de 2, 4 e 6 s.
        assertEquals(3, r.enviados.count { it == "3E00" })
    }

    @Test
    fun `parar no meio do pulso devolve o controle`() = runTest {
        val r = roteiroGm()
        val liberar = CompletableDeferred<Unit>()
        val t = TesteAtuadores(r::enviar, esperar = { liberar.await() })
        t.preparar("7E0")
        val job = async { t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL]) }
        testScheduler.runCurrent()
        assertEquals(EtapaAtuadores.EXECUTANDO, t.etapa)
        job.cancel()
        testScheduler.runCurrent()
        assertEquals("2F010100", r.enviados.last())
        assertEquals(EtapaAtuadores.PRONTA, t.etapa)
    }

    @Test
    fun `sem sessao iniciada nada e enviado`() = runTest {
        val r = roteiroGm()
        val t = TesteAtuadores(r::enviar, esperar = {})
        assertTrue(t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL]) is ResultadoAtuador.Falha)
        assertTrue(t.executar(Atuador.BOMBA_COMBUSTIVEL, null) is ResultadoAtuador.Falha)
        assertTrue(r.enviados.isEmpty())
    }

    @Test
    fun `parse do RPM com e sem espacos`() {
        assertEquals(0.0, TesteAtuadores.parseRpm("410C0000\r\r")!!, 0.0)
        assertEquals(800.0, TesteAtuadores.parseRpm("41 0C 0C 80\r\r")!!, 0.0)
        assertNull(TesteAtuadores.parseRpm("NO DATA"))
        assertNull(TesteAtuadores.parseRpm("7F0111"))
    }

    // ---------------------------------------------------------------- ponta a ponta no Modo Simulado

    private suspend fun fake(marca: String): ObdClientFake {
        val f = ObdClientFake(random = Random(5), latenciaMs = 0, latenciaComandoMs = 0)
        f.conectar(DispositivoObd("Sim", "00:00:00:00:00:01"), marca)
        f.lerTelemetria(250).take(2).toList() // última leitura com o motor girando (~800 rpm)
        f.iniciarSessaoManual()
        return f
    }

    private fun maquina(f: ObdClientFake) = TesteAtuadores({ c -> f.enviarComandoManual(c).getOrNull() }, esperar = {})

    @Test
    fun `Cruze simulado aceita os atuadores, bloqueia por RPM e pede Seed Key nas bobinas`() = runTest {
        val f = fake("Chevrolet")
        val t = maquina(f)
        assertEquals(ResultadoSessao.Estendida, t.preparar("7E0"))

        // Motor girando: os críticos ficam bloqueados; o eletroventilador pode.
        assertTrue(t.executar(Atuador.BOMBA_COMBUSTIVEL, gm[Atuador.BOMBA_COMBUSTIVEL]) is ResultadoAtuador.BloqueadoMotor)
        assertTrue(t.executar(Atuador.ELETROVENTILADOR_ALTA, gm[Atuador.ELETROVENTILADOR_ALTA]) is ResultadoAtuador.Sucesso)

        f.simularMotor(ligado = false)
        Atuador.entries.filter { it.grupo != GrupoAtuador.IGNICAO }.forEach { a ->
            val r = t.executar(a, gm[a])
            assertTrue("$a: $r", r is ResultadoAtuador.Sucesso)
        }
        Atuador.entries.filter { it.grupo == GrupoAtuador.IGNICAO }.forEach { a ->
            assertEquals(0x33, (t.executar(a, gm[a]) as ResultadoAtuador.Recusado).negativa.nrc)
        }
        // DID desconhecido (customizado) → 7F 2F 31.
        val custom = t.executarCustomizado(ComandoAtuador.customizado("2F 0F0F 03 01", "7E0")!!, 1_000)
        assertEquals(0x31, (custom as ResultadoAtuador.Recusado).negativa.nrc)
        t.encerrar()
        f.encerrarSessaoManual()
    }

    @Test
    fun `fora da sessao estendida a ECU do Cruze responde 7F 2F 7F`() = runTest {
        val f = fake("Chevrolet")
        f.simularMotor(ligado = false)
        assertTrue(f.enviarComandoManual("ATSH7E0").getOrThrow().resposta.startsWith("OK"))
        assertTrue(f.enviarComandoManual("2F01010301").getOrThrow().resposta.startsWith("7F2F7F"))
        f.encerrarSessaoManual()
    }

    @Test
    fun `SW4 simulada recusa o servico 2F com NRC 11 sem travar a sessao`() = runTest {
        val f = fake("Toyota")
        f.simularMotor(ligado = false)
        val t = maquina(f)
        val sessao = t.preparar("7E0") as ResultadoSessao.Recusada
        assertEquals(0x12, sessao.resposta.nrc)
        val r = t.executar(Atuador.BOMBA_COMBUSTIVEL, MapeamentoAtuadores.GENERICO[Atuador.BOMBA_COMBUSTIVEL]) as ResultadoAtuador.Recusado
        assertEquals(Nrc.SERVICO_NAO_SUPORTADO, r.negativa.conhecido)
        assertEquals(EtapaAtuadores.PRONTA, t.etapa)
        t.encerrar()
        f.encerrarSessaoManual()
        assertFalse(f.sessaoManualAtiva.value)
    }
}
