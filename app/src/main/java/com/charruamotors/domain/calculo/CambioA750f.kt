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

    /** Velocidade que já conta como "em movimento" (o `010D` é inteiro: 0 = parado). */
    const val VELOCIDADE_MOVIMENTO_KMH = 1.0

    /** Depois de andar, a alavanca `00 00` segue como N por este tempo, mesmo a 0 km/h. */
    const val MEMORIA_MOVIMENTO_MS = 3_000L

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
     * `P/N` do `21D9` (bytes C/D `00 00`) resolvido: `P` parado (0 km/h) e sem movimento recente;
     * `N` em movimento ou logo depois dele. Sem velocidade, segue `P/N`.
     */
    fun resolverPN(velocidadeKmh: Double?, movimentoRecente: Boolean): Double = when {
        velocidadeKmh == null || !velocidadeKmh.isFinite() -> Decodificadores.ALAVANCA_P_N
        velocidadeKmh < VELOCIDADE_MOVIMENTO_KMH && !movimentoRecente -> Decodificadores.ALAVANCA_P
        else -> Decodificadores.ALAVANCA_N
    }

    /**
     * Marcha exibida para a [alavanca] já resolvida: R → [MARCHA_RE]; D andando (≥ 5 km/h) → 1..5
     * pela razão; N andando → [MARCHA_NEUTRO]; P, P/N ou abaixo de 5 km/h → [MARCHA_PARADO].
     * Null em D andando sem RPM (não dá para estimar).
     */
    fun marchaExibida(alavanca: Double, rpm: Double?, velocidadeKmh: Double?): Int? {
        val andando = velocidadeKmh != null && velocidadeKmh.isFinite() && velocidadeKmh >= VELOCIDADE_MINIMA_KMH
        return when (alavanca) {
            Decodificadores.ALAVANCA_R -> MARCHA_RE
            Decodificadores.ALAVANCA_D -> if (andando) marchaPorRazao(rpm, velocidadeKmh) else MARCHA_PARADO
            Decodificadores.ALAVANCA_N -> if (andando) MARCHA_NEUTRO else MARCHA_PARADO
            else -> MARCHA_PARADO
        }
    }
}

/**
 * Pós-processamento do instantâneo da SW4 (A750F) na sessão: troca o `P/N` da alavanca do
 * `7E1 · 21D9` por `P` ou `N` (lembrando do último movimento) e publica a marcha estimada em
 * `TOYOTA_MARCHA` quando o `2130` não traz uma marcha válida (ausente ou `0xFF`). Sem a alavanca
 * do `7E1` no instantâneo (outros veículos), o instantâneo segue igual.
 */
class EstimadorCambioA750f(private val memoriaMovimentoMs: Long = CambioA750f.MEMORIA_MOVIMENTO_MS) {

    private var ultimoMovimentoMs: Long? = null

    fun reiniciar() {
        ultimoMovimentoMs = null
    }

    fun aplicar(dados: DadosObd, agoraMs: Long): DadosObd {
        val velocidade = dados.valores[Pids.VELOCIDADE]?.takeIf { it.isFinite() }
        if (velocidade != null && velocidade >= CambioA750f.VELOCIDADE_MOVIMENTO_KMH) ultimoMovimentoMs = agoraMs
        val bruta = dados.valores[CambioA750f.CHAVE_ALAVANCA]?.takeIf { it.isFinite() } ?: return dados
        val movimentoRecente = ultimoMovimentoMs?.let { agoraMs - it < memoriaMovimentoMs } ?: false
        val alavanca = if (bruta == Decodificadores.ALAVANCA_P_N) CambioA750f.resolverPN(velocidade, movimentoRecente) else bruta
        val daEcu = dados.valores[CambioA750f.CHAVE_MARCHA]?.takeIf { it.isFinite() }?.toInt()
        val extras = buildMap {
            if (alavanca != bruta) put(CambioA750f.CHAVE_ALAVANCA, alavanca)
            if (daEcu == null || daEcu !in 1..5) {
                CambioA750f.marchaExibida(alavanca, dados.valores[Pids.RPM], velocidade)
                    ?.let { put(CambioA750f.CHAVE_MARCHA, it.toDouble()) }
            }
        }
        return if (extras.isEmpty()) dados else DadosObd(dados.valores + extras)
    }
}
