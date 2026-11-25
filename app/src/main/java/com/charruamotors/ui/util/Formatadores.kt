package com.charruamotors.ui.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val localeBr: Locale = Locale.forLanguageTag("pt-BR")
private val formatoData: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

private val formatoDataHora: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Valor em reais (ex.: R$ 1.234,50). */
fun Double.comoMoeda(): String = NumberFormat.getCurrencyInstance(localeBr).format(this)

/** Número com [casas] casas decimais no padrão brasileiro (vírgula decimal). */
fun Double.comoDecimal(casas: Int = 2): String = String.format(localeBr, "%.${casas}f", this)

/** Número com separador de milhar e exatamente [casas] decimais (ex.: 1.726 / 14,2 / 0,812). */
fun Double.comoNumero(casas: Int = 0): String = NumberFormat.getNumberInstance(localeBr).apply {
    minimumFractionDigits = casas
    maximumFractionDigits = casas
}.format(this)

/** Inteiro com separador de milhar (ex.: 45.000). */
fun Int.comoMilhar(): String = NumberFormat.getIntegerInstance(localeBr).format(this)

/** Data no formato dd/MM/yyyy. */
fun LocalDate.comoTexto(): String = format(formatoData)

/** Data e hora no formato dd/MM/yyyy HH:mm. */
fun LocalDateTime.comoTexto(): String = format(formatoDataHora)

/** Converte o texto dd/MM/yyyy digitado/selecionado; null se vazio ou inválido. */
fun String.paraDataOuNulo(): LocalDate? = try {
    LocalDate.parse(trim(), formatoData)
} catch (_: DateTimeParseException) {
    null
}

/**
 * Consumo médio (guardado em km/L) na unidade global (v2.3.0): `12,3 km/L`, `8,1 L/100km` ou
 * `28,9 MPG`; null se não houver valor finito na unidade escolhida.
 */
fun Double.comoConsumo(conversor: com.charruamotors.sistema.unidades.ConversorUnidades, casas: Int = 1): String? =
    conversor.consumo(this).takeIf { it.isFinite() }?.let { "${it.comoDecimal(casas)} ${conversor.simboloConsumo}" }
