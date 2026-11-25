package com.charruamotors.oficina.sensores

import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.direcao.painel.ResultadoFixar
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.3.1: histórico da sessão, Todos os Sensores, "+ Painel", Gráficos e isolamento por veículo. */
class PacoteOficinaV231Test {

    private class Memoria : ArmazenamentoChaveValor {
        val mapa = mutableMapOf<String, Any>()
        override fun lerTexto(chave: String) = mapa[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean) = mapa[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) = valores.forEach { (c, v) -> if (v == null) mapa.remove(c) else mapa[c] = v }
    }

    // ---------------------------------------------------------------- histórico (buffer circular)

    @Test
    fun `buffer descarta o que passa da retencao mas o minimo e maximo seguem da sessao`() {
        val h = HistoricoSessao(retencaoMs = 10_000)
        h.registrar(mapOf("010C" to 5_000.0), 0)
        h.registrar(mapOf("010C" to 800.0), 5_000)
        h.registrar(mapOf("010C" to 1_000.0), 15_000)

        assertEquals(listOf(5_000L, 15_000L), h.amostras().map { it.tMs })
        assertEquals(MinMax(800.0, 5_000.0), h.estatisticas()["010C"])
        h.limpar()
        assertTrue(h.amostras().isEmpty() && h.estatisticas().isEmpty())
    }

    @Test
    fun `buffer respeita o teto de amostras e ignora valores nao finitos`() {
        val h = HistoricoSessao(maximoAmostras = 3)
        for (i in 1..5) h.registrar(mapOf("010C" to i.toDouble(), "010D" to Double.NaN), i.toLong())

        assertEquals(listOf(3L, 4L, 5L), h.amostras().map { it.tMs })
        assertFalse("010D" in h.estatisticas())
    }

    // ---------------------------------------------------------------- gráficos

    private val amostras = listOf(
        Amostra(0, mapOf("010C" to 4_000.0)),
        Amostra(40_000, mapOf("010C" to 1_000.0, "010B" to 100.0)),
        Amostra(55_000, mapOf("010C" to 2_000.0)),
        Amostra(70_000, mapOf("010C" to 1_500.0, "010B" to 140.0)),
    )

    @Test
    fun `janela deslizante recorta as amostras e calcula atual e pico`() {
        val q = MontadorGrafico.montar(amostras, listOf("010C", "010B"), 70_000, JanelaTempo.S60)
        val rpm = q.canais[0]

        assertEquals(10_000L, q.inicioMs)
        assertEquals(2_000.0, rpm.pico!!, 0.0)
        assertEquals(1_500.0, rpm.atual!!, 0.0)
        assertEquals(1_000.0, rpm.minimo!!, 0.0)
        assertEquals(4_000.0, MontadorGrafico.montar(amostras, listOf("010C"), 70_000, JanelaTempo.S120).canais[0].pico!!, 0.0)
        assertTrue(rpm.escala().start < 1_000.0 && rpm.escala().endInclusive > 2_000.0)
    }

    @Test
    fun `cursor le todos os canais no mesmo instante`() {
        val q = MontadorGrafico.montar(amostras, listOf("010C", "010B"), 70_000, JanelaTempo.S60)

        val meio = q.cursor(0.75f)!! // 55 s
        assertEquals(55_000L, meio.tMs)
        assertEquals(2_000.0, meio.valores["010C"]!!, 0.0)
        assertNull(meio.valores["010B"])
        assertEquals(70_000L, q.cursor(2f)!!.tMs)
        assertEquals(40_000L, q.cursor(-1f)!!.tMs)
        assertNull(MontadorGrafico.montar(emptyList(), listOf("010C"), 0, JanelaTempo.S30).cursor(0.5f))
    }

    @Test
    fun `pausar congela, retomar volta ao vivo e zerar limpa os picos`() {
        val c = ControleGrafico()
        c.alternarPausa(amostras.take(2))
        assertTrue(c.pausado)
        assertEquals(40_000L, c.quadro(amostras, listOf("010C")).fimMs)

        c.alternarPausa(amostras)
        assertEquals(70_000L, c.quadro(amostras, listOf("010C")).fimMs)

        c.zerar(amostras)
        assertTrue(c.quadro(amostras, listOf("010C")).vazio)
        val depois = amostras + Amostra(71_000, mapOf("010C" to 900.0))
        assertEquals(900.0, c.quadro(depois, listOf("010C")).canais[0].pico!!, 0.0)
    }

    @Test
    fun `sugestao de sensores para diesel e para flex`() {
        val tudo = listOf("0105", "010C", "010B", "0123", "0106", "0107")

        assertEquals(listOf("010C", "010B", "0123"), SelecaoGrafico.efetiva(emptyList(), tudo, diesel = true))
        assertEquals(listOf("010C", "010B", "0106", "0107"), SelecaoGrafico.efetiva(emptyList(), tudo, diesel = false))
        assertEquals(listOf("010C", "010B", "0123"), SelecaoGrafico.efetiva(emptyList(), tudo, diesel = null))
        assertEquals(listOf("0105"), SelecaoGrafico.efetiva(listOf("0105", "FORA"), tudo, diesel = false))
        assertEquals(4, SelecaoGrafico.efetiva(tudo, tudo, diesel = false).size)
        assertEquals(listOf("0105", "010F"), SelecaoGrafico.sugestao(listOf("0105", "010F", "0111"), diesel = false))
    }

    @Test
    fun `selecao do grafico fica isolada por veiculo`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        perfis.atualizar(1) { it.copy(favoritosGraficos = listOf("010C", "0123")) }
        perfis.atualizar(2) { it.copy(favoritosGraficos = listOf("0106")) }

        assertEquals(listOf("010C", "0123"), perfis.ler(1).favoritosGraficos)
        assertEquals(listOf("0106"), perfis.ler(2).favoritosGraficos)
    }

    // ---------------------------------------------------------------- "+ Painel"

    @Test
    fun `mais Painel adiciona e remove o mostrador e devolve o bloco removido`() {
        val inicial = ConfigPainel().ocultar(SecaoPainel.MARCADORES)
        val (com, adicionado) = inicial.alternarNoPainel("0106")

        assertEquals(ResultadoFixar.MOSTRADOR_ADICIONADO, adicionado)
        assertTrue(com.mostradores.any { it.sensor == SensorPainel.STFT })
        assertTrue(com.visivel(SecaoPainel.MARCADORES))
        assertTrue(com.fixadoNoPainel("0106"))

        val (sem, removido) = com.alternarNoPainel("0106")
        assertEquals(ResultadoFixar.MOSTRADOR_REMOVIDO, removido)
        assertFalse(sem.fixadoNoPainel("0106"))
    }

    @Test
    fun `sensor sem mostrador vai para a lista rapida e o ultimo mostrador fica`() {
        val (com, r) = ConfigPainel().alternarNoPainel("011F")
        assertEquals(ResultadoFixar.LISTA_ADICIONADO, r)
        assertEquals(listOf("011F"), com.listaRapida)
        assertEquals(ResultadoFixar.LISTA_REMOVIDO, com.alternarNoPainel("011F").second)

        var um = ConfigPainel()
        while (um.podeRemoverMostrador) um = um.removerMostrador(um.mostradores.lastIndex)
        val (igual, ultimo) = um.alternarNoPainel(um.mostradores.single().sensor.comando)
        assertEquals(ResultadoFixar.ULTIMO_MOSTRADOR, ultimo)
        assertEquals(um, igual)
    }

    @Test
    fun `mais Painel grava so no veiculo ativo`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        val painel = PreferenciasPainel(perfis.escopo(1))
        painel.atualizar { it.alternarNoPainel("0106").first }
        painel.trocarArmazenamento(perfis.escopo(2))

        assertFalse(painel.config.value.fixadoNoPainel("0106"))
        assertTrue(perfis.ler(1).painel.fixadoNoPainel("0106"))
        assertFalse(perfis.ler(2).painel.fixadoNoPainel("0106"))
    }

    // ---------------------------------------------------------------- Todos os Sensores

    @Test
    fun `lista filtra por nome ou PID, separa ativos e converte a unidade`() {
        val defs = listOf("010C", "010D", "0105").map { CatalogoTeste.padrao[it]!! }
        val estatisticas = mapOf("010D" to MinMax(0.0, 100.0))
        fun montar(filtro: String = "", soAtivos: Boolean = true) = MontadorListaSensores.montar(
            defs, estatisticas, mapOf("010D" to 80.0),
            ConversorUnidades(SistemaUnidades.IMPERIAL), ConfigPainel(), filtro, soAtivos,
        )

        val ativa = montar().single()
        assertEquals("mph", ativa.unidade)
        assertEquals("50", ativa.valor)
        assertEquals("62", ativa.maximo)
        assertTrue(ativa.fixado)
        assertEquals(3, montar(soAtivos = false).size)
        assertEquals(listOf("0105"), montar("0105", soAtivos = false).map { it.comando })
        assertEquals(listOf("010D"), montar("VELOCIDADE", soAtivos = false).map { it.comando })
        assertEquals(FormatadorSensor.SEM_LEITURA, montar("010C", soAtivos = false).single().valor)
    }
}
