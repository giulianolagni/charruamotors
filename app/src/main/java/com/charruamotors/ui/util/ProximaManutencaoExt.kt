package com.charruamotors.ui.util

import android.content.Context
import com.charruamotors.R
import com.charruamotors.domain.calculo.ProximaManutencao

/** Texto "Tipo — aos X km até dd/MM/yyyy", com sufixo "(vencida)" quando atrasada. */
fun ProximaManutencao.comoTexto(context: Context): String {
    val alvo = listOfNotNull(
        kmPrevisto?.let { context.getString(R.string.manutencao_previsao_km, it.comoMilhar()) },
        dataPrevista?.let { context.getString(R.string.manutencao_previsao_data, it.comoTexto()) },
    ).joinToString(" ")
    val linha = context.getString(R.string.manutencao_previsao_linha, tipo.rotulo(context), alvo)
    return if (vencida) context.getString(R.string.manutencao_previsao_vencida, linha) else linha
}
