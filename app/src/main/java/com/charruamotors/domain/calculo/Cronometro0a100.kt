package com.charruamotors.domain.calculo

/** Estado do cronômetro de arrancada. */
sealed interface EstadoCronometro {
    /** Armado: aguardando parar, pronto ou medindo (o botão vira "Cancelar"). */
    val armado: Boolean get() = this is AguardandoParada || this is Pronto || this is Medindo

    /** Botão ainda não armado (ou cancelado). */
    data object Desarmado : EstadoCronometro

    /** Armado com o carro em movimento (ou sem leitura de velocidade): pare o veículo. */
    data object AguardandoParada : EstadoCronometro

    /** Armado e parado: a contagem começa sozinha quando a velocidade passar de 0. */
    data object Pronto : EstadoCronometro

    /** Contando desde [inicioMs]; [velocidade] = última leitura. */
    data class Medindo(val inicioMs: Long, val decorridoMs: Long, val velocidade: Double) : EstadoCronometro

    /** Alvo atingido em [tempoMs]. */
    data class Concluido(val tempoMs: Long) : EstadoCronometro
}

/**
 * Cronômetro automático 0–[alvoKmh] km/h, alimentado pelas leituras de velocidade (`010D`).
 *
 * - **Armar** com o carro parado → [EstadoCronometro.Pronto]; em movimento → aguarda parar.
 * - A contagem começa na primeira leitura com velocidade acima de 0, depois de uma leitura parada.
 * - Termina ao atingir o alvo: o instante exato é interpolado entre as duas últimas leituras
 *   (o PID é lido a cada ~100–250 ms; sem interpolar, o erro seria do tamanho do ciclo).
 * - Parar antes do alvo reinicia a tentativa (volta a [EstadoCronometro.Pronto]); passar de
 *   [tempoMaximoMs] sem atingir o alvo também a descarta (aguarda o carro parar de novo).
 *
 * Objeto puro, testável na JVM (o tempo vem de fora, de cada leitura).
 */
class Cronometro0a100(
    private val alvoKmh: Double = ALVO_PADRAO_KMH,
    private val tempoMaximoMs: Long = TEMPO_MAXIMO_MS,
) {
    init {
        require(alvoKmh > LIMITE_PARADO_KMH) { "Alvo inválido: $alvoKmh km/h" }
        require(tempoMaximoMs > 0) { "Tempo máximo inválido" }
    }

    var estado: EstadoCronometro = EstadoCronometro.Desarmado
        private set

    /** Melhor tempo desde a criação (sobrevive a novas tentativas). */
    var melhorTempoMs: Long? = null
        private set

    private var amostraAnterior: Pair<Long, Double>? = null

    val armado: Boolean get() = estado.armado

    /** Arma para uma nova tentativa, conforme a [velocidadeAtual] (null = desconhecida). */
    fun armar(velocidadeAtual: Double?): EstadoCronometro {
        estado = if (velocidadeAtual != null && parado(velocidadeAtual)) EstadoCronometro.Pronto else EstadoCronometro.AguardandoParada
        amostraAnterior = null
        return estado
    }

    fun desarmar(): EstadoCronometro {
        estado = EstadoCronometro.Desarmado
        amostraAnterior = null
        return estado
    }

    /** Registra uma leitura de velocidade (km/h) feita em [instanteMs]; leituras nulas são ignoradas. */
    fun registrar(velocidade: Double?, instanteMs: Long): EstadoCronometro {
        if (velocidade == null || !velocidade.isFinite()) return estado
        val anterior = amostraAnterior
        amostraAnterior = instanteMs to velocidade
        estado = when (val atual = estado) {
            EstadoCronometro.Desarmado, is EstadoCronometro.Concluido -> atual
            EstadoCronometro.AguardandoParada ->
                if (parado(velocidade)) EstadoCronometro.Pronto else atual
            EstadoCronometro.Pronto -> when {
                parado(velocidade) -> atual
                // Já chegou rápido demais (ex.: leitura perdida): não dá para medir esta tentativa.
                velocidade >= alvoKmh -> EstadoCronometro.AguardandoParada
                else -> EstadoCronometro.Medindo(instanteMs, 0, velocidade)
            }
            is EstadoCronometro.Medindo -> medir(atual, anterior, velocidade, instanteMs)
        }
        return estado
    }

    private fun medir(atual: EstadoCronometro.Medindo, anterior: Pair<Long, Double>?, velocidade: Double, instanteMs: Long): EstadoCronometro {
        if (velocidade >= alvoKmh) {
            val chegada = if (anterior != null && anterior.first >= atual.inicioMs && velocidade > anterior.second) {
                val (t0, v0) = anterior
                t0 + ((alvoKmh - v0) / (velocidade - v0) * (instanteMs - t0)).toLong()
            } else {
                instanteMs
            }
            val tempo = (chegada - atual.inicioMs).coerceAtLeast(0)
            melhorTempoMs = melhorTempoMs?.coerceAtMost(tempo) ?: tempo
            return EstadoCronometro.Concluido(tempo)
        }
        val decorrido = instanteMs - atual.inicioMs
        return when {
            parado(velocidade) -> EstadoCronometro.Pronto // parou antes do alvo: nova tentativa
            decorrido > tempoMaximoMs -> EstadoCronometro.AguardandoParada
            else -> atual.copy(decorridoMs = decorrido, velocidade = velocidade)
        }
    }

    private fun parado(velocidade: Double) = velocidade < LIMITE_PARADO_KMH

    companion object {
        const val ALVO_PADRAO_KMH = 100.0
        const val TEMPO_MAXIMO_MS = 60_000L

        /** O PID 010D tem resolução de 1 km/h: abaixo de 0,5 é "0". */
        private const val LIMITE_PARADO_KMH = 0.5
    }
}
