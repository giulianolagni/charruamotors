package com.charruamotors.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.model.Veiculo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(repository: VeiculoRepository) : ViewModel() {

    /**
     * Lista de veículos (Read do CRUD). Como vem de um Flow do Room, a lista é atualizada
     * sozinha quando um veículo é cadastrado, editado ou excluído no formulário.
     */
    val veiculos: StateFlow<List<Veiculo>?> = repository.observarTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                DashboardViewModel(app.veiculoRepository)
            }
        }
    }
}
