package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Peça trocada em uma manutenção. Excluir a manutenção exclui suas peças (CASCADE). */
@Entity(
    tableName = "pecas_trocadas",
    foreignKeys = [
        ForeignKey(
            entity = Manutencao::class,
            parentColumns = ["id"],
            childColumns = ["manutencaoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("manutencaoId")],
)
data class PecaTrocada(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manutencaoId: Long = 0,
    val nome: String,
    val quantidade: Int = 1,
    val valorUnitario: Double,
) {
    val valorTotal: Double get() = quantidade * valorUnitario
}
