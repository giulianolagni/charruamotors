package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/** Código de falha (DTC, ex.: P0300) capturado da ECU. */
@Entity(
    tableName = "codigos_falha",
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
data class CodigoFalha(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val veiculoId: Long,
    val dataHora: LocalDateTime,
    val codigo: String,
    val descricao: String? = null,
    val status: StatusCodigoFalha = StatusCodigoFalha.ATIVO,
)
