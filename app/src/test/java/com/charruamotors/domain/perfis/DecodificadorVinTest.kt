package com.charruamotors.domain.perfis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v2.2: WMI (3 primeiros caracteres) → montadora e 10º caractere → ano-modelo. */
class DecodificadorVinTest {

    private fun vin(wmi: String, ano: Char) = "${wmi}ZZZ12Z${ano}T000001".also { assertEquals(17, it.length) }

    @Test
    fun `WMIs comuns no Brasil`() {
        val esperado = mapOf(
            "9BG" to "Chevrolet", "9BW" to "Volkswagen", "9BD" to "Fiat", "8AJ" to "Toyota", "9BR" to "Toyota",
            "93H" to "Honda", "9BH" to "Hyundai", "93Y" to "Renault", "9BF" to "Ford", "988" to "Jeep",
        )
        esperado.forEach { (wmi, montadora) ->
            val d = DecodificadorVin.decodificar(vin(wmi, 'J'), anoReferencia = 2026)!!
            assertEquals(wmi, d.wmi)
            assertEquals(wmi, montadora, d.montadora)
        }
        // Importados da Argentina: Cruze (8AG) e Hilux/SW4 (8AJ).
        assertEquals("Chevrolet", DecodificadorVin.montadora("8ag"))
        assertEquals("Argentina", DecodificadorVin.decodificar(vin("8AJ", 'D'))!!.origem)
        assertEquals("Brasil", DecodificadorVin.decodificar(vin("9BG", 'D'))!!.origem)
    }

    @Test
    fun `ano-modelo pelo 10o caractere no ciclo de 30 anos`() {
        val r = 2026
        assertEquals(2013, DecodificadorVin.anoModelo('D', r))
        assertEquals(2018, DecodificadorVin.anoModelo('J', r))
        assertEquals(2010, DecodificadorVin.anoModelo('A', r))
        assertEquals(2020, DecodificadorVin.anoModelo('L', r))
        assertEquals(2024, DecodificadorVin.anoModelo('R', r))
        assertEquals(2026, DecodificadorVin.anoModelo('T', r))
        // O ano-modelo seguinte já é vendido no fim do ano.
        assertEquals(2027, DecodificadorVin.anoModelo('V', r))
        assertEquals(1998, DecodificadorVin.anoModelo('W', r))
        assertEquals(2000, DecodificadorVin.anoModelo('Y', r))
        assertEquals(2001, DecodificadorVin.anoModelo('1', r))
        assertEquals(2009, DecodificadorVin.anoModelo('9', r))
        assertEquals(2005, DecodificadorVin.anoModelo('5', 2010))
        // Em 2035 o "1" já é 2031 e o "D" continua 2013.
        assertEquals(2031, DecodificadorVin.anoModelo('1', 2035))
        assertEquals(2013, DecodificadorVin.anoModelo('D', 2035))
        // Códigos que não são de ano.
        assertNull(DecodificadorVin.anoModelo('0', r))
        assertNull(DecodificadorVin.anoModelo('U', r))
        assertNull(DecodificadorVin.anoModelo('Z', r))
    }

    @Test
    fun `VIN real do simulado e formato invalido`() {
        val cruze = DecodificadorVin.decodificar("8AGBN69S0JR104567", 2026)!!
        assertEquals("Chevrolet", cruze.montadora)
        assertEquals(2018, cruze.anoModelo)
        assertEquals("Chevrolet · 2018 (WMI 8AG, Argentina)", cruze.descricao)
        val sw4 = DecodificadorVin.decodificar("8AJFZ29G4D6012345", 2026)!!
        assertEquals("Toyota", sw4.montadora)
        assertEquals(2013, sw4.anoModelo)
        // Minúsculas, espaços e hífens são normalizados.
        assertEquals("9BGKS48U0JG123456", DecodificadorVin.decodificar(" 9bg-ks48u0jg123456 ")!!.vin)
        // WMI fora da tabela: decodifica o ano, montadora desconhecida.
        val outro = DecodificadorVin.decodificar("WVWZZZ1KZ8W000001", 2026)!!
        assertNull(outro.montadora)
        assertEquals(2008, outro.anoModelo)
        // Inválidos: tamanho errado, I/O/Q, vazio.
        assertNull(DecodificadorVin.decodificar("9BG123"))
        assertNull(DecodificadorVin.decodificar("9BGIS48U0JG123456"))
        assertNull(DecodificadorVin.decodificar("9BGOS48U0JG123456"))
        assertNull(DecodificadorVin.decodificar(""))
        assertNull(DecodificadorVin.decodificar(null))
    }
}
