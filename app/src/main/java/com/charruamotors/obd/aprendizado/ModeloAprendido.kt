package com.charruamotors.obd.aprendizado

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Regime de condução em que uma amostra real foi observada (por RPM, velocidade e carga). */
enum class RegimeConducao(val rotulo: String) {
    LENTA("lenta"),
    CRUZEIRO("cruzeiro"),
    CARGA("carga"),
    ;

    companion object {
        private const val VELOCIDADE_PARADO_KMH = 3.0
        private const val RPM_LENTA_MAX = 1200.0
        private const val RPM_CARGA_MIN = 3500.0
        private const val CARGA_ALTA_PERCENT = 70.0

        /** Sem RPM nem velocidade a amostra não tem como ser classificada (null). */
        fun classificar(rpm: Double, velocidade: Double, cargaMotor: Double): RegimeConducao? {
            if (rpm.isNaN() && velocidade.isNaN()) return null
            if ((!cargaMotor.isNaN() && cargaMotor >= CARGA_ALTA_PERCENT) || (!rpm.isNaN() && rpm >= RPM_CARGA_MIN)) return CARGA
            val parado = if (velocidade.isNaN()) true else velocidade < VELOCIDADE_PARADO_KMH
            val girandoPouco = rpm.isNaN() || rpm <= RPM_LENTA_MAX
            return if (parado && girandoPouco) LENTA else CRUZEIRO
        }
    }
}

/** Agregado de um sensor num regime. Só somas e extremos: dois agregados se unem sem reler os CSVs. */
data class EstatisticaFaixa(
    val n: Int = 0,
    val soma: Double = 0.0,
    val somaQuadrados: Double = 0.0,
    val minimo: Double = Double.NaN,
    val maximo: Double = Double.NaN,
) {
    val media: Double get() = if (n == 0) Double.NaN else soma / n

    val desvio: Double
        get() = if (n < 2) 0.0 else sqrt(((somaQuadrados / n) - media * media).coerceAtLeast(0.0))

    fun somar(valor: Double): EstatisticaFaixa {
        if (!valor.isFinite()) return this
        return EstatisticaFaixa(
            n + 1, soma + valor, somaQuadrados + valor * valor,
            if (minimo.isNaN()) valor else min(minimo, valor),
            if (maximo.isNaN()) valor else max(maximo, valor),
        )
    }

    fun unir(o: EstatisticaFaixa): EstatisticaFaixa = when {
        o.n == 0 -> this
        n == 0 -> o
        else -> EstatisticaFaixa(n + o.n, soma + o.soma, somaQuadrados + o.somaQuadrados, min(minimo, o.minimo), max(maximo, o.maximo))
    }
}

/** CSV real já consolidado no modelo (o nome evita incorporar o mesmo arquivo duas vezes). */
data class LogIncorporado(val nome: String, val amostras: Int)

/**
 * Trecho real de condução reamostrado a [passoS] segundos: uma linha por instante com um valor por
 * sensor em [comandos] (`NaN` = sem leitura naquele instante).
 */
class TrechoReal(val comandos: List<String>, val linhas: List<DoubleArray>, val passoS: Double = 1.0)

/**
 * O que o app aprendeu dos CSVs **reais** de um único veículo ([veiculoId]): faixas observadas por
 * regime e trechos de condução para o Modo Simulado reproduzir. Nunca mistura veículos: cada modelo
 * nasce só dos logs registrados para o seu [veiculoId].
 */
data class ModeloAprendido(
    val veiculoId: Long,
    val logs: List<LogIncorporado> = emptyList(),
    /** CSVs que não deram para ler (corrompidos, vazios ou sem sensores): não são tentados de novo. */
    val ignorados: List<String> = emptyList(),
    val faixas: Map<RegimeConducao, Map<String, EstatisticaFaixa>> = emptyMap(),
    val trechos: List<TrechoReal> = emptyList(),
) {
    val quantidadeLogs: Int get() = logs.size

    /** Há com o que animar o Painel (trecho com RPM)? Senão o Modo Simulado segue sintético. */
    val utilizavel: Boolean get() = trechos.any { com.charruamotors.obd.pid.Pids.RPM in it.comandos }

    fun faixa(regime: RegimeConducao, comando: String): EstatisticaFaixa? = faixas[regime]?.get(comando)

    fun paraJson(): String {
        val raiz = JSONObject()
        raiz.put("versao", VERSAO)
        raiz.put("veiculoId", veiculoId)
        raiz.put("logs", JSONArray().also { a -> logs.forEach { a.put(JSONObject().put("nome", it.nome).put("amostras", it.amostras)) } })
        raiz.put("ignorados", JSONArray(ignorados))
        val jf = JSONObject()
        for ((regime, mapa) in faixas) {
            val jr = JSONObject()
            for ((cmd, e) in mapa) {
                jr.put(cmd, JSONObject().put("n", e.n).put("soma", e.soma).put("somaQuad", e.somaQuadrados).put("min", e.minimo).put("max", e.maximo))
            }
            jf.put(regime.name, jr)
        }
        raiz.put("faixas", jf)
        raiz.put(
            "trechos",
            JSONArray().also { a ->
                for (t in trechos) {
                    val linhas = JSONArray()
                    for (l in t.linhas) {
                        val jl = JSONArray()
                        // NaN não existe em JSON: vira null.
                        l.forEach { v -> jl.put(if (v.isFinite()) v else JSONObject.NULL) }
                        linhas.put(jl)
                    }
                    a.put(JSONObject().put("passoS", t.passoS).put("comandos", JSONArray(t.comandos)).put("linhas", linhas))
                }
            },
        )
        return raiz.toString()
    }

    companion object {
        const val VERSAO = 1

        /** Modelo vazio de um veículo (ainda sem nenhum log real incorporado). */
        fun vazio(veiculoId: Long) = ModeloAprendido(veiculoId)

        /** null = JSON ilegível, de outra versão ou incoerente (o chamador reconstrói a partir dos CSVs). */
        fun deJson(texto: String): ModeloAprendido? = try {
            val raiz = JSONObject(texto)
            if (raiz.getInt("versao") != VERSAO) {
                null
            } else {
                val logs = raiz.getJSONArray("logs").let { a -> List(a.length()) { LogIncorporado(a.getJSONObject(it).getString("nome"), a.getJSONObject(it).getInt("amostras")) } }
                val ignorados = raiz.getJSONArray("ignorados").let { a -> List(a.length()) { a.getString(it) } }
                val faixas = raiz.getJSONObject("faixas").let { jf ->
                    jf.keys().asSequence().associate { chave ->
                        val jr = jf.getJSONObject(chave)
                        RegimeConducao.valueOf(chave) to jr.keys().asSequence().associateWith { cmd ->
                            val e = jr.getJSONObject(cmd)
                            EstatisticaFaixa(e.getInt("n"), e.getDouble("soma"), e.getDouble("somaQuad"), e.getDouble("min"), e.getDouble("max"))
                        }
                    }
                }
                val trechos = raiz.getJSONArray("trechos").let { a ->
                    List(a.length()) { i ->
                        val jt = a.getJSONObject(i)
                        val comandos = jt.getJSONArray("comandos").let { c -> List(c.length()) { c.getString(it) } }
                        val linhas = jt.getJSONArray("linhas").let { l ->
                            List(l.length()) { r ->
                                val jl = l.getJSONArray(r)
                                require(jl.length() == comandos.size) { "linha com tamanho incoerente" }
                                DoubleArray(jl.length()) { c -> if (jl.isNull(c)) Double.NaN else jl.getDouble(c) }
                            }
                        }
                        TrechoReal(comandos, linhas, jt.getDouble("passoS"))
                    }
                }
                ModeloAprendido(raiz.getLong("veiculoId"), logs, ignorados, faixas, trechos)
            }
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** De onde vêm os dados do Modo Simulado nesta conexão (indicador discreto no Painel). */
sealed interface FonteSimulacao {
    /** Simulador sintético de sempre (o veículo não tem logs reais utilizáveis). */
    data object Sintetica : FonteSimulacao

    /** Reproduz trechos aprendidos de [quantidade] CSVs reais do veículo selecionado. */
    data class LogsReais(val quantidade: Int) : FonteSimulacao
}
