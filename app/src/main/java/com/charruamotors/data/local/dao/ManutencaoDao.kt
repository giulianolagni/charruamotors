package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.domain.model.PecaTrocada
import kotlinx.coroutines.flow.Flow

/** Classe abstrata (e não interface) para permitir métodos @Transaction com corpo. */
@Dao
abstract class ManutencaoDao {

    @Insert
    protected abstract suspend fun inserirManutencao(manutencao: Manutencao): Long

    @Insert
    protected abstract suspend fun inserirPecas(pecas: List<PecaTrocada>)

    @Update
    protected abstract suspend fun atualizarManutencao(manutencao: Manutencao)

    @Query("DELETE FROM pecas_trocadas WHERE manutencaoId = :manutencaoId")
    protected abstract suspend fun excluirPecasDe(manutencaoId: Long)

    /** Insere ou atualiza a manutenção e substitui suas peças, tudo em uma única transação. */
    @Transaction
    open suspend fun salvar(manutencao: Manutencao, pecas: List<PecaTrocada>): Long {
        val id = if (manutencao.id == 0L) {
            inserirManutencao(manutencao)
        } else {
            atualizarManutencao(manutencao)
            excluirPecasDe(manutencao.id)
            manutencao.id
        }
        inserirPecas(pecas.map { it.copy(id = 0, manutencaoId = id) })
        return id
    }

    /** Excluir a manutenção apaga as peças via CASCADE. */
    @Delete
    abstract suspend fun excluir(manutencao: Manutencao)

    @Transaction
    @Query("SELECT * FROM manutencoes WHERE veiculoId = :veiculoId ORDER BY data DESC, km DESC")
    abstract fun observarPorVeiculo(veiculoId: Long): Flow<List<ManutencaoComPecas>>

    @Transaction
    @Query("SELECT * FROM manutencoes WHERE veiculoId = :veiculoId")
    abstract suspend fun listarPorVeiculo(veiculoId: Long): List<ManutencaoComPecas>

    /** Maior km já registrada em manutenções do veículo; null se não houver nenhuma. */
    @Query("SELECT MAX(km) FROM manutencoes WHERE veiculoId = :veiculoId")
    abstract suspend fun maiorKm(veiculoId: Long): Int?

    @Transaction
    @Query("SELECT * FROM manutencoes WHERE id = :id")
    abstract suspend fun buscarPorId(id: Long): ManutencaoComPecas?
}
