package com.charruamotors.ui.componentes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JanelaTelemetriaTest {

    private fun ponto(t: Long, rpm: Double? = 1000.0) = PontoTelemetria(t, rpm, 40.0)

    @Test
    fun `janela desliza e mantem so os ultimos 60 s`() {
        val janela = JanelaTelemetria()
        (0..300).forEach { janela.adicionar(ponto(it * 250L)) } // 75 s a 4 leituras/s
        val pontos = janela.pontos
        assertEquals(75_000L, pontos.last().instanteMs)
        assertEquals(15_000L, pontos.first().instanteMs)
        assertEquals(241, pontos.size)
    }

    @Test
    fun `limite de pontos protege a memoria`() {
        val janela = JanelaTelemetria(duracaoMs = 60_000, maxPontos = 10)
        (0 until 50).forEach { janela.adicionar(ponto(it.toLong())) }
        assertEquals(10, janela.pontos.size)
        assertEquals(49L, janela.pontos.last().instanteMs)
    }

    @Test
    fun `relogio voltando reinicia a janela e limpar esvazia`() {
        val janela = JanelaTelemetria()
        janela.adicionar(ponto(10_000))
        janela.adicionar(ponto(11_000))
        assertEquals(listOf(ponto(5_000)), janela.adicionar(ponto(5_000)))
        janela.limpar()
        assertTrue(janela.pontos.isEmpty())
    }

    @Test
    fun `escala automatica arredonda o maximo para um numero redondo`() {
        assertEquals(4_000.0, EscalaAutomatica.maximo(listOf(800.0, 3_420.0), minimoEscala = 2_000.0), 0.0)
        assertEquals(8_000.0, EscalaAutomatica.maximo(listOf(6_100.0), minimoEscala = 2_000.0), 0.0)
        assertEquals(2_000.0, EscalaAutomatica.maximo(listOf(850.0), minimoEscala = 2_000.0), 0.0) // marcha lenta
        assertEquals(2_000.0, EscalaAutomatica.maximo(emptyList(), minimoEscala = 2_000.0), 0.0)
        assertEquals(100.0, EscalaAutomatica.maximo(listOf(95.0), minimoEscala = 100.0), 0.0) // aspirado
        assertEquals(200.0, EscalaAutomatica.maximo(listOf(180.0, Double.NaN), minimoEscala = 100.0), 0.0) // turbo
    }

    @Test
    fun `passos redondos 1 2 2,5 e 5`() {
        assertEquals(1_000.0, EscalaAutomatica.passo(855.0), 1e-9)
        assertEquals(25.0, EscalaAutomatica.passo(23.75), 1e-9)
        assertEquals(0.5, EscalaAutomatica.passo(0.3), 1e-9)
        assertEquals(2.0, EscalaAutomatica.passo(2.0), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { EscalaAutomatica.passo(0.0) }
    }

    @Test
    fun `fracao vertical satura nos limites`() {
        assertEquals(0.5f, EscalaAutomatica.fracao(2_000.0, 4_000.0), 0f)
        assertEquals(1f, EscalaAutomatica.fracao(9_000.0, 4_000.0), 0f)
        assertEquals(0f, EscalaAutomatica.fracao(-5.0, 4_000.0), 0f)
    }
}
