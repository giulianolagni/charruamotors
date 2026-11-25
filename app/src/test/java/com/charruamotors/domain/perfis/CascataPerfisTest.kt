package com.charruamotors.domain.perfis

import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.EstimadorMarchaPerfil
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.NivelPerfil
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.faixas.ResolucaoPerfil
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.2: perfis base do catálogo, cascata exato → genérico → OBD2 padrão e sugestões pelo VIN. */
class CascataPerfisTest {

    private val catalogo = CarregadorPerfis.catalogo

    @Test
    fun `catalogo lista os modelos pedidos com o selo`() {
        val ids = PresetVeiculo.entries.map { it.id }
        assertEquals(
            listOf(
                "toyota_sw4_1kd", "chevrolet_cruze_14t", "chevrolet_onix_tracker_turbo", "vw_tsi", "fiat_t200", "honda_l15",
                "generico_flex_aspirado", "generico_flex_turbo", "generico_diesel_cr",
            ),
            ids,
        )
        assertEquals(listOf("toyota_sw4_1kd"), PresetVeiculo.entries.filter { it.status == StatusPerfil.VALIDADO }.map { it.id })
        assertEquals("2005–2015", PresetVeiculo.SW4_2013.perfil.faixaAnos)
        assertEquals("2017–2023", PresetVeiculo.CRUZE_2018.perfil.faixaAnos)
    }

    @Test
    fun `perfis base reconhecidos pelo cadastro`() {
        fun id(marca: String, modelo: String, ano: Int) = PresetVeiculo.detectar(marca, modelo, ano)?.id
        assertEquals("vw_tsi", id("Volkswagen", "Polo 1.0 TSI", 2022))
        assertEquals("vw_tsi", id("VW", "Virtus 200 TSI", 2021))
        assertEquals("vw_tsi", id("VW - Volkswagen", "T-Cross 250 TSI", 2023))
        assertEquals("vw_tsi", id("Volkswagen", "Nivus Highline TSI", 2024))
        assertNull(id("Volkswagen", "Polo 1.6 MSI", 2020)) // aspirado
        assertEquals("fiat_t200", id("Fiat", "Pulse T200", 2023))
        assertEquals("fiat_t200", id("FIAT", "Fastback Turbo 200", 2024))
        assertNull(id("Fiat", "Pulse Drive 1.3", 2022))
        assertEquals("honda_l15", id("Honda", "City EXL", 2022))
        assertEquals("honda_l15", id("Honda", "HR-V EX", 2023))
        assertNull(id("Honda", "Civic Touring 1.5 Turbo", 2020))
        assertEquals("chevrolet_onix_tracker_turbo", id("Chevrolet", "Onix 1.0 Turbo", 2021))
        assertEquals("chevrolet_onix_tracker_turbo", id("Chevrolet", "Tracker 1.2T Premier", 2022))
        assertNull(id("Hyundai", "HB20 1.0", 2020))
    }

    @Test
    fun `perfis base com as faixas pedidas`() {
        fun faixa(id: String, p: ParametroFaixa) = catalogo[id]!!.faixas[p]!!.let { it.min to it.max }
        assertEquals("ATSP6", catalogo["vw_tsi"]!!.protocolo)
        assertEquals(25.0 to 220.0, faixa("vw_tsi", ParametroFaixa.MAP_TURBO))
        assertEquals(30.0 to 200.0, faixa("vw_tsi", ParametroFaixa.RAIL))
        assertEquals(85.0 to 105.0, faixa("vw_tsi", ParametroFaixa.TEMP_AGUA))
        assertEquals("ATSP6", catalogo["fiat_t200"]!!.protocolo)
        assertEquals(25.0 to 210.0, faixa("fiat_t200", ParametroFaixa.MAP_TURBO))
        assertEquals(30.0 to 200.0, faixa("fiat_t200", ParametroFaixa.RAIL))
        assertEquals(85.0 to 105.0, faixa("fiat_t200", ParametroFaixa.TEMP_AGUA))
        assertEquals("ATSP6", catalogo["honda_l15"]!!.protocolo)
        assertEquals(25.0 to 105.0, faixa("honda_l15", ParametroFaixa.MAP_TURBO))
        assertEquals(82.0 to 100.0, faixa("honda_l15", ParametroFaixa.TEMP_AGUA))
        assertNull(catalogo["honda_l15"]!!.faixas[ParametroFaixa.RAIL])
        assertEquals("ECU do motor 7E0 · TCM do câmbio 7E1 · serviço 22", catalogo["vw_tsi"]!!.linhasRede.last())
        assertEquals("ECU do motor 7E0 · câmbio sem TCM · OBD2 modo 01", catalogo["generico_flex_turbo"]!!.linhasRede.last())
    }

    @Test
    fun `cascata exato generico e OBD2 padrao`() {
        val limites = LimitesProtecao(aguaMaxC = 102.0, atfMaxC = 110.0, tensaoMinV = 13.0)
        val exato = ResolucaoPerfil.resolver("Volkswagen", "Polo TSI", 2022, TipoCombustivel.FLEX, limites)
        assertEquals(NivelPerfil.EXATO, exato.nivel)
        assertFalse(exato.generico)
        assertEquals(105.0, exato.faixas[ParametroFaixa.TEMP_AGUA]!!.max!!, 0.0) // o do perfil

        val turbo = ResolucaoPerfil.resolver("Peugeot", "208 1.0 Turbo", 2023, TipoCombustivel.FLEX, limites)
        assertEquals(NivelPerfil.GENERICO, turbo.nivel)
        assertEquals("generico_flex_turbo", turbo.preset!!.id)
        assertEquals("Genérico · Flex Turbo (limites do cadastro)", turbo.faixas.perfil)
        assertEquals(80.0 to 102.0, turbo.faixas[ParametroFaixa.TEMP_AGUA]!!.let { it.min to it.max!! })
        assertEquals(50.0 to 110.0, turbo.faixas[ParametroFaixa.TEMP_ATF]!!.let { it.min to it.max!! })
        assertEquals(13.0 to 14.8, turbo.faixas[ParametroFaixa.TENSAO]!!.let { it.min to it.max })
        assertEquals(20.0 to 250.0, turbo.faixas[ParametroFaixa.MAP_TURBO]!!.let { it.min to it.max })

        assertEquals("generico_flex_aspirado", ResolucaoPerfil.resolver("Fiat", "Argo 1.3", 2020, TipoCombustivel.FLEX).preset!!.id)
        assertEquals("generico_flex_aspirado", ResolucaoPerfil.resolver("Fiat", "Uno", 2012, TipoCombustivel.GASOLINA).preset!!.id)
        val diesel = ResolucaoPerfil.resolver("Ford", "Ranger 3.2", 2018, TipoCombustivel.DIESEL)
        assertEquals("generico_diesel_cr", diesel.preset!!.id)
        assertNull(diesel.faixas[ParametroFaixa.STFT]) // sem correções de ciclo Otto

        // Limite do cadastro abaixo do mínimo do genérico: a faixa fica só com o máximo, nunca inválida.
        val baixo = ResolucaoPerfil.resolver("Fiat", "Argo", 2020, TipoCombustivel.FLEX, LimitesProtecao(aguaMaxC = 78.0))
        assertEquals(null to 78.0, baixo.faixas[ParametroFaixa.TEMP_AGUA]!!.let { it.min to it.max })
    }

    @Test
    fun `sem catalogo cai no OBD2 padrao com as faixas da v2_1_5`() {
        val original = CarregadorPerfis.catalogo
        try {
            CarregadorPerfis.usar(CatalogoPerfis.VAZIO)
            val r = ResolucaoPerfil.resolver("Toyota", "Hilux SW4 3.0", 2013, TipoCombustivel.DIESEL)
            assertEquals(NivelPerfil.OBD2_PADRAO, r.nivel)
            assertNull(r.preset)
            assertEquals(FaixasVeiculo.genericas(TipoCombustivel.DIESEL), r.faixas)
            assertTrue(PresetVeiculo.entries.isEmpty())
        } finally {
            CarregadorPerfis.usar(original)
        }
    }

    @Test
    fun `sugestoes pelo VIN para a montadora e o ano`() {
        val gm2018 = catalogo.sugestoesPara("Chevrolet", 2018).map { it.id }
        assertEquals(listOf("chevrolet_cruze_14t"), gm2018) // Onix/Tracker Turbo só a partir de 2020
        assertEquals(listOf("chevrolet_cruze_14t", "chevrolet_onix_tracker_turbo"), catalogo.sugestoesPara("Chevrolet", 2021).map { it.id })
        assertEquals(listOf("toyota_sw4_1kd"), catalogo.sugestoesPara("Toyota", 2013).map { it.id })
        assertTrue(catalogo.sugestoesPara("Toyota", 2020).isEmpty())
        assertTrue(catalogo.sugestoesPara("Hyundai", 2020).isEmpty())
        assertTrue(catalogo.sugestoesPara(null, 2020).isEmpty())
    }

    @Test
    fun `estimador de marcha pelo perfil Aisin de 6 marchas`() {
        val aisin = catalogo["vw_tsi"]!!.perfilCambio
        val e = EstimadorMarchaPerfil(aisin)
        fun marcha(rpm: Double, v: Double) =
            e.aplicar(DadosObd(mapOf(Pids.RPM to rpm, Pids.VELOCIDADE to v))).valores[CambioA750f.CHAVE_MARCHA]
        assertEquals(1.0, marcha(2_500.0, 20.0)!!, 0.0) // 125
        assertEquals(3.0, marcha(2_000.0, 40.0)!!, 0.0) // 50
        assertEquals(6.0, marcha(2_100.0, 110.0)!!, 0.0) // 19
        assertEquals(CambioA750f.MARCHA_PARADO.toDouble(), marcha(800.0, 0.0)!!, 0.0)
        // Marcha vinda da ECU não é sobrescrita; sem velocidade, nada muda.
        val daEcu = DadosObd(mapOf(Pids.RPM to 2_000.0, Pids.VELOCIDADE to 40.0, CambioA750f.CHAVE_MARCHA to 4.0))
        assertEquals(daEcu, e.aplicar(daEcu))
        val semVelocidade = DadosObd(mapOf(Pids.RPM to 2_000.0))
        assertEquals(semVelocidade, e.aplicar(semVelocidade))
        // CVT e manual não estimam marcha.
        assertFalse(catalogo["honda_l15"]!!.perfilCambio.estimaMarcha)
        assertFalse(catalogo["generico_flex_aspirado"]!!.perfilCambio.estimaMarcha)
    }

    @Test
    fun `texto do cadastro marca e turbo`() {
        assertTrue(TextoPerfis.marcaCorresponde("Chevrolet / GM", listOf("gm")))
        assertTrue(TextoPerfis.marcaCorresponde("VW-Volkswagen", listOf("volkswagen")))
        assertFalse(TextoPerfis.marcaCorresponde("Gurgel", listOf("gm")))
        assertFalse(TextoPerfis.marcaCorresponde("", listOf("gm")))
        listOf("Polo TSI", "208 Turbo", "Pulse T200", "Tracker 1.2T", "Cruze 1,4 t", "Ranger EcoBoost", "308 THP").forEach {
            assertTrue(it, TextoPerfis.indicaTurbo(it))
        }
        listOf("Gol 1.6", "Argo 1.3 Firefly", "Uno", "HB20 1.0").forEach { assertFalse(it, TextoPerfis.indicaTurbo(it)) }
    }
}
