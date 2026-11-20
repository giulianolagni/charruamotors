package com.charruamotors.ui.util

import androidx.fragment.app.FragmentManager
import com.charruamotors.R
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Abre o seletor de data do Material (datas futuras desabilitadas, pois o domínio não as aceita). */
fun FragmentManager.mostrarSeletorData(inicial: LocalDate?, aoSelecionar: (LocalDate) -> Unit) {
    val selecao = (inicial ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val seletor = MaterialDatePicker.Builder.datePicker()
        .setTitleText(R.string.seletor_data_titulo)
        .setSelection(selecao)
        .setCalendarConstraints(
            CalendarConstraints.Builder().setValidator(DateValidatorPointBackward.now()).build(),
        )
        .build()
    seletor.addOnPositiveButtonClickListener { millis ->
        // O MaterialDatePicker devolve a data como meia-noite UTC.
        aoSelecionar(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
    }
    seletor.show(this, "seletor_data")
}
