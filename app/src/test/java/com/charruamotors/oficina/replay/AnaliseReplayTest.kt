package com.charruamotors.oficina.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnaliseReplayTest {

    private val instantes = longArrayOf(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10_000)
    private val total = JanelaReplay(0, 10_000)

    // ---------------------------------------------------------------- índices

    @Test
    fun `faixa de indices da janela inclui as bordas`() {
        assertEquals(2..5, AnaliseReplay.faixaIndices(instantes, JanelaReplay(2000, 5000)))
        assertEquals(3..5, AnaliseReplay.faixaIndices(instantes, JanelaReplay(2500, 5500)))
        assertTrue(AnaliseReplay.faixaIndices(instantes, JanelaReplay(2100, 2900)).isEmpty())
    }

    @Test
    fun `indice mais proximo do cursor`() {
        assertEquals(0, AnaliseReplay.indiceMaisProximo(instantes, -500))
        assertEquals(2, AnaliseReplay.indiceMaisProximo(instantes, 2400))
        assertEquals(3, AnaliseReplay.indiceMaisProximo(instantes, 2600))
        assertEquals(10, AnaliseReplay.indiceMaisProximo(instantes, 99_000))
        assertEquals(-1, AnaliseReplay.indiceMaisProximo(LongArray(0), 10))
    }

    // ---------------------------------------------------------------- estatísticas e normalização

    @Test
    fun `estatisticas da janela ignoram leituras ausentes`() {
        val serie = doubleArrayOf(800.0, Double.NaN, 1200.0, 3000.0, Double.NaN)
        val e = AnaliseReplay.estatisticas(serie, 0..4)!!
        assertEquals(800.0, e.minimo, 1e-9)
        assertEquals(3000.0, e.maximo, 1e-9)
        assertEquals(5000.0 / 3, e.media, 1e-9)
        assertEquals(3, e.leituras)
        assertNull(AnaliseReplay.estatisticas(serie, 1..1))
        assertNull(AnaliseReplay.estatisticas(serie, IntRange.EMPTY))
    }

    @Test
    fun `cada serie normaliza na propria escala com margem`() {
        val (min, max) = AnaliseReplay.escala(EstatisticaSerie(100.0, 200.0, 150.0, 10))
        assertEquals(94.0, min, 1e-9)
        assertEquals(206.0, max, 1e-9)
        assertEquals(0.5, AnaliseReplay.normalizar(150.0, min, max), 1e-9)
        assertEquals(0.0, AnaliseReplay.normalizar(-50.0, min, max), 1e-9) // limitado à borda
        assertEquals(1.0, AnaliseReplay.normalizar(999.0, min, max), 1e-9)
    }

    @Test
    fun `serie constante ganha faixa em volta do valor em vez de dividir por zero`() {
        val (min, max) = AnaliseReplay.escala(EstatisticaSerie(14.0, 14.0, 14.0, 5))
        assertTrue(min < 14.0 && max > 14.0)
        assertEquals(0.5, AnaliseReplay.normalizar(14.0, min, max), 1e-9)
        assertEquals(0.5, AnaliseReplay.normalizar(3.0, 7.0, 7.0), 1e-9)
    }

    // ---------------------------------------------------------------- zoom e arraste

    @Test
    fun `zoom mantem o instante do foco no mesmo lugar`() {
        val janela = AnaliseReplay.zoom(total, total, 2.0, focoMs = 2_500)
        assertEquals(5_000, janela.duracaoMs)
        // O foco estava a 25 % da janela e continua a 25 %.
        assertEquals(0.25, (2_500 - janela.inicioMs).toDouble() / janela.duracaoMs, 1e-3)
    }

    @Test
    fun `zoom respeita a duracao minima e nao afasta alem do log inteiro`() {
        val perto = AnaliseReplay.zoom(total, total, 1_000.0, focoMs = 5_000, duracaoMinimaMs = 2_000)
        assertEquals(2_000, perto.duracaoMs)
        assertTrue(perto.inicioMs in 0..8_000)
        val longe = AnaliseReplay.zoom(JanelaReplay(4_000, 6_000), total, 0.01, focoMs = 5_000)
        assertEquals(total, longe)
    }

    @Test
    fun `arraste fica dentro do log`() {
        val janela = JanelaReplay(2_000, 4_000)
        assertEquals(JanelaReplay(5_000, 7_000), AnaliseReplay.deslocar(janela, total, 3_000))
        assertEquals(JanelaReplay(8_000, 10_000), AnaliseReplay.deslocar(janela, total, 60_000))
        assertEquals(JanelaReplay(0, 2_000), AnaliseReplay.deslocar(janela, total, -60_000))
    }

    @Test
    fun `duracao minima considera o periodo das amostras`() {
        val log = LeitorCsvLog.ler("tempo_s,A (x)\n0,1\n10,2\n20,3\n")
        assertEquals(40_000, AnaliseReplay.duracaoMinima(log)) // 4 amostras de 10 s
        assertEquals(JanelaReplay(0, 20_000), AnaliseReplay.janelaCompleta(log))
    }

    // ---------------------------------------------------------------- seleção de sensores

    @Test
    fun `selecao por chips aceita ate 3 sensores e recusa o quarto`() {
        var sel = emptyList<Int>()
        for (i in 0..2) sel = AnaliseReplay.alternarSelecao(sel, i)!!
        assertEquals(listOf(0, 1, 2), sel)
        assertNull(AnaliseReplay.alternarSelecao(sel, 3))
        assertEquals(listOf(0, 2), AnaliseReplay.alternarSelecao(sel, 1))
        assertEquals(listOf(0, 2, 3), AnaliseReplay.alternarSelecao(listOf(0, 2), 3))
    }

    @Test
    fun `selecao inicial prefere RPM MAP e pressao do rail`() {
        val log = LeitorCsvLog.ler(
            "timestamp_ms,Carga (%) [0104],Rail (kPa) [0123],RPM (rpm) [010C],Vazio (x) [0199],MAP (kPa) [010B]\n" +
                "1000,20,35000,800,,101\n",
        )
        assertEquals(listOf(2, 4, 1), AnaliseReplay.selecaoInicial(log))
        val semPreferidos = LeitorCsvLog.ler("timestamp_ms,A (x),B (y)\n1000,1,2\n")
        assertEquals(listOf(0, 1), AnaliseReplay.selecaoInicial(semPreferidos))
    }
}
