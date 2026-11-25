package com.charruamotors.domain.perfis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v2.2.2: `padroesVin` (WMI + VDS) do `veiculos.json`, `0151` decodificado e a cascata pura
 * `Match VDS → Sondagem ECU → Fallback genérico`.
 */
class IdentificacaoAutomaticaTest {

    private val catalogo: CatalogoPerfis = CatalogoPerfis.carregar(FontePerfis.doClasspath())

    private fun vin(texto: String) = DecodificadorVin.decodificar(texto, anoReferencia = 2026)

    private fun idPorVds(texto: String): String? = catalogo.detectarPorVin(vin(texto))?.id

    // ---------------------------------------------------------------- padroesVin

    @Test
    fun `catalogo carrega sem erros e so os perfis exatos tem padroes de VIN`() {
        assertEquals(emptyList<String>(), catalogo.erros)
        catalogo.veiculos.filter { it.generico }.forEach { assertTrue("${it.id} genérico não tem padrão de VIN", it.padroesVin.isEmpty()) }
        // SW4, Cruze, VW TSI, Fiat T200 e Honda têm chassi catalogado (o Onix/Tracker Turbo segue só pelo cadastro).
        listOf("toyota_sw4_1kd", "chevrolet_cruze_14t", "vw_tsi", "fiat_t200", "honda_l15").forEach { id ->
            assertTrue("$id deveria ter padroesVin", catalogo[id]!!.perfil.padroesVin.isNotEmpty())
        }
    }

    @Test
    fun `todo padrao de VIN do veiculos json e regex valida ancorada no inicio`() {
        val texto = FontePerfis.doClasspath().ler(CatalogoPerfis.ARQUIVO_VEICULOS)!!
        val brutos = org.json.JSONObject(texto).getJSONArray("veiculos").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.flatMap { o ->
                val a = o.optJSONArray("padroesVin")
                (0 until (a?.length() ?: 0)).map { o.getString("id") to a!!.getString(it) }
            }
        }
        assertEquals(12, brutos.size)
        brutos.forEach { (id, padrao) ->
            assertTrue("$id: $padrao deve começar com ^", padrao.startsWith("^"))
            val regex = try {
                Regex(padrao)
            } catch (e: Exception) {
                fail("$id: regex inválida $padrao (${e.message})")
                return
            }
            assertFalse("$id: $padrao casa com vazio", regex.matches(""))
            // O prefixo fixo cabe nos 9 primeiros caracteres (WMI + VDS).
            assertTrue("$id: $padrao longo demais", padrao.removePrefix("^").replace(Regex("\\[[^\\]]*\\]"), "X").length <= 9)
        }
    }

    @Test
    fun `padrao de VIN invalido derruba o arquivo com mensagem clara`() {
        val json = """{"veiculos":[{"id":"x","titulo":"X","montadora":"X","modelo":"X","motorId":"m","cambioId":"c",
            "status":"base","padroesVin":["^8AJ[ZZ"],"deteccao":{"marcas":["x"],"modelos":["x"]}}]}"""
        try {
            CatalogoPerfis.veiculosDeJson(json)
            fail("regex inválida deveria falhar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, "x: padrão de VIN inválido" in e.message.orEmpty())
        }
        // Prefixo sem ^ vira prefixo literal.
        assertTrue(CatalogoPerfis.padraoVin("x", "9BD363").containsMatchIn("9BD363ABC"))
        assertFalse(CatalogoPerfis.padraoVin("x", "9BD363").containsMatchIn("X9BD363"))
    }

    @Test
    fun `match exato por VDS - Toyota SW4`() {
        assertEquals("toyota_sw4_1kd", idPorVds("8AJFZ29G4D6012345")) // 2013
        assertEquals("toyota_sw4_1kd", idPorVds("8AJYZ59G2B6012345")) // 2011
        assertEquals("toyota_sw4_1kd", idPorVds("8AJFR22G5C6012345")) // 2012
        assertNull("2018 está fora de 2005–2015", idPorVds("8AJFZ29G0J6012345"))
    }

    @Test
    fun `match exato por VDS - Chevrolet Cruze`() {
        assertEquals("chevrolet_cruze_14t", idPorVds("8AGBN69S0JR104567")) // 2018
        assertEquals("chevrolet_cruze_14t", idPorVds("8AGBT49S2KR104567")) // 2019
        assertNull("2016 é anterior ao Cruze 1.4T (2017+)", idPorVds("8AGBN69S0GR104567"))
        assertNull("WMI 8AG sem a série B não é Cruze", idPorVds("8AGXN69S0JR104567"))
    }

    @Test
    fun `match exato por VDS - VW TSI`() {
        assertEquals("vw_tsi", idPorVds("9BWAG5BZ9KP012345")) // Polo 2019
        assertEquals("vw_tsi", idPorVds("9BWAJ5BZ1LP012345")) // 2020
        assertEquals("vw_tsi", idPorVds("9BWDB5BZ2MP012345")) // Virtus/T-Cross 2021
        assertEquals("vw_tsi", idPorVds("9BWDE5BZ3NP012345")) // 2022
        assertNull("o Gol/Fox de teste (9BWZZZ) não é TSI", idPorVds("9BWZZZ377VT004251"))
        assertNull("2017 é anterior ao TSI (2018+)", idPorVds("9BWAG5BZ9HP012345"))
    }

    @Test
    fun `match exato por VDS - Fiat T200 e Honda`() {
        assertEquals("fiat_t200", idPorVds("9BD363A28NY012345")) // Pulse 2022
        assertEquals("fiat_t200", idPorVds("9BD376A21PY012345")) // Fastback 2023
        assertNull("antes de 2022 não é T200", idPorVds("9BD363A21LY012345"))
        assertEquals("honda_l15", idPorVds("93HFC2650KZ012345")) // Civic 2019
        assertEquals("honda_l15", idPorVds("93HRU5870MZ012345")) // HR-V 2021
        assertEquals("honda_l15", idPorVds("93HGM2660NZ012345")) // City 2022
    }

    @Test
    fun `ano-modelo invalido nao restringe o match e VIN nulo nao casa`() {
        // 10º caractere "0" não é código de ano: o padrão decide sozinho.
        assertEquals("toyota_sw4_1kd", idPorVds("8AJFZ29G406012345"))
        assertNull(catalogo.detectarPorVin(null))
        assertNull(DecodificadorVin.decodificar("123", 2026))
    }

    @Test
    fun `decodificador extrai WMI VDS e ano-modelo`() {
        val d = vin("8AGBN69S0JR104567")!!
        assertEquals("8AG", d.wmi)
        assertEquals("BN69S0", d.vds)
        assertEquals("8AGBN69S0", d.wmiVds)
        assertEquals(2018, d.anoModelo)
    }

    @Test
    fun `cada VIN de exemplo casa com um unico perfil`() {
        listOf("8AJFZ29G4D6012345", "8AGBN69S0JR104567", "9BWAG5BZ9KP012345", "9BD363A28NY012345", "93HFC2650KZ012345").forEach { v ->
            val casam = catalogo.presets.filter { !it.generico && it.perfil.casaVin(v) }
            assertEquals("$v casou com ${casam.map { it.id }}", 1, casam.size)
        }
    }

    // ---------------------------------------------------------------- 0151

    @Test
    fun `decodifica o tipo de combustivel do 0151`() {
        assertEquals(CombustivelEcu.GASOLINA, CombustivelEcu.deByte(0x01))
        assertEquals(CombustivelEcu.ETANOL, CombustivelEcu.deByte(0x03))
        assertEquals(CombustivelEcu.DIESEL, CombustivelEcu.deByte(0x04))
        assertEquals(CombustivelEcu.FLEX, CombustivelEcu.deByte(0x09))
        assertEquals(CombustivelEcu.FLEX, CombustivelEcu.deByte(0x0B))
        assertEquals(CombustivelEcu.HIBRIDO, CombustivelEcu.deByte(0x11))
        assertEquals(CombustivelEcu.HIBRIDO_DIESEL, CombustivelEcu.deByte(0x13))
        assertEquals(CombustivelEcu.ELETRICO, CombustivelEcu.deByte(0x08))
        assertEquals(CombustivelEcu.DESCONHECIDO, CombustivelEcu.deByte(0x00))
        assertEquals(CombustivelEcu.DESCONHECIDO, CombustivelEcu.deByte(0x7E))
        assertEquals(CombustivelEcu.DESCONHECIDO, CombustivelEcu.deByte(0xFF))
        assertEquals(ClasseCombustivel.DIESEL, CombustivelEcu.DIESEL.classe)
        assertEquals(ClasseCombustivel.GASOLINA_FLEX, CombustivelEcu.FLEX.classe)
        assertEquals("Diesel (04)", ImpressaoEcu(codigoCombustivel = 4).descricaoCombustivel)
        assertNull(ImpressaoEcu.VAZIA.descricaoCombustivel)
    }

    // ---------------------------------------------------------------- cascata

    private fun impressao(codigo: Int?) = ImpressaoEcu(codigoCombustivel = codigo)

    private fun resolver(
        vin: String?,
        codigo: Int?,
        suportados: Set<String> = emptySet(),
        map: Double? = null,
        cadastroGenerico: Boolean = true,
    ) = IdentificadorPerfil.resolver(catalogo, cadastroGenerico, vin?.let(::vin), impressao(codigo), suportados, map)

    @Test
    fun `cascata - VDS vence a sondagem`() {
        val r = resolver("8AGBN69S0JR104567", codigo = 0x04)!! // a ECU diz diesel, mas o chassi é de Cruze
        assertEquals("chevrolet_cruze_14t", r.preset.id)
        assertEquals(OrigemPerfil.VDS_VIN, r.origem)
        assertTrue(r.motivo, "8AGBN69S0" in r.motivo)
    }

    @Test
    fun `cascata - sem VDS o 0151 diesel aplica o generico diesel`() {
        val r = resolver("9BWZZZ377VT004251", codigo = 0x04)!!
        assertEquals("generico_diesel_cr", r.preset.id)
        assertEquals(OrigemPerfil.SONDAGEM_ECU, r.origem)
        assertEquals("generico_diesel_cr", resolver(null, codigo = 0x13)!!.preset.id)
    }

    @Test
    fun `cascata - gasolina ou flex com 0123 ou MAP acima de 115 kPa aplica o generico turbo`() {
        assertEquals("generico_flex_turbo", resolver(null, 0x01, suportados = setOf("010C", "0123"))!!.preset.id)
        assertEquals("generico_flex_turbo", resolver(null, 0x09, suportados = setOf("0123"))!!.preset.id)
        assertEquals("generico_flex_turbo", resolver(null, 0x09, map = 140.0)!!.preset.id)
        assertEquals("generico_flex_turbo", resolver(null, 0x03, map = 115.5)!!.preset.id)
        assertEquals(OrigemPerfil.SONDAGEM_ECU, resolver(null, 0x09, map = 140.0)!!.origem)
    }

    @Test
    fun `cascata - caso contrario aplica o generico aspirado`() {
        assertEquals("generico_flex_aspirado", resolver(null, 0x01, suportados = setOf("010C"))!!.preset.id)
        assertEquals("generico_flex_aspirado", resolver(null, 0x09, map = 115.0)!!.preset.id) // 115 não passa de 115
        assertEquals("generico_flex_aspirado", resolver(null, 0x09, map = 98.0)!!.preset.id)
        assertEquals("generico_flex_aspirado", resolver(null, 0x7E)!!.preset.id) // código desconhecido
    }

    @Test
    fun `cascata - NO DATA no 0151 nunca troca o cadastro nem falha`() {
        assertNull(resolver(null, codigo = null, suportados = setOf("0123"), map = 150.0))
        assertNull(resolver("9BWZZZ377VT004251", codigo = null))
        assertNull(IdentificadorPerfil.resolver(catalogo, true, null, ImpressaoEcu.VAZIA, emptySet()))
    }

    @Test
    fun `cascata - perfil exato do cadastro nunca e trocado`() {
        assertNull(resolver("8AGBN69S0JR104567", codigo = 0x04, cadastroGenerico = false))
        assertNull(resolver(null, codigo = 0x09, suportados = setOf("0123"), cadastroGenerico = false))
    }

    @Test
    fun `refino tardio por MAP promove so o aspirado e so acima de 115 kPa`() {
        val aspirado = catalogo[IdentificadorPerfil.ID_FLEX_ASPIRADO]
        assertNotNull(aspirado)
        assertEquals("generico_flex_turbo", IdentificadorPerfil.refinarPorMap(catalogo, aspirado, 130.0)!!.preset.id)
        assertNull(IdentificadorPerfil.refinarPorMap(catalogo, aspirado, 115.0))
        assertNull(IdentificadorPerfil.refinarPorMap(catalogo, aspirado, null))
        assertNull(IdentificadorPerfil.refinarPorMap(catalogo, catalogo[IdentificadorPerfil.ID_DIESEL], 200.0))
        assertNull(IdentificadorPerfil.refinarPorMap(catalogo, catalogo["chevrolet_cruze_14t"], 200.0))
        assertNull(IdentificadorPerfil.refinarPorMap(catalogo, null, 200.0))
    }
}
