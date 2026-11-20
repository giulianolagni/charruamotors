package com.charruamotors.ui.diagnostico.abastecimentos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.AbastecimentoRepository
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.calculo.CalculosVeiculo
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.validacao.CampoAbastecimento
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioAbastecimento
import com.charruamotors.domain.validacao.ValidadorAbastecimento
import com.charruamotors.domain.validacao.paraDecimalOuNulo
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Histórico de abastecimentos e indicadores calculados no domínio (null = dados insuficientes). */
data class ResumoAbastecimentos(
    val itens: List<Abastecimento>,
    val consumoMedio: Double?,
    val custoPorKm: Double?,
)

sealed interface EventoAbastecimento {
    data object Salvo : EventoAbastecimento
    data object Excluido : EventoAbastecimento
}

/** Dados digitados no formulário de abastecimento. */
data class DadosFormularioAbastecimento(
    val formulario: FormularioAbastecimento,
    val tipoCombustivel: TipoCombustivel,
    val tanqueCheio: Boolean,
)

/** ViewModel da aba Abastecimentos: lista, consumo médio, custo por km e CRUD com validação. */
class AbastecimentosViewModel(
    private val repository: AbastecimentoRepository,
    manutencaoRepository: ManutencaoRepository,
    private val veiculoRepository: VeiculoRepository,
    savedStateHandle: SavedStateHandle,
    private val hoje: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    val veiculoId: Long = savedStateHandle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L

    /** O custo por km considera também as manutenções, por isso observa as duas listas. */
    val resumo: StateFlow<ResumoAbastecimentos?> = combine(
        repository.observarPorVeiculo(veiculoId),
        manutencaoRepository.observarPorVeiculo(veiculoId).map { lista -> lista.map { it.manutencao } },
    ) { abastecimentos, manutencoes ->
        ResumoAbastecimentos(
            itens = abastecimentos,
            consumoMedio = CalculosVeiculo.consumoMedio(abastecimentos),
            custoPorKm = CalculosVeiculo.custoPorKm(abastecimentos, manutencoes),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    private val _erros = MutableStateFlow<Map<CampoAbastecimento, ErroValidacao>>(emptyMap())
    val erros: StateFlow<Map<CampoAbastecimento, ErroValidacao>> = _erros.asStateFlow()

    private val _salvando = MutableStateFlow(false)
    val salvando: StateFlow<Boolean> = _salvando.asStateFlow()

    private val _eventos = Channel<EventoAbastecimento>(Channel.BUFFERED)
    val eventos: Flow<EventoAbastecimento> = _eventos.receiveAsFlow()

    /** Combustível do veículo, usado como padrão no formulário novo (FLEX abastece como flex). */
    suspend fun combustivelPadrao(): TipoCombustivel =
        veiculoRepository.buscarPorId(veiculoId)?.tipoCombustivel ?: TipoCombustivel.GASOLINA

    suspend fun carregar(id: Long): Abastecimento? = repository.buscarPorId(id)

    /** Create (id = 0) ou Update, depois de validar as regras de negócio. */
    fun salvar(id: Long, dados: DadosFormularioAbastecimento) {
        if (_salvando.value) return
        viewModelScope.launch {
            _salvando.value = true
            try {
                // Mesma regra da manutenção: só o registro novo precisa respeitar a km atual do veículo.
                val kmMinimo = if (id == 0L) repository.kmMinimo(veiculoId) else 0
                val capacidade = veiculoRepository.buscarPorId(veiculoId)?.capacidadeTanque
                val erros = ValidadorAbastecimento.validar(dados.formulario, hoje(), kmMinimo, capacidade)
                _erros.value = erros
                if (erros.isNotEmpty()) return@launch

                repository.salvar(
                    Abastecimento(
                        id = id,
                        veiculoId = veiculoId,
                        data = dados.formulario.data!!,
                        km = dados.formulario.km.trim().toInt(),
                        litros = dados.formulario.litros.paraDecimalOuNulo()!!,
                        valorTotal = dados.formulario.valorTotal.paraDecimalOuNulo()!!,
                        tipoCombustivel = dados.tipoCombustivel,
                        tanqueCheio = dados.tanqueCheio,
                    ),
                )
                _eventos.send(EventoAbastecimento.Salvo)
            } finally {
                _salvando.value = false
            }
        }
    }

    fun excluir(abastecimento: Abastecimento) {
        viewModelScope.launch {
            repository.excluir(abastecimento)
            _eventos.send(EventoAbastecimento.Excluido)
        }
    }

    fun limparErro(campo: CampoAbastecimento) {
        if (campo in _erros.value) _erros.value -= campo
    }

    fun limparErros() {
        _erros.value = emptyMap()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                AbastecimentosViewModel(
                    app.abastecimentoRepository,
                    app.manutencaoRepository,
                    app.veiculoRepository,
                    createSavedStateHandle(),
                )
            }
        }
    }
}
