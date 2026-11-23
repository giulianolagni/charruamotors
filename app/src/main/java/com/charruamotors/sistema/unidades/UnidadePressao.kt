package com.charruamotors.sistema.unidades

/**
 * Unidade de exibição das pressões (Turbo/MAP no mostrador principal, grade de PIDs e HUD).
 * Os PIDs chegam sempre em kPa (`010B`, `0123`, `0133`...); a conversão é só de exibição —
 * logs `.csv`/`.txt` e o banco continuam em kPa.
 */
enum class UnidadePressao(
    val simbolo: String,
    /** Quantos kPa vale 1 unidade. */
    val kpaPorUnidade: Double,
    /** Casas decimais usadas na exibição (kPa inteiro, bar com 2 casas, psi com 1). */
    val casasDecimais: Int,
    /** Escala do mostrador de Turbo/MAP nesta unidade. */
    val mostradorMap: FaixaMostrador,
) {
    KPA("kPa", 1.0, 0, FaixaMostrador(maximo = 250.0, zonaVermelha = 200.0, divisoes = 5, subdivisoes = 5)),
    BAR("bar", 100.0, 2, FaixaMostrador(maximo = 2.5, zonaVermelha = 2.0, divisoes = 5, subdivisoes = 5)),
    PSI("psi", KPA_POR_PSI, 1, FaixaMostrador(maximo = 35.0, zonaVermelha = 29.0, divisoes = 7, subdivisoes = 5)),
    ;

    /** Converte um valor em kPa para esta unidade. */
    fun deKpa(kpa: Double): Double = kpa / kpaPorUnidade

    fun deKpa(kpa: Double?): Double? = kpa?.let { deKpa(it) }

    /** Casas para um PID de pressão: nunca menos do que o catálogo já pede. */
    fun casasPara(casasCatalogo: Int): Int = maxOf(casasCatalogo, casasDecimais)

    companion object {
        val PADRAO = KPA

        /** Lê o nome salvo nas preferências/backup; qualquer valor desconhecido volta ao padrão. */
        fun deCodigo(codigo: String?): UnidadePressao = entries.firstOrNull { it.name.equals(codigo, ignoreCase = true) } ?: PADRAO

        /** A unidade do catálogo indica uma pressão convertível? */
        fun ehPressao(unidadeCatalogo: String?): Boolean = unidadeCatalogo.equals(KPA.simbolo, ignoreCase = true)
    }
}

/** 1 psi = 6,894757 kPa. */
private const val KPA_POR_PSI = 6.894757293168361

/** Faixa de um mostrador analógico (mínimo sempre 0). */
data class FaixaMostrador(
    val maximo: Double,
    val zonaVermelha: Double?,
    val divisoes: Int,
    val subdivisoes: Int,
)
