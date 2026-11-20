package com.charruamotors.ui.util

import androidx.fragment.app.FragmentManager
import com.charruamotors.R
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val TAG_SELETOR_DATA = "seletor_data"

/**
 * O MaterialDatePicker trabalha com "dia = meia-noite UTC". Convertemos sempre a partir da data
 * do calendário local ([LocalDate]), nunca de `Instant.now()`, para o dia não "virar" à noite
 * (ex.: 21h em UTC-3 já é o dia seguinte em UTC).
 */
private fun LocalDate.comoMillisDoSeletor(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun Long.comoDataLocal(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Abre o seletor de data do Material (datas futuras desabilitadas, pois o domínio não as aceita). */
fun FragmentManager.mostrarSeletorData(inicial: LocalDate?, aoSelecionar: (LocalDate) -> Unit) {
    val hoje = LocalDate.now() // data local do aparelho
    val seletor = MaterialDatePicker.Builder.datePicker()
        .setTitleText(R.string.seletor_data_titulo)
        .setSelection((inicial ?: hoje).comoMillisDoSeletor())
        .setCalendarConstraints(
            // Validador explícito com o "hoje" local (inclusive), em vez de DateValidatorPointBackward.now().
            CalendarConstraints.Builder()
                .setEnd(hoje.comoMillisDoSeletor())
                .setValidator(DateValidatorPointBackward.before(hoje.comoMillisDoSeletor()))
                .build(),
        )
        .build()
    seletor.addOnPositiveButtonClickListener { millis -> aoSelecionar(millis.comoDataLocal()) }
    seletor.show(this, TAG_SELETOR_DATA)
}

/**
 * Ciclo de vida: ao girar a tela com o calendário aberto, o FragmentManager recria o diálogo,
 * mas o listener (uma lambda) não sobrevive. Chame em `onViewCreated` do Fragment dono do
 * [FragmentManager] para religar o listener ao seletor restaurado.
 */
@Suppress("UNCHECKED_CAST")
fun FragmentManager.reconectarSeletorData(aoSelecionar: (LocalDate) -> Unit) {
    val seletor = findFragmentByTag(TAG_SELETOR_DATA) as? MaterialDatePicker<Long> ?: return
    seletor.addOnPositiveButtonClickListener { millis -> aoSelecionar(millis.comoDataLocal()) }
}
