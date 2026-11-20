package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.ManutencaoDao
import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.domain.model.PecaTrocada
import kotlinx.coroutines.flow.Flow

class ManutencaoRepository(
    private val dao: ManutencaoDao,
    private val veiculoDao: VeiculoDao,
) {

    fun observarPorVeiculo(veiculoId: Long): Flow<List<ManutencaoComPecas>> =
        dao.observarPorVeiculo(veiculoId)

    suspend fun listarPorVeiculo(veiculoId: Long): List<ManutencaoComPecas> =
        dao.listarPorVeiculo(veiculoId)

    suspend fun buscarPorId(id: Long): ManutencaoComPecas? = dao.buscarPorId(id)

    /** Km mínima aceita para um novo registro: a km atual do veículo (sempre a maior já registrada). */
    suspend fun kmMinimo(veiculoId: Long): Int = veiculoDao.buscarPorId(veiculoId)?.kmAtual ?: 0

    /** Maior km registrada nas manutenções do veículo (0 sem histórico). */
    suspend fun maiorKmRegistrada(veiculoId: Long): Int = dao.maiorKm(veiculoId) ?: 0

    /** Salva a manutenção com suas peças e, se a km for maior, atualiza a km do veículo. */
    suspend fun salvar(manutencao: Manutencao, pecas: List<PecaTrocada>): Long {
        val id = dao.salvar(manutencao, pecas)
        veiculoDao.atualizarKmSeMaior(manutencao.veiculoId, manutencao.km)
        return id
    }

    suspend fun excluir(manutencao: Manutencao) = dao.excluir(manutencao)
}
