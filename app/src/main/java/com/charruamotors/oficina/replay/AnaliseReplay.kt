package com.charruamotors.oficina.replay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Mínimo / máximo / média de uma série na janela visível (só as leituras válidas). */
data class EstatisticaSerie(val minimo: Double, val maximo: Double, val media: Double, val leituras: Int)

/** Trecho visível da linha do tempo, em ms (mesma base de [LogCsv.instantesMs]). */
data class JanelaReplay(val inicioMs: Long, val fimMs: Long) {
    val duracaoMs: Long get() = fimMs - inicioMs
}

/**
 * Cálculos do Visualizador de Logs (Replay), puros e testáveis: faixa de índices da janela,
 * estatísticas, normalização de cada série na própria escala, zoom/arraste com limites e o ponto
 * mais próximo do cursor.
 */
object AnaliseReplay {

    /** Zoom máximo: a janela nunca fica menor que isto (ou que [MIN_AMOSTRAS_JANELA] amostras). */
    const val DURACAO_MINIMA_MS = 1_000L
    const val MIN_AMOSTRAS_JANELA = 4

    /** Margem acima/abaixo da curva normalizada (fração da altura), para o traço não colar nas bordas. */
    const val MARGEM_NORMALIZACAO = 0.06

    /** Primeiro índice com instante ≥ [alvoMs] (busca binária; [instantes] em ordem crescente). */
    fun limiteInferior(instantes: LongArray, alvoMs: Long): Int {
        var de = 0
        var ate = instantes.size
        while (de < ate) {
            val meio = (de + ate) ushr 1
            if (instantes[meio] < alvoMs) de = meio + 1 else ate = meio
        }
        return de
    }

    /** Índices [inicio, fim) das amostras dentro da [janela] (fim exclusivo). */
    fun faixaIndices(instantes: LongArray, janela: JanelaReplay): IntRange {
        val de = limiteInferior(instantes, janela.inicioMs)
        val ate = limiteInferior(instantes, janela.fimMs + 1)
        return de until ate
    }

    /** Índice da amostra mais próxima de [alvoMs]; -1 se não houver amostras. */
    fun indiceMaisProximo(instantes: LongArray, alvoMs: Long): Int {
        if (instantes.isEmpty()) return -1
        val i = limiteInferior(instantes, alvoMs)
        if (i <= 0) return 0
        if (i >= instantes.size) return instantes.lastIndex
        return if (alvoMs - instantes[i - 1] <= instantes[i] - alvoMs) i - 1 else i
    }

    /** Estatísticas das leituras válidas (não `NaN`) em [faixa]; null se não houver nenhuma. */
    fun estatisticas(serie: DoubleArray, faixa: IntRange): EstatisticaSerie? {
        var minimo = Double.POSITIVE_INFINITY
        var maximo = Double.NEGATIVE_INFINITY
        var soma = 0.0
        var n = 0
        for (i in faixa) {
            val v = serie.getOrNull(i) ?: continue
            if (v.isNaN()) continue
            minimo = min(minimo, v)
            maximo = max(maximo, v)
            soma += v
            n++
        }
        return if (n == 0) null else EstatisticaSerie(minimo, maximo, soma / n, n)
    }

    /**
     * Escala própria de uma série: (mínimo, máximo) da janela com uma margem; série constante ganha
     * uma faixa artificial em volta do valor (senão a divisão por zero achataria tudo).
     */
    fun escala(estatistica: EstatisticaSerie): Pair<Double, Double> {
        val amplitude = estatistica.maximo - estatistica.minimo
        if (amplitude <= 1e-12) {
            val folga = max(kotlin.math.abs(estatistica.maximo) * 0.05, 1.0)
            return (estatistica.minimo - folga) to (estatistica.maximo + folga)
        }
        val margem = amplitude * MARGEM_NORMALIZACAO
        return (estatistica.minimo - margem) to (estatistica.maximo + margem)
    }

    /** Valor normalizado em 0–1 na escala (mínimo, máximo); fora da escala é limitado às bordas. */
    fun normalizar(valor: Double, minimo: Double, maximo: Double): Double {
        if (maximo - minimo <= 1e-12) return 0.5
        return ((valor - minimo) / (maximo - minimo)).coerceIn(0.0, 1.0)
    }

    /** Janela inicial: o log inteiro (um log de uma amostra só ganha 1 s em volta dela). */
    fun janelaCompleta(log: LogCsv): JanelaReplay =
        if (log.fimMs > log.inicioMs) JanelaReplay(log.inicioMs, log.fimMs) else JanelaReplay(log.inicioMs - 500, log.fimMs + 500)

    /**
     * Zoom de [fator] (> 1 aproxima, < 1 afasta) mantendo o instante [focoMs] no mesmo lugar da tela.
     * Limitado à duração total e à duração mínima ([DURACAO_MINIMA_MS] ou [MIN_AMOSTRAS_JANELA] amostras).
     */
    fun zoom(janela: JanelaReplay, total: JanelaReplay, fator: Double, focoMs: Long, duracaoMinimaMs: Long = DURACAO_MINIMA_MS): JanelaReplay {
        require(fator > 0) { "fator deve ser positivo" }
        val minima = min(duracaoMinimaMs, total.duracaoMs).coerceAtLeast(1)
        val nova = (janela.duracaoMs / fator).roundToLong().coerceIn(minima, total.duracaoMs.coerceAtLeast(minima))
        val foco = focoMs.coerceIn(janela.inicioMs, janela.fimMs)
        val proporcao = if (janela.duracaoMs > 0) (foco - janela.inicioMs).toDouble() / janela.duracaoMs else 0.5
        val inicio = foco - (nova * proporcao).roundToLong()
        return limitar(JanelaReplay(inicio, inicio + nova), total)
    }

    /** Arrasta a janela [deltaMs] (positivo = para o futuro), sem sair do log. */
    fun deslocar(janela: JanelaReplay, total: JanelaReplay, deltaMs: Long): JanelaReplay =
        limitar(JanelaReplay(janela.inicioMs + deltaMs, janela.fimMs + deltaMs), total)

    /** Encaixa a janela dentro do [total] mantendo a duração (ou o total inteiro, se maior). */
    fun limitar(janela: JanelaReplay, total: JanelaReplay): JanelaReplay {
        val duracao = min(janela.duracaoMs, total.duracaoMs)
        val inicio = janela.inicioMs.coerceIn(total.inicioMs, total.fimMs - duracao)
        return JanelaReplay(inicio, inicio + duracao)
    }

    /** Duração mínima do zoom para este log: [DURACAO_MINIMA_MS] ou o tempo de [MIN_AMOSTRAS_JANELA] amostras, o maior. */
    fun duracaoMinima(log: LogCsv): Long {
        if (log.amostras < 2) return DURACAO_MINIMA_MS
        val periodoMedio = (log.fimMs - log.inicioMs) / (log.amostras - 1)
        return max(DURACAO_MINIMA_MS, periodoMedio * MIN_AMOSTRAS_JANELA)
    }

    /**
     * Seleção por chips de até [maximo] sensores: alterna [indice]; null = recusado (já há
     * [maximo] selecionados). A ordem de seleção é mantida (define as cores).
     */
    fun alternarSelecao(selecionados: List<Int>, indice: Int, maximo: Int = MAX_SENSORES): List<Int>? = when {
        indice in selecionados -> selecionados - indice
        selecionados.size >= maximo -> null
        else -> selecionados + indice
    }

    /**
     * Seleção inicial: RPM, MAP/turbo e pressão do rail (os comandos `010C`, `010B` e `0123`, ou
     * os espelhos da mesma chave), na falta deles as primeiras colunas com leitura.
     */
    fun selecaoInicial(log: LogCsv, maximo: Int = MAX_SENSORES): List<Int> {
        val comDados = log.colunasComDados
        val preferidos = COMANDOS_PREFERIDOS.mapNotNull { cmd -> comDados.firstOrNull { log.colunas[it].comando == cmd } }
        return (preferidos + comDados).distinct().take(maximo)
    }

    const val MAX_SENSORES = 3
    private val COMANDOS_PREFERIDOS = listOf("010C", "010B", "0123")
}
