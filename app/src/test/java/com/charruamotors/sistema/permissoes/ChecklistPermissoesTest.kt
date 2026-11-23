package com.charruamotors.sistema.permissoes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Status verde/vermelho do Checklist de Permissões conforme a versão do Android. */
class ChecklistPermissoesTest {

    private val tudoNegadoS24 = EstadoSistemaPermissoes(
        sdk = 35,
        bluetoothConcedido = false,
        notificacoesConcedidas = false,
        bateriaSemRestricoes = false,
        acessoNotificacoes = false,
        sobreporApps = false,
    )

    private val tudoLiberadoS24 = tudoNegadoS24.copy(
        bluetoothConcedido = true,
        notificacoesConcedidas = true,
        bateriaSemRestricoes = true,
        acessoNotificacoes = true,
        sobreporApps = true,
    )

    @Test
    fun `S24 sem nada concedido deixa os cinco itens pendentes`() {
        val status = ChecklistPermissoes.avaliar(tudoNegadoS24)
        assertEquals(ItemPermissao.entries, status.map { it.item })
        assertTrue(status.all { it.situacao == SituacaoPermissao.PENDENTE })
        assertEquals(5, ChecklistPermissoes.pendentes(status))
        assertFalse(ChecklistPermissoes.essenciaisOk(tudoNegadoS24))
    }

    @Test
    fun `tudo concedido fica verde`() {
        val status = ChecklistPermissoes.avaliar(tudoLiberadoS24)
        assertTrue(status.all { it.situacao == SituacaoPermissao.OK })
        assertEquals(0, ChecklistPermissoes.pendentes(status))
        assertTrue(ChecklistPermissoes.essenciaisOk(tudoLiberadoS24))
    }

    @Test
    fun `recursos de direcao nao contam para os essenciais`() {
        val semDirecao = tudoLiberadoS24.copy(acessoNotificacoes = false, sobreporApps = false)
        assertTrue(ChecklistPermissoes.essenciaisOk(semDirecao))
        assertEquals(2, ChecklistPermissoes.pendentes(ChecklistPermissoes.avaliar(semDirecao)))
        assertEquals(listOf(ItemPermissao.ACESSO_NOTIFICACOES, ItemPermissao.SOBREPOR_APPS), ItemPermissao.DIRECAO)
    }

    @Test
    fun `bateria otimizada bloqueia os essenciais`() {
        assertFalse(ChecklistPermissoes.essenciaisOk(tudoLiberadoS24.copy(bateriaSemRestricoes = false)))
        assertEquals(
            SituacaoPermissao.PENDENTE,
            ChecklistPermissoes.situacao(ItemPermissao.BATERIA_SEM_RESTRICOES, tudoLiberadoS24.copy(bateriaSemRestricoes = false)),
        )
    }

    @Test
    fun `Android 12 sem notificacao em tempo de execucao`() {
        val android12 = tudoNegadoS24.copy(sdk = 31)
        assertEquals(SituacaoPermissao.NAO_APLICAVEL, ChecklistPermissoes.situacao(ItemPermissao.NOTIFICACOES, android12))
        assertEquals(SituacaoPermissao.PENDENTE, ChecklistPermissoes.situacao(ItemPermissao.BLUETOOTH, android12))
    }

    @Test
    fun `Android 11 ou anterior tem Bluetooth concedido na instalacao`() {
        val android11 = tudoNegadoS24.copy(sdk = 30)
        assertEquals(SituacaoPermissao.NAO_APLICAVEL, ChecklistPermissoes.situacao(ItemPermissao.BLUETOOTH, android11))
        assertTrue(ChecklistPermissoes.avaliar(android11).first { it.item == ItemPermissao.BLUETOOTH }.ok)
    }

    @Test
    fun `aparelho sem Bluetooth nao cobra a permissao`() {
        val emulador = tudoNegadoS24.copy(bluetoothDisponivel = false)
        assertEquals(SituacaoPermissao.NAO_APLICAVEL, ChecklistPermissoes.situacao(ItemPermissao.BLUETOOTH, emulador))
    }

    @Test
    fun `avaliar so os itens pedidos mantendo a ordem`() {
        val etapa4 = ChecklistPermissoes.avaliar(tudoLiberadoS24.copy(sobreporApps = false), ItemPermissao.DIRECAO)
        assertEquals(listOf(ItemPermissao.ACESSO_NOTIFICACOES, ItemPermissao.SOBREPOR_APPS), etapa4.map { it.item })
        assertEquals(listOf(true, false), etapa4.map { it.ok })
    }
}
