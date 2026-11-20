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

    /** Read reativo: emite novamente quando o veículo muda (ex.: km atualizada por um registro). */
    @Query("SELECT * FROM veiculos WHERE id = :id")
    fun observarPorId(id: Long): Flow<Veiculo?>

    /** Update */
    @Update
    suspend fun atualizar(veiculo: Veiculo)

    /** Delete */
    @Delete
    suspend fun excluir(veiculo: Veiculo)

    /** Só aumenta a km: ignora valores menores ou iguais à km atual. */
    @Query("UPDATE veiculos SET kmAtual = :km WHERE id = :id AND kmAtual < :km")
    suspend fun atualizarKmSeMaior(id: Long, km: Int)

    /** Conta outros veículos com a mesma placa (ignorando o próprio veículo em edição). */
    @Query("SELECT COUNT(*) FROM veiculos WHERE placa = :placa AND id != :idIgnorado")
    suspend fun contarPorPlaca(placa: String, idIgnorado: Long): Int
}
