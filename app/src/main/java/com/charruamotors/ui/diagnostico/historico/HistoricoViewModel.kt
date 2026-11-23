package com.charruamotors.ui.diagnostico.historico

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.log.OrganizadorLogs
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import com.charruamotors.ui.oficina.replay.RepositorioLogsCsv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Aba Histórico: os logs salvos em `Downloads/CharruaOBD` — CSV de telemetria em [logsCsv] e os
 * relatórios `.txt` das sessões da caixa-preta (o diário de viagens) em [relatoriosViagem] — e as
 * leituras salvas do veículo. Os abastecimentos ficam no Fragment próprio, embutido na aba.
 */
class HistoricoViewModel(
    veiculoId: Long,
    private val repositorio: RepositorioLogsCsv,
    diagnostico: DiagnosticoRepository,
) : ViewModel() {

    private val arquivos = MutableStateFlow<List<ArquivoLogCsv>>(emptyList())

    private val _carregando = MutableStateFlow(false)
    val carregando: StateFlow<Boolean> = _carregando.asStateFlow()

    val logsCsv: StateFlow<List<ArquivoLogCsv>> = arquivos.map { lista -> OrganizadorLogs.ordenar(lista.filterNot { it.texto }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val relatoriosViagem: StateFlow<List<ArquivoLogCsv>> = arquivos.map { lista -> OrganizadorLogs.ordenar(lista.filter { it.texto }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val leituras: StateFlow<List<LeituraObd>> = diagnostico.observarLeituras(veiculoId)
        .map { it.take(MAX_LEITURAS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        atualizar()
    }

    /** Relê a pasta (um log novo pode ter sido salvo pela sessão em segundo plano). */
    fun atualizar() {
        viewModelScope.launch {
            _carregando.value = true
            arquivos.value = runCatching { repositorio.listar() }.getOrDefault(emptyList())
            _carregando.value = false
        }
    }

    fun uriCompartilhavel(arquivo: ArquivoLogCsv) = repositorio.uriCompartilhavel(arquivo)

    companion object {
        const val MAX_LEITURAS = 50

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                HistoricoViewModel(veiculoId, RepositorioLogsCsv(app), app.diagnosticoRepository)
            }
        }
    }
}
