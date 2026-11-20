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
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.dtc.TipoDtc
import com.charruamotors.obd.log.DataLogger
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.SessaoLog
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.ArquivoExportado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Writer

/** Eventos de uso único para a UI (mensagens em Snackbar). */
sealed interface EventoObd {
    data class Mensagem(@param:StringRes val resId: Int) : EventoObd
    data class Texto(val texto: String) : EventoObd

    /** Log CSV salvo: a UI oferece a opção de compartilhar. */
    data class LogSalvo(val arquivo: ArquivoExportado) : EventoObd
}

/**
 * ViewModel da aba OBD2. Preserva a conexão e as leituras em mudanças de configuração
 * (o `ObdClient` vive no Application; o ViewModel só o usa) e controla o polling de PIDs,
 * o Data Logger e a leitura completa de falhas (modos 03, 07, 0A e 02).
 *
 * @param clientePorModo devolve o [ObdClient] do modo pedido (simulado ou Bluetooth real)
 * @param aoSelecionarModo avisa o Application qual modo está ativo
 * @param marcaVeiculo marca do veículo, para escolher a tabela de DTCs do fabricante
 * @param salvarCsv grava um arquivo (nome + escrita em streaming) e devolve onde ficou
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class ObdViewModel(
    private val veiculoId: Long,
    private val clientePorModo: (ModoObd) -> ObdClient,
    private val aoSelecionarModo: (ModoObd) -> Unit,
    modoInicial: ModoObd,
    private val repository: DiagnosticoRepository,
    private val catalogoPids: CatalogoPids,
    private val catalogoDtc: CatalogoDtc,
    private val marcaVeiculo: suspend () -> String?,
    private val salvarCsv: suspend (String, (Writer) -> Unit) -> ArquivoExportado,
    private val relogio: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _modo = MutableStateFlow(modoInicial)
    val modo: StateFlow<ModoObd> = _modo.asStateFlow()

    private val cliente: ObdClient get() = clientePorModo(_modo.value)

    /** Estado da conexão do cliente do modo atual (acompanha a troca de modo). */
    val estado: StateFlow<EstadoConexaoObd> = _modo
        .flatMapLatest { clientePorModo(it).estado }
        .stateIn(viewModelScope, SharingStarted.Eagerly, clientePorModo(modoInicial).estado.value)

    /** Comandos do catálogo que a ECU conectada suporta (bitmasks 0100/0120/0140). */
    val pidsSuportados: StateFlow<Set<String>> = _modo
        .flatMapLatest { clientePorModo(it).pidsSuportados }
        .stateIn(viewModelScope, SharingStarted.Eagerly, clientePorModo(modoInicial).pidsSuportados.value)

    /** Últimas leituras salvas no histórico do veículo (a lista completa pode ser grande). */
    val historicoLeituras: StateFlow<List<LeituraObd>> = repository.observarLeituras(veiculoId)
        .map { it.take(MAX_HISTORICO) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Demonstração: o [ObdClientFake] (modo simulado) falha ao conectar, mostrando o estado Erro. */
    private val clienteSimulado: ObdClientFake? get() = clientePorModo(ModoObd.SIMULADO) as? ObdClientFake
    private val _simularFalha = MutableStateFlow(clienteSimulado?.simularFalhaNaConexao ?: false)
    val simularFalha: StateFlow<Boolean> = _simularFalha.asStateFlow()

    fun definirSimularFalha(ativa: Boolean) {
        clienteSimulado?.simularFalhaNaConexao = ativa
        _simularFalha.value = ativa
    }

    private val _dispositivos = MutableStateFlow<List<DispositivoObd>>(emptyList())
    val dispositivos: StateFlow<List<DispositivoObd>> = _dispositivos.asStateFlow()

    private val _dispositivoSelecionado = MutableStateFlow<DispositivoObd?>(null)
    val dispositivoSelecionado: StateFlow<DispositivoObd?> = _dispositivoSelecionado.asStateFlow()

    private val _dados = MutableStateFlow<DadosObd?>(null)
    val dados: StateFlow<DadosObd?> = _dados.asStateFlow()

    /** Todos os PIDs suportados com o valor do último ciclo, na ordem do catálogo (grade ao vivo). */
    val sensores: StateFlow<List<LeituraSensor>> = combine(pidsSuportados, _dados) { suportados, dados ->
        catalogoPids.pids.filter { it.comando in suportados }.map { LeituraSensor(it, dados?.get(it.comando)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tamanho do catálogo, para "N de M PIDs suportados". */
    val totalPidsCatalogo: Int get() = catalogoPids.pids.size

    private val _vin = MutableStateFlow<String?>(null)
    private val _vinLido = MutableStateFlow(false)

    /** VIN (modo 09) e quantidade de PIDs detectados. */
    val infoEcu: StateFlow<InfoEcu> = combine(_vin, _vinLido, pidsSuportados) { vin, lido, pids ->
        InfoEcu(vin, lido, pids.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InfoEcu())

    /** Falhas lidas por categoria + Freeze Frame; null = ainda não lidas nesta conexão. */
    private val _diagnostico = MutableStateFlow<ResultadoDiagnostico?>(null)
    val diagnostico: StateFlow<ResultadoDiagnostico?> = _diagnostico.asStateFlow()

    private val _ocupado = MutableStateFlow(false)
    /** true enquanto lê/limpa DTCs (desabilita os botões). */
    val ocupado: StateFlow<Boolean> = _ocupado.asStateFlow()

    private val logger = DataLogger()
    private val _estadoLog = MutableStateFlow(EstadoLog())
    val estadoLog: StateFlow<EstadoLog> = _estadoLog.asStateFlow()

    private val _eventos = Channel<EventoObd>(Channel.BUFFERED)
    val eventos: Flow<EventoObd> = _eventos.receiveAsFlow()

    private var pollingJob: Job? = null
    private var conexaoJob: Job? = null
    private var vinJob: Job? = null

    /** Marca do veículo (carregada uma vez) para a tabela de DTCs do fabricante. */
    private var marca: String? = null

    /** A tela está visível (entre onStart/onResume e onPause/onStop do Fragment)? */
    private var telaVisivel = false

    init {
        // Reage às mudanças de estado: inicia o polling ao conectar e o encerra ao perder a conexão.
        viewModelScope.launch {
            // drop(1): ignora o valor inicial, para não repetir um erro antigo ao reabrir a tela.
            estado.drop(1).collect { novo ->
                if (novo is EstadoConexaoObd.Conectado) {
                    lerVin()
                } else {
                    _dados.value = null
                    _diagnostico.value = null
                    vinJob?.cancel()
                    _vin.value = null
                    _vinLido.value = false
                    // Conexão caiu durante a gravação: salva o que já foi coletado.
                    if (logger.gravando) pararLog()
                }
                if (novo is EstadoConexaoObd.Erro) _eventos.send(EventoObd.Texto(novo.mensagem))
                atualizarPolling()
            }
        }
        // Já conectado (ex.: rotação de tela / reabertura com o cliente vivo no Application).
        if (estado.value is EstadoConexaoObd.Conectado) lerVin()
        viewModelScope.launch { marca = runCatching { marcaVeiculo() }.getOrNull() }
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
            // Espera o polling terminar de fato antes de fechar o socket: senão uma leitura em
            // andamento falharia com IOException e o estado viraria Erro depois de Desconectado.
            pollingJob?.cancelAndJoin()
            pollingJob = null
            cliente.desconectar()
        }
    }

    /** VIN uma vez por conexão (modo 09); veículos antigos podem não suportar. */
    private fun lerVin() {
        if (vinJob?.isActive == true) return
        _vinLido.value = false
        vinJob = viewModelScope.launch {
            _vin.value = cliente.lerVin().getOrNull()
            _vinLido.value = true
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
            // comandos = null: todos os PIDs suportados (grade completa e Data Logger).
            cliente.lerDadosEmTempoReal(comandos = null)
                .catch { _eventos.send(EventoObd.Texto(it.message ?: "")) }
                .collect { dados ->
                    _dados.value = dados
                    registrarNoLog(dados)
                }
        }
    }

    // ------------------------------------------------------------------ Data Logger CSV

    /** Botão "Gravar Log CSV": inicia a gravação ou para e salva o arquivo. */
    fun alternarLog() {
        if (logger.gravando) pararLog() else iniciarLog()
    }

    private fun iniciarLog() {
        if (estado.value !is EstadoConexaoObd.Conectado) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_sem_conexao))
            return
        }
        val suportados = pidsSuportados.value
        val colunas = catalogoPids.pids.filter { it.comando in suportados }
        if (colunas.isEmpty()) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_sem_pids))
            return
        }
        logger.iniciar(colunas, relogio())
        _estadoLog.value = EstadoLog(gravando = true, amostras = 0)
        _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_iniciado))
    }

    private fun registrarNoLog(dados: DadosObd) {
        if (!logger.gravando) return
        if (logger.registrar(dados, relogio())) {
            _estadoLog.value = EstadoLog(gravando = true, amostras = logger.quantidade)
        }
        if (logger.cheio) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_limite))
            pararLog()
        }
    }

    private fun pararLog() {
        val sessao = logger.parar() ?: return
        _estadoLog.value = EstadoLog()
        if (sessao.vazia) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_vazio))
            return
        }
        viewModelScope.launch {
            try {
                _eventos.send(EventoObd.LogSalvo(exportar(sessao)))
            } catch (e: IOException) {
                _eventos.send(EventoObd.Texto(e.message ?: "Falha ao salvar o log"))
            }
        }
    }

    private suspend fun exportar(sessao: SessaoLog): ArquivoExportado =
        salvarCsv(GeradorCsv.nomeArquivo(sessao.inicioMs)) { saida -> GeradorCsv.escrever(sessao, saida) }

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
     * Diagnóstico completo: confirmados (03), pendentes (07), permanentes (0A) e Freeze Frame (02).
     * Os confirmados viram o conjunto de códigos ativos do veículo no histórico (os anteriores são
     * marcados como limpos). Só a falha do modo 03 aborta; os demais modos são opcionais na ECU.
     */
    fun lerCodigos() {
        if (_ocupado.value) return
        viewModelScope.launch {
            _ocupado.value = true
            try {
                lerDiagnostico(anunciar = true)
            } finally {
                _ocupado.value = false
            }
        }
    }

    private suspend fun lerDiagnostico(anunciar: Boolean) {
        val confirmados = cliente.lerCodigosFalha().getOrElse {
            _eventos.send(EventoObd.Texto(it.message ?: ""))
            return
        }
        val pendentes = cliente.lerCodigosPendentes().getOrNull()
        val permanentes = cliente.lerCodigosPermanentes().getOrNull()
        val freezeFrame = cliente.lerFreezeFrame().getOrNull()

        val exibicaoConfirmados = confirmados.map { exibir(TipoDtc.CONFIRMADO, it) }
        repository.marcarCodigosComoLimpos(veiculoId)
        exibicaoConfirmados.forEach { dtc ->
            repository.salvarCodigo(DtcLido(dtc.codigo, dtc.info.titulo).paraCodigoFalha(veiculoId))
        }
        val resultado = ResultadoDiagnostico(
            confirmados = exibicaoConfirmados,
            pendentes = pendentes?.map { exibir(TipoDtc.PENDENTE, it) },
            permanentes = permanentes?.map { exibir(TipoDtc.PERMANENTE, it) },
            freezeFrame = freezeFrame,
            infoFreezeFrame = freezeFrame?.dtc?.let { catalogoDtc.descrever(it, marca) },
        )
        _diagnostico.value = resultado
        if (anunciar) {
            _eventos.send(
                EventoObd.Mensagem(if (resultado.total == 0) R.string.obd_sem_codigos else R.string.obd_codigos_lidos),
            )
        }
    }

    private fun exibir(tipo: TipoDtc, dtc: DtcLido) = DtcExibicao(tipo, catalogoDtc.descrever(dtc.codigo, marca))

    /**
     * Limpa os códigos na ECU (modo 04) e relê tudo: pendentes e Freeze Frame somem, mas os
     * permanentes continuam até a própria ECU validar o reparo. A UI deve pedir confirmação antes.
     */
    fun limparCodigos() {
        if (_ocupado.value) return
        viewModelScope.launch {
            _ocupado.value = true
            try {
                cliente.limparCodigosFalha()
                    .onSuccess {
                        repository.marcarCodigosComoLimpos(veiculoId)
                        _eventos.send(EventoObd.Mensagem(R.string.obd_codigos_limpos))
                        lerDiagnostico(anunciar = false)
                    }
                    .onFailure { _eventos.send(EventoObd.Texto(it.message ?: "")) }
            } finally {
                _ocupado.value = false
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida do ViewModel

    /**
     * ViewModel destruído de vez (a Activity terminou, não foi só uma rotação): salva um log em
     * andamento e fecha o socket Bluetooth. O `viewModelScope` já está cancelado aqui, então usa
     * um escopo próprio.
     */
    override fun onCleared() {
        val clienteAtual = cliente
        val sessao = logger.parar()?.takeUnless { it.vazia }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sessao?.let { runCatching { exportar(it) } }
            clienteAtual.desconectar()
        }
        super.onCleared()
    }

    companion object {
        private const val MAX_HISTORICO = 10

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val handle: SavedStateHandle = createSavedStateHandle()
                val veiculoId = handle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                ObdViewModel(
                    veiculoId = veiculoId,
                    clientePorModo = app::obdClientDe,
                    aoSelecionarModo = app::selecionarModoObd,
                    modoInicial = app.modoObd,
                    repository = app.diagnosticoRepository,
                    catalogoPids = app.catalogoPids,
                    catalogoDtc = app.catalogoDtc,
                    marcaVeiculo = { app.veiculoRepository.buscarPorId(veiculoId)?.marca },
                    salvarCsv = { nome, escrever -> app.exportadorCsv.salvar(nome, escrever) },
                )
            }
        }
    }
}
