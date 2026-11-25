package com.charruamotors.domain.calculo

import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.IndicadorMarcha
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.perfis.CatalogoPerfis
import com.charruamotors.domain.perfis.FontePerfis
import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Decodificadores
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.6: detecção automática da reduzida (4L) da SW4 pela razão rpm/velocidade, sem comandos
 * extras ao ELM327, com o `fatorReduzida` do perfil de câmbio (`2.57` na A750F).
 */
class ReduzidaA750fTest {

    private val catalogo = CatalogoPerfis.carregar(FontePerfis.doClasspath())
    private val a750f: PerfilCambio = catalogo.cambios["toyota_a750f"]!!

    private val d = Decodificadores.ALAVANCA_D

    /** Estimador da SW4 com o câmbio [cambio] em uso. */
    private fun estimador(cambio: PerfilCambio? = a750f) = EstimadorCambioA750f { cambio }

    private fun amostra(alavanca: Double, rpm: Double, velocidade: Double) =
        DadosObd(mapOf(CambioA750f.CHAVE_ALAVANCA to alavanca, Pids.RPM to rpm, Pids.VELOCIDADE to velocidade))

    private fun EstimadorCambioA750f.marcha(alavanca: Double, rpm: Double, velocidade: Double): Int =
        aplicar(amostra(alavanca, rpm, velocidade)).valores.getValue(CambioA750f.CHAVE_MARCHA).toInt()

    private fun rotulo(codigo: Int) = CambioA750f.ROTULOS_MARCHA[codigo]

    /** Leva o estimador para a 4L: 3 amostras seguidas a 2.092 rpm / 7 km/h (razão 299). */
    private fun EstimadorCambioA750f.entrarEmReduzida(): Int {
        var ultima = 0
        repeat(DetectorReduzida.AMOSTRAS_ENTRADA) { ultima = marcha(d, 2_092.0, 7.0) }
        return ultima
    }

    @Test
    fun `fatorReduzida so existe na A750F`() {
        assertEquals(2.57, a750f.fatorReduzida!!, 0.0)
        catalogo.cambios.values.filter { it.id != "toyota_a750f" }.forEach { assertNull(it.id, it.fatorReduzida) }
    }

    @Test
    fun `2092 rpm a 7 km por h vira 1a L depois de 3 amostras seguidas`() {
        val e = estimador()
        // As duas primeiras ainda são 4H (razão 299 ≥ 85 → 1ª): uma ou duas amostras não bastam.
        assertEquals(1, e.marcha(d, 2_092.0, 7.0))
        assertEquals(1, e.marcha(d, 2_092.0, 7.0))
        assertFalse(e.emReduzida)
        // 2.092 / 2,57 / 7 = 116 → 1ª da tabela, exibida como 1ª L.
        assertEquals(CambioA750f.codigoReduzida(1), e.marcha(d, 2_092.0, 7.0))
        assertTrue(e.emReduzida)
        assertEquals("1ª L", rotulo(CambioA750f.codigoReduzida(1)))
    }

    @Test
    fun `em 4L 2014 rpm a 13 km por h e 2a L`() {
        val e = estimador()
        e.entrarEmReduzida()
        // 2.014 / 13 = 155 (abaixo da entrada, mas já em 4L) → 155 / 2,57 = 60 → 2ª L.
        val m = e.marcha(d, 2_014.0, 13.0)
        assertEquals(12, m)
        assertEquals("2ª L", rotulo(m))
    }

    @Test
    fun `patinagem isolada em 4H nao entra em 4L`() {
        val e = estimador()
        assertEquals(1, e.marcha(d, 2_000.0, 20.0)) // 100 → 1ª
        assertEquals(1, e.marcha(d, 2_500.0, 10.0)) // roda patinou: razão 250, uma amostra só
        assertEquals(3, e.marcha(d, 1_500.0, 30.0)) // aderiu de novo: 50 → 3ª
        // Duas amostras seguidas e depois normal: ainda 4H.
        e.marcha(d, 2_500.0, 10.0)
        e.marcha(d, 2_500.0, 10.0)
        assertEquals(2, e.marcha(d, 1_800.0, 30.0)) // 60 → 2ª
        assertFalse(e.emReduzida)
    }

    @Test
    fun `entrada exige 5 km por h, 1200 rpm e alavanca a frente`() {
        val semGiro = estimador()
        repeat(5) { semGiro.marcha(d, 1_100.0, 5.0) } // 220, mas abaixo de 1.200 rpm
        assertFalse(semGiro.emReduzida)

        val devagar = estimador()
        repeat(5) { devagar.aplicar(amostra(d, 1_300.0, 4.0)) } // abaixo de 5 km/h
        assertFalse(devagar.emReduzida)

        val re = estimador()
        repeat(5) { assertEquals(CambioA750f.MARCHA_RE, re.marcha(Decodificadores.ALAVANCA_R, 2_000.0, 7.0)) }
        assertFalse(re.emReduzida)

        // Todas as posições à frente valem (D, 4, 3, 2, L).
        for (posicao in listOf(Decodificadores.ALAVANCA_4, Decodificadores.ALAVANCA_3, Decodificadores.ALAVANCA_2, Decodificadores.ALAVANCA_L)) {
            val e = estimador()
            repeat(3) { e.marcha(posicao, 2_092.0, 7.0) }
            assertTrue("alavanca $posicao", e.emReduzida)
        }
    }

    @Test
    fun `sai de 4L com a alavanca em P N e o carro parado`() {
        val e = estimador()
        e.entrarEmReduzida()
        // Andando em P/N não sai (só parado).
        e.aplicar(amostra(Decodificadores.ALAVANCA_P_N, 900.0, 6.0))
        assertTrue(e.emReduzida)
        assertEquals(CambioA750f.MARCHA_PARADO, e.marcha(Decodificadores.ALAVANCA_P_N, 750.0, 0.0))
        assertFalse(e.emReduzida)
        assertEquals(1, e.marcha(d, 1_500.0, 10.0)) // 150 → 1ª da 4H
    }

    @Test
    fun `sai de 4L com razao de 4H por 3 amostras a partir de 15 km por h`() {
        val e = estimador()
        e.entrarEmReduzida()
        // 1.500 / 40 = 37,5: abaixo de 46,8 (26 × 2,57 × 0,7), só possível em 4H.
        assertEquals(CambioA750f.codigoReduzida(5), e.marcha(d, 1_500.0, 40.0))
        assertEquals(CambioA750f.codigoReduzida(5), e.marcha(d, 1_500.0, 40.0))
        assertEquals(3, e.marcha(d, 1_500.0, 40.0))
        assertFalse(e.emReduzida)
    }

    @Test
    fun `5a L devagar nao e confundida com 4H`() {
        val e = estimador()
        e.entrarEmReduzida()
        // 1.100 / 20 = 55 (acima de 46,8): segue em 4L, 55 / 2,57 = 21 → 5ª L.
        repeat(5) { assertEquals(15, e.marcha(d, 1_100.0, 20.0)) }
        // Abaixo de 15 km/h nunca conta para sair, mesmo com razão baixa.
        repeat(5) { e.marcha(d, 400.0, 12.0) }
        assertTrue(e.emReduzida)
        // Uma amostra de 4H no meio zera a contagem de saída.
        e.marcha(d, 1_500.0, 40.0)
        e.marcha(d, 1_500.0, 40.0)
        e.marcha(d, 1_100.0, 20.0)
        e.marcha(d, 1_500.0, 40.0)
        assertTrue(e.emReduzida)
    }

    @Test
    fun `cambio sem fatorReduzida nunca entra em 4L`() {
        val semReduzida = a750f.copy(fatorReduzida = null)
        for (cambio in listOf(semReduzida, catalogo.cambios["gm_6t35"]!!, null)) {
            val e = estimador(cambio)
            repeat(10) { assertEquals(1, e.marcha(d, 2_092.0, 7.0)) }
            assertFalse(e.emReduzida)
        }
        // Estimador padrão (sem câmbio informado) também não.
        val padrao = EstimadorCambioA750f()
        repeat(10) { padrao.aplicar(amostra(d, 2_092.0, 7.0)) }
        assertFalse(padrao.emReduzida)
        // O estimador pelas razões do perfil (sem alavanca) também não tem reduzida.
        val aisin = EstimadorMarchaPerfil(catalogo.cambios["aisin_6m"]!!)
        repeat(10) {
            val m = aisin.aplicar(DadosObd(mapOf(Pids.RPM to 2_092.0, Pids.VELOCIDADE to 7.0))).valores.getValue(CambioA750f.CHAVE_MARCHA)
            assertEquals(1.0, m, 0.0)
        }
        assertNull(DetectorReduzida.para(catalogo.cambios["gm_6t35"]))
        assertNotNull(DetectorReduzida.para(a750f))
    }

    @Test
    fun `reiniciar volta a 4H`() {
        val e = estimador()
        e.entrarEmReduzida()
        e.reiniciar()
        assertFalse(e.emReduzida)
        assertEquals(1, e.marcha(d, 2_092.0, 7.0))
    }

    @Test
    fun `painel e cluster mostram a marcha da reduzida com sufixo L`() {
        assertEquals("1ª L", SensorPainel.MARCHA_ATUAL.rotulos[11])
        assertEquals("5ª L", SensorPainel.MARCHA_ATUAL.rotulos[15])
        assertEquals("2ª L", com.charruamotors.obd.pid.DefinicaoPid.rotuloDe(SensorPainel.MARCHA_ATUAL.rotulos, 12.0))
        val dados = DadosObd(mapOf(CambioA750f.CHAVE_ALAVANCA to d, CambioA750f.CHAVE_MARCHA to 12.0))
        assertEquals(IndicadorMarcha("2ª L", "D"), ClusterPainel.indicadorMarcha(dados))
        assertTrue(CambioA750f.ehReduzida(11) && CambioA750f.ehReduzida(15))
        assertFalse(CambioA750f.ehReduzida(5) || CambioA750f.ehReduzida(254) || CambioA750f.ehReduzida(255))
    }
}
