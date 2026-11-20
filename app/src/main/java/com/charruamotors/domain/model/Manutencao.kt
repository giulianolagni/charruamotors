package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Registro de manutenção de um veículo. Excluir o veículo exclui suas manutenções (CASCADE). */
@Entity(
    tableName = "manutencoes",
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
data class Manutencao(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val veiculoId: Long,
    val data: LocalDate,
    val km: Int,
    val tipo: TipoManutencao,
    val descricao: String? = null,
    val oficina: String? = null,
    val custoTotal: Double,
)
