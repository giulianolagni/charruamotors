package com.charruamotors.domain.perfis

import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Decodificadores
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.2: valida **todos** os arquivos de `assets/perfis/` lidos do disco (não só os referenciados):
 * sintaxe, id igual ao nome do arquivo, integridade referencial de `motorId`/`cambioId`, faixas com
 * `min < max`, razões de marcha decrescentes e os PIDs opcionais existentes no `pids_estendidos.json`.
 */
class ArquivosPerfisTest {

    private val pasta: File = listOf("src/main/assets/perfis", "app/src/main/assets/perfis")
        .map(::File).firstOrNull { it.isDirectory } ?: error("assets/perfis não encontrado a partir de ${File(".").absolutePath}")

    private fun arquivos(sub: String): List<File> =
        File(pasta, sub).listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }

    private val motores: Map<String, PerfilMotor> by lazy {
        arquivos(CatalogoPerfis.PASTA_MOTORES).associate { f ->
            val m = lerOuFalhar(f) { CatalogoPerfis.motorDeJson(it) }
            assertEquals("id de ${f.name}", f.nameWithoutExtension, m.id)
            m.id to m
        }
    }

    private val cambios: Map<String, PerfilCambio> by lazy {
        arquivos(CatalogoPerfis.PASTA_CAMBIOS).associate { f ->
            val c = lerOuFalhar(f) { CatalogoPerfis.cambioDeJson(it) }
            assertEquals("id de ${f.name}", f.nameWithoutExtension, c.id)
            c.id to c
        }
    }

    private val veiculos: List<PerfilVeiculo> by lazy {
        lerOuFalhar(File(pasta, CatalogoPerfis.ARQUIVO_VEICULOS)) { CatalogoPerfis.veiculosDeJson(it) }
    }

    private fun <T> lerOuFalhar(f: File, parse: (String) -> T): T = try {
        parse(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        fail("${f.path}: ${e.message}")
        throw e
    }

    @Test
    fun `so ha arquivos json e todos tem sintaxe valida`() {
        val todos = pasta.walkTopDown().filter { it.isFile }.toList()
        assertEquals("8 motores + 5 câmbios + veiculos.json", 14, todos.size)
        todos.forEach { f ->
            assertTrue("${f.name} não é .json", f.extension == "json")
            try {
                JSONObject(f.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                fail("${f.path}: JSON inválido (${e.message})")
            }
        }
    }

    @Test
    fun `motores e cambios pedidos estao no catalogo`() {
        assertEquals(
            setOf(
                "toyota_1kd_ftv", "gm_le2_14t", "vw_ea211_tsi", "fiat_firefly_t200", "honda_l15_aspirado",
                "generico_flex_aspirado", "generico_flex_turbo", "generico_diesel_cr",
            ),
            motores.keys,
        )
        assertEquals(setOf("toyota_a750f", "gm_6t35", "aisin_6m", "cvt", "manual"), cambios.keys)
        assertEquals(StatusPerfil.VALIDADO, motores.getValue("toyota_1kd_ftv").status)
        assertEquals(StatusPerfil.VALIDADO, cambios.getValue("toyota_a750f").status)
        assertEquals(StatusPerfil.VALIDADO, cambios.getValue("manual").status)
        (motores.keys - "toyota_1kd_ftv").forEach { assertEquals(it, StatusPerfil.BASE, motores.getValue(it).status) }
    }

    @Test
    fun `veiculos referenciam motores e cambios existentes`() {
        veiculos.forEach { v ->
            assertTrue("${v.id}: motorId ${v.motorId} inexistente", v.motorId in motores)
            assertTrue("${v.id}: cambioId ${v.cambioId} inexistente", v.cambioId in cambios)
        }
        // Todo motor e câmbio está em uso por algum veículo (nenhum arquivo órfão).
        assertEquals(motores.keys, veiculos.map { it.motorId }.toSet())
        assertEquals(cambios.keys, veiculos.map { it.cambioId }.toSet())
        assertEquals(veiculos.size, veiculos.map { it.id }.toSet().size)
        // Um genérico por motorização.
        assertEquals(
            setOf("generico_flex_aspirado", "generico_flex_turbo", "generico_diesel_cr"),
            veiculos.filter { it.generico }.map { it.motorId }.toSet(),
        )
        veiculos.filter { !it.generico }.forEach { assertTrue("${it.id} sem detecção", it.deteccao != null) }
    }

    @Test
    fun `faixas com min menor que max e sem parametro repetido`() {
        val todas = motores.values.map { it.id to it.faixas } + cambios.values.map { it.id to it.faixas }
        todas.forEach { (id, faixas) ->
            faixas.forEach { f ->
                assertTrue("$id: ${f.parametro} sem limites", f.min != null || f.max != null)
                if (f.min != null && f.max != null) assertTrue("$id: ${f.parametro} ${f.min}..${f.max}", f.min!! < f.max!!)
            }
            assertEquals("$id: parâmetro repetido", faixas.size, faixas.map { it.parametro }.toSet().size)
        }
        // A faixa do ATF pertence ao câmbio, nunca ao motor.
        motores.values.forEach { m -> assertTrue(m.id, m.faixas.none { it.parametro.name == "TEMP_ATF" }) }
    }

    @Test
    fun `relacoes de marcha em ordem decrescente e sequenciais`() {
        cambios.values.forEach { c ->
            c.relacoes.forEachIndexed { i, r ->
                assertEquals("${c.id}: marcha", i + 1, r.marcha)
                if (i > 0) assertTrue("${c.id}: ${r.marcha}ª não decresce", r.razaoMinima < c.relacoes[i - 1].razaoMinima)
            }
            if (c.relacoes.isNotEmpty()) assertEquals(0.0, c.relacoes.last().razaoMinima, 0.0)
        }
        assertEquals(5, cambios.getValue("toyota_a750f").relacoes.size)
        assertEquals(6, cambios.getValue("gm_6t35").relacoes.size)
        assertEquals(6, cambios.getValue("aisin_6m").relacoes.size)
        assertTrue(cambios.getValue("manual").relacoes.isEmpty())
        assertTrue(!cambios.getValue("manual").temTcm)
    }

    @Test
    fun `PIDs opcionais e de ATF existem no catalogo estendido com o cabecalho e o servico do perfil`() {
        val estendido = CatalogoTeste.estendido
        motores.values.forEach { m ->
            m.pidsOpcionais.forEach { id ->
                val req = estendido[id]?.estendida ?: error("${m.id}: PID $id ausente de pids_estendidos.json")
                assertEquals("${m.id}/$id cabeçalho", m.cabecalho, req.cabecalho)
                assertEquals("${m.id}/$id serviço", m.servicoExtra, req.servico)
            }
        }
        cambios.values.forEach { c ->
            (c.pidsAtf + listOfNotNull(c.mapaAlavanca?.chave)).forEach { id ->
                val req = estendido[id]?.estendida ?: error("${c.id}: PID $id ausente de pids_estendidos.json")
                assertTrue("${c.id}/$id cabeçalho ${req.cabecalho}", req.cabecalho in c.cabecalhos)
                assertEquals("${c.id}/$id serviço", c.servico, req.servico)
            }
        }
    }

    @Test
    fun `mapa da alavanca da A750F e o mesmo do decodificador e dos rotulos`() {
        val mapa = cambios.getValue("toyota_a750f").mapaAlavanca!!
        assertEquals("7E1", mapa.cabecalho)
        assertEquals("21D9", mapa.comando)
        assertEquals(listOf("P/N", "R", "D", "4", "3", "2", "L"), mapa.posicoes.map { it.rotulo })
        val alavanca = CatalogoTeste.estendido[mapa.chave]!!
        mapa.posicoes.forEach { p ->
            val valor = Decodificadores.decodificar("ALAVANCA_A750F", listOf(0x00, 0x00, p.byteC, p.byteD))
            assertEquals("bytes ${p.byteC}/${p.byteD}", p.rotulo, alavanca.rotulos[valor.toInt()])
        }
    }

    @Test
    fun `carregador le o catalogo completo sem erros`() {
        val catalogo = CatalogoPerfis.carregar(FontePerfis { File(pasta, it).takeIf(File::isFile)?.readText(Charsets.UTF_8) })
        assertTrue(catalogo.erros.toString(), catalogo.erros.isEmpty())
        assertEquals(veiculos.size, catalogo.presets.size)
        // O classpath dos testes (assets como recursos) dá o mesmo catálogo que o disco.
        val doClasspath = CatalogoPerfis.carregar(FontePerfis.doClasspath())
        assertEquals(catalogo.presets.map { it.id }, doClasspath.presets.map { it.id })
        assertTrue(CarregadorPerfis.catalogo.erros.isEmpty())
    }

    @Test
    fun `arquivo invalido ou ausente nao derruba o catalogo`() {
        val arquivos = mapOf(
            "veiculos.json" to File(pasta, "veiculos.json").readText(),
            "motores/toyota_1kd_ftv.json" to "{ isto não é json",
            "cambios/toyota_a750f.json" to File(pasta, "cambios/toyota_a750f.json").readText(),
        )
        val c = CatalogoPerfis.carregar(FontePerfis { arquivos[it] })
        assertTrue(c.presets.isEmpty()) // nenhum veículo com motor e câmbio resolvidos
        assertTrue(c.erros.any { "toyota_1kd_ftv" in it })
        assertTrue(CatalogoPerfis.carregar(FontePerfis { null }).presets.isEmpty())
    }
}
