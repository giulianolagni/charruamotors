package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.charruamotors.domain.model.LeituraObd
import kotlinx.coroutines.flow.Flow

@Dao
interface LeituraObdDao {

    @Insert
    suspend fun inserir(leitura: LeituraObd): Long

    @Delete
    suspend fun excluir(leitura: LeituraObd)

    @Query("SELECT * FROM leituras_obd WHERE veiculoId = :veiculoId ORDER BY dataHora DESC")
    fun observarPorVeiculo(veiculoId: Long): Flow<List<LeituraObd>>
}
