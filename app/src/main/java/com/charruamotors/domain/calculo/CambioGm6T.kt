package com.charruamotors.domain.calculo

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids

/**
 * Câmbio automático GM 6T35/6T40 (Chevrolet Cruze 1.4 Turbo Flex 2018, LE2) visto pelo app: sem PID
 * direto de marcha, a marcha engatada (1ª–6ª) é estimada pela razão `rpm / velocidade`. Funções
 * puras (JVM). Publica na mesma chave da marcha da A750F ([CambioA750f.CHAVE_MARCHA]).
 */
object CambioGm6T {

    /** Abaixo disto o veículo conta como parado (a razão rpm/velocidade não vale). */
    const val VELOCIDADE_MINIMA_KMH = CambioA750f.VELOCIDADE_MINIMA_KMH

    /**
     * Marcha pela razão `rpm / velocidade` (km/h): `≥ 115` = 1ª, `72–115` = 2ª, `49–72` = 3ª,
     * `36–49` = 4ª, `27–36` = 5ª, `< 27` = 6ª. Null abaixo de [VELOCIDADE_MINIMA_KMH] ou sem leitura válida.
     */
    fun marchaPorRazao(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (rpm == null || velocidadeKmh == null || !rpm.isFinite() || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < VELOCIDADE_MINIMA_KMH || rpm <= 0.0) return null
        val razao = rpm / velocidadeKmh
        return when {
            razao >= 115.0 -> 1
            razao >= 72.0 -> 2
            razao >= 49.0 -> 3
            razao >= 36.0 -> 4
            razao >= 27.0 -> 5
            else -> 6
        }
    }

    /** Marcha exibida: abaixo de 5 km/h, [CambioA750f.MARCHA_PARADO]; andando, a estimada (null sem RPM). */
    fun marchaExibida(rpm: Double?, velocidadeKmh: Double?): Int? {
        if (velocidadeKmh == null || !velocidadeKmh.isFinite()) return null
        if (velocidadeKmh < VELOCIDADE_MINIMA_KMH) return CambioA750f.MARCHA_PARADO
        return marchaPorRazao(rpm, velocidadeKmh)
    }
}

/**
 * Pós-processamento do instantâneo do Cruze na sessão. v2.3.7: com a posição do TCM `7E2`
 * ([AlavancaGmTcm]), publica [AlavancaGmTcm.CHAVE_POSICAO] no lugar da estimativa; sem ela, publica a
 * marcha estimada em `TOYOTA_MARCHA` quando a ECU não traz marcha própria.
 */
class EstimadorCambioGm6T {

    fun aplicar(dados: DadosObd): DadosObd {
        AlavancaGmTcm.aplicar(dados)?.let { return it }
        if (dados.valores[CambioA750f.CHAVE_MARCHA]?.isFinite() == true) return dados
        val velocidade = dados.valores[Pids.VELOCIDADE]?.takeIf { it.isFinite() }
        val marcha = CambioGm6T.marchaExibida(dados.valores[Pids.RPM], velocidade) ?: return dados
        return DadosObd(dados.valores + (CambioA750f.CHAVE_MARCHA to marcha.toDouble()))
    }
}
