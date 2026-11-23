package com.charruamotors.domain.calculo

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
     * Marcha da A750F pela razão `rpm / velocidade` (km/h): `≥ 85` = 1ª, `55–85` = 2ª, `36–55` = 3ª,
     * `26–36` = 4ª, `< 26` = 5ª. Null abaixo de [VELOCIDADE_MINIMA_KMH] ou sem leitura válida.
     */
    fun marchaPorRazao(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (rpm == null || velocidadeKmh == null || !rpm.isFinite() || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < VELOCIDADE_MINIMA_KMH || rpm <= 0.0) return null
        val razao = rpm / velocidadeKmh
        return when {
            razao >= 85.0 -> 1
            razao >= 55.0 -> 2
            razao >= 36.0 -> 3
            razao >= 26.0 -> 4
            else -> 5
        }
    }

    /**
     * Marcha exibida para a [alavanca]: R → [MARCHA_RE]; D, 2/3 e L andando (≥ 5 km/h) → 1..5 pela
     * razão; P/N andando → [MARCHA_NEUTRO]; P/N, M ou abaixo de 5 km/h → [MARCHA_PARADO].
     * Null em D/2/3/L andando sem RPM (não dá para estimar).
     */
    fun marchaExibida(alavanca: Double, rpm: Double?, velocidadeKmh: Double?): Int? {
        val andando = velocidadeKmh != null && velocidadeKmh.isFinite() && velocidadeKmh >= VELOCIDADE_MINIMA_KMH
        return when (alavanca) {
            Decodificadores.ALAVANCA_R -> MARCHA_RE
            Decodificadores.ALAVANCA_D, Decodificadores.ALAVANCA_2_3, Decodificadores.ALAVANCA_L ->
                if (andando) marchaPorRazao(rpm, velocidadeKmh) else MARCHA_PARADO
            Decodificadores.ALAVANCA_P_N, Decodificadores.ALAVANCA_N -> if (andando) MARCHA_NEUTRO else MARCHA_PARADO
            else -> MARCHA_PARADO
        }
    }
}

/**
 * Pós-processamento do instantâneo da SW4 (A750F) na sessão: publica a marcha estimada em
 * `TOYOTA_MARCHA` quando o `2130` não traz uma marcha válida (ausente ou `0xFF`). A alavanca do
 * `7E1 · 21D9` fica como veio (`P/N` não distingue P de N). Sem a alavanca do `7E1` no instantâneo
 * (outros veículos), o instantâneo segue igual.
 */
class EstimadorCambioA750f {

    fun aplicar(dados: DadosObd): DadosObd {
        val alavanca = dados.valores[CambioA750f.CHAVE_ALAVANCA]?.takeIf { it.isFinite() } ?: return dados
        val velocidade = dados.valores[Pids.VELOCIDADE]?.takeIf { it.isFinite() }
        val daEcu = dados.valores[CambioA750f.CHAVE_MARCHA]?.takeIf { it.isFinite() }?.toInt()
        if (daEcu != null && daEcu in 1..5) return dados
        val marcha = CambioA750f.marchaExibida(alavanca, dados.valores[Pids.RPM], velocidade) ?: return dados
        return DadosObd(dados.valores + (CambioA750f.CHAVE_MARCHA to marcha.toDouble()))
    }
}
