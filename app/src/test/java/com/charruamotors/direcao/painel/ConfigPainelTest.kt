package com.charruamotors.direcao.painel

import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personalizar Painel 2.0: todos os blocos (ordem, remover e adicionar de volta), botões da
 * Navegação, lista dinâmica de mostradores com estilo individual, lista rápida e HUD ordenáveis e
 * a persistência (inclusive a migração das preferências da v1.4).
 */
class ConfigPainelTest {

    private class Memoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        var gravacoes = 0
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            gravacoes++
            this.valores.putAll(valores)
        }
    }

    private val padrao = ConfigPainel()

    // ---------------------------------------------------------------- blocos: ordem

    @Test
    fun `todos os blocos do painel entram na lista arrastavel`() {
        assertEquals(SecaoPainel.entries.toSet(), padrao.secoesVisiveis.toSet())
        assertEquals(8, padrao.secoesVisiveis.size)
        assertEquals(listOf(SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO, SecaoPainel.MARCADORES), padrao.secoesVisiveis.take(3))
        assertTrue(padrao.secoesRemovidas.isEmpty())
    }

    @Test
    fun `arrastar qualquer bloco para cima ou para baixo reordena`() {
        val ecuNoTopo = padrao.mover(padrao.secoesVisiveis.indexOf(SecaoPainel.INFO_ECU), 0)
        assertEquals(SecaoPainel.INFO_ECU, ecuNoTopo.secoesVisiveis.first())
        val graficoNoFim = padrao.mover(padrao.secoesVisiveis.indexOf(SecaoPainel.GRAFICO), 7)
        assertEquals(SecaoPainel.GRAFICO, graficoNoFim.secoesVisiveis.last())
        assertEquals(8, graficoNoFim.secoesVisiveis.distinct().size)
    }

    @Test
    fun `mover para a mesma posicao ou fora da lista nao muda nada`() {
        assertEquals(padrao.ordem, padrao.mover(1, 1).ordem)
        assertEquals(padrao.ordem, padrao.mover(-1, 2).ordem)
        assertEquals(padrao.ordem, padrao.mover(0, 8).ordem)
    }

    @Test
    fun `ordem da v1_4 com 3 blocos ganha os novos no fim`() {
        val antiga = OrdemPainel.normalizar(listOf(SecaoPainel.MARCADORES, SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO, SecaoPainel.MUSICA))
        assertEquals(listOf(SecaoPainel.MARCADORES, SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO), antiga.take(3))
        assertEquals(SecaoPainel.entries.size, antiga.size)
    }

    // ---------------------------------------------------------------- blocos: remover e restaurar

    @Test
    fun `remover um bloco tira da tela e manda para blocos removidos`() {
        val semWaze = padrao.ocultar(SecaoPainel.NAVEGACAO).ocultar(SecaoPainel.MUSICA)
        assertFalse(SecaoPainel.NAVEGACAO in semWaze.secoesVisiveis)
        assertFalse(semWaze.visivel(SecaoPainel.MUSICA))
        assertEquals(listOf(SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO), semWaze.secoesRemovidas)
        assertEquals(6, semWaze.secoesVisiveis.size)
    }

    @Test
    fun `adicionar de volta restaura o bloco depois do ultimo visivel`() {
        val semMusica = padrao.ocultar(SecaoPainel.MUSICA)
        val restaurado = semMusica.restaurar(SecaoPainel.MUSICA)
        assertTrue(restaurado.secoesRemovidas.isEmpty())
        assertEquals(SecaoPainel.MUSICA, restaurado.secoesVisiveis.last())
        assertEquals(restaurado, restaurado.restaurar(SecaoPainel.MUSICA))
    }

    @Test
    fun `arrastar com blocos removidos mexe so nos visiveis`() {
        val config = padrao.ocultar(SecaoPainel.MUSICA).mover(0, 1)
        assertEquals(listOf(SecaoPainel.MARCADORES, SecaoPainel.NAVEGACAO), config.secoesVisiveis.take(2))
        assertEquals(listOf(SecaoPainel.MUSICA), config.secoesRemovidas)
    }

    @Test
    fun `da para remover todos os blocos e trazer todos de volta`() {
        val vazio = SecaoPainel.entries.fold(padrao) { c, s -> c.ocultar(s) }
        assertTrue(vazio.secoesVisiveis.isEmpty())
        val cheio = vazio.secoesRemovidas.fold(vazio) { c, s -> c.restaurar(s) }
        assertEquals(SecaoPainel.entries.toSet(), cheio.secoesVisiveis.toSet())
    }

    @Test
    fun `botoes da navegacao podem ser escondidos um a um`() {
        assertTrue(BotaoNavegacao.entries.all(padrao::botaoVisivel))
        val semPosto = padrao.alternarBotaoNavegacao(BotaoNavegacao.POSTO)
        assertFalse(semPosto.botaoVisivel(BotaoNavegacao.POSTO))
        assertTrue(semPosto.botaoVisivel(BotaoNavegacao.HUD))
        assertTrue(semPosto.alternarBotaoNavegacao(BotaoNavegacao.POSTO).botaoVisivel(BotaoNavegacao.POSTO))
    }

    // ---------------------------------------------------------------- mostradores

    @Test
    fun `comeca com os 4 padrao e aceita mais de 4 ate o total de sensores`() {
        assertEquals(listOf(SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR), padrao.mostradores.map { it.sensor })
        val seis = padrao.adicionarMostrador().adicionarMostrador()
        assertEquals(6, seis.mostradores.size)
        assertEquals(6, seis.mostradores.map { it.sensor }.distinct().size)
        val todos = (1..SensorPainel.entries.size + 5).fold(padrao) { c, _ -> c.adicionarMostrador() }
        assertEquals(SensorPainel.entries.size, ConfigPainel.MAX_MOSTRADORES)
        assertTrue(todos.mostradores.size > 12)
        assertEquals(ConfigPainel.MAX_MOSTRADORES, todos.mostradores.size)
        assertFalse(todos.podeAdicionarMostrador)
        assertEquals(todos, todos.adicionarMostrador())
    }

    @Test
    fun `novo mostrador herda o estilo do ultimo`() {
        val config = padrao.definirEstilo(3, EstiloMostrador.FITA).adicionarMostrador()
        assertEquals(EstiloMostrador.FITA, config.mostradores.last().estilo)
    }

    @Test
    fun `remover mostrador nunca deixa o painel sem relogio`() {
        val um = (1..10).fold(padrao) { c, _ -> c.removerMostrador(0) }
        assertEquals(1, um.mostradores.size)
        assertFalse(um.podeRemoverMostrador)
        assertEquals(SensorPainel.TEMP_MOTOR, um.mostradores.single().sensor)
        assertEquals(padrao, padrao.removerMostrador(9))
    }

    @Test
    fun `estilos individuais permitem misturar no mesmo painel`() {
        val misto = padrao
            .definirEstilo(0, EstiloMostrador.ESPORTIVO)
            .definirEstilo(2, EstiloMostrador.FITA)
            .definirEstilo(3, EstiloMostrador.DIGITAL)
        assertEquals(
            listOf(EstiloMostrador.ESPORTIVO, EstiloMostrador.ESPORTIVO, EstiloMostrador.FITA, EstiloMostrador.DIGITAL),
            misto.mostradores.map { it.estilo },
        )
        assertTrue(misto.aplicarEstiloATodos(EstiloMostrador.DIGITAL).mostradores.all { it.estilo == EstiloMostrador.DIGITAL })
    }

    @Test
    fun `arrastar mostrador leva o sensor e o estilo juntos`() {
        val config = padrao.definirEstilo(2, EstiloMostrador.FITA).moverMostrador(2, 0)
        assertEquals(Mostrador(SensorPainel.TURBO, EstiloMostrador.FITA), config.mostradores.first())
        assertEquals(SensorPainel.RPM, config.mostradores[1].sensor)
    }

    @Test
    fun `escolher um sensor que ja esta em outro relogio troca os dois mantendo os estilos`() {
        val config = padrao.definirEstilo(3, EstiloMostrador.DIGITAL).definirMostrador(0, SensorPainel.TEMP_MOTOR)
        assertEquals(Mostrador(SensorPainel.TEMP_MOTOR, EstiloMostrador.ESPORTIVO), config.mostradores[0])
        assertEquals(Mostrador(SensorPainel.RPM, EstiloMostrador.DIGITAL), config.mostradores[3])
        val comRail = config.definirMostrador(1, SensorPainel.RAIL)
        assertEquals(SensorPainel.RAIL, comRail.mostradores[1].sensor)
        assertEquals(4, comRail.mostradores.map { it.sensor }.distinct().size)
        assertEquals(config, config.definirMostrador(7, SensorPainel.MAF))
    }

    @Test
    fun `normalizacao tira repetidos, nao corta em 12 e nunca fica vazia`() {
        assertEquals(ConfigPainel.MOSTRADORES_PADRAO, OrdemPainel.normalizarMostradores(emptyList()))
        val repetidos = OrdemPainel.normalizarMostradores(listOf(Mostrador(SensorPainel.TENSAO), Mostrador(SensorPainel.TENSAO, EstiloMostrador.FITA)))
        assertEquals(listOf(Mostrador(SensorPainel.TENSAO)), repetidos)
        val todos = OrdemPainel.normalizarMostradores(SensorPainel.entries.map { Mostrador(it) })
        assertEquals(SensorPainel.entries.size, todos.size)
    }

    // ---------------------------------------------------------------- lista rápida e HUD

    @Test
    fun `lista rapida vazia mostra tudo e com selecao segue a ordem escolhida`() {
        val comandos = listOf("010C", "010D", "0105", "ATRV")
        assertEquals(comandos, padrao.filtrarListaRapida(comandos) { it })
        val rapida = padrao.alternarListaRapida("atrv").alternarListaRapida("010C")
        assertEquals(listOf("ATRV", "010C"), rapida.listaRapida)
        assertEquals(listOf("ATRV", "010C"), rapida.filtrarListaRapida(comandos) { it })
        assertEquals(listOf("010C", "ATRV"), rapida.moverListaRapida(1, 0).filtrarListaRapida(comandos) { it })
        assertEquals(listOf("010C"), rapida.alternarListaRapida("ATRV").listaRapida)
    }

    @Test
    fun `sensores do HUD podem ser reordenados por arraste`() {
        val hud = ConfigHud().moverSensor(3, 0)
        assertEquals(listOf(SensorPainel.RAIL, SensorPainel.RPM, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR), hud.sensores)
        assertEquals(hud.sensores, hud.sensoresVisiveis)
        assertEquals(hud, hud.moverSensor(0, 9))
    }

    // ---------------------------------------------------------------- persistência

    @Test
    fun `personalizacao completa sobrevive a reabertura`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        assertEquals(ConfigPainel(), prefs.config.value)
        prefs.atualizar {
            it.mover(2, 0)
                .ocultar(SecaoPainel.MUSICA)
                .alternarBotaoNavegacao(BotaoNavegacao.OFICINA)
                .adicionarMostrador().adicionarMostrador()
                .definirEstilo(1, EstiloMostrador.FITA)
                .definirEstilo(2, EstiloMostrador.DIGITAL)
                .alternarListaRapida("0105").alternarListaRapida("010C")
        }
        prefs.definirHud(ConfigHud(TamanhoHud.MINIMIZADO, listOf(SensorPainel.TENSAO, SensorPainel.VELOCIDADE), EstiloHud.NUMEROS_GRANDES))

        val esperado = prefs.config.value
        val reaberto = PreferenciasPainel(memoria).config.value
        assertEquals(esperado, reaberto)
        assertEquals(SecaoPainel.MARCADORES, reaberto.secoesVisiveis.first())
        assertEquals(listOf(SecaoPainel.MUSICA), reaberto.secoesRemovidas)
        assertFalse(reaberto.botaoVisivel(BotaoNavegacao.OFICINA))
        assertEquals(6, reaberto.mostradores.size)
        assertEquals(EstiloMostrador.FITA, reaberto.mostradores[1].estilo)
        assertEquals(listOf("0105", "010C"), reaberto.listaRapida)
        assertEquals(TamanhoHud.MINIMIZADO, reaberto.hud.tamanho)
        assertEquals(listOf(SensorPainel.TENSAO, SensorPainel.VELOCIDADE), reaberto.hud.sensores)
    }

    @Test
    fun `preferencias da v1_4 migram com o estilo global em todos os relogios`() {
        val memoria = Memoria().apply {
            valores["ordem"] = "MARCADORES,MUSICA,NAVEGACAO"
            valores["estilo"] = "FITA"
            valores["mostradores"] = "RPM,TURBO,TENSAO,RAIL"
            valores["lista_rapida"] = "010C,0105"
        }
        val config = PreferenciasPainel(memoria).config.value
        assertEquals(listOf(SensorPainel.RPM, SensorPainel.TURBO, SensorPainel.TENSAO, SensorPainel.RAIL), config.mostradores.map { it.sensor })
        assertTrue(config.mostradores.all { it.estilo == EstiloMostrador.FITA })
        assertEquals(SecaoPainel.entries.size, config.secoesVisiveis.size)
        assertTrue(BotaoNavegacao.entries.all(config::botaoVisivel))
        assertEquals(listOf("010C", "0105"), config.listaRapida)
    }

    @Test
    fun `navegacao sem nenhum botao continua assim ao reabrir`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        prefs.atualizar { c -> BotaoNavegacao.entries.fold(c) { acc, b -> acc.alternarBotaoNavegacao(b) } }
        assertTrue(PreferenciasPainel(memoria).config.value.botoesNavegacao.isEmpty())
    }

    @Test
    fun `sem mudanca nao grava de novo e restaurar volta ao padrao`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        prefs.atualizar { it }
        assertEquals(0, memoria.gravacoes)
        prefs.atualizar { it.aplicarEstiloATodos(EstiloMostrador.DIGITAL) }
        assertEquals(1, memoria.gravacoes)
        prefs.restaurarPadrao()
        assertEquals(ConfigPainel(), prefs.config.value)
    }

    @Test
    fun `valores corrompidos no arquivo voltam aos padroes`() {
        val memoria = Memoria().apply {
            valores["mostradores_v2"] = "RPM:TURBO_MAXIMO,RPM:FITA,XYZ:DIGITAL"
            valores["ocultas"] = "LIXO"
            valores["hud_sensores"] = ""
            valores["hud_tamanho"] = "gigante"
        }
        val config = PreferenciasPainel(memoria).config.value
        assertEquals(listOf(Mostrador(SensorPainel.RPM, EstiloMostrador.ESPORTIVO)), config.mostradores)
        assertTrue(config.secoesRemovidas.isEmpty())
        assertEquals(ConfigHud.PADRAO_EXPANDIDO, config.hud.sensores)
        assertEquals(TamanhoHud.EXPANDIDO, config.hud.tamanho)
        assertTrue(config.listaRapida.isEmpty())
    }

    @Test
    fun `codec ida e volta preserva tudo`() {
        val config = CodecPainel.normalizar(
            padrao.ocultar(SecaoPainel.INFO_ECU)
                .adicionarMostrador()
                .definirEstilo(4, EstiloMostrador.FITA)
                .alternarListaRapida("ATRV")
                .copy(hud = ConfigHud(estilo = EstiloHud.BARRAS).moverSensor(0, 2)),
        )
        val mapa = CodecPainel.codificar(config)
        assertEquals(CodecPainel.CHAVES.toSet(), mapa.keys)
        assertEquals(config, CodecPainel.decodificar(mapa::get))
    }
}
