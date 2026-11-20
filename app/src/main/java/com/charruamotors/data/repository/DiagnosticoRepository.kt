package com.charruamotors.data.repository

import com.charruamotors.data.local.dao.CodigoFalhaDao
import com.charruamotors.data.local.dao.LeituraObdDao
import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import kotlinx.coroutines.flow.Flow

/** Histórico do diagnóstico OBD2: leituras em tempo real e códigos de falha (DTCs). */
class DiagnosticoRepository(
    private val leituraDao: LeituraObdDao,
    private val codigoFalhaDao: CodigoFalhaDao,
) {

    fun observarLeituras(veiculoId: Long): Flow<List<LeituraObd>> = leituraDao.observarPorVeiculo(veiculoId)

    suspend fun salvarLeitura(leitura: LeituraObd) = leituraDao.inserir(leitura)

    fun observarCodigos(veiculoId: Long): Flow<List<CodigoFalha>> = codigoFalhaDao.observarPorVeiculo(veiculoId)

    fun observarQuantidadeFalhasAtivas(veiculoId: Long): Flow<Int> =
        codigoFalhaDao.observarQuantidadeAtivos(veiculoId)

    suspend fun salvarCodigo(codigo: CodigoFalha) = codigoFalhaDao.inserir(codigo)

    suspend fun marcarCodigosComoLimpos(veiculoId: Long) = codigoFalhaDao.marcarTodosComoLimpos(veiculoId)
}
