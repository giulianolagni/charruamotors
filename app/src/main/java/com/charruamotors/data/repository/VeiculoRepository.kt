package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Veiculo
import kotlinx.coroutines.flow.Flow

class VeiculoRepository(private val dao: VeiculoDao) {

    fun observarTodos(): Flow<List<Veiculo>> = dao.observarTodos()

    suspend fun buscarPorId(id: Long): Veiculo? = dao.buscarPorId(id)

    fun observarPorId(id: Long): Flow<Veiculo?> = dao.observarPorId(id)

    /** Insere quando o veículo é novo (id = 0) ou atualiza quando já existe. Devolve o id. */
    suspend fun salvar(veiculo: Veiculo): Long {
        if (veiculo.id == 0L) return dao.inserir(veiculo)
        dao.atualizar(veiculo)
        return veiculo.id
    }

    suspend fun excluir(veiculo: Veiculo) = dao.excluir(veiculo)

    suspend fun placaEmUso(placa: String, idIgnorado: Long): Boolean =
        dao.contarPorPlaca(placa, idIgnorado) > 0
}
