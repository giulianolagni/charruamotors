package com.charruamotors.direcao.hud

import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.direcao.painel.TamanhoHudEsportivo
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Decodificadores
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.2.7: preset Esportivo do HUD flutuante (conta-giros + turbo + pedal) e a escolha persistida. */
class HudEsportivoTest {

    private class Memoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            this.valores.putAll(valores)
        }
    }

    private fun dados(vararg pares: Pair<String, Double>) = DadosObd(mapOf(*pares))

    // ---------------------------------------------------------------- preset persistido

    @Test
    fun `padrao continua sendo o visual atual e a escolha sobrevive a reabertura`() {
        assertEquals(PresetHud.PADRAO, ConfigHud().preset)
        assertEquals("Padrão (Atual)", PresetHud.PADRAO.titulo)
        assertEquals("Esportivo (Conta-giros)", PresetHud.ESPORTIVO.titulo)

        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        assertEquals(PresetHud.PADRAO, prefs.config.value.hud.preset)
        prefs.definirPresetHud(PresetHud.ESPORTIVO)
        assertEquals("ESPORTIVO", memoria.valores[CodecPainel.HUD_PRESET])
        assertEquals(PresetHud.ESPORTIVO, PreferenciasPainel(memoria).config.value.hud.preset)

        // O atalho de dois toques alterna e também salva; o resto da config do HUD fica igual.
        prefs.definirHud(prefs.config.value.hud.copy(tamanho = TamanhoHud.MINIMIZADO))
        assertEquals(PresetHud.PADRAO, prefs.alternarPresetHud())
        val reaberto = PreferenciasPainel(memoria).config.value.hud
        assertEquals(PresetHud.PADRAO, reaberto.preset)
        assertEquals(TamanhoHud.MINIMIZADO, reaberto.tamanho)
        assertEquals(PresetHud.ESPORTIVO, prefs.alternarPresetHud())
    }

    @Test
    fun `preferencias antigas ou corrompidas abrem no padrao`() {
        val semChave = CodecPainel.codificar(ConfigPainel()) - CodecPainel.HUD_PRESET
        assertEquals(PresetHud.PADRAO, CodecPainel.decodificar { semChave[it] }.hud.preset)
        val lixo = CodecPainel.codificar(ConfigPainel()) + (CodecPainel.HUD_PRESET to "DRAGAO")
        assertEquals(PresetHud.PADRAO, CodecPainel.decodificar { lixo[it] }.hud.preset)
        val esportivo = CodecPainel.codificar(ConfigPainel(hud = ConfigHud(preset = PresetHud.ESPORTIVO)))
        assertEquals(PresetHud.ESPORTIVO, CodecPainel.decodificar { esportivo[it] }.hud.preset)
    }

    // ---------------------------------------------------------------- tamanho P / M / G (v2.2.8)

    @Test
    fun `tamanhos do esportivo mapeiam para 230 285 e 330 dp com medio como padrao`() {
        assertEquals(230, TamanhoHudEsportivo.PEQUENO.larguraDp)
        assertEquals(285, TamanhoHudEsportivo.MEDIO.larguraDp)
        assertEquals(330, TamanhoHudEsportivo.GRANDE.larguraDp)
        assertEquals(TamanhoHudEsportivo.MEDIO, ConfigHud().tamanhoEsportivo)
    }

    @Test
    fun `tamanho do esportivo e persistido e preferencias antigas abrem no medio`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        prefs.definirHud(prefs.config.value.hud.copy(preset = PresetHud.ESPORTIVO, tamanhoEsportivo = TamanhoHudEsportivo.GRANDE))
        assertEquals("GRANDE", memoria.valores[CodecPainel.HUD_TAMANHO_ESPORTIVO])
        assertEquals(TamanhoHudEsportivo.GRANDE, PreferenciasPainel(memoria).config.value.hud.tamanhoEsportivo)

        val semChave = CodecPainel.codificar(ConfigPainel()) - CodecPainel.HUD_TAMANHO_ESPORTIVO
        assertEquals(TamanhoHudEsportivo.MEDIO, CodecPainel.decodificar { semChave[it] }.hud.tamanhoEsportivo)
        val lixo = CodecPainel.codificar(ConfigPainel()) + (CodecPainel.HUD_TAMANHO_ESPORTIVO to "ENORME")
        assertEquals(TamanhoHudEsportivo.MEDIO, CodecPainel.decodificar { lixo[it] }.hud.tamanhoEsportivo)
    }

    // ---------------------------------------------------------------- pedal auto-calibrado

    @Test
    fun `repouso do pedal vira 0 e o teto sobe de 15 para 40 e fica fixo`() {
        val c = CalibradorPedal()
        assertEquals(0f, c.registrar(0.0)!!, 0f) // repouso
        assertEquals(1f, c.registrar(15.0)!!, 1e-6f) // o maior até agora enche a barra
        assertEquals(0.5f, c.registrar(7.5)!!, 1e-6f)
        assertEquals(1f, c.registrar(40.0)!!, 1e-6f) // novo 100 % fixo
        assertEquals(0.375f, c.registrar(15.0)!!, 1e-6f) // 15 / 40
        assertEquals(40.0, c.pedalMax!!, 0.0)
        assertEquals(1f, c.registrar(40.0)!!, 1e-6f)
        assertEquals(0f, c.registrar(0.0)!!, 0f)
    }

    @Test
    fun `repouso da SW4 em 16,1 por cento e o zero`() {
        val c = CalibradorPedal()
        assertEquals(0f, c.registrar(16.1)!!, 0f)
        assertEquals(16.1, c.pedalMin!!, 1e-9)
        assertEquals(1f, c.registrar(56.1)!!, 1e-6f)
        assertEquals(0.5f, c.registrar(36.1)!!, 1e-6f)
    }

    @Test
    fun `ruido parado abaixo de 1 ponto nao vira teto`() {
        val c = CalibradorPedal()
        c.registrar(16.1)
        assertEquals(0f, c.registrar(16.6)!!, 0f)
        assertEquals(0f, c.registrar(17.0)!!, 0f) // 0,9 acima do repouso
        assertNull(c.pedalMax)
        assertEquals(1f, c.registrar(17.2)!!, 1e-6f) // 1,1 acima: pisou
        assertNull(c.registrar(null))
        assertNull(c.registrar(Double.NaN))
        c.reiniciar()
        assertNull(c.pedalMin)
    }

    @Test
    fun `pedal vem do Toyota, do 0149 ou da borboleta`() {
        val faixas = PresetVeiculo.SW4_2013.faixas
        val toyota = FormatadorHudEsportivo.montar(dados("TOYOTA_PEDAL_D" to 16.1, "0149" to 50.0), true, faixas, CalibradorPedal())
        assertEquals(0f, toyota.pedal!!, 0f)
        val borboleta = CalibradorPedal()
        FormatadorHudEsportivo.montar(dados(Pids.BORBOLETA to 10.0), true, null, borboleta)
        assertEquals(10.0, borboleta.pedalMin!!, 0.0)
        assertNull(FormatadorHudEsportivo.montar(dados(Pids.RPM to 800.0), true, null, CalibradorPedal()).pedal)
    }

    // ---------------------------------------------------------------- turbo

    @Test
    fun `turbo comeca travado em 0 bar e desconta o barometro real`() {
        assertEquals(0.0, FormatadorHudEsportivo.boostBar(dados(Pids.PRESSAO_COLETOR to 75.0)), 0.0) // vácuo
        assertEquals(0.0, FormatadorHudEsportivo.boostBar(dados()), 0.0)
        assertEquals(0.0, FormatadorHudEsportivo.boostBar(null), 0.0)
        assertEquals(0.99, FormatadorHudEsportivo.boostBar(dados(Pids.PRESSAO_COLETOR to 200.0)), 1e-9) // 101 kPa
        assertEquals(1.1, FormatadorHudEsportivo.boostBar(dados(Pids.PRESSAO_COLETOR to 200.0, "TOYOTA_BARO" to 90.0)), 1e-9)
        assertEquals(1.07, FormatadorHudEsportivo.boostBar(dados(Pids.PRESSAO_COLETOR to 200.0, "0133" to 93.0)), 1e-9)
        // Aspirado em altitude: MAP abaixo do barômetro fica no 0,0.
        assertEquals(0.0, FormatadorHudEsportivo.boostBar(dados(Pids.PRESSAO_COLETOR to 88.0, "0133" to 90.0)), 0.0)
    }

    @Test
    fun `escala do turbo da SW4 vai de 0,0 a 1,2 com vermelho acima de 1,25`() {
        val sw4 = EscalaTurbo.para(215.0)
        assertEquals(listOf(0.0, 0.4, 0.8, 1.2), sw4.rotulos.map { Math.round(it * 10) / 10.0 })
        assertEquals(1.25, sw4.redlineBar, 1e-9)
        assertEquals(0f, sw4.fracao(-0.3), 0f)
        assertEquals(1f, sw4.fracao(3.0), 0f)
        assertEquals(sw4, EscalaTurbo.para(null))
        assertEquals(1.6, EscalaTurbo.para(250.0).rotuloMaximo, 1e-9)
    }

    // ---------------------------------------------------------------- conta-giros

    @Test
    fun `escala do conta-giros pelo redline do perfil`() {
        val sw4 = EscalaRpm.para(PresetVeiculo.SW4_2013.faixas[com.charruamotors.domain.faixas.ParametroFaixa.RPM_MAXIMO]!!.max)
        assertEquals(EscalaRpm(4_200.0, 6), sw4)
        assertEquals(0.7f, sw4.fracaoRedline, 1e-6f)
        assertEquals(6, EscalaRpm.para(5_500.0).fimMil)
        assertEquals(7, EscalaRpm.para(6_000.0).fimMil)
        assertEquals(8, EscalaRpm.para(6_500.0).fimMil)
        assertEquals(8, EscalaRpm.para(7_500.0).fimMil)
        assertEquals(7, EscalaRpm.para(null).fimMil)
        assertEquals(1f, sw4.fracao(9_000.0), 0f)
        assertEquals(0f, sw4.fracao(null), 0f)
    }

    @Test
    fun `shift light acende perto do redline`() {
        val faixas = PresetVeiculo.SW4_2013.faixas
        assertFalse(FormatadorHudEsportivo.montar(dados(Pids.RPM to 3_700.0), true, faixas, CalibradorPedal()).shiftLight)
        assertTrue(FormatadorHudEsportivo.montar(dados(Pids.RPM to 3_800.0), true, faixas, CalibradorPedal()).shiftLight)
    }

    // ---------------------------------------------------------------- marcha e KPH

    @Test
    fun `velocidade em 3 digitos KPH pelo GPS com fallback da ECU`() {
        assertEquals("KPH", FormatadorHudEsportivo.ROTULO_VELOCIDADE)
        assertEquals("087", FormatadorHudEsportivo.velocidade(dados(Pids.VELOCIDADE_GPS to 87.4, Pids.VELOCIDADE to 90.0)))
        assertEquals("090", FormatadorHudEsportivo.velocidade(dados(Pids.VELOCIDADE to 90.0)))
        assertEquals("000", FormatadorHudEsportivo.velocidade(dados(Pids.VELOCIDADE to 0.0)))
        assertEquals("118", FormatadorHudEsportivo.velocidade(dados(Pids.VELOCIDADE to 118.0)))
        assertEquals("---", FormatadorHudEsportivo.velocidade(dados()))
        assertEquals("---", FormatadorHudEsportivo.velocidade(null))
        val desconectado = FormatadorHudEsportivo.montar(dados(Pids.VELOCIDADE to 50.0), false, null, CalibradorPedal())
        assertEquals("---", desconectado.velocidade)
        assertEquals("–", desconectado.marcha)
    }

    @Test
    fun `marcha P R N numeros e reduzida`() {
        fun m(vararg pares: Pair<String, Double>) = FormatadorHudEsportivo.marcha(dados(*pares))
        val al = CambioA750f.CHAVE_ALAVANCA
        val ma = CambioA750f.CHAVE_MARCHA
        assertEquals("P", m(al to Decodificadores.ALAVANCA_P_N, Pids.VELOCIDADE to 0.0))
        assertEquals("N", m(al to Decodificadores.ALAVANCA_P_N, Pids.VELOCIDADE to 30.0))
        assertEquals("R", m(al to Decodificadores.ALAVANCA_R, ma to 254.0))
        assertEquals("P", m("TOYOTA_ALAVANCA_ECM" to Decodificadores.ALAVANCA_P))
        assertEquals("N", m(al to Decodificadores.ALAVANCA_N))
        for (n in 1..5) assertEquals("$n", m(al to Decodificadores.ALAVANCA_D, ma to n.toDouble(), Pids.VELOCIDADE to 40.0))
        for (n in 1..5) assertEquals("${n}L", m(al to Decodificadores.ALAVANCA_D, ma to (10 + n).toDouble(), Pids.VELOCIDADE to 10.0))
        assertEquals("1", m(al to Decodificadores.ALAVANCA_D, ma to 255.0, Pids.VELOCIDADE to 0.0)) // parado em D
        assertEquals("6", m(ma to 6.0, Pids.VELOCIDADE to 100.0)) // GM sem alavanca
        assertEquals("N", m(ma to 0.0))
        assertEquals("–", m())
    }
}
