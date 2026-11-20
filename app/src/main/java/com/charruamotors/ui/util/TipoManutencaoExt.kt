package com.charruamotors.ui.util

import android.content.Context
import com.charruamotors.R
import com.charruamotors.domain.model.TipoManutencao

/** Nome do tipo de manutenção exibido na tela (os textos ficam em strings.xml). */
fun TipoManutencao.rotulo(context: Context): String = context.getString(
    when (this) {
        TipoManutencao.TROCA_OLEO -> R.string.manutencao_tipo_troca_oleo
        TipoManutencao.FILTROS -> R.string.manutencao_tipo_filtros
        TipoManutencao.FREIOS -> R.string.manutencao_tipo_freios
        TipoManutencao.PNEUS -> R.string.manutencao_tipo_pneus
        TipoManutencao.REVISAO -> R.string.manutencao_tipo_revisao
        TipoManutencao.ELETRICA -> R.string.manutencao_tipo_eletrica
        TipoManutencao.OUTROS -> R.string.manutencao_tipo_outros
    },
)
