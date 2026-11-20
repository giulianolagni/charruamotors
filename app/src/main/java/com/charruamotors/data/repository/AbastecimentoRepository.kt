package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.AbastecimentoDao
import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Abastecimento
import kotlinx.coroutines.flow.Flow

class AbastecimentoRepository(
    private val dao: AbastecimentoDao,
    private val veiculoDao: VeiculoDao,
) {

    fun observarPorVeiculo(veiculoId: Long): Flow<List<Abastecimento>> =
        dao.observarPorVeiculo(veiculoId)

    suspend fun listarPorVeiculo(veiculoId: Long): List<Abastecimento> =
        dao.listarPorVeiculo(veiculoId)

    suspend fun buscarPorId(id: Long): Abastecimento? = dao.buscarPorId(id)

    /** Km mínima aceita para um novo registro: a km atual do veículo (sempre a maior já registrada). */
    suspend fun kmMinimo(veiculoId: Long): Int = veiculoDao.buscarPorId(veiculoId)?.kmAtual ?: 0

    /** Maior km registrada nos abastecimentos do veículo (0 sem histórico). */
    suspend fun maiorKmRegistrada(veiculoId: Long): Int = dao.maiorKm(veiculoId) ?: 0

    /** Insere ou atualiza o abastecimento e, se a km for maior, atualiza a km do veículo. */
    suspend fun salvar(abastecimento: Abastecimento) {
        if (abastecimento.id == 0L) dao.inserir(abastecimento) else dao.atualizar(abastecimento)
        veiculoDao.atualizarKmSeMaior(abastecimento.veiculoId, abastecimento.km)
    }

    suspend fun excluir(abastecimento: Abastecimento) = dao.excluir(abastecimento)
}
