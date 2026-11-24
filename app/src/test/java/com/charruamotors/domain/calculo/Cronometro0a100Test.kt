package com.charruamotors.domain.calculo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Cronometro0a100Test {

    /** Alimenta o cronômetro com (instante ms, velocidade km/h) e devolve o último estado. */
    private fun Cronometro0a100.leituras(vararg amostras: Pair<Long, Double?>): EstadoCronometro {
        var e = estado
        amostras.forEach { (t, v) -> e = registrar(v, t) }
        return e
    }

    /** Aceleração constante de [kmhPorS] a partir de [inicioMs], amostrada a cada [passoMs]. */
    private fun arrancada(inicioMs: Long, kmhPorS: Double, passoMs: Long = 250, ate: Double = 110.0) =
        generateSequence(0) { it + 1 }
            .map { i -> (inicioMs + i * passoMs) to (kmhPorS * i * passoMs / 1000.0) }
            .takeWhile { it.second <= ate }
            .toList()

    @Test
    fun `comeca desarmado e ignora leituras`() {
        val c = Cronometro0a100()
        assertEquals(EstadoCronometro.Desarmado, c.leituras(0L to 0.0, 250L to 50.0, 500L to 120.0))
        assertFalse(c.armado)
    }

    @Test
    fun `armado parado fica pronto e armado em movimento espera parar`() {
        val c = Cronometro0a100()
        assertEquals(EstadoCronometro.Pronto, c.armar(0.0))
        assertEquals(EstadoCronometro.AguardandoParada, c.armar(35.0))
        assertEquals(EstadoCronometro.AguardandoParada, c.leituras(0L to 20.0, 250L to 5.0))
        assertEquals(EstadoCronometro.Pronto, c.leituras(500L to 0.0))
        assertEquals(EstadoCronometro.AguardandoParada, c.armar(null)) // sem leitura de velocidade
        assertTrue(c.armado)
    }

    @Test
    fun `conta a partir da primeira leitura acima de zero e interpola a chegada aos 100`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(1_000L to 0.0, 1_250L to 3.0)
        val medindo = c.estado as EstadoCronometro.Medindo
        assertEquals(1_250L, medindo.inicioMs)

        c.leituras(6_000L to 55.0)
        assertEquals(4_750L, (c.estado as EstadoCronometro.Medindo).decorridoMs)
        assertEquals(55.0, (c.estado as EstadoCronometro.Medindo).velocidade, 0.0)

        // 96 km/h em 12.250 ms e 104 km/h em 12.500 ms: cruzou os 100 na metade → 12.375 ms.
        val fim = c.leituras(12_250L to 96.0, 12_500L to 104.0)
        assertEquals(EstadoCronometro.Concluido(11_125L), fim)
        assertFalse(c.armado)
        assertEquals(11_125L, c.melhorTempoMs)
    }

    @Test
    fun `aceleracao constante de 10 km por h por s da 10 s menos a latencia da primeira leitura`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(*arrancada(0, kmhPorS = 10.0).toTypedArray())
        // A contagem começa na leitura de 2,5 km/h (t = 250 ms); os 100 km/h chegam em t = 10 s.
        assertEquals(EstadoCronometro.Concluido(9_750L), c.estado)
    }

    @Test
    fun `parar antes dos 100 reinicia a tentativa`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(0L to 0.0, 250L to 4.0, 3_000L to 40.0, 5_000L to 12.0)
        assertTrue(c.estado is EstadoCronometro.Medindo)
        assertEquals(EstadoCronometro.Pronto, c.leituras(6_000L to 0.0))
        // Nova tentativa conta do zero.
        c.leituras(10_000L to 2.0)
        assertEquals(10_000L, (c.estado as EstadoCronometro.Medindo).inicioMs)
    }

    @Test
    fun `tentativa longa demais e descartada`() {
        val c = Cronometro0a100(tempoMaximoMs = 30_000)
        c.armar(0.0)
        c.leituras(0L to 0.0, 1_000L to 5.0, 20_000L to 60.0)
        assertEquals(EstadoCronometro.AguardandoParada, c.leituras(31_001L to 70.0))
        assertNull(c.melhorTempoMs)
    }

    @Test
    fun `leituras nulas ou invalidas sao ignoradas`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(0L to 0.0, 250L to 5.0, 500L to null, 750L to Double.NaN)
        val medindo = c.estado as EstadoCronometro.Medindo
        assertEquals(250L, medindo.inicioMs)
        assertEquals(0L, medindo.decorridoMs) // nada mudou com as leituras inválidas
    }

    @Test
    fun `salto direto acima do alvo a partir do zero nao vale como tempo`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        assertEquals(EstadoCronometro.AguardandoParada, c.leituras(0L to 0.0, 250L to 120.0))
    }

    @Test
    fun `resultado fica na tela ate armar de novo e o melhor tempo e o menor`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(*arrancada(0, kmhPorS = 8.0).toTypedArray())
        val primeiro = (c.estado as EstadoCronometro.Concluido).tempoMs
        c.leituras(60_000L to 0.0, 60_250L to 5.0) // concluído: leituras não mudam nada
        assertEquals(EstadoCronometro.Concluido(primeiro), c.estado)

        c.armar(0.0)
        c.leituras(*arrancada(100_000, kmhPorS = 10.0).toTypedArray())
        assertEquals(9_750L, c.melhorTempoMs)
        c.armar(0.0)
        c.leituras(*arrancada(200_000, kmhPorS = 5.0).toTypedArray())
        assertEquals(9_750L, c.melhorTempoMs) // tentativa pior não troca o melhor
        assertTrue(primeiro > 9_750L)
    }

    @Test
    fun `desarmar cancela uma medicao em andamento`() {
        val c = Cronometro0a100()
        c.armar(0.0)
        c.leituras(0L to 0.0, 250L to 10.0)
        assertEquals(EstadoCronometro.Desarmado, c.desarmar())
        assertEquals(EstadoCronometro.Desarmado, c.leituras(500L to 30.0))
    }

    @Test
    fun `alvo configuravel e validado`() {
        val c = Cronometro0a100(alvoKmh = 60.0)
        c.armar(0.0)
        c.leituras(*arrancada(0, kmhPorS = 10.0).toTypedArray())
        assertEquals(EstadoCronometro.Concluido(5_750L), c.estado)
        assertThrows(IllegalArgumentException::class.java) { Cronometro0a100(alvoKmh = 0.0) }
    }
}
