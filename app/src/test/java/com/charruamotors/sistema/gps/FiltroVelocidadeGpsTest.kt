package com.charruamotors.sistema.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fallback seguro da velocidade GPS: só fixes recentes e confiáveis viram km/h. */
class FiltroVelocidadeGpsTest {

    private fun fix(ms: Float?, instante: Long = 10_000, precisaoVel: Float? = 0.5f, precisaoPos: Float? = 5f) =
        AmostraGps(ms, precisaoVel, precisaoPos, instante)

    @Test
    fun `fix recente converte m por s em km por h`() {
        assertEquals(100.0, FiltroVelocidadeGps.kmh(fix(27.777778f), 10_500)!!, 1e-3)
        assertEquals(36.0, FiltroVelocidadeGps.kmh(fix(10f, precisaoVel = null, precisaoPos = null), 10_000)!!, 1e-4)
    }

    @Test
    fun `sem amostra ou sem velocidade no fix nao ha leitura`() {
        assertNull(FiltroVelocidadeGps.kmh(null, 10_000))
        assertNull(FiltroVelocidadeGps.kmh(fix(null), 10_000))
        assertNull(FiltroVelocidadeGps.kmh(fix(Float.NaN), 10_000))
        assertNull(FiltroVelocidadeGps.kmh(fix(-1f), 10_000))
    }

    @Test
    fun `fix velho expira - tunel ou perda de sinal nunca congela o ultimo valor`() {
        val f = fix(20f)
        assertEquals(72.0, FiltroVelocidadeGps.kmh(f, 10_000 + FiltroVelocidadeGps.VALIDADE_MS)!!, 1e-4)
        assertNull(FiltroVelocidadeGps.kmh(f, 10_000 + FiltroVelocidadeGps.VALIDADE_MS + 1))
        assertNull("fix muito no futuro = relógio incoerente", FiltroVelocidadeGps.kmh(f, 8_000))
    }

    @Test
    fun `fix impreciso e descartado`() {
        assertNull(FiltroVelocidadeGps.kmh(fix(20f, precisaoVel = 3f), 10_000))
        assertNull(FiltroVelocidadeGps.kmh(fix(20f, precisaoPos = 80f), 10_000))
        assertNull(FiltroVelocidadeGps.kmh(fix(20f, precisaoVel = Float.NaN), 10_000))
    }

    @Test
    fun `parado o ruido vira zero e velocidade absurda e descartada`() {
        assertEquals(0.0, FiltroVelocidadeGps.kmh(fix(0.3f), 10_000)!!, 0.0) // ~1,1 km/h de ruído
        assertEquals(0.0, FiltroVelocidadeGps.kmh(fix(0f), 10_000)!!, 0.0)
        assertNull(FiltroVelocidadeGps.kmh(fix(120f), 10_000)) // 432 km/h
    }
}
