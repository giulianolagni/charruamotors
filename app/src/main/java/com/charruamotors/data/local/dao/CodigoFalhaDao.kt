package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.charruamotors.domain.model.CodigoFalha
import kotlinx.coroutines.flow.Flow

@Dao
interface CodigoFalhaDao {

    @Insert
    suspend fun inserir(codigo: CodigoFalha): Long

    @Update
    suspend fun atualizar(codigo: CodigoFalha)

    @Delete
    suspend fun excluir(codigo: CodigoFalha)

    @Query("SELECT * FROM codigos_falha WHERE veiculoId = :veiculoId ORDER BY dataHora DESC")
    fun observarPorVeiculo(veiculoId: Long): Flow<List<CodigoFalha>>

    /** Marca como LIMPO todos os códigos ativos do veículo (após o comando 04 na ECU). */
    @Query("UPDATE codigos_falha SET status = 'LIMPO' WHERE veiculoId = :veiculoId AND status = 'ATIVO'")
    suspend fun marcarTodosComoLimpos(veiculoId: Long)

    @Query("SELECT COUNT(*) FROM codigos_falha WHERE veiculoId = :veiculoId AND status = 'ATIVO'")
    fun observarQuantidadeAtivos(veiculoId: Long): Flow<Int>
}
