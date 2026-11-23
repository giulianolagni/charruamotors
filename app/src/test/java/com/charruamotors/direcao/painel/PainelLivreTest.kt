package com.charruamotors.direcao.painel

import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import com.charruamotors.obd.DadosObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7: Painel livre — mostradores sem o limite de 4, estilo individual, atalhos reordenáveis e o km/L como mostrador. */
class PainelLivreTest {

    private val padrao = ConfigPainel()

    // ---------------------------------------------------------------- mais de 4 mostradores

    @Test
    fun `permite bem mais de 4 mostradores e a grade segue em 2 colunas`() {
        val dez = (1..6).fold(padrao) { c, _ -> c.adicionarMostrador() }
        assertEquals(10, dez.mostradores.size)
        assertEquals(10, dez.mostradores.map { it.sensor }.distinct().size)
        val linhas = GradeMostradores.linhas(dez.mostradores)
        assertEquals(List(5) { listOf(2 * it, 2 * it + 1) }, linhas)
        assertTrue(linhas.all { it.size <= GradeMostradores.COLUNAS })
    }

    @Test
    fun `o consumo em km por litro entra como mostrador do painel`() {
        val config = padrao.definirMostrador(0, SensorPainel.CONSUMO_KML)
        assertEquals(SensorPainel.CONSUMO_KML, config.mostradores[0].sensor)
        assertEquals(SensorPainel.CONSUMO_KML, CodecPainel.decodificar(CodecPainel.codificar(config)::get).mostradores[0].sensor)
    }

    // ---------------------------------------------------------------- estilo individual

    @Test
    fun `cada mostrador tem estilo proprio entre esportivo digital e fita`() {
        val config = padrao.adicionarMostrador().adicionarMostrador()
            .definirEstilo(0, EstiloMostrador.ESPORTIVO)
            .definirEstilo(1, EstiloMostrador.DIGITAL)
            .definirEstilo(2, EstiloMostrador.FITA)
        assertEquals(
            listOf(EstiloMostrador.ESPORTIVO, EstiloMostrador.DIGITAL, EstiloMostrador.FITA),
            config.mostradores.take(3).map { it.estilo },
        )
        assertEquals("Relógio Esportivo", EstiloMostrador.ESPORTIVO.titulo)
        assertEquals("Digital Grande", EstiloMostrador.DIGITAL.titulo)
        assertEquals("Barra / Fita", EstiloMostrador.FITA.titulo)
        val volta = CodecPainel.decodificar(CodecPainel.codificar(config)::get)
        assertEquals(config.mostradores, volta.mostradores)
    }

    // ---------------------------------------------------------------- atalhos externos

    @Test
    fun `waze maps e spotify sao atalhos que se escondem e se mostram`() {
        assertTrue(listOf(BotaoNavegacao.WAZE, BotaoNavegacao.MAPS, BotaoNavegacao.SPOTIFY).all(padrao::botaoVisivel))
        val semMaps = padrao.alternarBotaoNavegacao(BotaoNavegacao.MAPS)
        assertFalse(BotaoNavegacao.MAPS in semMaps.botoesVisiveis)
        assertTrue(BotaoNavegacao.SPOTIFY in semMaps.botoesVisiveis)
        assertTrue(BotaoNavegacao.MAPS in semMaps.alternarBotaoNavegacao(BotaoNavegacao.MAPS).botoesVisiveis)
    }

    @Test
    fun `atalhos podem ser reordenados e os escondidos vao para o fim`() {
        assertEquals(BotaoNavegacao.entries, padrao.botoesVisiveis)
        val movido = padrao.moverBotao(0, 2)
        assertEquals(listOf(BotaoNavegacao.MAPS, BotaoNavegacao.SPOTIFY, BotaoNavegacao.WAZE), movido.botoesVisiveis.take(3))
        // Escondido não ocupa lugar na ordem visível, mas guarda o seu para quando voltar.
        val semMaps = movido.alternarBotaoNavegacao(BotaoNavegacao.MAPS)
        assertEquals(BotaoNavegacao.SPOTIFY, semMaps.botoesVisiveis.first())
        // Ao voltar, o Maps reaparece onde estava na ordem guardada.
        assertEquals(movido.botoesVisiveis, semMaps.alternarBotaoNavegacao(BotaoNavegacao.MAPS).botoesVisiveis)
        // Índices inválidos não mudam nada.
        assertEquals(padrao, padrao.moverBotao(0, 99))
        assertEquals(padrao, padrao.moverBotao(3, 3))
    }

    @Test
    fun `ordem dos atalhos persiste e preferencias antigas continuam validas`() {
        val config = padrao.moverBotao(5, 0).alternarBotaoNavegacao(BotaoNavegacao.OFICINA)
        val valores = CodecPainel.codificar(config)
        val volta = CodecPainel.decodificar(valores::get)
        assertEquals(config.botoesVisiveis, volta.botoesVisiveis)
        assertEquals(BotaoNavegacao.HUD, volta.botoesVisiveis.first())
        assertFalse(volta.botaoVisivel(BotaoNavegacao.OFICINA))

        // v1.5/v1.6: sem ordem_botoes e com só 4 botões salvos → ordem padrão, Maps e Spotify escondidos até ligar.
        val antigo = mapOf(CodecPainel.BOTOES_NAVEGACAO to "WAZE,POSTO,OFICINA,HUD")
        val migrado = CodecPainel.decodificar(antigo::get)
        assertEquals(listOf(BotaoNavegacao.WAZE, BotaoNavegacao.POSTO, BotaoNavegacao.OFICINA, BotaoNavegacao.HUD), migrado.botoesVisiveis)
        // Valores corrompidos ou repetidos são normalizados.
        val sujo = mapOf(CodecPainel.ORDEM_BOTOES to "HUD,HUD,XYZ,WAZE")
        assertEquals(BotaoNavegacao.entries.size, CodecPainel.decodificar(sujo::get).ordemBotoes.size)
        assertEquals(BotaoNavegacao.HUD, CodecPainel.decodificar(sujo::get).ordemBotoes.first())
    }

    @Test
    fun `os blocos do painel continuam todos reordenaveis e removiveis`() {
        val reordenado = padrao.mover(0, 3)
        assertEquals(SecaoPainel.MUSICA, reordenado.secoesVisiveis[3])
        val sem = reordenado.ocultar(SecaoPainel.MUSICA)
        assertFalse(SecaoPainel.MUSICA in sem.secoesVisiveis)
        assertTrue(SecaoPainel.MUSICA in sem.restaurar(SecaoPainel.MUSICA).secoesVisiveis)
    }

    // ---------------------------------------------------------------- mostrador de consumo

    @Test
    fun `km por litro vem do computador de bordo e some parado`() {
        val consumo = SensorPainel.CONSUMO_KML
        assertEquals(11.4, consumo.ler(null, 11.4)!!, 0.0)
        assertNull(consumo.ler(DadosObd(mapOf("CALC_KML" to 9.0)), null)) // só vale o que o computador calculou
        assertEquals(SensorPainel.MAX_KM_L, consumo.ler(null, 450.0)!!, 0.0) // banguela não estoura o relógio
        assertNull(consumo.ler(null, Double.NaN))
        // Os demais sensores continuam lendo a telemetria.
        assertEquals(2300.0, SensorPainel.RPM.ler(DadosObd(mapOf("010C" to 2300.0)), 11.4)!!, 0.0)
    }

    @Test
    fun `km por litro exibe com uma casa e converte para MPG no sistema imperial`() {
        val metrico = ConversorUnidades()
        assertEquals("km/L", SensorPainel.CONSUMO_KML.unidade(metrico))
        assertEquals("11,4", SensorPainel.CONSUMO_KML.texto(11.4, metrico).replace('.', ','))
        assertEquals("—", SensorPainel.CONSUMO_KML.texto(null, metrico))
        val imperial = ConversorUnidades(SistemaUnidades.IMPERIAL)
        assertEquals("MPG", SensorPainel.CONSUMO_KML.unidade(imperial))
        assertEquals(11.4 * 3.785411784 / 1.609344, SensorPainel.CONSUMO_KML.exibir(11.4, imperial).valor, 1e-9)
        assertTrue(SensorPainel.CONSUMO_KML.escala(imperial).maximo > SensorPainel.CONSUMO_KML.escala(metrico).maximo)
    }
}
