package com.charruamotors.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.PecaTrocada
import com.charruamotors.domain.model.Veiculo

/**
 * Backup e Restauração: leitura de todas as tabelas ligadas aos veículos e a troca completa do
 * conteúdo numa única transação (ou restaura tudo, ou nada muda). Os IDs do arquivo são mantidos.
 */
@Dao
abstract class BackupDao {

    @Query("SELECT * FROM veiculos ORDER BY id")
    abstract suspend fun veiculos(): List<Veiculo>

    @Query("SELECT * FROM manutencoes ORDER BY id")
    abstract suspend fun manutencoes(): List<Manutencao>

    @Query("SELECT * FROM pecas_trocadas ORDER BY id")
    abstract suspend fun pecas(): List<PecaTrocada>

    @Query("SELECT * FROM abastecimentos ORDER BY id")
    abstract suspend fun abastecimentos(): List<Abastecimento>

    @Query("SELECT * FROM leituras_obd ORDER BY id")
    abstract suspend fun leituras(): List<LeituraObd>

    @Query("SELECT * FROM codigos_falha ORDER BY id")
    abstract suspend fun codigosFalha(): List<CodigoFalha>

    @Query("DELETE FROM pecas_trocadas")
    protected abstract suspend fun apagarPecas()

    @Query("DELETE FROM manutencoes")
    protected abstract suspend fun apagarManutencoes()

    @Query("DELETE FROM abastecimentos")
    protected abstract suspend fun apagarAbastecimentos()

    @Query("DELETE FROM leituras_obd")
    protected abstract suspend fun apagarLeituras()

    @Query("DELETE FROM codigos_falha")
    protected abstract suspend fun apagarCodigos()

    @Query("DELETE FROM veiculos")
    protected abstract suspend fun apagarVeiculos()

    @Insert
    protected abstract suspend fun inserirVeiculos(lista: List<Veiculo>)

    @Insert
    protected abstract suspend fun inserirManutencoes(lista: List<Manutencao>)

    @Insert
    protected abstract suspend fun inserirPecas(lista: List<PecaTrocada>)

    @Insert
    protected abstract suspend fun inserirAbastecimentos(lista: List<Abastecimento>)

    @Insert
    protected abstract suspend fun inserirLeituras(lista: List<LeituraObd>)

    @Insert
    protected abstract suspend fun inserirCodigos(lista: List<CodigoFalha>)

    /** Substitui todo o conteúdo pelo do backup (filhos apagados antes dos pais, inseridos depois). */
    @Transaction
    open suspend fun substituirTudo(
        veiculos: List<Veiculo>,
        manutencoes: List<Manutencao>,
        pecas: List<PecaTrocada>,
        abastecimentos: List<Abastecimento>,
        leituras: List<LeituraObd>,
        codigos: List<CodigoFalha>,
    ) {
        apagarPecas()
        apagarManutencoes()
        apagarAbastecimentos()
        apagarLeituras()
        apagarCodigos()
        apagarVeiculos()
        inserirVeiculos(veiculos)
        inserirManutencoes(manutencoes)
        inserirPecas(pecas)
        inserirAbastecimentos(abastecimentos)
        inserirLeituras(leituras)
        inserirCodigos(codigos)
    }
}
