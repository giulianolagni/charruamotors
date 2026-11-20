package com.charruamotors.domain.model

import androidx.room.Embedded
import androidx.room.Relation

/** Manutenção junto com suas peças (relação 1:N carregada por @Transaction no DAO). */
data class ManutencaoComPecas(
    @Embedded val manutencao: Manutencao,
    @Relation(parentColumn = "id", entityColumn = "manutencaoId")
    val pecas: List<PecaTrocada>,
)
