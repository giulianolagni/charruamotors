package com.charruamotors.domain.validacao

/** Converte texto digitado aceitando vírgula ou ponto decimal; null se não for um número. */
fun String.paraDecimalOuNulo(): Double? = trim().replace(',', '.').toDoubleOrNull()
