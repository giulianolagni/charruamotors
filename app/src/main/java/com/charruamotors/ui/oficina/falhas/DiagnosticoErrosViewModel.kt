package com.charruamotors.ui.oficina.falhas

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.dtc.TipoDtc
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.obd.DtcExibicao
import com.charruamotors.ui.diagnostico.obd.ResultadoDiagnostico
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface EventoFalhas {
    data class Mensagem(@param:StringRes val resId: Int) : EventoFalhas
    data class Texto(val texto: String) : EventoFalhas
}

/**
 * Oficina → Diagnóstico de Erros: leitura completa de falhas (modos 03, 07, 0A e 02) e limpeza
 * (modo 04) na sessão OBD compartilhada. Os confirmados viram o conjunto de códigos ativos do
 * veículo no histórico, e a leitura também entra no relatório `.txt` da sessão.
 */
class DiagnosticoErrosViewModel(
    private val veiculoId: Long,
    private val sessao: SessaoObd,
    private val repository: DiagnosticoRepository,
    private val catalogoDtc: CatalogoDtc,
    veiculo: suspend () -> Veiculo?,
) : ViewModel() {

    private val cliente: ObdClient get() = sessao.cliente

    val estado: StateFlow<EstadoConexaoObd> = sessao.estado

    private val _diagnostico = MutableStateFlow<ResultadoDiagnostico?>(null)
    /** Falhas por categoria + Freeze Frame; null = ainda não lidas nesta conexão. */
    val diagnostico: StateFlow<ResultadoDiagnostico?> = _diagnostico.asStateFlow()

    private val _ocupado = MutableStateFlow(false)
    val ocupado: StateFlow<Boolean> = _ocupado.asStateFlow()

    private val _eventos = Channel<EventoFalhas>(Channel.BUFFERED)
    val eventos: Flow<EventoFalhas> = _eventos.receiveAsFlow()

    /** Marca do veículo, para a tabela de DTCs do fabricante. */
    private val marca = viewModelScope.async { runCatching { veiculo() }.getOrNull()?.marca }

    init {
        // A conexão caiu: o resultado antigo não vale mais para este carro ligado.
        viewModelScope.launch { estado.drop(1).collect { if (it !is EstadoConexaoObd.Conectado) _diagnostico.value = null } }
    }

    fun lerCodigos() = executar { lerDiagnostico(anunciar = true) }

    /**
     * Limpa os códigos na ECU (modo 04) e relê tudo: pendentes e Freeze Frame somem, mas os
     * permanentes continuam até a própria ECU validar o reparo. A UI deve pedir confirmação antes.
     */
    fun limparCodigos() = executar {
        cliente.limparCodigosFalha()
            .onSuccess {
                repository.marcarCodigosComoLimpos(veiculoId)
                _eventos.send(EventoFalhas.Mensagem(R.string.obd_codigos_limpos))
                lerDiagnostico(anunciar = false)
            }
            .onFailure { _eventos.send(EventoFalhas.Texto(it.message ?: "")) }
    }

    private fun executar(acao: suspend () -> Unit) {
        if (_ocupado.value) return
        if (estado.value !is EstadoConexaoObd.Conectado) {
            _eventos.trySend(EventoFalhas.Mensagem(R.string.oficina_requer_conexao))
            return
        }
        viewModelScope.launch {
            _ocupado.value = true
            try {
                acao()
            } finally {
                _ocupado.value = false
            }
        }
    }

    private suspend fun lerDiagnostico(anunciar: Boolean) {
        val confirmados = cliente.lerCodigosFalha().getOrElse {
            _eventos.send(EventoFalhas.Texto(it.message ?: ""))
            return
        }
        val pendentes = cliente.lerCodigosPendentes().getOrNull()
        val permanentes = cliente.lerCodigosPermanentes().getOrNull()
        val freezeFrame = cliente.lerFreezeFrame().getOrNull()
        val m = marca.await()
        fun exibir(tipo: TipoDtc, dtc: DtcLido) = DtcExibicao(tipo, catalogoDtc.descrever(dtc.codigo, m))

        val exibicaoConfirmados = confirmados.map { exibir(TipoDtc.CONFIRMADO, it) }
        repository.marcarCodigosComoLimpos(veiculoId)
        exibicaoConfirmados.forEach { dtc -> repository.salvarCodigo(DtcLido(dtc.codigo, dtc.info.titulo).paraCodigoFalha(veiculoId)) }
        val resultado = ResultadoDiagnostico(
            confirmados = exibicaoConfirmados,
            pendentes = pendentes?.map { exibir(TipoDtc.PENDENTE, it) },
            permanentes = permanentes?.map { exibir(TipoDtc.PERMANENTE, it) },
            freezeFrame = freezeFrame,
            infoFreezeFrame = freezeFrame?.dtc?.let { catalogoDtc.descrever(it, m) },
        )
        _diagnostico.value = resultado
        sessao.registrarDtcs(
            confirmados = resultado.confirmados.map(::paraDtcLido),
            pendentes = resultado.pendentes?.map(::paraDtcLido),
            permanentes = resultado.permanentes?.map(::paraDtcLido),
        )
        if (anunciar) {
            _eventos.send(EventoFalhas.Mensagem(if (resultado.total == 0) R.string.obd_sem_codigos else R.string.obd_codigos_lidos))
        }
    }

    private fun paraDtcLido(dtc: DtcExibicao) = DtcLido(dtc.codigo, dtc.info.titulo)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                DiagnosticoErrosViewModel(
                    veiculoId = veiculoId,
                    sessao = app.sessaoObd,
                    repository = app.diagnosticoRepository,
                    catalogoDtc = app.catalogoDtc,
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                )
            }
        }
    }
}
