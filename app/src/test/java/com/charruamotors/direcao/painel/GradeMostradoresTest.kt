package com.charruamotors.direcao.painel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Grade de 2 colunas do Painel, com a Fita em largura total. */
class GradeMostradoresTest {

    private fun m(estilo: EstiloMostrador, sensor: SensorPainel = SensorPainel.RPM) = Mostrador(sensor, estilo)

    private val e = EstiloMostrador.ESPORTIVO
    private val d = EstiloMostrador.DIGITAL
    private val f = EstiloMostrador.FITA

    @Test
    fun `4 relogios redondos viram 2 linhas de 2`() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), GradeMostradores.linhas(List(4) { m(e) }))
    }

    @Test
    fun `mais de 4 mostradores seguem em pares e o impar fica sozinho no fim`() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), GradeMostradores.linhas(List(5) { m(d) }))
        assertEquals(6, GradeMostradores.linhas(List(12) { m(e) }).size)
    }

    @Test
    fun `fita ocupa a linha inteira sem pular a ordem`() {
        // RPM Esportivo + Turbo Fita + Motor Digital + Velocidade Esportivo
        val linhas = GradeMostradores.linhas(listOf(m(e), m(f), m(d), m(e)))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), linhas)
    }

    @Test
    fun `duas fitas seguidas nunca ficam lado a lado`() {
        val linhas = GradeMostradores.linhas(listOf(m(f), m(f), m(e), m(e)))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), linhas)
    }

    @Test
    fun `todos os indices aparecem uma unica vez`() {
        val estilos = listOf(e, f, d, d, f, e, e, f, d)
        val linhas = GradeMostradores.linhas(estilos.map { m(it) })
        assertEquals(estilos.indices.toList(), linhas.flatten())
        linhas.forEach { linha -> assert(linha.size in 1..GradeMostradores.COLUNAS) }
    }
}
