package com.charruamotors.obd.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogoDtcTest {

    /** Catálogo real de `src/main/assets`, lido do disco (sem AssetManager). */
    private val catalogo: CatalogoDtc by lazy {
        val arquivo = listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, CatalogoDtc.ARQUIVO_PADRAO) }
            .first { it.isFile }
        CatalogoDtc.deJson(arquivo.readText(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- conteúdo do asset

    @Test
    fun `asset carrega com codigos genericos dos quatro sistemas`() {
        assertTrue("só ${catalogo.genericos.size} genéricos", catalogo.genericos.size >= 150)
        listOf('P', 'C', 'B', 'U').forEach { sistema ->
            assertTrue("sem códigos $sistema", catalogo.genericos.keys.any { it.first() == sistema })
        }
        listOf("P0300", "P0171", "P0420", "P0087", "P0299", "C0035", "B0001", "U0100").forEach {
            assertNotNull("faltou $it", catalogo.genericos[it])
        }
    }

    @Test
    fun `asset tem tabelas Toyota e Volkswagen`() {
        val nomes = catalogo.fabricantes.map { it.nome }
        assertTrue("Toyota" in nomes)
        assertTrue("Volkswagen" in nomes)
        assertTrue(catalogo.fabricantes.all { it.codigos.size >= 20 })
        assertTrue(catalogo.total > catalogo.genericos.size)
    }

    @Test
    fun `toda entrada tem titulo e descricao tecnica`() {
        val todas = catalogo.genericos.values + catalogo.fabricantes.flatMap { it.codigos.values }
        todas.forEach {
            assertTrue("${it.codigo} sem título", it.titulo.isNotBlank())
            assertTrue("${it.codigo} sem descrição técnica", it.descricao.length >= 20)
        }
    }

    @Test
    fun `genericos sao de fato padronizados SAE e especificos nao`() {
        // 2º caractere 0 = genérico. Exceções conhecidas: P2xxx também é genérico (faixa SAE).
        catalogo.genericos.keys.forEach { assertTrue("$it não é genérico", it[1] == '0' || it.startsWith("P2")) }
        catalogo.fabricantes.flatMap { it.codigos.keys }.forEach { assertEquals("$it deveria ser P1xxx", '1', it[1]) }
    }

    // ---------------------------------------------------------------- consulta

    @Test
    fun `codigo generico devolve titulo e descricao do catalogo`() {
        val info = catalogo.descrever("p0300")
        assertEquals("P0300", info.codigo)
        assertTrue(info.titulo.contains("ignição"))
        assertTrue(info.descricao!!.contains("velas"))
        assertNull(info.fabricante)
        assertTrue(info.catalogado)
    }

    @Test
    fun `marca do veiculo escolhe a tabela do fabricante`() {
        val vw = catalogo.descrever("P1128", marca = "VW")
        assertEquals("Volkswagen", vw.fabricante)
        assertTrue(vw.titulo.contains("pobre"))

        val toyota = catalogo.descrever("P1128", marca = "Toyota")
        assertEquals("Toyota", toyota.fabricante)
        assertTrue(toyota.titulo.contains("borboleta"))
    }

    @Test
    fun `aliases e variacoes de escrita da marca`() {
        listOf("Volkswagen", "volkswagen ", "VOLKS", "Vw Gol").forEach {
            assertEquals(it, "Volkswagen", catalogo.descrever("P1570", it).fabricante)
        }
        assertEquals("Toyota", catalogo.descrever("P1601", "Toyota Hilux").fabricante)
    }

    @Test
    fun `codigo especifico de um unico fabricante sem marca informa o fabricante`() {
        val info = catalogo.descrever("P1601") // só a Toyota tem
        assertEquals("Toyota", info.fabricante)
        assertTrue(info.descricao!!.contains("injetores"))
    }

    @Test
    fun `codigo especifico ambiguo sem marca lista os significados por fabricante`() {
        val info = catalogo.descrever("P1128", marca = "Fiat")
        assertFalse(info.catalogado)
        assertNull(info.fabricante)
        assertTrue(info.titulo.contains("fabricante"))
        assertTrue(info.descricao!!.contains("Toyota:"))
        assertTrue(info.descricao!!.contains("Volkswagen:"))
    }

    @Test
    fun `marca sem tabela ainda usa os genericos`() {
        assertTrue(catalogo.descrever("P0171", marca = "Fiat").titulo.contains("pobre"))
    }

    @Test
    fun `codigo desconhecido cai no fallback por sistema`() {
        val p = catalogo.descrever("P0999")
        assertFalse(p.catalogado)
        assertNull(p.descricao)
        assertTrue(p.titulo.contains("trem de força"))
        assertTrue(catalogo.descrever("C0999").titulo.contains("chassi"))
        assertTrue(catalogo.descrever("B1999").titulo.contains("fabricante"))
        assertTrue(catalogo.descrever("U0999").titulo.contains("rede"))
        assertEquals("Código desconhecido", catalogo.descrever("X123").titulo)
    }

    @Test
    fun `catalogo vazio so usa o fallback`() {
        val info = CatalogoDtc.VAZIO.descrever("P0300")
        assertFalse(info.catalogado)
        assertTrue(info.titulo.contains("trem de força"))
    }

    // ---------------------------------------------------------------- validação do JSON

    @Test
    fun `json minimo e campos opcionais`() {
        val c = CatalogoDtc.deJson("""{"genericos":[{"codigo":"p0300","titulo":"Falha"}]}""")
        assertEquals("", c.genericos["P0300"]!!.descricao)
        assertTrue(c.fabricantes.isEmpty())
        assertEquals(0, CatalogoDtc.deJson("{}").total)
    }

    @Test
    fun `json invalido ou entradas inconsistentes sao rejeitados`() {
        val ok = """{"codigo":"P0300","titulo":"Falha"}"""
        listOf(
            "não é json",
            """{"genericos":[$ok,$ok]}""", // repetido
            """{"genericos":[{"codigo":"P03","titulo":"Curto"}]}""", // código malformado
            """{"genericos":[{"codigo":"X0300","titulo":"Sistema X"}]}""", // sistema inexistente
            """{"genericos":[{"codigo":"P0300","titulo":"  "}]}""", // sem título
            """{"genericos":[{"titulo":"Sem código"}]}""", // campo obrigatório ausente
            """{"fabricantes":[{"nome":"VW","codigos":[$ok,$ok]}]}""", // repetido no fabricante
            """{"fabricantes":[{"nome":"","codigos":[]}]}""", // fabricante sem nome
        ).forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { CatalogoDtc.deJson(json) }
        }
    }

    @Test
    fun `mesmo codigo pode existir em fabricantes diferentes`() {
        val json = """{"fabricantes":[
            {"nome":"A","codigos":[{"codigo":"P1000","titulo":"Significado A"}]},
            {"nome":"B","aliases":["bê"],"codigos":[{"codigo":"P1000","titulo":"Significado B"}]}
        ]}"""
        val c = CatalogoDtc.deJson(json)
        assertEquals("Significado B", c.descrever("P1000", "Bê").titulo)
        assertEquals("Significado A", c.descrever("P1000", "a").titulo)
    }

    @Test
    fun `normalizacao de marca remove acentos caixa e espacos`() {
        assertEquals("citroen c3", normalizarMarca("  Citroën   C3 "))
    }
}
