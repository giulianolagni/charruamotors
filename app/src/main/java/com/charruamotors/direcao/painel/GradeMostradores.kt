package com.charruamotors.direcao.painel

/**
 * Distribuição dos mostradores no Painel: grade limpa de 2 colunas, na ordem escolhida; a Fita
 * ocupa a largura total (barra horizontal: dois lado a lado deixavam os números das escalas quase
 * encostados, o `8` do RPM colado no `0` da Velocidade). Lógica pura.
 */
object GradeMostradores {

    const val COLUNAS = 2

    /** Linhas com os índices dos [mostradores]: Fita sozinha; os demais aos pares, sem pular a ordem. */
    fun linhas(mostradores: List<Mostrador>): List<List<Int>> {
        val linhas = mutableListOf<List<Int>>()
        var pendente = mutableListOf<Int>()
        mostradores.forEachIndexed { i, m ->
            if (m.estilo == EstiloMostrador.FITA) {
                if (pendente.isNotEmpty()) linhas += pendente
                pendente = mutableListOf()
                linhas += listOf(i)
            } else {
                pendente += i
                if (pendente.size == COLUNAS) {
                    linhas += pendente
                    pendente = mutableListOf()
                }
            }
        }
        if (pendente.isNotEmpty()) linhas += pendente
        return linhas
    }
}
