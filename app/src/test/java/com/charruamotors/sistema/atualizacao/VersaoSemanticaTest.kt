package com.charruamotors.sistema.atualizacao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersaoSemanticaTest {

    private fun v(texto: String) = VersaoSemantica.deTexto(texto)!!

    @Test
    fun `tag com v e partes ausentes viram major minor patch`() {
        assertEquals(VersaoSemantica(1, 5, 0), v("v1.5"))
        assertEquals(VersaoSemantica(1, 5, 0), v("1.5"))
        assertEquals(VersaoSemantica(2, 0, 0), v("V2"))
        assertEquals(VersaoSemantica(1, 6, 3), v(" v1.6.3 "))
        assertEquals(VersaoSemantica(1, 6, 0, "beta.2"), v("v1.6.0-beta.2"))
        assertEquals(VersaoSemantica(1, 5, 0), v("Charrua OBD Pro v1.5"))
        assertEquals(VersaoSemantica(1, 5, 0), v("1.5 (debug)"))
    }

    @Test
    fun `texto sem numero de versao nao e versao`() {
        assertNull(VersaoSemantica.deTexto(null))
        assertNull(VersaoSemantica.deTexto(""))
        assertNull(VersaoSemantica.deTexto("latest"))
        assertNull(VersaoSemantica.deTexto("release-final"))
    }

    @Test
    fun `comparacao numerica por parte e nao pelo texto`() {
        assertTrue(v("1.10") > v("1.9")) // texto puro diria o contrário
        assertTrue(v("1.5.1") > v("1.5"))
        assertTrue(v("2.0") > v("1.99.99"))
        assertEquals(0, v("1.5").compareTo(v("v1.5.0")))
        assertTrue(v("1.4") < v("1.5"))
    }

    @Test
    fun `pre-release vem antes da versao final`() {
        assertTrue(v("1.6.0-beta") < v("1.6.0"))
        assertTrue(v("1.6.0-alpha") < v("1.6.0-beta"))
        assertTrue(v("1.6.0-beta.2") < v("1.6.0-beta.10"))
        assertTrue(v("1.6.0-beta") > v("1.5.9"))
    }

    @Test
    fun `eh mais nova compara a tag remota com o versionName instalado`() {
        assertTrue(VersaoSemantica.ehMaisNova("v1.6", "1.5"))
        assertTrue(VersaoSemantica.ehMaisNova("v1.5.1", "1.5"))
        assertFalse(VersaoSemantica.ehMaisNova("v1.5", "1.5"))
        assertFalse(VersaoSemantica.ehMaisNova("v1.4", "1.5"))
        assertFalse(VersaoSemantica.ehMaisNova("latest", "1.5"))
        assertTrue(VersaoSemantica.ehMaisNova("v1.5", "dev")) // versão local ilegível: oferece a publicada
    }

    @Test
    fun `forma curta e completa`() {
        assertEquals("1.5", v("v1.5").curta)
        assertEquals("1.5.2", v("1.5.2").curta)
        assertEquals("1.5.0", v("1.5").toString())
        assertEquals("2.0-rc1", v("2.0.0-rc1").curta)
    }
}
