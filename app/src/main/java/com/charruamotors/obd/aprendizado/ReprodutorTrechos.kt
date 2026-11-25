package com.charruamotors.obd.aprendizado

import kotlin.math.floor

/**
 * Reproduz em loop os [TrechoReal]s aprendidos de um veículo: um ciclo contínuo (trecho 1, 2, ...,
 * de volta ao 1) com [transicaoS] segundos interpolados em cada emenda, para a velocidade, o RPM e
 * os demais sensores não "pularem" no fim do loop. Sensores de montadora com valor inteiro (marcha,
 * alavanca) mudam em degrau; os PIDs padrão (`01xx`, `ATRV`) e os demais, por interpolação linear. Todo instante traz todos os sensores do ciclo.
 */
class ReprodutorTrechos(trechos: List<TrechoReal>, transicaoS: Int = 3) {

    /** Sensores presentes em algum trecho. */
    val comandos: Set<String>

    private val ciclo: List<Map<String, Double>>
    private var posicaoS = 0.0

    init {
        val uteis = trechos.filter { it.linhas.isNotEmpty() }
        comandos = uteis.flatMapTo(linkedSetOf()) { it.comandos }
        val linhas = uteis.map { t -> t.linhas.map { linha -> t.comandos.indices.associate { t.comandos[it] to linha[it] }.filterValues { v -> v.isFinite() } } }
        val bruto = ArrayList<Map<String, Double>>()
        linhas.forEachIndexed { i, trecho ->
            if (i > 0) emendar(bruto.last(), trecho.first(), transicaoS, bruto)
            bruto += trecho
        }
        if (bruto.isNotEmpty()) emendar(bruto.last(), bruto.first(), transicaoS, bruto)
        ciclo = preencher(bruto, comandos)
    }

    /** Há o que reproduzir? */
    val vazio: Boolean get() = ciclo.isEmpty()

    fun reiniciar() {
        posicaoS = 0.0
    }

    /** Avança [dt] segundos e devolve o instantâneo (vazio se não houver trecho). */
    fun proxima(dt: Double): Map<String, Double> {
        if (ciclo.isEmpty()) return emptyMap()
        posicaoS = (posicaoS + dt) % ciclo.size
        val i0 = floor(posicaoS).toInt().coerceIn(0, ciclo.size - 1)
        val fracao = posicaoS - i0
        return mesclar(ciclo[i0], ciclo[(i0 + 1) % ciclo.size], fracao)
    }

    private companion object {
        /** PIDs padrão são contínuos (RPM e velocidade em inteiros não podem virar degrau). */
        val PADRAO = Regex("^(01[0-9A-F]{2}|ATRV)$")

        fun mesclar(a: Map<String, Double>, b: Map<String, Double>, f: Double): Map<String, Double> {
            if (f <= 0.0) return a
            val saida = LinkedHashMap<String, Double>(a.size)
            for ((cmd, va) in a) {
                val vb = b[cmd] ?: va
                val inteiros = va == floor(va) && vb == floor(vb) && !PADRAO.matches(cmd)
                saida[cmd] = when {
                    va == vb -> va
                    inteiros -> if (f < 0.5) va else vb
                    else -> va + (vb - va) * f
                }
            }
            return saida
        }

        /** [n] instantes interpolados entre [de] e [para] (exclusivos). */
        fun emendar(de: Map<String, Double>, para: Map<String, Double>, n: Int, destino: MutableList<Map<String, Double>>) {
            for (k in 1..n) destino += mesclar(de, para, k / (n + 1.0))
        }

        /** Todo instante com todos os sensores: lacunas pelo último valor; as iniciais pelo primeiro que aparecer. */
        fun preencher(linhas: List<Map<String, Double>>, comandos: Set<String>): List<Map<String, Double>> {
            val corrente = HashMap<String, Double>()
            for (cmd in comandos) linhas.firstNotNullOfOrNull { it[cmd] }?.let { corrente[cmd] = it }
            return linhas.map { l ->
                corrente.putAll(l)
                LinkedHashMap(corrente)
            }
        }
    }
}
