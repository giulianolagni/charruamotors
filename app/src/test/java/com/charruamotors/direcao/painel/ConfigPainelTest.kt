package com.charruamotors.direcao.painel

import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordem do Painel (arrastar e soltar), mostradores, lista rápida, HUD e a persistência. */
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

    // ---------------------------------------------------------------- ordem das seções

    @Test
    fun `ordem padrao e musica navegacao e marcadores`() {
        assertEquals(listOf(SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO, SecaoPainel.MARCADORES), ConfigPainel().ordem)
    }

    @Test
    fun `arrastar marcadores para o topo reordena as secoes`() {
        val config = ConfigPainel().mover(2, 0)
        assertEquals(listOf(SecaoPainel.MARCADORES, SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO), config.ordem)
        assertEquals(listOf(SecaoPainel.MARCADORES, SecaoPainel.NAVEGACAO, SecaoPainel.MUSICA), config.mover(1, 2).ordem)
    }

    @Test
    fun `mover para a mesma posicao ou fora da lista nao muda nada`() {
        val ordem = ConfigPainel.ORDEM_PADRAO
        assertEquals(ordem, OrdemPainel.mover(ordem, 1, 1))
        assertEquals(ordem, OrdemPainel.mover(ordem, -1, 2))
        assertEquals(ordem, OrdemPainel.mover(ordem, 0, 3))
    }

    @Test
    fun `ordem incompleta ou repetida e normalizada com todas as secoes`() {
        assertEquals(
            listOf(SecaoPainel.NAVEGACAO, SecaoPainel.MUSICA, SecaoPainel.MARCADORES),
            OrdemPainel.normalizar(listOf(SecaoPainel.NAVEGACAO, SecaoPainel.NAVEGACAO)),
        )
        assertEquals(ConfigPainel.ORDEM_PADRAO, OrdemPainel.decodificarOrdem(null))
        assertEquals(
            listOf(SecaoPainel.MARCADORES, SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO),
            OrdemPainel.decodificarOrdem("MARCADORES, LIXO,MUSICA"),
        )
    }

    // ---------------------------------------------------------------- mostradores e lista rápida

    @Test
    fun `escolher um sensor que ja esta em outro mostrador troca os dois`() {
        val config = ConfigPainel().definirMostrador(0, SensorPainel.TEMP_MOTOR)
        assertEquals(
            listOf(SensorPainel.TEMP_MOTOR, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.RPM),
            config.mostradores,
        )
        val comRail = config.definirMostrador(1, SensorPainel.RAIL)
        assertEquals(SensorPainel.RAIL, comRail.mostradores[1])
        assertEquals(4, comRail.mostradores.distinct().size)
        assertEquals(config, config.definirMostrador(7, SensorPainel.MAF))
    }

    @Test
    fun `mostradores sempre sao 4 sem repetidos`() {
        assertEquals(ConfigPainel.MOSTRADORES_PADRAO, OrdemPainel.normalizarMostradores(emptyList()))
        val dois = OrdemPainel.normalizarMostradores(listOf(SensorPainel.TENSAO, SensorPainel.TENSAO, SensorPainel.RPM))
        assertEquals(listOf(SensorPainel.TENSAO, SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO), dois)
    }

    @Test
    fun `lista rapida vazia mostra tudo e com selecao filtra na ordem original`() {
        val comandos = listOf("010C", "010D", "0105", "ATRV")
        val todos = ConfigPainel()
        assertEquals(comandos, todos.filtrarListaRapida(comandos) { it })
        val rapida = todos.alternarListaRapida("atrv").alternarListaRapida("010C")
        assertEquals(setOf("ATRV", "010C"), rapida.listaRapida)
        assertEquals(listOf("010C", "ATRV"), rapida.filtrarListaRapida(comandos) { it })
        assertEquals(setOf("010C"), rapida.alternarListaRapida("ATRV").listaRapida)
    }

    // ---------------------------------------------------------------- persistência

    @Test
    fun `preferencias do painel e do HUD sobrevivem a reabertura`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        assertEquals(ConfigPainel(), prefs.config.value)
        prefs.atualizar {
            it.mover(2, 0)
                .copy(estilo = EstiloMostrador.FITA)
                .definirMostrador(3, SensorPainel.RAIL)
                .alternarListaRapida("0105")
        }
        prefs.definirHud(ConfigHud(TamanhoHud.MINIMIZADO, listOf(SensorPainel.TENSAO, SensorPainel.VELOCIDADE), EstiloHud.NUMEROS_GRANDES))

        val reaberto = PreferenciasPainel(memoria).config.value
        assertEquals(listOf(SecaoPainel.MARCADORES, SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO), reaberto.ordem)
        assertEquals(EstiloMostrador.FITA, reaberto.estilo)
        assertEquals(SensorPainel.RAIL, reaberto.mostradores[3])
        assertEquals(setOf("0105"), reaberto.listaRapida)
        assertEquals(TamanhoHud.MINIMIZADO, reaberto.hud.tamanho)
        assertEquals(listOf(SensorPainel.TENSAO, SensorPainel.VELOCIDADE), reaberto.hud.sensores)
        assertEquals(EstiloHud.NUMEROS_GRANDES, reaberto.hud.estilo)
    }

    @Test
    fun `sem mudanca nao grava de novo e restaurar volta ao padrao`() {
        val memoria = Memoria()
        val prefs = PreferenciasPainel(memoria)
        prefs.atualizar { it }
        assertEquals(0, memoria.gravacoes)
        prefs.atualizar { it.copy(estilo = EstiloMostrador.DIGITAL) }
        assertEquals(1, memoria.gravacoes)
        prefs.restaurarPadrao()
        assertEquals(ConfigPainel(), prefs.config.value)
    }

    @Test
    fun `valores corrompidos no arquivo voltam aos padroes`() {
        val memoria = Memoria().apply {
            valores["estilo"] = "TURBO_MAXIMO"
            valores["mostradores"] = "RPM,RPM,XYZ"
            valores["hud_sensores"] = ""
            valores["hud_tamanho"] = "gigante"
        }
        val config = PreferenciasPainel(memoria).config.value
        assertEquals(EstiloMostrador.ESPORTIVO, config.estilo)
        assertEquals(SensorPainel.RPM, config.mostradores.first())
        assertEquals(4, config.mostradores.distinct().size)
        assertEquals(ConfigHud.PADRAO_EXPANDIDO, config.hud.sensores)
        assertEquals(TamanhoHud.EXPANDIDO, config.hud.tamanho)
        assertTrue(config.listaRapida.isEmpty())
    }
}
