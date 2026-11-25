package com.charruamotors.oficina.sensores

/** Um instantâneo da telemetria no tempo [tMs]. */
data class Amostra(val tMs: Long, val valores: Map<String, Double>)

/** Menor e maior valor de um sensor desde o começo da sessão. */
data class MinMax(val min: Double, val max: Double)

/**
 * Memória da sessão OBD (v2.3.1): um buffer circular com as amostras recentes (a maior janela dos
 * Gráficos em Tempo Real, com folga) e o mínimo/máximo de cada sensor na sessão inteira. Quem
 * respondeu ao menos uma vez está em [estatisticas]. Seguro entre threads (grava a telemetria, lê a
 * tela). Puro (JVM).
 */
class HistoricoSessao(
    private val retencaoMs: Long = RETENCAO_MS,
    private val maximoAmostras: Int = MAXIMO_AMOSTRAS,
) {
    private val buffer = ArrayDeque<Amostra>()
    private val extremos = LinkedHashMap<String, MinMax>()

    @Synchronized
    fun registrar(valores: Map<String, Double>, tMs: Long) {
        // Relógio do aparelho voltou (ajuste de hora): o trecho antigo deixa de ser comparável.
        if (buffer.isNotEmpty() && tMs < buffer.last().tMs) buffer.clear()
        val finitos = valores.filterValues { it.isFinite() }
        buffer.addLast(Amostra(tMs, finitos))
        while (buffer.size > maximoAmostras || tMs - buffer.first().tMs > retencaoMs) buffer.removeFirst()
        for ((chave, valor) in finitos) {
            val atual = extremos[chave]
            extremos[chave] = if (atual == null) MinMax(valor, valor) else MinMax(minOf(atual.min, valor), maxOf(atual.max, valor))
        }
    }

    /** Cópia das amostras retidas, da mais antiga à mais nova. */
    @Synchronized
    fun amostras(): List<Amostra> = buffer.toList()

    /** Cópia do mínimo/máximo de cada sensor que respondeu na sessão. */
    @Synchronized
    fun estatisticas(): Map<String, MinMax> = LinkedHashMap(extremos)

    @Synchronized
    fun limpar() {
        buffer.clear()
        extremos.clear()
    }

    companion object {
        /** 120 s (a maior janela do gráfico) com folga. */
        const val RETENCAO_MS = 125_000L

        /** Teto de memória: ~48 amostras por segundo durante a retenção inteira. */
        const val MAXIMO_AMOSTRAS = 6_000
    }
}
