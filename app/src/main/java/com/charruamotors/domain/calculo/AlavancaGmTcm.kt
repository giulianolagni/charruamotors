package com.charruamotors.domain.calculo

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import kotlin.math.roundToInt

/**
 * v2.3.7 — posição da alavanca e marcha do TCM GM 6T30/6T35 (`ATSH 7E2`, UDS 22).
 *
 * A marcha atual (`22 199A`) responde no carro. O PID da alavanca **ainda é desconhecido** (v2.3.8):
 * os candidatos de [IDS_SONDA] são sondados na conexão e os que respondem `62...` têm o payload
 * gravado no log a cada mudança. [CHAVE_SELETOR] fica sem PID no catálogo até essa calibração; a
 * tabela `0`=P, `1`=R, `2`=N, `3`=D, `4`=M é hipótese.
 */
object AlavancaGmTcm {

    /** Chave do seletor decodificado (sem PID no catálogo até a calibração). */
    const val CHAVE_SELETOR = "GM_TCM_SELETOR"
    const val CHAVE_MARCHA_TCM = "GM_TCM_MARCHA"

    /** Candidatos GM Global A a seletor/PRNDL/IMS, na ordem da sondagem. */
    val IDS_SONDA: List<String> =
        listOf("19AD", "1925", "192A", "192F", "1963", "1982", "19B5", "2889", "1941")

    /** Chaves dos candidatos no catálogo (`pids_estendidos.json`, perfil GM). */
    val CHAVES_SONDA: List<String> = IDS_SONDA.map { "GM_TCM_SONDA_$it" }

    /** Chave calculada pela sessão: posição exibida (`P`, `R`, `N`, `D`, `M`, `M1`–`M6`). */
    const val CHAVE_POSICAO = "GM_ALAVANCA"

    val CHAVES_SELETOR: List<String> = listOf(CHAVE_SELETOR)
    val CHAVES_TCM: List<String> = CHAVES_SONDA + CHAVE_MARCHA_TCM

    /** DIDs cuja resposta precisa ter exatamente 1 byte de dados (mais bytes = é outro parâmetro). */
    val IDS_BYTE_UNICO: Set<String> = setOf("199A")

    /** DIDs do `7E2` cujo payload bruto vai para o log a cada mudança. */
    val IDS_REGISTRADOS: Set<String> = IDS_BYTE_UNICO + IDS_SONDA

    /** Códigos de [CHAVE_POSICAO], fora das faixas de marcha/alavanca da A750F. */
    const val P = 100
    const val R = 101
    const val N = 102
    const val D = 103
    const val M = 104
    private const val BASE_MANUAL = 110

    val ROTULOS: Map<Int, String> = buildMap {
        put(P, "P")
        put(R, "R")
        put(N, "N")
        put(D, "D")
        put(M, "M")
        for (m in 1..6) put(BASE_MANUAL + m, "M$m")
    }

    /** Acima disto a ré não é plausível. */
    private const val VELOCIDADE_MAXIMA_RE_KMH = 30.0

    /**
     * Posição exibida a partir do byte do [seletor] e, em modo manual, da [marcha] do TCM. Null se o
     * valor não está na tabela ou contradiz a velocidade (P andando, R acima de 30 km/h).
     */
    fun posicao(seletor: Double?, marcha: Double?, velocidadeKmh: Double?): Int? {
        val codigo = when (inteiro(seletor)) {
            0 -> P
            1 -> R
            2 -> N
            3 -> D
            4 -> M
            else -> return null
        }
        val v = velocidadeKmh?.takeIf { it.isFinite() }
        if (v != null && codigo == P && v >= CambioA750f.VELOCIDADE_MINIMA_KMH) return null
        if (v != null && codigo == R && v > VELOCIDADE_MAXIMA_RE_KMH) return null
        if (codigo != M) return codigo
        val engatada = inteiro(marcha)?.takeIf { it in 1..6 } ?: return M
        return BASE_MANUAL + engatada
    }

    /** [dados] com [CHAVE_POSICAO], pelo primeiro seletor válido; null se o TCM não deu uma posição. */
    fun aplicar(dados: DadosObd): DadosObd? {
        val posicao = CHAVES_SELETOR.firstNotNullOfOrNull {
            posicao(dados.valores[it], dados.valores[CHAVE_MARCHA_TCM], dados.valores[Pids.VELOCIDADE])
        } ?: return null
        return DadosObd(dados.valores + (CHAVE_POSICAO to posicao.toDouble()))
    }

    /** Rótulo da posição publicada no instantâneo (null sem [CHAVE_POSICAO]). */
    fun rotulo(valor: Double?): String? = valor?.takeIf { it.isFinite() }?.let { ROTULOS[it.roundToInt()] }

    private fun inteiro(valor: Double?): Int? = valor?.takeIf { it.isFinite() }?.roundToInt()
}
