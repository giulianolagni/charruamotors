package com.charruamotors.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTest {

    private fun avaliar(texto: String, vararg bytes: Int, x: Double = 0.0) =
        Formula.compilar(texto).avaliar(bytes.toList(), x)

    @Test
    fun `precedencia de multiplicacao e divisao sobre soma e subtracao`() {
        assertEquals(7.0, avaliar("1+2*3"), 0.0)
        assertEquals(9.0, avaliar("(1+2)*3"), 0.0)
        assertEquals(1.0, avaliar("8/4/2"), 0.0) // associatividade à esquerda
        assertEquals(3.0, avaliar("10-4-3"), 0.0)
    }

    @Test
    fun `variaveis A a D sao os bytes de dados na ordem`() {
        assertEquals(1726.0, avaliar("(256*A+B)/4", 0x1A, 0xF8), 0.0)
        assertEquals(0x01020304.toDouble(), avaliar("A*16777216+B*65536+C*256+D", 1, 2, 3, 4), 0.0)
    }

    @Test
    fun `sinal unario numeros decimais minusculas e espacos`() {
        assertEquals(-64.0, avaliar("a / 2 - 64", 0), 0.0)
        assertEquals(-5.0, avaliar("-(A+5)", 0), 0.0)
        assertEquals(2.5, avaliar("--2.5"), 0.0)
        assertEquals(0.75, avaliar("A*0.005", 150), 1e-12)
    }

    @Test
    fun `variavel X recebe o valor textual dos comandos AT`() {
        assertEquals(12.6, avaliar("X", x = 12.6), 0.0)
        assertEquals(126.0, avaliar("X*10", x = 12.6), 1e-9)
    }

    @Test
    fun `bytes necessarios segue a maior variavel usada`() {
        assertEquals(0, Formula.compilar("X").bytesNecessarios)
        assertEquals(1, Formula.compilar("A-40").bytesNecessarios)
        assertEquals(2, Formula.compilar("(256*A+B)/4").bytesNecessarios)
        assertEquals(2, Formula.compilar("B/2").bytesNecessarios) // B exige que A também exista
        assertEquals(setOf('A', 'B'), Formula.compilar("(256*A+B)/4").variaveis)
    }

    @Test
    fun `faltar bytes na avaliacao e erro`() {
        assertThrows(IllegalArgumentException::class.java) { avaliar("(256*A+B)/4", 0x1A) }
    }

    @Test
    fun `divisao por zero nao lanca e gera valor nao finito`() {
        assertTrue(!avaliar("100/A", 0).isFinite())
    }

    @Test
    fun `formulas malformadas sao rejeitadas na compilacao`() {
        listOf("", "A+", "(A+B", "A+B)", "A$2", "2..5", "Z", "A B").forEach { texto ->
            assertThrows("\"$texto\" deveria ser inválida", IllegalArgumentException::class.java) {
                Formula.compilar(texto)
            }
        }
    }
}
