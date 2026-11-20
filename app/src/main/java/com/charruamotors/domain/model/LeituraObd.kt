package com.charruamotors.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/** Leitura em tempo real do scanner OBD2. Campos nulos = PID sem resposta (ex.: NO DATA). */
@Entity(
    tableName = "leituras_obd",
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
data class LeituraObd(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val veiculoId: Long,
    val dataHora: LocalDateTime,
    val rpm: Int? = null,
    val velocidade: Int? = null,
    val temperaturaMotor: Int? = null,
    val cargaMotor: Double? = null,
    val tensaoBateria: Double? = null,
)
