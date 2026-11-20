package com.charruamotors.ui.diagnostico.obd

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
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
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Eventos de uso único para a UI (mensagens em Snackbar). */
sealed interface EventoObd {
    data class Mensagem(@param:StringRes val resId: Int) : EventoObd
    data class Texto(val texto: String) : EventoObd
}

/**
 * ViewModel da aba OBD2. Preserva a conexão e as leituras em mudanças de configuração
 * (o `ObdClient` vive no Application; o ViewModel só o usa) e controla o polling de PIDs.
 *
 * @param clientePorModo devolve o [ObdClient] do modo pedido (simulado ou Bluetooth real)
 * @param aoSelecionarModo avisa o Application qual modo está ativo
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class ObdViewModel(
    private val veiculoId: Long,
    private val clientePorModo: (ModoObd) -> ObdClient,
    private val aoSelecionarModo: (ModoObd) -> Unit,
    modoInicial: ModoObd,
    private val repository: DiagnosticoRepository,
) : ViewModel() {

    private val _modo = MutableStateFlow(modoInicial)
    val modo: StateFlow<ModoObd> = _modo.asStateFlow()

    private val cliente: ObdClient get() = clientePorModo(_modo.value)

    /** Estado da conexão do cliente do modo atual (acompanha a troca de modo). */
    val estado: StateFlow<EstadoConexaoObd> = _modo
        .flatMapLatest { clientePorModo(it).estado }
        .stateIn(viewModelScope, SharingStarted.Eagerly, clientePorModo(modoInicial).estado.value)

    private val _dispositivos = MutableStateFlow<List<DispositivoObd>>(emptyList())
    val dispositivos: StateFlow<List<DispositivoObd>> = _dispositivos.asStateFlow()

    private val _dispositivoSelecionado = MutableStateFlow<DispositivoObd?>(null)
    val dispositivoSelecionado: StateFlow<DispositivoObd?> = _dispositivoSelecionado.asStateFlow()

    private val _dados = MutableStateFlow<DadosObd?>(null)
    val dados: StateFlow<DadosObd?> = _dados.asStateFlow()

    /** Códigos de falha lidos da ECU; null = ainda não lidos nesta sessão. */
    private val _codigos = MutableStateFlow<List<DtcLido>?>(null)
    val codigos: StateFlow<List<DtcLido>?> = _codigos.asStateFlow()

    private val _ocupado = MutableStateFlow(false)
    /** true enquanto lê/limpa DTCs (desabilita os botões). */
    val ocupado: StateFlow<Boolean> = _ocupado.asStateFlow()

    private val _eventos = Channel<EventoObd>(Channel.BUFFERED)
    val eventos: Flow<EventoObd> = _eventos.receiveAsFlow()

    private var pollingJob: Job? = null
    private var conexaoJob: Job? = null

    /** A tela está visível (entre onStart/onResume e onPause/onStop do Fragment)? */
    private var telaVisivel = false

    init {
        // Reage às mudanças de estado: inicia o polling ao conectar e o encerra ao perder a conexão.
        viewModelScope.launch {
            // drop(1): ignora o valor inicial, para não repetir um erro antigo ao reabrir a tela.
            estado.drop(1).collect { novo ->
                if (novo !is EstadoConexaoObd.Conectado) {
                    _dados.value = null
                    _codigos.value = null
                }
                if (novo is EstadoConexaoObd.Erro) _eventos.send(EventoObd.Texto(novo.mensagem))
                atualizarPolling()
            }
        }
        carregarDispositivos()
    }

    // ------------------------------------------------------------------ modo e dispositivos

    /** Só troca de modo com o scanner desconectado. */
    fun selecionarModo(modo: ModoObd) {
        if (modo == _modo.value || estado.value is EstadoConexaoObd.Conectado ||
            estado.value is EstadoConexaoObd.Conectando
        ) {
            return
        }
        _modo.value = modo
        aoSelecionarModo(modo)
        _dispositivoSelecionado.value = null
        _dispositivos.value = emptyList()
        carregarDispositivos()
    }

    /** Lista os scanners disponíveis (pareados, no Bluetooth real — exige permissão concedida). */
    fun carregarDispositivos() {
        viewModelScope.launch {
            val lista = cliente.listarDispositivos()
            _dispositivos.value = lista
            val atual = _dispositivoSelecionado.value
            _dispositivoSelecionado.value = lista.firstOrNull { it.endereco == atual?.endereco }
                ?: lista.firstOrNull()
        }
    }

    fun selecionarDispositivo(dispositivo: DispositivoObd) {
        _dispositivoSelecionado.value = dispositivo
    }

    // ------------------------------------------------------------------ conexão

    fun conectar() {
        val dispositivo = _dispositivoSelecionado.value
        if (dispositivo == null) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_erro_sem_dispositivo))
            return
        }
        if (conexaoJob?.isActive == true) return
        conexaoJob = viewModelScope.launch {
            // Falhas já chegam como EstadoConexaoObd.Erro (tratado no init); aqui só o sucesso.
            cliente.conectar(dispositivo).onSuccess {
                _eventos.send(EventoObd.Mensagem(R.string.obd_conectado))
            }
        }
    }

    fun desconectar() {
        viewModelScope.launch {
            pollingJob?.cancel()
            cliente.desconectar()
        }
    }

    // ------------------------------------------------------------------ polling (ciclo de vida)

    /** Chamado pelo Fragment quando a tela fica visível (onStart/onResume). */
    fun retomarPolling() {
        telaVisivel = true
        atualizarPolling()
    }

    /** Chamado pelo Fragment quando a tela deixa de ficar visível (onPause/onStop). */
    fun pausarPolling() {
        telaVisivel = false
        atualizarPolling()
    }

    private fun atualizarPolling() {
        val deveRodar = telaVisivel && estado.value is EstadoConexaoObd.Conectado
        if (!deveRodar) {
            pollingJob?.cancel()
            pollingJob = null
            return
        }
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            cliente.lerDadosEmTempoReal()
                .catch { _eventos.send(EventoObd.Texto(it.message ?: "")) }
                .collect { _dados.value = it }
        }
    }

    // ------------------------------------------------------------------ histórico e DTCs

    /** Salva a última leitura recebida no histórico do veículo. */
    fun salvarLeitura() {
        val dados = _dados.value
        if (dados == null) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_erro_sem_leitura))
            return
        }
        viewModelScope.launch {
            repository.salvarLeitura(dados.paraLeitura(veiculoId))
            _eventos.send(EventoObd.Mensagem(R.string.obd_leitura_salva))
        }
    }

    /**
     * Lê os códigos de falha (modo 03). O resultado vira o conjunto de códigos ativos do veículo:
     * os ativos anteriores são marcados como limpos e os lidos agora são gravados no histórico.
     */
    fun lerCodigos() {
        if (_ocupado.value) return
        viewModelScope.launch {
            _ocupado.value = true
            try {
                cliente.lerCodigosFalha()
                    .onSuccess { lidos ->
                        _codigos.value = lidos
                        repository.marcarCodigosComoLimpos(veiculoId)
                        lidos.forEach { repository.salvarCodigo(it.paraCodigoFalha(veiculoId)) }
                        _eventos.send(
                            EventoObd.Mensagem(
                                if (lidos.isEmpty()) R.string.obd_sem_codigos else R.string.obd_codigos_lidos,
                            ),
                        )
                    }
                    .onFailure { _eventos.send(EventoObd.Texto(it.message ?: "")) }
            } finally {
                _ocupado.value = false
            }
        }
    }

    /** Limpa os códigos na ECU (modo 04). A UI deve pedir confirmação antes de chamar. */
    fun limparCodigos() {
        if (_ocupado.value) return
        viewModelScope.launch {
            _ocupado.value = true
            try {
                cliente.limparCodigosFalha()
                    .onSuccess {
                        _codigos.value = emptyList()
                        repository.marcarCodigosComoLimpos(veiculoId)
                        _eventos.send(EventoObd.Mensagem(R.string.obd_codigos_limpos))
                    }
                    .onFailure { _eventos.send(EventoObd.Texto(it.message ?: "")) }
            } finally {
                _ocupado.value = false
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida do ViewModel

    /**
     * ViewModel destruído de vez (a Activity terminou, não foi só uma rotação): fecha o socket
     * Bluetooth. O `viewModelScope` já está cancelado aqui, então usa um escopo próprio.
     */
    override fun onCleared() {
        val clienteAtual = cliente
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { clienteAtual.desconectar() }
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val handle: SavedStateHandle = createSavedStateHandle()
                ObdViewModel(
                    veiculoId = handle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L,
                    clientePorModo = app::obdClientDe,
                    aoSelecionarModo = app::selecionarModoObd,
                    modoInicial = app.modoObd,
                    repository = app.diagnosticoRepository,
                )
            }
        }
    }
}
