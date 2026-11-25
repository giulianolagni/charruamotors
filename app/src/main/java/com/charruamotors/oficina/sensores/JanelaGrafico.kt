package com.charruamotors.oficina.sensores

import com.charruamotors.obd.pid.Pids

/** Janela deslizante dos Gráficos em Tempo Real. */
enum class JanelaTempo(val segundos: Int) {
    S30(30),
    S60(60),
    S120(120),
    ;

    val ms: Long get() = segundos * 1_000L

    companion object {
        val PADRAO = S60
    }
}

data class PontoGrafico(val tMs: Long, val valor: Double)

/** Um sensor dentro da janela: os pontos (com falhas de leitura ausentes) e os extremos dela. */
data class CanalGrafico(val comando: String, val pontos: List<PontoGrafico?>) {
    private val validos = pontos.filterNotNull()

    /** Valor da amostra mais nova da janela; null se ela veio sem este sensor. */
    val atual: Double? = pontos.lastOrNull()?.valor
    val minimo: Double? = validos.minOfOrNull { it.valor }

    /** Maior valor dentro da janela. */
    val pico: Double? = validos.maxOfOrNull { it.valor }

    /** Escala própria do canal, com folga; um sinal constante fica no meio. */
    fun escala(): ClosedFloatingPointRange<Double> {
        val min = minimo ?: return 0.0..1.0
        val max = pico ?: return 0.0..1.0
        val folga = if (max > min) (max - min) * FOLGA else maxOf(kotlin.math.abs(max) * FOLGA, 1.0)
        return (min - folga)..(max + folga)
    }

    private companion object {
        const val FOLGA = 0.08
    }
}

/** Leitura de todos os canais num mesmo instante (o cursor de toque). */
data class LeituraCursor(val tMs: Long, val valores: Map<String, Double?>)

/** O que o gráfico desenha: a janela de tempo e os canais, todos sobre as mesmas amostras. */
data class QuadroGrafico(val inicioMs: Long, val fimMs: Long, val canais: List<CanalGrafico>, private val tempos: List<Long>) {

    val vazio: Boolean get() = tempos.isEmpty()

    /**
     * Cursor na fração [fracao] (0 = borda esquerda, 1 = direita): os valores exatos de todos os
     * canais na amostra mais próxima daquele instante. Null sem amostras.
     */
    fun cursor(fracao: Float): LeituraCursor? {
        if (tempos.isEmpty()) return null
        val alvo = inicioMs + ((fimMs - inicioMs) * fracao.coerceIn(0f, 1f).toDouble()).toLong()
        val i = tempos.indices.minByOrNull { kotlin.math.abs(tempos[it] - alvo) } ?: return null
        return LeituraCursor(tempos[i], canais.associate { it.comando to it.pontos[i]?.valor })
    }
}

/** Recorta as amostras na janela. Puro (JVM). */
object MontadorGrafico {

    /**
     * @param fimMs borda direita (a última amostra ao vivo, ou a do momento da pausa).
     * @param desdeMs corte do "Zerar": nada anterior a ele aparece nem entra nos picos.
     */
    fun montar(amostras: List<Amostra>, comandos: List<String>, fimMs: Long, janela: JanelaTempo, desdeMs: Long = 0): QuadroGrafico {
        val inicio = fimMs - janela.ms
        val dentro = amostras.filter { it.tMs >= inicio && it.tMs <= fimMs && it.tMs >= desdeMs }
        val canais = comandos.map { c -> CanalGrafico(c, dentro.map { a -> a.valores[c]?.let { PontoGrafico(a.tMs, it) } }) }
        return QuadroGrafico(inicio, fimMs, canais, dentro.map { it.tMs })
    }
}

/** Quais sensores o gráfico mostra: os salvos no perfil do veículo ou a sugestão. Puro (JVM). */
object SelecaoGrafico {
    const val MAXIMO = 4

    /** Diesel (SW4): RPM + Turbo + Rail. */
    val SUGESTAO_DIESEL = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.PRESSAO_RAIL)

    /** Flex/gasolina (Cruze, Onix): RPM + MAP + STFT + LTFT. */
    val SUGESTAO_OTTO = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.STFT, Pids.LTFT)

    /** @param diesel null = combustível desconhecido: decide pela presença do rail. */
    fun sugestao(disponiveis: Collection<String>, diesel: Boolean?): List<String> {
        val ehDiesel = diesel ?: (Pids.PRESSAO_RAIL in disponiveis)
        val base = (if (ehDiesel) SUGESTAO_DIESEL else SUGESTAO_OTTO).filter { it in disponiveis }
        return base.ifEmpty { disponiveis.take(2) }
    }

    /** Os [salvos] que esta sessão tem; nenhum deles = a [sugestao]. */
    fun efetiva(salvos: List<String>, disponiveis: Collection<String>, diesel: Boolean?): List<String> =
        salvos.filter { it in disponiveis }.distinct().take(MAXIMO).ifEmpty { sugestao(disponiveis, diesel) }
}
