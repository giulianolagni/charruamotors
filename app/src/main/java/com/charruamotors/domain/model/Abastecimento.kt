package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Registro de abastecimento. Excluir o veículo exclui seus abastecimentos (CASCADE). */
@Entity(
    tableName = "abastecimentos",
    foreignKeys = [
        ForeignKey(
            entity = Veiculo::class,
            parentColumns = ["id"],
            childColumns = ["veiculoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("veiculoId")],
)
data class Abastecimento(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val veiculoId: Long,
    val data: LocalDate,
    val km: Int,
    val litros: Double,
    val valorTotal: Double,
    val tipoCombustivel: TipoCombustivel,
    val tanqueCheio: Boolean,
) {
    val precoPorLitro: Double get() = valorTotal / litros
}
