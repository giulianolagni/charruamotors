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
import com.charruamotors.domain.calculo.ComputadorBordo
import com.charruamotors.domain.calculo.Cronometro0a100
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.TelemetriaCalculada
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.dtc.TipoDtc
import com.charruamotors.obd.log.DataLogger
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.RegistroComunicacao
import com.charruamotors.obd.log.SessaoLog
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import com.charruamotors.ui.componentes.JanelaTelemetria
import com.charruamotors.ui.componentes.PontoTelemetria
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.ArquivoExportado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    /** Falha/queda da conexão: a mensagem vem com o atalho para o Log de comunicação. */
    data class ErroConexao(val texto: String) : EventoObd
}

/**
 * ViewModel da aba OBD2. Preserva a conexão e as leituras em mudanças de configuração
 * (o `ObdClient` vive no Application; o ViewModel só o usa) e controla a telemetria em duas
 * filas, o computador de bordo, o cronômetro 0–100 km/h, o gráfico ao vivo, o perfil de PIDs
 * da montadora, o Data Logger e a leitura completa de falhas (modos 03, 07, 0A e 02).
 *
 * @param clientePorModo devolve o [ObdClient] do modo pedido (simulado ou Bluetooth real)
 * @param aoSelecionarModo avisa o Application qual modo está ativo
 * @param veiculo veículo do diagnóstico: marca (tabela de DTCs e perfil de PIDs da montadora),
 *   modelo (cilindrada) e combustível (computador de bordo)
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
    private val veiculo: suspend () -> Veiculo?,
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

    /** Protocolo (ATDP) e modo de comunicação (OBD2 padrão / Toyota M-OBD) da conexão. */
    private val infoConexao: StateFlow<InfoConexao> = _modo
        .flatMapLatest { clientePorModo(it).infoConexao }
        .stateIn(viewModelScope, SharingStarted.Eagerly, clientePorModo(modoInicial).infoConexao.value)

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

    /** Perfil de PIDs da montadora desta conexão; null = verificando ou desconectado. */
    private val _perfil = MutableStateFlow<ResultadoPerfil?>(null)

    /** PIDs de montadora ativos (entram na grade e no log depois dos PIDs padrão). */
    private val pidsDaMontadora: List<DefinicaoPid>
        get() = (_perfil.value as? ResultadoPerfil.Ativo)?.pids.orEmpty()

    /** Definições de todos os sensores suportados agora, na ordem do catálogo + montadora. */
    private fun definicoesSuportadas(suportados: Set<String>): List<DefinicaoPid> =
        (catalogoPids.pids + pidsDaMontadora).filter { it.comando in suportados }

    /** Todos os PIDs suportados com o último valor conhecido (grade ao vivo; a fila lenta a atualiza aos poucos). */
    val sensores: StateFlow<List<LeituraSensor>> = combine(pidsSuportados, _dados, _perfil) { suportados, dados, _ ->
        definicoesSuportadas(suportados).map { LeituraSensor(it, dados?.get(it.comando)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------ telemetria derivada

    private val computadorBordo = ComputadorBordo()
    private val _computador = MutableStateFlow(ComputadorBordoUi())
    /** Consumo instantâneo (MAF ou Speed-Density) e média da viagem. */
    val computador: StateFlow<ComputadorBordoUi> = _computador.asStateFlow()

    private val cronometro0a100 = Cronometro0a100()
    private val _cronometro = MutableStateFlow(CronometroUi())
    val cronometro: StateFlow<CronometroUi> = _cronometro.asStateFlow()

    private val janelaGrafico = JanelaTelemetria()
    private val _grafico = MutableStateFlow<List<PontoTelemetria>>(emptyList())
    /** Últimos 60 s de RPM e MAP para o gráfico ao vivo. */
    val grafico: StateFlow<List<PontoTelemetria>> = _grafico.asStateFlow()

    /** Tamanho do catálogo, para "N de M PIDs suportados". */
    val totalPidsCatalogo: Int get() = catalogoPids.pids.size

    private val _vin = MutableStateFlow<String?>(null)
    private val _vinLido = MutableStateFlow(false)

    /** VIN (modo 09), quantidade de PIDs padrão detectados, perfil de montadora, protocolo e modo. */
    val infoEcu: StateFlow<InfoEcu> =
        combine(_vin, _vinLido, pidsSuportados, _perfil, infoConexao) { vin, lido, pids, perfil, conexao ->
            InfoEcu(vin, lido, pids.count { it in catalogoPids }, perfil, conexao)
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

    /**
     * A tela deve ficar acesa: conectando, conectado (telemetria ao vivo) ou gravando o log CSV.
     * O Fragment só aplica enquanto a aba está em primeiro plano.
     */
    val manterTelaAcesa: StateFlow<Boolean> = combine(estado, _estadoLog) { e, log ->
        e is EstadoConexaoObd.Conectado || e is EstadoConexaoObd.Conectando || log.gravando
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _eventos = Channel<EventoObd>(Channel.BUFFERED)
    val eventos: Flow<EventoObd> = _eventos.receiveAsFlow()

    private var pollingJob: Job? = null
    private var conexaoJob: Job? = null
    private var vinJob: Job? = null
    private var perfilJob: Job? = null

    /** Veículo do diagnóstico, carregado uma vez (marca, modelo e combustível). */
    private val dadosVeiculo = viewModelScope.async { runCatching { veiculo() }.getOrNull() }

    /** Marca do veículo (carregada uma vez) para a tabela de DTCs do fabricante. */
    private var marca: String? = null

    /** Cilindrada e combustível para o consumo; padrão até o veículo carregar. */
    private var parametrosMotor = ParametrosMotor()

    /** A tela está visível (entre onStart/onResume e onPause/onStop do Fragment)? */
    private var telaVisivel = false

    init {
        // Reage às mudanças de estado: inicia o polling ao conectar e o encerra ao perder a conexão.
        viewModelScope.launch {
            // drop(1): ignora o valor inicial, para não repetir um erro antigo ao reabrir a tela.
            estado.drop(1).collect { novo ->
                if (novo is EstadoConexaoObd.Conectado) {
                    iniciarSessao()
                } else {
                    _dados.value = null
                    _diagnostico.value = null
                    vinJob?.cancel()
                    perfilJob?.cancel()
                    _vin.value = null
                    _vinLido.value = false
                    _perfil.value = null
                    if (cronometro0a100.armado) _cronometro.value = CronometroUi(cronometro0a100.desarmar(), cronometro0a100.melhorTempoMs)
                    // Conexão caiu durante a gravação: salva o que já foi coletado.
                    if (logger.gravando) pararLog()
                }
                if (novo is EstadoConexaoObd.Erro) _eventos.send(EventoObd.ErroConexao(novo.mensagem))
                atualizarPolling()
            }
        }
        // Já conectado (ex.: reabertura da tela com o cliente vivo no Application).
        if (estado.value is EstadoConexaoObd.Conectado) iniciarSessao()
        viewModelScope.launch {
            val v = dadosVeiculo.await()
            marca = v?.marca
            parametrosMotor = ParametrosMotor.deVeiculo(v?.modelo, v?.tipoCombustivel)
            _computador.value = _computador.value.copy(motor = parametrosMotor)
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
        viewModelScope.launch { atualizarDispositivos() }
    }

    private suspend fun atualizarDispositivos() {
        val lista = cliente.listarDispositivos()
        _dispositivos.value = lista
        _dispositivoSelecionado.value = escolherDispositivo(lista, _dispositivoSelecionado.value)
    }

    fun selecionarDispositivo(dispositivo: DispositivoObd) {
        _dispositivoSelecionado.value = dispositivo
    }

    // ------------------------------------------------------------------ conexão

    /**
     * Conecta ao scanner escolhido. Sem escolha (lista ainda vazia porque a permissão ou o
     * Bluetooth acabaram de ser liberados), recarrega os pareados e só segue sozinho se achar
     * um que pareça adaptador OBD — nunca tenta conectar num fone ou relógio.
     */
    fun conectar() {
        if (conexaoJob?.isActive == true) return
        conexaoJob = viewModelScope.launch {
            var dispositivo = _dispositivoSelecionado.value
            if (dispositivo == null) {
                atualizarDispositivos()
                dispositivo = _dispositivoSelecionado.value?.takeIf { pareceScannerObd(it) }
            }
            if (dispositivo == null) {
                _eventos.send(EventoObd.Mensagem(R.string.obd_erro_sem_dispositivo))
                return@launch
            }
            // Falhas já chegam como EstadoConexaoObd.Erro (tratado no init); aqui só o sucesso.
            // A marca decide o modo alternativo quando a ECU recusa o OBD2 padrão (Toyota M-OBD).
            cliente.conectar(dispositivo, dadosVeiculo.await()?.marca).onSuccess {
                _eventos.send(EventoObd.Mensagem(R.string.obd_conectado))
            }
        }
    }

    /**
     * Desconecta (ou cancela uma conexão em andamento). O cliente fecha o socket na hora, o que
     * destrava um comando preso esperando o ELM327, e descarta a falha da leitura interrompida:
     * não é preciso (nem seguro) esperar o polling terminar antes, porque um comando travado
     * nunca terminaria sem o socket fechado.
     */
    fun desconectar() {
        conexaoJob?.cancel()
        pollingJob?.cancel()
        pollingJob = null
        viewModelScope.launch { cliente.desconectar() }
    }

    /** Registro das últimas trocas com o ELM327, pronto para exibir/copiar; null se vazio. */
    fun textoRegistroComunicacao(): String? =
        cliente.registroComunicacao().takeIf { it.isNotEmpty() }?.let(RegistroComunicacao::formatar)

    /** Nova conexão: zera a viagem e o gráfico, lê o VIN e tenta ativar o perfil da montadora. */
    private fun iniciarSessao() {
        computadorBordo.zerar()
        janelaGrafico.limpar()
        _grafico.value = emptyList()
        _computador.value = ComputadorBordoUi(motor = parametrosMotor)
        lerVin()
        ativarPerfilFabricante()
    }

    /**
     * Perfil de PIDs da montadora (Toyota modo 21 / VW UDS 22) conforme a marca cadastrada; os
     * PIDs que a ECU responder entram na fila lenta da telemetria, na grade e no log.
     */
    private fun ativarPerfilFabricante() {
        if (perfilJob?.isActive == true) return
        _perfil.value = null
        perfilJob = viewModelScope.launch {
            val resultado = cliente.ativarPerfilFabricante(dadosVeiculo.await()?.marca)
            _perfil.value = resultado
            if (resultado is ResultadoPerfil.Ativo) {
                _eventos.send(EventoObd.Texto("Perfil ${resultado.fabricante} ativado: ${resultado.pids.size} PIDs da montadora"))
            }
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
            // Duas filas: mostradores em todo ciclo, o resto da grade (e do log) em rodízio.
            cliente.lerTelemetria()
                .catch { _eventos.send(EventoObd.Texto(it.message ?: "")) }
                .collect { dados ->
                    _dados.value = dados
                    processarTelemetria(dados, relogio())
                    registrarNoLog(dados)
                }
        }
    }

    /** Computador de bordo, cronômetro 0–100 e gráfico, a cada instantâneo da telemetria. */
    private fun processarTelemetria(dados: DadosObd, instanteMs: Long) {
        val velocidade = dados[Pids.VELOCIDADE]
        val consumo = TelemetriaCalculada.consumoInstantaneo(dados, parametrosMotor)
        _computador.value = ComputadorBordoUi(
            consumo = consumo,
            viagem = computadorBordo.registrar(consumo, velocidade, instanteMs),
            motor = parametrosMotor,
        )
        if (cronometro0a100.armado) {
            _cronometro.value = CronometroUi(cronometro0a100.registrar(velocidade, instanteMs), cronometro0a100.melhorTempoMs)
        }
        _grafico.value = janelaGrafico.adicionar(PontoTelemetria(instanteMs, dados[Pids.RPM], dados[Pids.PRESSAO_COLETOR]))
    }

    // ------------------------------------------------------------------ cronômetro 0–100 km/h

    /**
     * Botão "Armar 0–100 km/h": arma (a contagem começa sozinha quando o carro sair do 0) ou
     * cancela. No modo simulado, também dispara a arrancada de demonstração do [ObdClientFake].
     */
    fun alternarCronometro() {
        if (cronometro0a100.armado) {
            _cronometro.value = CronometroUi(cronometro0a100.desarmar(), cronometro0a100.melhorTempoMs)
            return
        }
        if (estado.value !is EstadoConexaoObd.Conectado) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_cronometro_sem_conexao))
            return
        }
        if (Pids.VELOCIDADE !in pidsSuportados.value) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_cronometro_sem_velocidade))
            return
        }
        _cronometro.value = CronometroUi(cronometro0a100.armar(_dados.value?.get(Pids.VELOCIDADE)), cronometro0a100.melhorTempoMs)
        if (_modo.value == ModoObd.SIMULADO) clienteSimulado?.simularArrancada()
    }

    /** Zera a média da viagem do computador de bordo. */
    fun zerarViagem() {
        computadorBordo.zerar()
        _computador.value = _computador.value.copy(viagem = computadorBordo.resumo)
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
        val colunas = definicoesSuportadas(pidsSuportados.value)
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

        /** Nomes comuns de adaptadores ELM327/OBD2 Bluetooth (o S24 costuma ter fones, relógio e carro pareados). */
        private val regexNomeScanner = Regex(
            "obd|elm|v-?link|vgate|icar|konnwei|veepeak|obdlink|carista|viecar|scan",
            RegexOption.IGNORE_CASE,
        )

        fun pareceScannerObd(dispositivo: DispositivoObd): Boolean = regexNomeScanner.containsMatchIn(dispositivo.nome)

        /**
         * Escolha padrão na lista de pareados: mantém a [atual] se ainda existir; senão o primeiro
         * com nome de scanner OBD; senão o primeiro da lista (o usuário pode trocar no menu).
         */
        fun escolherDispositivo(lista: List<DispositivoObd>, atual: DispositivoObd?): DispositivoObd? =
            lista.firstOrNull { it.endereco == atual?.endereco }
                ?: lista.firstOrNull(::pareceScannerObd)
                ?: lista.firstOrNull()

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
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                    salvarCsv = { nome, escrever -> app.exportadorCsv.salvar(nome, escrever) },
                )
            }
        }
    }
}
