package com.charruamotors.ui.componentes

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Uma amostra do gráfico ao vivo: RPM e MAP/turbo (kPa) num instante (null = sem leitura). */
data class PontoTelemetria(val instanteMs: Long, val rpm: Double?, val mapKpa: Double?)

/**
 * Janela deslizante dos últimos [duracaoMs] de telemetria para o [TelemetriaChartView].
 * Mantida no ViewModel (sobrevive à rotação); objeto puro, testável na JVM.
 */
class JanelaTelemetria(
    val duracaoMs: Long = DURACAO_PADRAO_MS,
    private val maxPontos: Int = MAX_PONTOS,
) {
    init {
        require(duracaoMs > 0 && maxPontos > 1) { "Janela inválida" }
    }

    private val buffer = ArrayDeque<PontoTelemetria>()

    /** Pontos dentro da janela, do mais antigo para o mais recente. */
    val pontos: List<PontoTelemetria> get() = buffer.toList()

    /**
     * Acrescenta um ponto e descarta os que saíram da janela (mais antigos que o último menos
     * [duracaoMs]). Um ponto anterior ao último (relógio voltou) reinicia a janela.
     */
    fun adicionar(ponto: PontoTelemetria): List<PontoTelemetria> {
        if (buffer.isNotEmpty() && ponto.instanteMs < buffer.last().instanteMs) buffer.clear()
        buffer.addLast(ponto)
        val limite = ponto.instanteMs - duracaoMs
        while (buffer.first().instanteMs < limite || buffer.size > maxPontos) buffer.removeFirst()
        return pontos
    }

    fun limpar() = buffer.clear()

    companion object {
        const val DURACAO_PADRAO_MS = 60_000L
        /** 60 s a 4 leituras/s = 240; folga para ciclos mais rápidos. */
        private const val MAX_PONTOS = 1_200
    }
}

/** Escala automática dos eixos do gráfico: máximo "redondo" e divisões legíveis. */
object EscalaAutomatica {

    private val PASSOS = doubleArrayOf(1.0, 2.0, 2.5, 5.0, 10.0)

    /**
     * Menor número "redondo" (1, 2, 2,5 ou 5 × 10ⁿ, vezes [divisoes]) maior ou igual ao maior
     * [valores], nunca abaixo de [minimoEscala] (para o ruído da marcha lenta não ocupar o gráfico
     * inteiro). Ex.: 3.420 rpm em 4 divisões → 4.000 (passo 1.000).
     */
    fun maximo(valores: Iterable<Double>, minimoEscala: Double, divisoes: Int = DIVISOES_PADRAO): Double {
        require(minimoEscala > 0 && divisoes > 0) { "Escala inválida" }
        val maior = valores.filter { it.isFinite() }.maxOrNull()?.coerceAtLeast(minimoEscala) ?: minimoEscala
        return passo(maior / divisoes) * divisoes
    }

    /** Passo "redondo" ≥ [bruto]. */
    fun passo(bruto: Double): Double {
        require(bruto > 0 && bruto.isFinite()) { "Passo inválido: $bruto" }
        val magnitude = 10.0.pow(floor(log10(bruto)))
        val normalizado = bruto / magnitude
        val escolhido = PASSOS.first { it >= normalizado - 1e-9 }
        return escolhido * magnitude
    }

    /** Fração vertical (0 = base, 1 = topo) de [valor] numa escala 0..[maximo], saturada. */
    fun fracao(valor: Double, maximo: Double): Float = (valor / maximo).coerceIn(0.0, 1.0).toFloat()

    const val DIVISOES_PADRAO = 4
}
