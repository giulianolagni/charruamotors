package com.charruamotors.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogoEstendidoTest {

    private val catalogo = CatalogoTeste.estendido

    // ---------------------------------------------------------------- asset

    @Test
    fun `asset tem perfis Toyota modo 21 e Volkswagen UDS 22`() {
        val toyota = catalogo.perfilPara("Toyota")!!
        val vw = catalogo.perfilPara("Volkswagen")!!
        assertTrue(toyota.pids.all { it.estendida!!.servico == "21" })
        assertTrue(vw.pids.all { it.estendida!!.servico == "22" })
        assertTrue("TOYOTA_ATF" in toyota.pids.map { it.comando })
        assertEquals("7E1", catalogo["TOYOTA_ATF"]!!.estendida!!.cabecalho) // módulo da transmissão
        assertTrue(listOf(toyota, vw).all { it.observacao.isNotBlank() })
    }

    @Test
    fun `todo PID do asset e estendido e tem chave unica fora do padrao`() {
        val padrao = CatalogoTeste.padrao.comandos
        catalogo.definicoes.forEach {
            assertTrue(it.comando, it.ehEstendido && !it.ehModo01 && !it.ehComandoAt)
            assertFalse(it.comando, it.comando in padrao)
            assertEquals(null, it.pid)
        }
    }

    @Test
    fun `injetores do D-4D saem de uma unica requisicao`() {
        val requisicoes = catalogo.perfilPara("Toyota")!!.requisicoes
        val injetores = requisicoes.values.single { grupo -> grupo.any { it.comando == "TOYOTA_INJ1" } }
        assertEquals(listOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4"), injetores.map { it.comando })
        // 7E0: bicos (21AD), tensão, barômetro, marcha, pedal D/E, ATF e alavanca; 7E1: ATF, 21D9 e marcha; 7B0 VSC; 7C0 4x4.
        assertEquals(13, requisicoes.size)
    }

    @Test
    fun `marca do veiculo escolhe o perfil com aliases`() {
        listOf("VW", "vw gol", "Volks", "Audi A3").forEach { assertEquals(it, "Volkswagen", catalogo.perfilPara(it)?.nome) }
        listOf("Toyota Hilux", "LEXUS").forEach { assertEquals(it, "Toyota", catalogo.perfilPara(it)?.nome) }
        assertNull(catalogo.perfilPara("Fiat"))
        assertNull(catalogo.perfilPara(""))
        assertNull(catalogo.perfilPara(null))
        assertNull(CatalogoEstendido.VAZIO.perfilPara("VW"))
    }

    @Test
    fun `rotulo tecnico mostra cabecalho e requisicao`() {
        assertEquals("7E1 · 2182", catalogo["TOYOTA_ATF"]!!.rotuloComando)
        assertEquals("010C", CatalogoTeste.pid("010C").rotuloComando)
    }

    // ---------------------------------------------------------------- requisição

    @Test
    fun `requisicao monta o comando e os prefixos de resposta`() {
        val toyota = RequisicaoEstendida("7E1", "21", "82")
        assertEquals("2182", toyota.comando)
        assertEquals("6182", toyota.prefixoResposta)
        assertEquals("7F21", toyota.prefixoNegativa)
        val uds = RequisicaoEstendida("7E0", "22", "F40C")
        assertEquals("22F40C", uds.comando)
        assertEquals("62F40C", uds.prefixoResposta)
    }

    @Test
    fun `requisicao invalida e rejeitada`() {
        listOf(
            { RequisicaoEstendida("7E", "22", "F40C") }, // cabeçalho curto
            { RequisicaoEstendida("18DA10F1", "22", "F40C") }, // 29 bits não suportado
            { RequisicaoEstendida("7E0", "23", "F40C") }, // serviço desconhecido
            { RequisicaoEstendida("7E0", "22", "0C") }, // DID UDS tem 2 bytes
            { RequisicaoEstendida("7E0", "21", "F40C") }, // identificador do 21 tem 1 byte
        ).forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
    }

    // ---------------------------------------------------------------- JSON

    private fun json(vararg pids: String, fabricante: String = "VW") =
        """{"perfis":[{"fabricante":"$fabricante","pids":[${pids.joinToString(",")}]}]}"""

    private fun pid(id: String = "VW_A", formula: String = "A-40", bytes: Int = 1, cabecalho: String = "7E0") =
        """{"id":"$id","cabecalho":"$cabecalho","servico":"22","identificador":"11BD","nome":"Óleo","unidade":"°C","bytes":$bytes,"formula":"$formula","min":-40,"max":215}"""

    @Test
    fun `json minimo e normalizacao`() {
        val c = CatalogoEstendido.deJson(json(pid(id = "vw_a", cabecalho = "7e0")))
        val d = c["VW_A"]!!
        assertEquals("7E0", d.estendida!!.cabecalho)
        assertEquals(90.0, d.converter(listOf(0x82)), 0.0)
        assertEquals(0, CatalogoEstendido.deJson("{}").perfis.size)
    }

    @Test
    fun `json invalido ou inconsistente e rejeitado`() {
        listOf(
            "não é json",
            json(pid(), pid()), // id repetido
            """{"perfis":[{"fabricante":"A","pids":[${pid()}]},{"fabricante":"B","pids":[${pid()}]}]}""", // repetido entre perfis
            json(pid(formula = "256*A+B", bytes = 1)), // fórmula usa mais bytes que o declarado
            json(pid(id = "010C")), // id não pode parecer PID padrão
            json(pid(cabecalho = "XYZ")),
            json(pid(), fabricante = " "),
            json("""{"id":"VW_A","servico":"22","identificador":"11BD","nome":"x","unidade":"x","bytes":1,"formula":"A","min":0,"max":1}"""), // sem cabeçalho
        ).forEach { j -> assertThrows(j, IllegalArgumentException::class.java) { CatalogoEstendido.deJson(j) } }
    }
}
