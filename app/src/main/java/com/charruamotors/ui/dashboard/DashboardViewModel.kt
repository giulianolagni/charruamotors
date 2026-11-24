package com.charruamotors.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.AbastecimentoRepository
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.calculo.CalculosVeiculo
import com.charruamotors.domain.calculo.ResumoVeiculo
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.sistema.navegacao.VeiculoFavorito
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class DashboardViewModel(
    veiculoRepository: VeiculoRepository,
    /** Id do favorito salvo (veículo padrão da caixa-preta; 0 = nenhum). */
    favoritoSalvo: Flow<Long>,
    private val manutencaoRepository: ManutencaoRepository,
    private val abastecimentoRepository: AbastecimentoRepository,
    private val diagnosticoRepository: DiagnosticoRepository,
) : ViewModel() {

    /**
     * Resumo de cada veículo (Read do CRUD + indicadores). Combina os Flows do Room, então o
     * Dashboard se atualiza sozinho ao cadastrar um veículo, registrar manutenção/abastecimento
     * ou salvar/limpar códigos de falha. Os cálculos ficam em [CalculosVeiculo].
     * `null` enquanto a primeira carga não termina.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val resumos: StateFlow<List<ResumoVeiculo>?> = veiculoRepository.observarTodos()
        .flatMapLatest { veiculos ->
            if (veiculos.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(veiculos.map(::observarResumo)) { it.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    /** Cards de Meus Veículos com a estrela no favorito efetivo (o salvo ou, sem ele, o primeiro). */
    val itens: StateFlow<List<ItemVeiculo>?> = combine(resumos, favoritoSalvo) { lista, favorito ->
        lista?.let { r ->
            val ids = r.map { it.veiculo.id }
            r.map { ItemVeiculo(it, VeiculoFavorito.ehFavorito(it.veiculo.id, ids, favorito)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    private fun observarResumo(veiculo: Veiculo): Flow<ResumoVeiculo> = combine(
        abastecimentoRepository.observarPorVeiculo(veiculo.id),
        manutencaoRepository.observarPorVeiculo(veiculo.id),
        diagnosticoRepository.observarQuantidadeFalhasAtivas(veiculo.id),
    ) { abastecimentos, manutencoes, falhasAtivas ->
        CalculosVeiculo.resumir(
            veiculo = veiculo,
            abastecimentos = abastecimentos,
            manutencoes = manutencoes.map { it.manutencao },
            falhasAtivas = falhasAtivas,
            hoje = LocalDate.now(),
        )
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                DashboardViewModel(
                    app.veiculoRepository,
                    app.preferenciasAutoConexao.config.map { it.veiculoId },
                    app.manutencaoRepository,
                    app.abastecimentoRepository,
                    app.diagnosticoRepository,
                )
            }
        }
    }
}
