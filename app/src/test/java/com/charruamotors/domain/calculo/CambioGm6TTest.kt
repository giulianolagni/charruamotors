package com.charruamotors.domain.calculo

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** v2.1.5: marcha 1ª–6ª do câmbio GM 6T35/6T40 (Cruze 1.4T) pela razão rpm/velocidade. */
class CambioGm6TTest {

    private val conversor = ConversorUnidades.PADRAO

    private fun dados(vararg v: Pair<String, Double>) = DadosObd(mapOf(*v))

    @Test
    fun `faixas da razao rpm por velocidade`() {
        // velocidade fixa em 10 km/h: razão = rpm / 10
        assertEquals(1, CambioGm6T.marchaPorRazao(1_150.0, 10.0))
        assertEquals(2, CambioGm6T.marchaPorRazao(1_149.0, 10.0))
        assertEquals(2, CambioGm6T.marchaPorRazao(720.0, 10.0))
        assertEquals(3, CambioGm6T.marchaPorRazao(719.0, 10.0))
        assertEquals(3, CambioGm6T.marchaPorRazao(490.0, 10.0))
        assertEquals(4, CambioGm6T.marchaPorRazao(489.0, 10.0))
        assertEquals(4, CambioGm6T.marchaPorRazao(360.0, 10.0))
        assertEquals(5, CambioGm6T.marchaPorRazao(359.0, 10.0))
        assertEquals(5, CambioGm6T.marchaPorRazao(270.0, 10.0))
        assertEquals(6, CambioGm6T.marchaPorRazao(269.0, 10.0))
    }

    @Test
    fun `cenarios realistas de 1a a 6a`() {
        assertEquals(1, CambioGm6T.marchaPorRazao(3_000.0, 20.0)) // 150
        assertEquals(2, CambioGm6T.marchaPorRazao(2_500.0, 30.0)) // 83
        assertEquals(3, CambioGm6T.marchaPorRazao(2_400.0, 40.0)) // 60
        assertEquals(4, CambioGm6T.marchaPorRazao(2_400.0, 60.0)) // 40
        assertEquals(5, CambioGm6T.marchaPorRazao(2_400.0, 80.0)) // 30
        assertEquals(6, CambioGm6T.marchaPorRazao(2_100.0, 100.0)) // 21
    }

    @Test
    fun `sem estimativa abaixo de 5 km-h ou sem leitura valida`() {
        assertNull(CambioGm6T.marchaPorRazao(900.0, 4.9))
        assertNull(CambioGm6T.marchaPorRazao(0.0, 50.0))
        assertNull(CambioGm6T.marchaPorRazao(null, 50.0))
        assertNull(CambioGm6T.marchaPorRazao(2_000.0, null))
        assertNull(CambioGm6T.marchaPorRazao(2_000.0, Double.NaN))
        assertEquals(1, CambioGm6T.marchaPorRazao(900.0, 5.0)) // 180 na fronteira de 5 km/h
    }

    @Test
    fun `parado exibe Parado e andando exibe a marcha`() {
        assertEquals(CambioA750f.MARCHA_PARADO, CambioGm6T.marchaExibida(800.0, 0.0))
        assertEquals(CambioA750f.MARCHA_PARADO, CambioGm6T.marchaExibida(800.0, 4.9))
        assertEquals(6, CambioGm6T.marchaExibida(2_000.0, 100.0))
        assertNull(CambioGm6T.marchaExibida(null, 50.0))
        assertNull(CambioGm6T.marchaExibida(2_000.0, null))
    }

    @Test
    fun `estimador publica a marcha na chave do mostrador`() {
        val e = EstimadorCambioGm6T()
        val andando = e.aplicar(dados(Pids.RPM to 2_100.0, Pids.VELOCIDADE to 100.0))
        assertEquals(6.0, andando[CambioA750f.CHAVE_MARCHA]!!, 0.0)
        assertEquals("6ª", SensorPainel.MARCHA_ATUAL.texto(andando[CambioA750f.CHAVE_MARCHA], conversor))

        val parado = e.aplicar(dados(Pids.RPM to 780.0, Pids.VELOCIDADE to 0.0))
        assertEquals("Parado", SensorPainel.MARCHA_ATUAL.texto(parado[CambioA750f.CHAVE_MARCHA], conversor))
    }

    @Test
    fun `estimador respeita marcha direta e instantaneo sem velocidade`() {
        val e = EstimadorCambioGm6T()
        val direto = dados(Pids.RPM to 2_000.0, Pids.VELOCIDADE to 80.0, CambioA750f.CHAVE_MARCHA to 3.0)
        assertSame(direto, e.aplicar(direto))
        val semVelocidade = dados(Pids.RPM to 2_000.0)
        assertSame(semVelocidade, e.aplicar(semVelocidade))
    }
}
