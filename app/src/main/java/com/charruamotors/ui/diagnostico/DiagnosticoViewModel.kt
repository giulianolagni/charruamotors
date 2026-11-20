package com.charruamotors.ui.diagnostico

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.model.Veiculo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** ViewModel da Activity: fornece o veículo exibido na Toolbar (recarregado do banco pelo id). */
class DiagnosticoViewModel(
    repository: VeiculoRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Id recebido pelo Intent; 0 = ausente/inválido. */
    val veiculoId: Long = savedStateHandle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L

    val veiculo: StateFlow<Veiculo?> = repository.observarPorId(veiculoId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                DiagnosticoViewModel(app.veiculoRepository, createSavedStateHandle())
            }
        }
    }
}
