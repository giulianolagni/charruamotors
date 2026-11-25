package com.charruamotors.ui.veiculo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.data.repository.AbastecimentoRepository
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.validacao.CampoVeiculo
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioVeiculo
import com.charruamotors.domain.validacao.ValidadorVeiculo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Year

/** Eventos de uso único enviados para a Activity (ex.: fechar a tela após salvar). */
sealed interface EventoFormVeiculo {
    data class Salvo(val novo: Boolean) : EventoFormVeiculo
    data object Excluido : EventoFormVeiculo
}

/**
 * ViewModel do formulário de veículo. Concentra as operações de CRUD:
 * Create/Update em [salvar], Read em [carregar] e Delete em [excluir].
 */
class VeiculoFormViewModel(
    private val repository: VeiculoRepository,
    private val manutencaoRepository: ManutencaoRepository,
    private val abastecimentoRepository: AbastecimentoRepository,
    savedStateHandle: SavedStateHandle,
    /** v2.3.0: perfis isolados (criação do perfil do carro novo, duplicação e exclusão). */
    private val perfisCarro: GerenciadorPerfisCarro? = null,
) : ViewModel() {

    /** Id recebido pelo Intent (extras viram argumentos do SavedStateHandle). Null = cadastro novo. */
    private val veiculoId: Long? =
        savedStateHandle.get<Long>(VeiculoFormActivity.EXTRA_VEICULO_ID)?.takeIf { it > 0 }

    val modoEdicao: Boolean = veiculoId != null

    /** v2.3.0: "Duplicar" em Meus Carros — cadastro novo preenchido a partir deste veículo. */
    private val duplicarDe: Long? =
        savedStateHandle.get<Long>(VeiculoFormActivity.EXTRA_DUPLICAR_DE)?.takeIf { it > 0 && veiculoId == null }

    val modoDuplicar: Boolean = duplicarDe != null

    /** Veículo de origem da duplicação (para preencher os campos, sem a placa). */
    suspend fun carregarOrigem(): Veiculo? = duplicarDe?.let { repository.buscarPorId(it) }

    private var veiculoAtual: Veiculo? = null

    private val _erros = MutableStateFlow<Map<CampoVeiculo, ErroValidacao>>(emptyMap())
    val erros: StateFlow<Map<CampoVeiculo, ErroValidacao>> = _erros.asStateFlow()

    private val _salvando = MutableStateFlow(false)
    val salvando: StateFlow<Boolean> = _salvando.asStateFlow()

    private val _eventos = Channel<EventoFormVeiculo>(Channel.BUFFERED)
    val eventos: Flow<EventoFormVeiculo> = _eventos.receiveAsFlow()

    /** Read: busca o veículo em edição no banco. */
    suspend fun carregar(): Veiculo? {
        val id = veiculoId ?: return null
        return veiculoAtual ?: repository.buscarPorId(id).also { veiculoAtual = it }
    }

    /** Create ou Update, depois de validar as regras de negócio. */
    fun salvar(formulario: FormularioVeiculo, cor: String, tipoCombustivel: TipoCombustivel) {
        if (_salvando.value) return
        viewModelScope.launch {
            _salvando.value = true
            try {
                // Editando: a km não pode ficar abaixo do que o histórico do veículo já registrou.
                val kmMinimo = veiculoId?.let { kmMinimoDoHistorico(it) } ?: 0
                val erros = ValidadorVeiculo.validar(formulario, Year.now().value, kmMinimo).toMutableMap()
                val placa = ValidadorVeiculo.normalizarPlaca(formulario.placa)
                if (CampoVeiculo.PLACA !in erros && repository.placaEmUso(placa, veiculoId ?: 0)) {
                    erros[CampoVeiculo.PLACA] = ErroValidacao.PLACA_DUPLICADA
                }
                _erros.value = erros
                if (erros.isNotEmpty()) return@launch

                val veiculo = Veiculo(
                    id = veiculoId ?: 0,
                    marca = formulario.marca.trim(),
                    modelo = formulario.modelo.trim(),
                    ano = formulario.ano.trim().toInt(),
                    placa = placa,
                    cor = cor.trim().ifBlank { null },
                    kmAtual = formulario.km.trim().toInt(),
                    tipoCombustivel = tipoCombustivel,
                    capacidadeTanque = formulario.capacidadeTanque.trim().replace(',', '.').toDoubleOrNull(),
                    tempMaxAguaC = ValidadorVeiculo.numero(formulario.limiteAgua)?.toInt() ?: Veiculo.LIMITE_AGUA_PADRAO_C,
                    tempMaxAtfC = ValidadorVeiculo.numero(formulario.limiteAtf)?.toInt() ?: Veiculo.LIMITE_ATF_PADRAO_C,
                    tensaoMinAlternadorV = ValidadorVeiculo.numero(formulario.limiteTensao) ?: Veiculo.LIMITE_TENSAO_PADRAO_V,
                    calibracaoConsumo = ValidadorVeiculo.numero(formulario.calibracaoConsumo) ?: Veiculo.CALIBRACAO_PADRAO,
                )
                val id = repository.salvar(veiculo)
                if (!modoEdicao) criarPerfil(veiculo.copy(id = id))
                _eventos.send(EventoFormVeiculo.Salvo(novo = !modoEdicao))
            } finally {
                _salvando.value = false
            }
        }
    }

    /**
     * Perfil isolado do carro recém-cadastrado: cópia do de origem (duplicar) ou o inicial — com perfil
     * exato no catálogo, o automático; sem ele, OBD2 Genérico.
     */
    private fun criarPerfil(veiculo: Veiculo) {
        val perfis = perfisCarro ?: return
        val origem = duplicarDe
        if (origem != null) {
            perfis.duplicar(origem, veiculo.id)
            return
        }
        val inicial = PerfilCarro.paraCarroNovo(veiculo.id, PerfilCarro(veiculo.id).resolver(veiculo))
        perfis.criarSeAusente(veiculo.id) { inicial }
    }

    private suspend fun kmMinimoDoHistorico(id: Long): Int =
        maxOf(manutencaoRepository.maiorKmRegistrada(id), abastecimentoRepository.maiorKmRegistrada(id))

    /** Delete */
    fun excluir() {
        viewModelScope.launch {
            val veiculo = carregar() ?: return@launch
            repository.excluir(veiculo)
            // v2.3.0: o perfil isolado (Painel/HUD, Cluster, recordes) vai junto.
            perfisCarro?.excluir(veiculo.id)
            _eventos.send(EventoFormVeiculo.Excluido)
        }
    }

    fun limparErro(campo: CampoVeiculo) {
        if (campo in _erros.value) _erros.value -= campo
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                VeiculoFormViewModel(
                    app.veiculoRepository,
                    app.manutencaoRepository,
                    app.abastecimentoRepository,
                    createSavedStateHandle(),
                    app.perfisCarro,
                )
            }
        }
    }
}
