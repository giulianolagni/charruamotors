package com.charruamotors.domain.calculo

import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Decodificadores
import com.charruamotors.obd.pid.Pids

/**
 * Câmbio automático A750F (Toyota SW4/Hilux) visto pelo app: no log real da SW4 2013 o `7E1 · 2130`
 * devolve `0xFF` fixo mesmo a 110 km/h, então a marcha (1ª–5ª) é estimada pela razão
 * `rpm / velocidade` com a alavanca em D. Funções puras (JVM).
 */
object CambioA750f {

    /** Chaves do instantâneo: alavanca do `7E1 · 21D9` e marcha (`2130` ou estimada). */
    const val CHAVE_ALAVANCA = "TOYOTA_ALAVANCA"
    const val CHAVE_MARCHA = "TOYOTA_MARCHA"

    /** Valores da marcha (rótulos de `TOYOTA_MARCHA`): `0` = N, `1..5`, `254` = R, `255` = Parado. */
    const val MARCHA_NEUTRO = 0
    const val MARCHA_RE = 254
    const val MARCHA_PARADO = 255

    /** Abaixo disto o veículo conta como parado para a marcha (a razão rpm/velocidade não vale). */
    const val VELOCIDADE_MINIMA_KMH = 5.0

    /**
     * v2.2.6: marchas na reduzida (`4L`) ficam em `10 + marcha` (`11` = `1ª L` ... `15` = `5ª L`),
     * para o Painel e a seção `MARCHAS` do relatório separarem 4L de 4H.
     */
    const val BASE_REDUZIDA = 10

    /** Código de [marcha] (1..9) na reduzida. */
    fun codigoReduzida(marcha: Int): Int = BASE_REDUZIDA + marcha

    /** O código de `TOYOTA_MARCHA` é uma marcha da reduzida (`1ª L`..`9ª L`)? */
    fun ehReduzida(codigo: Int): Boolean = codigo in (BASE_REDUZIDA + 1)..(BASE_REDUZIDA + 9)

    /** Rótulos de `TOYOTA_MARCHA`: N, 1ª–6ª, 1ª L–5ª L (reduzida), R e Parado. */
    val ROTULOS_MARCHA: Map<Int, String> = buildMap {
        put(MARCHA_NEUTRO, "N")
        for (m in 1..6) put(m, "${m}ª")
        for (m in 1..5) put(codigoReduzida(m), "${m}ª L")
        put(MARCHA_RE, "R")
        put(MARCHA_PARADO, "Parado")
    }

    /** Marcha da tabela da A750F para uma razão `rpm / velocidade` já calculada. */
    fun marchaDaRazao(razao: Double): Int = when {
        razao >= 85.0 -> 1
        razao >= 55.0 -> 2
        razao >= 36.0 -> 3
        razao >= 26.0 -> 4
        else -> 5
    }

    /** Alavanca à frente (D, 4, 3, 2 ou L): a marcha sai da razão rpm/velocidade. */
    fun alavancaAFrente(alavanca: Double): Boolean = alavanca == Decodificadores.ALAVANCA_D ||
        alavanca == Decodificadores.ALAVANCA_4 || alavanca == Decodificadores.ALAVANCA_3 ||
        alavanca == Decodificadores.ALAVANCA_2 || alavanca == Decodificadores.ALAVANCA_L

    /**
     * Marcha da A750F pela razão `rpm / velocidade` (km/h): `≥ 85` = 1ª, `55–85` = 2ª, `36–55` = 3ª,
     * `26–36` = 4ª, `< 26` = 5ª. Null abaixo de [VELOCIDADE_MINIMA_KMH] ou sem leitura válida.
     */
    fun marchaPorRazao(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (rpm == null || velocidadeKmh == null || !rpm.isFinite() || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < VELOCIDADE_MINIMA_KMH || rpm <= 0.0) return null
        return marchaDaRazao(rpm / velocidadeKmh)
    }

    /**
     * Marcha exibida para a [alavanca]: R → [MARCHA_RE]; D, 4, 3, 2 e L andando (≥ 5 km/h) → 1..5 pela
     * razão; P/N andando → [MARCHA_NEUTRO]; P/N, M ou abaixo de 5 km/h → [MARCHA_PARADO].
     * Null em D/4/3/2/L andando sem RPM (não dá para estimar). v2.2.6: com [fatorReduzida] (carro
     * em 4L), a razão é dividida pelo fator antes da tabela e a marcha sai como `1ª L`..`5ª L`.
     */
    fun marchaExibida(alavanca: Double, rpm: Double?, velocidadeKmh: Double?, fatorReduzida: Double? = null): Int? {
        val andando = velocidadeKmh != null && velocidadeKmh.isFinite() && velocidadeKmh >= VELOCIDADE_MINIMA_KMH
        return when (alavanca) {
            Decodificadores.ALAVANCA_R -> MARCHA_RE
            Decodificadores.ALAVANCA_D, Decodificadores.ALAVANCA_4, Decodificadores.ALAVANCA_3,
            Decodificadores.ALAVANCA_2, Decodificadores.ALAVANCA_L -> when {
                !andando -> MARCHA_PARADO
                fatorReduzida != null -> marchaPorRazao(rpm?.div(fatorReduzida), velocidadeKmh)?.let(::codigoReduzida)
                else -> marchaPorRazao(rpm, velocidadeKmh)
            }
            Decodificadores.ALAVANCA_P_N, Decodificadores.ALAVANCA_N -> if (andando) MARCHA_NEUTRO else MARCHA_PARADO
            else -> MARCHA_PARADO
        }
    }
}

/**
 * v2.2.6: detecção automática da reduzida (`4L`) pela razão `rpm / velocidade`, sem nenhum comando
 * extra ao ELM327. Só age com o câmbio tendo `fatorReduzida` e a alavanca à frente (D, 4, 3, 2, L):
 *
 * - **entra em 4L** depois de [AMOSTRAS_ENTRADA] amostras seguidas com `velocidade ≥ 5 km/h`,
 *   `RPM ≥ 1.200` e razão `> 185` (≈ 1,7 × a razão máxima da 1ª normal, ~109 na SW4): uma patinada
 *   isolada em 4H (roda girando em falso, conversor deslizando) não basta;
 * - **sai de 4L** com a alavanca em P/N e o carro parado (a troca 4H↔4L da SW4 exige parar em N) ou
 *   depois de [AMOSTRAS_SAIDA] amostras seguidas com `velocidade ≥ 15 km/h` e razão que só a 4H
 *   normal dá: abaixo de [razaoSaida] (70 % da menor razão possível em 4L, a da marcha mais longa
 *   vezes o fator).
 *
 * Objeto puro e não thread-safe (um por sessão), testável na JVM.
 */
class DetectorReduzida(
    val fatorReduzida: Double,
    /** Menor razão mínima positiva da tabela normal (A750F: 26, a da 4ª): base do limiar de saída. */
    razaoMinimaMarchaLonga: Double = RAZAO_MINIMA_MARCHA_LONGA_A750F,
) {
    init {
        require(fatorReduzida.isFinite() && fatorReduzida > 1.0) { "Fator da reduzida inválido: $fatorReduzida" }
    }

    /** Abaixo desta razão (com o carro a ≥ 15 km/h) a leitura só é possível em 4H. */
    val razaoSaida: Double = razaoMinimaMarchaLonga * fatorReduzida * FRACAO_SAIDA

    var emReduzida: Boolean = false
        private set

    private var seguidasEntrada = 0
    private var seguidasSaida = 0

    /** Atualiza o estado com uma amostra; devolve se o carro está em 4L agora. */
    fun atualizar(alavanca: Double?, rpm: Double?, velocidadeKmh: Double?): Boolean {
        val vel = velocidadeKmh?.takeIf { it.isFinite() }
        val giro = rpm?.takeIf { it.isFinite() }
        val pn = alavanca == Decodificadores.ALAVANCA_P_N || alavanca == Decodificadores.ALAVANCA_P ||
            alavanca == Decodificadores.ALAVANCA_N
        if (pn && vel != null && vel <= 0.0) {
            reiniciar()
            return false
        }
        val frente = alavanca != null && CambioA750f.alavancaAFrente(alavanca)
        val razao = if (vel != null && giro != null && vel > 0.0) giro / vel else null
        if (!emReduzida) {
            val indica = frente && vel != null && vel >= CambioA750f.VELOCIDADE_MINIMA_KMH &&
                giro != null && giro >= RPM_MINIMO_ENTRADA && razao != null && razao > RAZAO_ENTRADA
            seguidasEntrada = if (indica) seguidasEntrada + 1 else 0
            if (seguidasEntrada >= AMOSTRAS_ENTRADA) {
                emReduzida = true
                seguidasEntrada = 0
                seguidasSaida = 0
            }
        } else {
            val indica4h = vel != null && vel >= VELOCIDADE_SAIDA_KMH && razao != null && razao < razaoSaida
            seguidasSaida = if (indica4h) seguidasSaida + 1 else 0
            if (seguidasSaida >= AMOSTRAS_SAIDA) {
                emReduzida = false
                seguidasSaida = 0
            }
        }
        return emReduzida
    }

    /** Volta a 4H sem histórico (nova conexão, ou P/N parado). */
    fun reiniciar() {
        emReduzida = false
        seguidasEntrada = 0
        seguidasSaida = 0
    }

    companion object {
        const val RAZAO_ENTRADA = 185.0
        const val RPM_MINIMO_ENTRADA = 1_200.0
        const val AMOSTRAS_ENTRADA = 3
        const val VELOCIDADE_SAIDA_KMH = 15.0
        const val AMOSTRAS_SAIDA = 3
        const val FRACAO_SAIDA = 0.7
        const val RAZAO_MINIMA_MARCHA_LONGA_A750F = 26.0

        /** Detector para o [cambio] (null quando ele não tem reduzida). */
        fun para(cambio: PerfilCambio?): DetectorReduzida? {
            val fator = cambio?.fatorReduzida ?: return null
            val longa = cambio.relacoes.map { it.razaoMinima }.filter { it > 0.0 }.minOrNull() ?: RAZAO_MINIMA_MARCHA_LONGA_A750F
            return DetectorReduzida(fator, longa)
        }
    }
}

/**
 * Pós-processamento do instantâneo da SW4 (A750F) na sessão: publica a marcha estimada em
 * `TOYOTA_MARCHA` quando o `2130` não traz uma marcha válida (ausente ou `0xFF`). A alavanca do
 * `7E1 · 21D9` fica como veio (`P/N` não distingue P de N). Sem a alavanca do `7E1` no instantâneo
 * (outros veículos), o instantâneo segue igual. v2.2.6: com o câmbio do perfil tendo `fatorReduzida`,
 * detecta a reduzida ([DetectorReduzida]) e publica `1ª L`..`5ª L` ([CambioA750f.codigoReduzida]).
 */
class EstimadorCambioA750f(
    /** Câmbio do perfil em uso (relido a cada amostra: o perfil automático pode trocar no meio da sessão). */
    private val cambio: () -> PerfilCambio? = { null },
) {
    private var detector: DetectorReduzida? = null

    /** Está em 4L agora? (false sem câmbio com reduzida.) */
    val emReduzida: Boolean get() = detector?.emReduzida == true

    fun aplicar(dados: DadosObd): DadosObd {
        val alavanca = dados.valores[CambioA750f.CHAVE_ALAVANCA]?.takeIf { it.isFinite() } ?: return dados
        val velocidade = dados.valores[Pids.VELOCIDADE]?.takeIf { it.isFinite() }
        val rpm = dados.valores[Pids.RPM]
        val reduzida = detectorAtual()?.atualizar(alavanca, rpm, velocidade) == true
        val daEcu = dados.valores[CambioA750f.CHAVE_MARCHA]?.takeIf { it.isFinite() }?.toInt()
        if (daEcu != null && daEcu in 1..5) return dados
        val fator = if (reduzida) detector?.fatorReduzida else null
        val marcha = CambioA750f.marchaExibida(alavanca, rpm, velocidade, fator) ?: return dados
        return DadosObd(dados.valores + (CambioA750f.CHAVE_MARCHA to marcha.toDouble()))
    }

    /** Nova sessão: esquece a reduzida. */
    fun reiniciar() {
        detector?.reiniciar()
    }

    private fun detectorAtual(): DetectorReduzida? {
        val c = cambio()
        if (c?.fatorReduzida == null) {
            detector = null
            return null
        }
        val atual = detector
        if (atual != null && atual.fatorReduzida == c.fatorReduzida) return atual
        return DetectorReduzida.para(c).also { detector = it }
    }
}
