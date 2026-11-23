package com.charruamotors.sistema.navegacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Veículo favorito, abertura direta e a pilha de retorno do menu lateral (v1.5). */
class NavegacaoAppTest {

    // ---------------------------------------------------------------- veículo favorito

    @Test
    fun `favorito salvo que existe e o efetivo`() {
        assertEquals(9L, VeiculoFavorito.idEfetivo(listOf(7, 9, 12), favoritoSalvo = 9))
        assertTrue(VeiculoFavorito.ehFavorito(9, listOf(7, 9, 12), 9))
        assertFalse(VeiculoFavorito.ehFavorito(7, listOf(7, 9, 12), 9))
    }

    @Test
    fun `sem favorito ou com favorito apagado vale o primeiro cadastrado`() {
        assertEquals(7L, VeiculoFavorito.idEfetivo(listOf(7, 9), favoritoSalvo = 0))
        assertEquals(7L, VeiculoFavorito.idEfetivo(listOf(7, 9), favoritoSalvo = 42))
        assertTrue(VeiculoFavorito.ehFavorito(7, listOf(7, 9), 42))
        assertNull(VeiculoFavorito.idEfetivo(emptyList(), favoritoSalvo = 9))
    }

    // ---------------------------------------------------------------- abertura direta

    @Test
    fun `logado com veiculos abre direto no painel do favorito`() {
        assertEquals(DestinoInicial.Painel(veiculoId = 9, gravarFavorito = false), VeiculoFavorito.destinoInicial(true, listOf(7, 9), 9))
    }

    @Test
    fun `favorito vazio ou apagado abre no primeiro e grava o efetivo`() {
        assertEquals(DestinoInicial.Painel(veiculoId = 7, gravarFavorito = true), VeiculoFavorito.destinoInicial(true, listOf(7, 9), 0))
        assertEquals(DestinoInicial.Painel(veiculoId = 7, gravarFavorito = true), VeiculoFavorito.destinoInicial(true, listOf(7, 9), 42))
    }

    @Test
    fun `sem veiculo cadastrado abre Meus Veiculos e sem login abre o login`() {
        assertEquals(DestinoInicial.MeusVeiculos, VeiculoFavorito.destinoInicial(true, emptyList(), 9))
        assertEquals(DestinoInicial.Login, VeiculoFavorito.destinoInicial(false, listOf(7, 9), 9))
    }

    // ---------------------------------------------------------------- menu lateral e pilha de retorno

    @Test
    fun `menu lateral so tem acoes globais, sem repetir as abas`() {
        assertEquals(
            listOf("Meus Veículos (Trocar / Favorito)", "Configurações", "Checklist de Permissões", "Backup e Restauração", "Sobre o App", "Sair (Logout)"),
            AcaoMenuLateral.entries.map { it.titulo },
        )
        val titulos = AcaoMenuLateral.entries.joinToString { it.titulo }
        assertFalse("Logs já é a aba Histórico", "Logs" in titulos)
        assertFalse("Oficina já é uma aba", "Oficina" in titulos)
    }

    /** Pilha de telas com as mesmas regras do Android para as flags usadas pelo menu. */
    private class Pilha(raiz: String) {
        val telas = mutableListOf(raiz)
        val topo get() = telas.last()

        fun abrir(acao: AcaoMenuLateral, tela: String) {
            val tipo = acao.navegacao
            if (tipo.limpaPilha) telas.clear()
            if (tipo.empilha) telas += tela
        }

        fun voltar() = telas.removeAt(telas.lastIndex)
    }

    @Test
    fun `voltar de uma tela do menu retorna exatamente para a aba de origem`() {
        for (acao in AcaoMenuLateral.entries.filter { it.navegacao == TipoNavegacao.EMPILHAR }) {
            val pilha = Pilha("Principal(aba=Oficina)")
            pilha.abrir(acao, acao.name)
            assertEquals(acao.name, pilha.topo)
            pilha.voltar()
            assertEquals("${acao.name} não pode resetar a pilha", "Principal(aba=Oficina)", pilha.topo)
        }
    }

    @Test
    fun `dialogos do menu nao mexem na pilha e so o logout limpa`() {
        val pilha = Pilha("Principal(aba=Histórico)")
        pilha.abrir(AcaoMenuLateral.BACKUP, "Backup")
        pilha.abrir(AcaoMenuLateral.SOBRE, "Sobre")
        assertEquals(listOf("Principal(aba=Histórico)"), pilha.telas)
        assertEquals(listOf(AcaoMenuLateral.SAIR), AcaoMenuLateral.entries.filter { it.navegacao.limpaPilha })
        pilha.abrir(AcaoMenuLateral.CONFIGURACOES, "Configurações")
        pilha.abrir(AcaoMenuLateral.SAIR, "Login")
        assertEquals(listOf("Login"), pilha.telas)
    }
}
