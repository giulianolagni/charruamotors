package com.charruamotors.ui.util

import android.content.Context
import com.charruamotors.R
import com.charruamotors.domain.model.TipoCombustivel

/** Nome do combustível exibido na tela (os textos ficam em strings.xml). */
fun TipoCombustivel.rotulo(context: Context): String = context.getString(
    when (this) {
        TipoCombustivel.GASOLINA -> R.string.combustivel_gasolina
        TipoCombustivel.ETANOL -> R.string.combustivel_etanol
        TipoCombustivel.FLEX -> R.string.combustivel_flex
        TipoCombustivel.DIESEL -> R.string.combustivel_diesel
        TipoCombustivel.GNV -> R.string.combustivel_gnv
    },
)
