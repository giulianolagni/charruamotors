package com.charruamotors.obd.sessao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proteção da sessão automática (Bluetooth do carro): `ACL_DISCONNECTED` é ignorado com o app em uso
 * e, em segundo plano, só encerra depois de 15 s sem o carro reconectar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtecaoSessaoTest {

    private val escopos = mutableListOf<CoroutineScope>()

    @After
    fun cancelarEscopos() = escopos.forEach { it.cancel() }

    private class Cenario(val protecao: ProtecaoSessao) {
        var estado = EstadoSessao(fase = FaseSessao.Conectada, origem = OrigemSessao.AUTOMATICA)
        val encerramentos = mutableListOf<String>()
        val eventos = mutableListOf<String>()
    }

    private fun TestScope.cenario(tolerancia: Long = 15_000): Cenario {
        lateinit var c: Cenario
        val protecao = ProtecaoSessao(
            escopo = CoroutineScope(StandardTestDispatcher(testScheduler)).also { escopos += it },
            estadoSessao = { c.estado },
            encerrar = { c.encerramentos += it },
            registrar = { c.eventos += it },
            assumir = { c.estado = c.estado.copy(assumidaPeloUsuario = true) },
            toleranciaMs = tolerancia,
        )
        c = Cenario(protecao)
        return c
    }

    private fun TestScope.avancar(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `ACL_DISCONNECTED com o app em primeiro plano nao desconecta e registra no log`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = false)

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(60_000)

        assertTrue("nenhum encerramento", c.encerramentos.isEmpty())
        assertFalse(c.protecao.encerramentoAgendado)
        assertEquals(listOf("Bluetooth do carro desconectado, mas sessão mantida pois o app está em uso"), c.eventos)
    }

    @Test
    fun `ACL_DISCONNECTED com ferramenta manual aberta mantem a sessao mesmo sem o resto do app`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = true) // Teste de Bateria / Terminal / Varredura
        assertTrue(c.protecao.ferramentaAberta)
        assertTrue("abrir a ferramenta assume a sessão", c.estado.assumidaPeloUsuario)

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(60_000)

        assertTrue(c.encerramentos.isEmpty())
        assertTrue(c.eventos.single().contains("sessão mantida"))
    }

    @Test
    fun `sessao assumida pelo usuario nao encerra pelo Bluetooth nem depois de sair do app`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = true)
        c.protecao.activityParada(ferramenta = true) // saiu da ferramenta e do app
        assertFalse(c.protecao.appEmPrimeiroPlano)

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(60_000)

        assertTrue("só Desconectar ou a ECU muda encerram", c.encerramentos.isEmpty())
    }

    @Test
    fun `em segundo plano espera 15 s e entao encerra a sessao`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        runCurrent()
        assertTrue(c.protecao.encerramentoAgendado)

        avancar(14_900)
        assertTrue("ainda dentro da tolerância", c.encerramentos.isEmpty())

        avancar(200)
        assertEquals(listOf("Bluetooth do carro (Toyota SW4) desconectado"), c.encerramentos)
        assertFalse(c.protecao.encerramentoAgendado)
    }

    @Test
    fun `Bluetooth do carro reconectando dentro da janela cancela o encerramento`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(8_000)
        c.protecao.carroConectou()
        avancar(60_000)

        assertTrue("sessão mantida após a piscada", c.encerramentos.isEmpty())
        assertFalse(c.protecao.encerramentoAgendado)
        assertTrue(c.eventos.any { it.contains("reconectou dentro da tolerância") })
    }

    @Test
    fun `reconectar so depois dos 15 s nao desfaz o encerramento`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(15_001)
        c.protecao.carroConectou()

        assertEquals(1, c.encerramentos.size)
    }

    @Test
    fun `abrir o app durante a espera cancela o encerramento`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(5_000)
        c.protecao.activityIniciada(ferramenta = false)
        avancar(60_000)

        assertTrue(c.encerramentos.isEmpty())
        assertTrue(c.eventos.any { it.contains("App aberto durante a espera") })
    }

    @Test
    fun `carro caiu com o app aberto e depois o app sai de cena inicia a tolerancia`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = false)
        c.protecao.carroDesconectou("Toyota SW4") // mantida: app em uso

        c.protecao.activityParada(ferramenta = false)
        runCurrent()
        assertTrue(c.protecao.encerramentoAgendado)
        avancar(15_001)

        assertEquals(1, c.encerramentos.size)
    }

    @Test
    fun `carro caiu com o app aberto e voltou a conectar nao encerra quando o app sai`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = false)
        c.protecao.carroDesconectou("Toyota SW4")
        c.protecao.carroConectou()

        c.protecao.activityParada(ferramenta = false)
        avancar(60_000)

        assertTrue(c.encerramentos.isEmpty())
    }

    @Test
    fun `troca de tela nao conta como sair do app`() = runTest {
        val c = cenario()
        c.protecao.activityIniciada(ferramenta = false)
        c.protecao.carroDesconectou("Toyota SW4")

        // A segunda Activity inicia antes de a primeira parar.
        c.protecao.activityIniciada(ferramenta = false)
        c.protecao.activityParada(ferramenta = false)

        assertTrue(c.protecao.appEmPrimeiroPlano)
        assertFalse(c.protecao.encerramentoAgendado)
        avancar(60_000)
        assertTrue(c.encerramentos.isEmpty())
    }

    @Test
    fun `sessao manual ou inexistente nunca e encerrada pelo carro`() = runTest {
        val c = cenario()
        c.estado = EstadoSessao(fase = FaseSessao.Conectada, origem = OrigemSessao.MANUAL)

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(60_000)
        c.estado = EstadoSessao()
        c.protecao.carroDesconectou("Toyota SW4")
        avancar(60_000)

        assertTrue(c.encerramentos.isEmpty())
        assertTrue(c.eventos.isEmpty())
    }

    @Test
    fun `sessao que termina durante a espera nao e encerrada de novo`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(5_000)
        c.estado = EstadoSessao() // o usuário tocou em Desconectar
        c.protecao.sessaoEncerrada()
        avancar(60_000)

        assertTrue(c.encerramentos.isEmpty())
    }

    @Test
    fun `sessao assumida durante a espera nao e encerrada`() = runTest {
        val c = cenario()

        c.protecao.carroDesconectou("Toyota SW4")
        avancar(5_000)
        c.estado = c.estado.copy(assumidaPeloUsuario = true) // assumiu sem abrir tela (ex.: notificação)
        avancar(60_000)

        assertTrue("reavalia ao fim da espera", c.encerramentos.isEmpty())
    }
}
