package com.charruamotors.direcao.waze

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deep links do Waze e a cadeia de fallback para a Play Store. */
class AtalhosWazeTest {

    @Test
    fun `abrir waze usa o esquema puro`() {
        assertEquals("waze://", AcaoWaze.ABRIR.uri)
    }

    @Test
    fun `posto mais proximo ja navega`() {
        assertEquals("waze://?q=Posto%20de%20combustivel&navigate=yes", AcaoWaze.POSTO.uri)
    }

    @Test
    fun `oficina mecanica ja navega`() {
        assertEquals("waze://?q=Oficina%20mecanica&navigate=yes", AcaoWaze.OFICINA.uri)
    }

    @Test
    fun `busca codifica espacos como por cento 20 e acentos em utf8`() {
        assertEquals("waze://?q=Borracharia%20S%C3%A3o%20Jos%C3%A9&navigate=yes", AtalhosWaze.uriBusca("  Borracharia São José "))
        assertEquals("waze://?q=Auto%20El%C3%A9trica", AtalhosWaze.uriBusca("Auto Elétrica", navegar = false))
    }

    @Test
    fun `sem waze instalado cai para a play store e depois para o navegador`() {
        assertEquals(
            listOf(
                "waze://?q=Posto%20de%20combustivel&navigate=yes",
                "market://details?id=com.waze",
                "https://play.google.com/store/apps/details?id=com.waze",
            ),
            AtalhosWaze.tentativas(AcaoWaze.POSTO),
        )
    }
}
