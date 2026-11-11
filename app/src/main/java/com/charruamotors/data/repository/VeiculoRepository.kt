package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Veiculo
import kotlinx.coroutines.flow.Flow

class VeiculoRepository(private val dao: VeiculoDao) {

    fun observarTodos(): Flow<List<Veiculo>> = dao.observarTodos()

    suspend fun buscarPorId(id: Long): Veiculo? = dao.buscarPorId(id)

    /** Insere quando o veículo é novo (id = 0) ou atualiza quando já existe. */
    suspend fun salvar(veiculo: Veiculo) {
        if (veiculo.id == 0L) dao.inserir(veiculo) else dao.atualizar(veiculo)
    }

    suspend fun excluir(veiculo: Veiculo) = dao.excluir(veiculo)

    suspend fun placaEmUso(placa: String, idIgnorado: Long): Boolean =
        dao.contarPorPlaca(placa, idIgnorado) > 0
}
