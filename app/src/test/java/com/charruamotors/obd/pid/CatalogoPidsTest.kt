package com.charruamotors.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoPidsTest {

    private val catalogo = CatalogoTeste.padrao

    private fun converter(comando: String, vararg bytes: Int) = CatalogoTeste.pid(comando).converter(bytes.toList())

    // ---------------------------------------------------------------- conteúdo do catálogo

    @Test
    fun `catalogo padrao tem pelo menos 20 PIDs do modo 01`() {
        assertTrue("só ${catalogo.pidsModo01.size} PIDs", catalogo.pidsModo01.size >= 20)
    }

    @Test
    fun `catalogo contem os sensores essenciais`() {
        listOf(
            "010C", "010D", "0105", "0104", "010B", "0110", "0111", "010F", "0106", "0107",
            "010E", "0123", "015C", "012F", "ATRV",
        ).forEach { assertNotNull("faltou $it", catalogo[it]) }
    }

    @Test
    fun `toda definicao tem nome em portugues unidade e faixa`() {
        catalogo.pids.forEach {
            assertTrue("${it.comando} sem nome", it.nome.isNotBlank())
            assertTrue("${it.comando} sem unidade", it.unidade.isNotBlank())
            assertTrue("${it.comando} faixa", it.min < it.max)
        }
    }

    /** Valida o JSON inteiro: com bytes extremos (0x00/0xFF), nenhuma fórmula sai da faixa declarada. */
    @Test
    fun `formulas ficam dentro da faixa declarada nos valores extremos dos bytes`() {
        catalogo.pidsModo01.forEach { def ->
            combinacoesExtremas(def.bytes).forEach { bytes ->
                val v = def.converter(bytes)
                assertTrue("${def.comando} ${def.formula} com $bytes = $v fora de ${def.min}..${def.max}", def.dentroDaFaixa(v))
            }
        }
    }

    @Test
    fun `busca por comando ignora caixa`() {
        assertEquals("010C", catalogo["010c"]?.comando)
        assertTrue("atrv" in catalogo)
        assertTrue("01FF" !in catalogo)
    }

    @Test
    fun `numero do PID e extraido do comando`() {
        assertEquals(0x0C, CatalogoTeste.pid("010C").pid)
        assertEquals("5C", CatalogoTeste.pid("015C").pidHex)
        assertEquals(null, CatalogoTeste.pid("ATRV").pid)
        assertTrue(CatalogoTeste.pid("ATRV").ehComandoAt)
    }

    // ---------------------------------------------------------------- avaliação das fórmulas do JSON

    @Test
    fun `rpm velocidade temperatura e carga`() {
        assertEquals(1726.0, converter("010C", 0x1A, 0xF8), 0.0)
        assertEquals(90.0, converter("010D", 0x5A), 0.0)
        assertEquals(50.0, converter("0105", 0x5A), 0.0)
        assertEquals(-40.0, converter("0105", 0x00), 0.0)
        assertEquals(50.2, converter("0104", 0x80), 0.05)
    }

    @Test
    fun `ajustes de combustivel STFT e LTFT centrados em 128`() {
        assertEquals(0.0, converter("0106", 0x80), 0.0)
        assertEquals(-100.0, converter("0106", 0x00), 0.0)
        assertEquals(99.22, converter("0107", 0xFF), 0.01)
        assertEquals(3.125, converter("0107", 0x84), 1e-9)
    }

    @Test
    fun `pressao do coletor MAF e borboleta`() {
        assertEquals(101.0, converter("010B", 0x65), 0.0)
        assertEquals(5.0, converter("0110", 0x01, 0xF4), 1e-9) // 500 / 100
        assertEquals(100.0, converter("0111", 0xFF), 1e-9)
    }

    @Test
    fun `avanco de ignicao e temperaturas de admissao e oleo`() {
        assertEquals(0.0, converter("010E", 0x80), 0.0)
        assertEquals(-64.0, converter("010E", 0x00), 0.0)
        assertEquals(12.5, converter("010E", 0x99), 0.0)
        assertEquals(25.0, converter("010F", 0x41), 0.0)
        assertEquals(95.0, converter("015C", 0x87), 0.0)
    }

    @Test
    fun `pressao do rail nivel de combustivel e tensao do modulo`() {
        assertEquals(50_000.0, converter("0123", 0x13, 0x88), 0.0) // 10 · 5000 kPa
        assertEquals(50.2, converter("012F", 0x80), 0.05)
        assertEquals(14.2, converter("0142", 0x37, 0x78), 1e-9) // 14200 / 1000
    }

    @Test
    fun `tensao da bateria usa o valor textual X`() {
        assertEquals(12.6, CatalogoTeste.pid("ATRV").converter(x = 12.6), 0.0)
    }

    // ---------------------------------------------------------------- validação do JSON

    @Test
    fun `json invalido ou definicoes inconsistentes sao rejeitados`() {
        fun json(vararg pids: String) = """{"pids": [${pids.joinToString(",")}]}"""
        val rpm = """{"comando":"010C","nome":"RPM","unidade":"rpm","bytes":2,"formula":"(256*A+B)/4","min":0,"max":16383.75}"""

        assertEquals(1, CatalogoPids.deJson(json(rpm)).pids.size)
        listOf(
            "nao é json",
            json(rpm, rpm), // duplicado
            json(rpm.replace("\"bytes\":2", "\"bytes\":1")), // fórmula usa B, mas só 1 byte
            json(rpm.replace("(256*A+B)/4", "(256*A+")), // fórmula malformada
            json(rpm.replace("010C", "XYZ")), // comando inválido
            json(rpm.replace("\"min\":0", "\"min\":20000")), // min > max
            json("""{"comando":"ATRV","nome":"V","unidade":"V","bytes":0,"formula":"A","min":0,"max":40}"""), // AT usando byte
        ).forEach { texto ->
            assertThrows(texto, IllegalArgumentException::class.java) { CatalogoPids.deJson(texto) }
        }
    }

    private fun combinacoesExtremas(n: Int): List<List<Int>> =
        (0 until (1 shl n)).map { mascara -> (0 until n).map { i -> if ((mascara shr i) and 1 == 1) 0xFF else 0x00 } }
}
