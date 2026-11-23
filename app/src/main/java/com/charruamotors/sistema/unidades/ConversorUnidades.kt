package com.charruamotors.sistema.unidades

/**
 * Unidades globais do app (Configurações → Unidades globais). Os PIDs, os logs `.csv`/`.txt` e o
 * banco continuam sempre no SI do OBD2 (km/h, °C, kPa, km, L); a conversão é só de exibição.
 */
enum class SistemaUnidades {
    /** km/h, °C, kPa/bar, km/L, km, L. */
    METRICO,

    /** mph, °F, psi, MPG (galão americano), milhas, galões. */
    IMPERIAL,
    ;

    companion object {
        val PADRAO = METRICO

        fun deCodigo(codigo: String?): SistemaUnidades = entries.firstOrNull { it.name.equals(codigo, ignoreCase = true) } ?: PADRAO
    }
}

/** Valor pronto para exibir: já convertido, com o símbolo e as casas decimais da unidade de destino. */
data class ValorExibicao(val valor: Double, val unidade: String, val casas: Int)

/**
 * Converte qualquer leitura (pela unidade do catálogo de PIDs) para o [sistema] escolhido. No
 * métrico a pressão segue a escolha de kPa/bar/psi das Configurações; no imperial é sempre psi.
 * Objeto puro, testável na JVM.
 */
data class ConversorUnidades(
    val sistema: SistemaUnidades = SistemaUnidades.PADRAO,
    private val pressaoMetrica: UnidadePressao = UnidadePressao.PADRAO,
) {
    /** Unidade efetiva das pressões (Turbo/MAP, combustível, barométrica). */
    val pressao: UnidadePressao get() = if (sistema == SistemaUnidades.IMPERIAL) UnidadePressao.PSI else pressaoMetrica

    val imperial: Boolean get() = sistema == SistemaUnidades.IMPERIAL

    // ---------------------------------------------------------------- grandezas

    fun velocidade(kmh: Double): Double = if (imperial) kmh / KM_POR_MILHA else kmh
    val simboloVelocidade: String get() = if (imperial) MPH else KMH

    fun temperatura(celsius: Double): Double = if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius
    val simboloTemperatura: String get() = if (imperial) FAHRENHEIT else CELSIUS

    fun pressao(kpa: Double): Double = pressao.deKpa(kpa)
    val simboloPressao: String get() = pressao.simbolo

    /** km/L → MPG (galão americano): 1 km/L = 2,352 MPG. */
    fun consumo(kmPorLitro: Double): Double = if (imperial) kmPorLitro * LITROS_POR_GALAO / KM_POR_MILHA else kmPorLitro
    val simboloConsumo: String get() = if (imperial) MPG else KM_L

    fun distancia(km: Double): Double = if (imperial) km / KM_POR_MILHA else km
    val simboloDistancia: String get() = if (imperial) MILHAS else KM

    fun volume(litros: Double): Double = if (imperial) litros / LITROS_POR_GALAO else litros
    val simboloVolume: String get() = if (imperial) GALOES else LITROS

    /** L/h → gal/h. */
    fun vazao(litrosPorHora: Double): Double = volume(litrosPorHora)
    val simboloVazao: String get() = if (imperial) GAL_H else L_H

    // ---------------------------------------------------------------- pela unidade do catálogo

    /**
     * Converte [valor] na [unidade] do catálogo (`km/h`, `°C`, `kPa`, `km/L`, `km`, `L`, `L/h`) e
     * devolve o símbolo e as casas de destino; outras unidades (`rpm`, `%`, `V`, `g/s`...) passam
     * intactas, com as [casas] do catálogo.
     */
    fun converter(valor: Double, unidade: String, casas: Int = 0): ValorExibicao = when (normalizar(unidade)) {
        KMH -> ValorExibicao(velocidade(valor), simboloVelocidade, casas)
        CELSIUS -> ValorExibicao(temperatura(valor), simboloTemperatura, casas)
        KPA_NORMALIZADO -> ValorExibicao(pressao(valor), simboloPressao, pressao.casasPara(casas))
        KM_L -> ValorExibicao(consumo(valor), simboloConsumo, maxOf(casas, 1))
        KM -> ValorExibicao(distancia(valor), simboloDistancia, if (imperial) maxOf(casas, 1) else casas)
        LITROS -> ValorExibicao(volume(valor), simboloVolume, if (imperial) maxOf(casas, 2) else casas)
        L_H -> ValorExibicao(vazao(valor), simboloVazao, if (imperial) maxOf(casas, 2) else casas)
        else -> ValorExibicao(valor, unidade, casas)
    }

    /** Só o símbolo de destino da [unidade] do catálogo (rótulos de mostradores e colunas). */
    fun simbolo(unidade: String): String = converter(0.0, unidade).unidade

    companion object {
        const val KM_POR_MILHA = 1.609344
        const val LITROS_POR_GALAO = 3.785411784

        const val KMH = "km/h"
        const val MPH = "mph"
        const val CELSIUS = "°C"
        const val FAHRENHEIT = "°F"
        const val KM_L = "km/L"
        const val MPG = "MPG"
        const val KM = "km"
        const val MILHAS = "mi"
        const val LITROS = "L"
        const val GALOES = "gal"
        const val L_H = "L/h"
        const val GAL_H = "gal/h"
        private const val KPA_NORMALIZADO = "kPa"

        val PADRAO = ConversorUnidades()

        /** Variações de grafia do catálogo e do app (`km/l`, `KPA`, `ºC`) para a forma canônica. */
        private fun normalizar(unidade: String): String = when (unidade.trim().lowercase().replace('º', '°')) {
            "km/h" -> KMH
            "°c" -> CELSIUS
            "kpa" -> KPA_NORMALIZADO
            "km/l" -> KM_L
            "km" -> KM
            "l" -> LITROS
            "l/h" -> L_H
            else -> unidade
        }
    }
}
