package com.charruamotors.domain.perfis

import java.time.LocalDate

/** O que o VIN (`0902`) revela: fabricante pelo WMI (1–3), o VDS (4–9) e o ano-modelo (10º). */
data class VinDecodificado(
    val vin: String,
    /** World Manufacturer Identifier (`9BG`, `8AJ`...). */
    val wmi: String,
    /** Vehicle Descriptor Section: caracteres 4–9 (modelo, motor, carroceria, dígito verificador). */
    val vds: String,
    /** Montadora pelo WMI; null = WMI fora da tabela. */
    val montadora: String?,
    /** País/fábrica do WMI (`Brasil`, `Argentina`); null = desconhecido. */
    val origem: String?,
    /** Ano-modelo pelo 10º caractere (ciclo de 30 anos resolvido pelo ano de referência); null = código inválido. */
    val anoModelo: Int?,
) {
    /** WMI + VDS (os 9 primeiros caracteres), a base dos `padroesVin` do catálogo. */
    val wmiVds: String get() = wmi + vds

    /** `Chevrolet · 2018 (WMI 9BG, Brasil)`. */
    val descricao: String
        get() = buildString {
            append(montadora ?: "Montadora desconhecida")
            anoModelo?.let { append(" · ").append(it) }
            append(" (WMI ").append(wmi)
            origem?.let { append(", ").append(it) }
            append(')')
        }
}

/**
 * Decodifica o VIN lido pelo modo 09 PID 02 (ISO 3779): WMI → montadora (tabela dos fabricantes
 * comuns no Brasil, inclusive os importados da Argentina) e o 10º caractere → ano-modelo. Puro (JVM).
 */
object DecodificadorVin {

    private class Fabricante(val montadora: String, val origem: String)

    private val WMI: Map<String, Fabricante> = mapOf(
        "9BG" to Fabricante("Chevrolet", "Brasil"),
        "8AG" to Fabricante("Chevrolet", "Argentina"),
        "9BW" to Fabricante("Volkswagen", "Brasil"),
        "8AW" to Fabricante("Volkswagen", "Argentina"),
        "9BD" to Fabricante("Fiat", "Brasil"),
        "8AP" to Fabricante("Fiat", "Argentina"),
        "8AJ" to Fabricante("Toyota", "Argentina"),
        "9BR" to Fabricante("Toyota", "Brasil"),
        "93H" to Fabricante("Honda", "Brasil"),
        "9BH" to Fabricante("Hyundai", "Brasil"),
        "93Y" to Fabricante("Renault", "Brasil"),
        "8A1" to Fabricante("Renault", "Argentina"),
        "9BF" to Fabricante("Ford", "Brasil"),
        "8AF" to Fabricante("Ford", "Argentina"),
        "988" to Fabricante("Jeep", "Brasil"),
        "94D" to Fabricante("Nissan", "Brasil"),
        "936" to Fabricante("Peugeot", "Brasil"),
        "935" to Fabricante("Citroën", "Brasil"),
        "9BM" to Fabricante("Mercedes-Benz", "Brasil"),
    )

    /** Códigos do 10º caractere em ordem: `A` = 1980/2010, ..., `Y` = 2000/2030, `1`–`9` = 2001–2009/2031–2039. */
    private const val CODIGOS_ANO = "ABCDEFGHJKLMNPRSTVWXY123456789"
    private const val PRIMEIRO_ANO = 1980
    private const val CICLO = 30

    private val REGEX_VIN = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    /** Normaliza (maiúsculas, sem espaços/hífens) e confere o formato: 17 caracteres sem I, O e Q. */
    fun normalizar(vin: String?): String? =
        vin?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { REGEX_VIN.matches(it) }

    /**
     * Decodifica [vin]; null se o formato for inválido. O ano-modelo é o mais recente do ciclo de 30
     * anos que não passe de [anoReferencia] + 1 (o ano-modelo seguinte já é vendido no fim do ano).
     */
    fun decodificar(vin: String?, anoReferencia: Int = LocalDate.now().year): VinDecodificado? {
        val v = normalizar(vin) ?: return null
        val wmi = v.substring(0, 3)
        val fabricante = WMI[wmi]
        return VinDecodificado(v, wmi, v.substring(3, 9), fabricante?.montadora, fabricante?.origem, anoModelo(v[9], anoReferencia))
    }

    /** Ano-modelo do código [c] (10º caractere); null se o código não for de ano (`0`, `U`, `Z`...). */
    fun anoModelo(c: Char, anoReferencia: Int = LocalDate.now().year): Int? {
        val i = CODIGOS_ANO.indexOf(c.uppercaseChar())
        if (i < 0) return null
        var ano = PRIMEIRO_ANO + i
        while (ano + CICLO <= anoReferencia + 1) ano += CICLO
        return ano
    }

    /** Montadora do WMI (`9BG` → `Chevrolet`); null = fora da tabela. */
    fun montadora(wmi: String): String? = WMI[wmi.uppercase()]?.montadora
}
