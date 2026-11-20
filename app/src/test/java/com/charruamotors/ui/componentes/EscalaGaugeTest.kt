package com.charruamotors.ui.componentes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalaGaugeTest {

    private val rpm = EscalaGauge(minimo = 0f, maximo = 8000f, zonaVermelha = 6500f, divisoes = 8, subdivisoes = 5, divisorRotulos = 1000f)
    private val temperatura = EscalaGauge(minimo = 40f, maximo = 130f, zonaVermelha = 110f, divisoes = 9, subdivisoes = 2)

    @Test
    fun `fracao e angulo cobrem o arco de 270 graus e saturam fora da faixa`() {
        assertEquals(0f, rpm.fracao(0f), 0f)
        assertEquals(0.5f, rpm.fracao(4000f), 1e-6f)
        assertEquals(1f, rpm.fracao(9000f), 0f)
        assertEquals(0f, temperatura.fracao(-10f), 0f) // abaixo do mínimo (motor frio)
        assertEquals(135f, rpm.angulo(0f), 0f)
        assertEquals(270f, rpm.angulo(4000f), 1e-4f)
        assertEquals(405f, rpm.angulo(8000f), 1e-4f)
    }

    @Test
    fun `zona vermelha`() {
        assertEquals(6500f / 8000f, rpm.fracaoZonaVermelha!!, 1e-6f)
        assertTrue(rpm.naZonaVermelha(6500f))
        assertFalse(rpm.naZonaVermelha(6499f))
        assertNull(EscalaGauge(0f, 240f).fracaoZonaVermelha)
        assertFalse(EscalaGauge(0f, 240f).naZonaVermelha(240f))
        assertNull(EscalaGauge(0f, 100f, zonaVermelha = 150f).fracaoZonaVermelha) // além do máximo
    }

    @Test
    fun `rotulos dos tracos maiores com divisor e minimo diferente de zero`() {
        assertEquals((0..8).map { it.toString() }, rpm.rotulos)
        assertEquals(listOf("40", "50", "60", "70", "80", "90", "100", "110", "120", "130"), temperatura.rotulos)
        assertEquals(listOf("0", "0,5", "1"), EscalaGauge(0f, 1f, divisoes = 2).rotulos)
    }

    @Test
    fun `tracos maiores e menores`() {
        assertEquals(41, rpm.totalTracos)
        assertTrue(rpm.tracoMaior(0))
        assertFalse(rpm.tracoMaior(3))
        assertTrue(rpm.tracoMaior(40))
    }

    @Test
    fun `escala invalida e rejeitada`() {
        assertThrows(IllegalArgumentException::class.java) { EscalaGauge(10f, 10f) }
        assertThrows(IllegalArgumentException::class.java) { EscalaGauge(0f, 10f, divisoes = 0) }
        assertThrows(IllegalArgumentException::class.java) { EscalaGauge(0f, 10f, divisorRotulos = 0f) }
    }
}
