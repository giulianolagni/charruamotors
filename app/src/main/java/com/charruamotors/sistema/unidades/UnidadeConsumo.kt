package com.charruamotors.sistema.unidades

/**
 * Unidade de exibição do consumo no sistema métrico (v2.3.0, Configurações → Unidades). O computador
 * de bordo, o banco e os logs continuam em km/L; a conversão é só de exibição. No imperial vale MPG.
 */
enum class UnidadeConsumo(val simbolo: String) {
    /** Quilômetros por litro (maior = mais econômico). */
    KM_L("km/L"),

    /** Litros a cada 100 km (menor = mais econômico), o padrão europeu. */
    L_100KM("L/100km"),
    ;

    /**
     * Converte um consumo em km/L para esta unidade. Em L/100km, 0 km/L (parado) não tem valor
     * finito: devolve NaN (o mostrador fica em "—"); o resultado é limitado a [MAX_L_100KM].
     */
    fun deKmPorLitro(kmPorLitro: Double): Double = when (this) {
        KM_L -> kmPorLitro
        L_100KM -> if (kmPorLitro > 0.0 && kmPorLitro.isFinite()) (100.0 / kmPorLitro).coerceAtMost(MAX_L_100KM) else Double.NaN
    }

    companion object {
        val PADRAO = KM_L

        /** Teto do L/100km exibido (quase parado o valor dispara). */
        const val MAX_L_100KM = 99.9

        fun deCodigo(codigo: String?): UnidadeConsumo = entries.firstOrNull { it.name.equals(codigo, ignoreCase = true) } ?: PADRAO
    }
}
