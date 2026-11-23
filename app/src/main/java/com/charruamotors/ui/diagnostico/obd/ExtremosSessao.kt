package com.charruamotors.ui.diagnostico.obd

/** Menor e maior valor de um sensor desde o início da sessão (na unidade do catálogo). */
data class Extremos(val min: Double, val max: Double)

/**
 * Mín/Máx registrados na sessão para cada sensor da grade "Sensores suportados pela ECU".
 * Só valores finitos entram; [limpar] zera tudo (nova conexão). Classe pura (JVM).
 */
class ExtremosSessao {

    private val porComando = HashMap<String, Extremos>()

    /** Registra [valor] de [comando] (ignora null, NaN e infinito) e devolve os extremos atuais. */
    fun registrar(comando: String, valor: Double?): Extremos? {
        if (valor == null || !valor.isFinite()) return porComando[comando]
        val atual = porComando[comando]
        val novo = if (atual == null) Extremos(valor, valor) else Extremos(minOf(atual.min, valor), maxOf(atual.max, valor))
        porComando[comando] = novo
        return novo
    }

    operator fun get(comando: String): Extremos? = porComando[comando]

    fun limpar() = porComando.clear()
}
