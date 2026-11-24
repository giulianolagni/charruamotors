package com.charruamotors.direcao.desempenho

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronometroDesempenhoTest {
    private var parcial: Double? = null
    private var final: Pair<Double, Double>? = null
    private val c = CronometroDesempenho({ parcial = it }, { a, b -> final = a to b })

    /** Amostra a cada 500 ms, em km/h. */
    private fun rodar(inicioMs: Long, vararg v: Double) = v.forEachIndexed { i, x -> c.amostrar(x, inicioMs + i * 500L) }

    @Test fun `em movimento sem ter parado aguarda a parada`() {
        c.amostrar(50.0, 0)
        assertEquals(FaseDesempenho.AGUARDANDO_PARADA, c.estado.value.fase)
        assertEquals("Pronto — pare o veículo", c.estado.value.texto)
    }

    @Test fun `parado fica pronto e sair do zero dispara`() {
        c.amostrar(0.0, 0)
        assertEquals(FaseDesempenho.PRONTO, c.estado.value.fase)
        c.amostrar(5.0, 1000)
        assertEquals(FaseDesempenho.MEDINDO, c.estado.value.fase)
    }

    @Test fun `0 a 60 e 0 a 100 com interpolacao e duas casas`() {
        c.amostrar(0.0, 0)
        // sai em t=1000 (v=10); 50 km/h em 1500; 70 em 2000 → 60 cruza a meio (1750 → 0,75 s)
        c.amostrar(10.0, 1000)
        c.amostrar(50.0, 1500)
        c.amostrar(70.0, 2000)
        assertEquals(0.75, c.estado.value.tempo060!!, 0.0)
        assertEquals(0.75, parcial!!, 0.0)
        c.amostrar(90.0, 2500)
        c.amostrar(110.0, 3000) // 100 cruza em 2750 → 1,75 s
        val e = c.estado.value
        assertEquals(FaseDesempenho.CONCLUIDO, e.fase)
        assertEquals(0.75, e.tempo060!!, 0.0)
        assertEquals(1.75, e.tempo0100!!, 0.0)
        assertEquals(0.75 to 1.75, final)
    }

    @Test fun `desacelerar antes de completar cancela`() {
        c.amostrar(0.0, 0)
        rodar(1000, 10.0, 40.0, 80.0)
        c.amostrar(60.0, 2500)
        assertEquals(FaseDesempenho.CANCELADO, c.estado.value.fase)
        assertNull(final)
    }

    @Test fun `parar antes de completar cancela sem tempo 0-60`() {
        c.amostrar(0.0, 0)
        rodar(1000, 10.0, 30.0)
        c.amostrar(0.0, 2000)
        assertEquals(FaseDesempenho.CANCELADO, c.estado.value.fase)
        assertNull(c.estado.value.tempo060)
    }

    @Test fun `recuo pequeno de troca de marcha nao cancela`() {
        c.amostrar(0.0, 0)
        rodar(1000, 10.0, 50.0, 48.0, 55.0)
        assertEquals(FaseDesempenho.MEDINDO, c.estado.value.fase)
    }

    @Test fun `depois do resultado so novo teste apos parar`() {
        c.amostrar(0.0, 0)
        rodar(1000, 10.0, 70.0, 120.0)
        assertEquals(FaseDesempenho.CONCLUIDO, c.estado.value.fase)
        c.amostrar(130.0, 5000) // ainda correndo: não rearma
        assertEquals(FaseDesempenho.CONCLUIDO, c.estado.value.fase)
        c.amostrar(0.0, 9000)
        c.amostrar(4.0, 9500)
        assertEquals(FaseDesempenho.MEDINDO, c.estado.value.fase)
    }

    @Test fun `sem leitura zera`() {
        c.amostrar(0.0, 0)
        c.amostrar(null, 500)
        assertEquals(FaseDesempenho.AGUARDANDO_PARADA, c.estado.value.fase)
    }

    @Test fun `recorde guarda o menor tempo`() {
        val r = RecordeDesempenho().com(7.5, 14.0).com(8.0, 13.0)
        assertEquals(7.5, r.melhor060!!, 0.0)
        assertEquals(13.0, r.melhor0100!!, 0.0)
    }
}
