package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.charruamotors.domain.model.Abastecimento
import kotlinx.coroutines.flow.Flow

@Dao
interface AbastecimentoDao {

    @Insert
    suspend fun inserir(abastecimento: Abastecimento): Long

    @Update
    suspend fun atualizar(abastecimento: Abastecimento)

    @Delete
    suspend fun excluir(abastecimento: Abastecimento)

    @Query("SELECT * FROM abastecimentos WHERE veiculoId = :veiculoId ORDER BY data DESC, km DESC")
    fun observarPorVeiculo(veiculoId: Long): Flow<List<Abastecimento>>

    @Query("SELECT * FROM abastecimentos WHERE veiculoId = :veiculoId ORDER BY km")
    suspend fun listarPorVeiculo(veiculoId: Long): List<Abastecimento>

    /** Maior km já registrada em abastecimentos do veículo; null se não houver nenhum. */
    @Query("SELECT MAX(km) FROM abastecimentos WHERE veiculoId = :veiculoId")
    suspend fun maiorKm(veiculoId: Long): Int?

    @Query("SELECT * FROM abastecimentos WHERE id = :id")
    suspend fun buscarPorId(id: Long): Abastecimento?
}
