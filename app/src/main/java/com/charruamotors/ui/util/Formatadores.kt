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
