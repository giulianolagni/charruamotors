package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.charruamotors.domain.model.Veiculo
import kotlinx.coroutines.flow.Flow

/** Operações de CRUD da tabela de veículos. */
@Dao
interface VeiculoDao {

    /** Create */
    @Insert
    suspend fun inserir(veiculo: Veiculo): Long

    /** Read: o Flow emite uma nova lista sempre que a tabela muda. */
    @Query("SELECT * FROM veiculos ORDER BY marca, modelo")
    fun observarTodos(): Flow<List<Veiculo>>

    /** Read */
    @Query("SELECT * FROM veiculos WHERE id = :id")
    suspend fun buscarPorId(id: Long): Veiculo?

    /** Update */
    @Update
    suspend fun atualizar(veiculo: Veiculo)

    /** Delete */
    @Delete
    suspend fun excluir(veiculo: Veiculo)

    /** Conta outros veículos com a mesma placa (ignorando o próprio veículo em edição). */
    @Query("SELECT COUNT(*) FROM veiculos WHERE placa = :placa AND id != :idIgnorado")
    suspend fun contarPorPlaca(placa: String, idIgnorado: Long): Int
}
