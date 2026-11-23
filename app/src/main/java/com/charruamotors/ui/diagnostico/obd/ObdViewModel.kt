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
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.direcao.desempenho.SeletorVelocidade
import com.charruamotors.domain.calculo.ComputadorBordo
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.calculo.Cronometro0a100
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.TelemetriaCalculada
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.log.DataLogger
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.LogsSessaoSalvos
import com.charruamotors.obd.log.RegistroComunicacao
import com.charruamotors.obd.log.SessaoLog
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EscolhaScanner
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.EventoSessao
import com.charruamotors.obd.sessao.OrigemSessao
import com.charruamotors.obd.sessao.PreferenciasAutoConexao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.ui.componentes.JanelaTelemetria
import com.charruamotors.ui.componentes.PontoTelemetria
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.ArquivoExportado
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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

    /** Sessão encerrada com os logs automáticos (`.txt` + `.csv`) salvos: a UI oferece abri-los. */
    data class LogsSessao(val logs: LogsSessaoSalvos) : EventoObd
}

/**
 * ViewModel da aba OBD2. A conexão, o polling, o perfil de PIDs da montadora, o VIN e os logs
 * automáticos vivem na [SessaoObd] do Application (compartilhada com o HUD flutuante e com o
 * `ObdSessaoService`): ao abrir a tela com uma sessão já ativa — por exemplo, a automática
 * iniciada pelo Bluetooth do carro com o celular no bolso —, o ViewModel só a assume, ao vivo,
 * sem abrir outro socket. Aqui fica o que é da tela: computador de bordo, cronômetro 0–100 km/h,
 * gráfico ao vivo, Data Logger CSV manual e as preferências da caixa-preta. A leitura de falhas
 * fica na Oficina (`DiagnosticoErrosViewModel`) e o histórico de leituras na aba Histórico.
 *
 * @param sessao sessão OBD compartilhada (cliente do modo atual, estado e telemetria)
 * @param preferencias auto-conexão, logs automáticos e vínculos de scanner/veículo (null = padrão, sem persistir)
 * @param veiculo veículo do diagnóstico: modelo (cilindrada) e combustível
 * @param salvarCsv grava um arquivo (nome + escrita em streaming) e devolve onde ficou
 */
class ObdViewModel(
    private val veiculoId: Long,
    private val sessao: SessaoObd,
    private val preferencias: PreferenciasAutoConexao?,
    private val clientePorModo: (ModoObd) -> ObdClient,
    private val repository: DiagnosticoRepository,
    private val catalogoPids: CatalogoPids,
    private val veiculo: suspend () -> Veiculo?,
    private val salvarCsv: suspend (String, (Writer) -> Unit) -> ArquivoExportado,
    private val relogio: () -> Long = System::currentTimeMillis,
    /** Fonte da velocidade do cronômetro 0–100 (Configurações): ECU ou GPS com fallback para a ECU. */
    private val fonteCronometro: () -> FonteVelocidade = { FonteVelocidade.PADRAO },
) : ViewModel() {

    val modo: StateFlow<ModoObd> = sessao.modo

    private val cliente: ObdClient get() = sessao.cliente

    /** Estado da conexão do cliente do modo atual (acompanha a troca de modo). */
    val estado: StateFlow<EstadoConexaoObd> = sessao.estado

    /** Comandos do catálogo que a ECU conectada suporta (bitmasks 0100/0120/0140). */
    val pidsSuportados: StateFlow<Set<String>> = sessao.pidsSuportados

    /** Etapa da sessão compartilhada (aguardando ECU, tentativa N/3, conectada, salvando logs...). */
    val estadoSessao: StateFlow<EstadoSessao> = sessao.estadoSessao

    /** Preferências da caixa-preta. */
    val configAuto: StateFlow<ConfigAutoConexao> = preferencias?.config ?: MutableStateFlow(ConfigAutoConexao())

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

    /** Último instantâneo da telemetria da sessão. */
    val dados: StateFlow<DadosObd?> = sessao.dados

    /** Todos os PIDs suportados com o último valor conhecido (grade ao vivo; a fila lenta a atualiza aos poucos). */
    val sensores: StateFlow<List<LeituraSensor>> =
        combine(pidsSuportados, sessao.dados, sessao.perfil, sessao.infoConexao) { suportados, dados, perfil, conexao ->
            sessao.definicoesSuportadas(suportados, conexao, perfil).map { LeituraSensor(it, dados?.get(it.comando)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------ telemetria derivada

    private val computadorBordo = ComputadorBordo()
    private val _computador = MutableStateFlow(ComputadorBordoUi())
    /** Consumo instantâneo (MAF ou Speed-Density) e média da viagem. */
    val computador: StateFlow<ComputadorBordoUi> = _computador.asStateFlow()

    private val cronometro0a100 = Cronometro0a100()

    /** Fonte que alimentou a última leitura do cronômetro 0–100 (para detectar a troca no meio da arrancada). */
    private var fonteCronometroAtual: FonteVelocidade? = null
    private val _cronometro = MutableStateFlow(CronometroUi())
    val cronometro: StateFlow<CronometroUi> = _cronometro.asStateFlow()

    private val janelaGrafico = JanelaTelemetria()
    private val _grafico = MutableStateFlow<List<PontoTelemetria>>(emptyList())
    /** Últimos 60 s de RPM e MAP para o gráfico ao vivo. */
    val grafico: StateFlow<List<PontoTelemetria>> = _grafico.asStateFlow()

    /** Tamanho do catálogo, para "N de M PIDs suportados". */
    val totalPidsCatalogo: Int get() = catalogoPids.pids.size

    /** VIN (modo 09), quantidade de PIDs padrão detectados, perfil de montadora, protocolo e modo. */
    val infoEcu: StateFlow<InfoEcu> =
        combine(sessao.vin, sessao.vinLido, pidsSuportados, sessao.perfil, sessao.infoConexao) { vin, lido, pids, perfil, conexao ->
            InfoEcu(vin, lido, pids.count { it in catalogoPids }, perfil, conexao)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InfoEcu())

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

    private var conexaoJob: Job? = null

    /** Veículo do diagnóstico, carregado uma vez (marca, modelo e combustível). */
    private val dadosVeiculo = viewModelScope.async { runCatching { veiculo() }.getOrNull() }

    private val _limites = MutableStateFlow(LimitesProtecao())

    /** Limites de alerta do veículo (água, ATF, alternador); padrão até o veículo carregar. */
    val limites: StateFlow<LimitesProtecao> = _limites.asStateFlow()

    /** Cilindrada e combustível para o consumo; padrão até o veículo carregar. */
    private var parametrosMotor = ParametrosMotor()

    init {
        // Reage às mudanças de estado da sessão compartilhada (inclusive a automática do carro).
        viewModelScope.launch {
            // drop(1): ignora o valor inicial, para não repetir um erro antigo ao reabrir a tela.
            estado.drop(1).collect { novo ->
                if (novo is EstadoConexaoObd.Conectado) {
                    iniciarViagem()
                    _eventos.send(EventoObd.Mensagem(R.string.obd_conectado))
                } else {
                    if (cronometro0a100.armado) _cronometro.value = CronometroUi(cronometro0a100.desarmar(), cronometro0a100.melhorTempoMs)
                    // Conexão caiu durante a gravação: salva o que já foi coletado.
                    if (logger.gravando) pararLog()
                }
                if (novo is EstadoConexaoObd.Erro) _eventos.send(EventoObd.ErroConexao(novo.mensagem))
            }
        }
        // Já conectado (ex.: a sessão automática do carro estava ativa ao abrir a tela).
        if (estado.value is EstadoConexaoObd.Conectado) iniciarViagem()
        viewModelScope.launch {
            val v = dadosVeiculo.await()
            parametrosMotor = ParametrosMotor.deVeiculo(v?.modelo, v?.tipoCombustivel, v?.calibracaoConsumo ?: 1.0)
            _limites.value = LimitesProtecao.deVeiculo(v)
            _computador.value = _computador.value.copy(motor = parametrosMotor)
            // O favorito (veículo da caixa-preta) só muda pela estrela em Meus Veículos; aqui ele é
            // definido se ainda não houver um, e a descrição acompanha renomeações.
            if (v != null) preferencias?.vincularVeiculoSeLivre(veiculoId, v.descricao)
        }
        // Cada instantâneo da telemetria da sessão. Segue com a tela em segundo plano: o serviço da
        // sessão mantém o polling, e o computador de bordo não perde a viagem.
        viewModelScope.launch {
            sessao.amostras.collect { dados ->
                processarTelemetria(dados, relogio())
                registrarNoLog(dados)
            }
        }
        viewModelScope.launch {
            sessao.eventos.collect { evento ->
                when (evento) {
                    is EventoSessao.Mensagem -> _eventos.send(EventoObd.Texto(evento.texto))
                    is EventoSessao.LogsSalvos -> if (evento.logs.arquivos.isNotEmpty()) _eventos.send(EventoObd.LogsSessao(evento.logs))
                }
            }
        }
        carregarDispositivos()
    }

    // ------------------------------------------------------------------ modo e dispositivos

    /** Só troca de modo com o scanner desconectado (e sem sessão ativa). */
    fun selecionarModo(modo: ModoObd) {
        if (modo == sessao.modo.value || !sessao.selecionarModo(modo)) return
        _dispositivoSelecionado.value = null
        _dispositivos.value = emptyList()
        carregarDispositivos()
    }

    /**
     * Início rápido do Painel: "Conectar no Carro (Bluetooth)" ou "Modo Simulado" com 1 toque. Troca
     * o modo se preciso (só com a sessão parada) e conecta ao adaptador salvo / ao simulado.
     */
    fun conectarNoModo(modo: ModoObd) {
        if (conexaoJob?.isActive == true || sessao.estadoSessao.value.ativa) return
        if (modo != sessao.modo.value) {
            if (!sessao.selecionarModo(modo)) return
            _dispositivoSelecionado.value = null
            _dispositivos.value = emptyList()
        }
        conectar()
    }

    /** Aparelhos pareados no Bluetooth real (para trocar o adaptador), mesmo no modo simulado. */
    suspend fun pareadosBluetooth(): List<DispositivoObd> =
        runCatching { clientePorModo(ModoObd.BLUETOOTH).listarDispositivos() }.getOrDefault(emptyList())

    /** Ícone de trocar o adaptador: vira o scanner salvo do "Conectar no Carro". */
    fun escolherAdaptador(dispositivo: DispositivoObd) {
        preferencias?.vincularScanner(dispositivo)
        if (modo.value == ModoObd.BLUETOOTH) _dispositivoSelecionado.value = dispositivo
    }

    /** Lista os scanners disponíveis (pareados, no Bluetooth real — exige permissão concedida). */
    fun carregarDispositivos() {
        viewModelScope.launch { atualizarDispositivos() }
    }

    private suspend fun atualizarDispositivos() {
        val lista = cliente.listarDispositivos()
        _dispositivos.value = lista
        // No Bluetooth real, a primeira escolha é o scanner vinculado (o último usado).
        val atual = _dispositivoSelecionado.value ?: configAuto.value.scanner.takeIf { modo.value == ModoObd.BLUETOOTH }
        _dispositivoSelecionado.value = escolherDispositivo(lista, atual)
    }

    fun selecionarDispositivo(dispositivo: DispositivoObd) {
        _dispositivoSelecionado.value = dispositivo
        if (modo.value == ModoObd.BLUETOOTH) preferencias?.vincularScanner(dispositivo)
    }

    // ------------------------------------------------------------------ conexão

    /**
     * Conecta ao scanner escolhido, abrindo a sessão compartilhada (com logs automáticos, se
     * ativados). Sem escolha (lista ainda vazia porque a permissão ou o Bluetooth acabaram de ser
     * liberados), recarrega os pareados e só segue sozinho se achar um que pareça adaptador OBD —
     * nunca tenta conectar num fone ou relógio.
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
            if (modo.value == ModoObd.BLUETOOTH) preferencias?.vincularScanner(dispositivo)
            // Falhas chegam como EstadoConexaoObd.Erro e o sucesso como Conectado (tratados no init).
            // A sessão carrega a marca do veículo, que decide os fallbacks da cascata (modos 21 / 22).
            if (!sessao.conectar(dispositivo, veiculoId, OrigemSessao.MANUAL)) {
                _eventos.send(EventoObd.Mensagem(R.string.obd_sessao_ja_ativa))
            }
        }
    }

    /**
     * Desconecta (ou cancela uma conexão em andamento) e salva os logs da sessão. O cliente fecha o
     * socket na hora, o que destrava um comando preso esperando o ELM327.
     */
    fun desconectar() {
        conexaoJob?.cancel()
        sessao.desconectar()
    }

    /** Registro das últimas trocas com o ELM327, pronto para exibir/copiar; null se vazio. */
    fun textoRegistroComunicacao(): String? =
        cliente.registroComunicacao().takeIf { it.isNotEmpty() }?.let(RegistroComunicacao::formatar)

    /** Nova conexão: zera a viagem e o gráfico (VIN e perfil da montadora vêm da sessão). */
    private fun iniciarViagem() {
        computadorBordo.zerar()
        janelaGrafico.limpar()
        _grafico.value = emptyList()
        _computador.value = ComputadorBordoUi(motor = parametrosMotor)
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
            val escolha = SeletorVelocidade.escolher(dados, fonteCronometro())
            // GPS perdeu/recuperou o sinal no meio da arrancada: recomeça a tentativa (nunca mistura as fontes).
            if (cronometro0a100.estado is EstadoCronometro.Medindo && SeletorVelocidade.mudouDeFonte(fonteCronometroAtual, escolha?.fonte)) {
                cronometro0a100.armar(escolha?.kmh)
            }
            if (escolha != null) fonteCronometroAtual = escolha.fonte
            _cronometro.value = CronometroUi(cronometro0a100.registrar(escolha?.kmh, instanteMs), cronometro0a100.melhorTempoMs, fonteCronometroAtual)
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
        val fonte = fonteCronometro()
        if (Pids.VELOCIDADE !in pidsSuportados.value && fonte != FonteVelocidade.GPS) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_cronometro_sem_velocidade))
            return
        }
        val escolha = SeletorVelocidade.escolher(dados.value, fonte)
        fonteCronometroAtual = escolha?.fonte
        _cronometro.value = CronometroUi(cronometro0a100.armar(escolha?.kmh), cronometro0a100.melhorTempoMs, fonteCronometroAtual)
        if (modo.value == ModoObd.SIMULADO) clienteSimulado?.simularArrancada()
    }

    /** Zera a média da viagem do computador de bordo. */
    fun zerarViagem() {
        computadorBordo.zerar()
        _computador.value = _computador.value.copy(viagem = computadorBordo.resumo)
    }

    // ------------------------------------------------------------------ Data Logger CSV (manual)

    /** Botão "Gravar Log CSV": inicia a gravação ou para e salva o arquivo. */
    fun alternarLog() {
        if (logger.gravando) pararLog() else iniciarLog()
    }

    private fun iniciarLog() {
        if (estado.value !is EstadoConexaoObd.Conectado) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_sem_conexao))
            return
        }
        val colunas = sessao.definicoesSuportadas(pidsSuportados.value) + sessao.colunasDoAparelho()
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
        val sessaoLog = logger.parar() ?: return
        _estadoLog.value = EstadoLog()
        if (sessaoLog.vazia) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_log_vazio))
            return
        }
        viewModelScope.launch {
            try {
                _eventos.send(EventoObd.LogSalvo(exportar(sessaoLog)))
            } catch (e: IOException) {
                _eventos.send(EventoObd.Texto(e.message ?: "Falha ao salvar o log"))
            }
        }
    }

    private suspend fun exportar(sessaoLog: SessaoLog): ArquivoExportado =
        salvarCsv(GeradorCsv.nomeArquivo(sessaoLog.inicioMs, simulado = sessao.modo.value == ModoObd.SIMULADO)) { saida -> GeradorCsv.escrever(sessaoLog, saida) }

    // ------------------------------------------------------------------ leitura salva

    /** Salva a última leitura recebida no histórico do veículo (aba Histórico → Viagens). */
    fun salvarLeitura() {
        val atual = dados.value
        if (atual == null) {
            _eventos.trySend(EventoObd.Mensagem(R.string.obd_erro_sem_leitura))
            return
        }
        viewModelScope.launch {
            repository.salvarLeitura(atual.paraLeitura(veiculoId))
            _eventos.send(EventoObd.Mensagem(R.string.obd_leitura_salva))
        }
    }

    // ------------------------------------------------------------------ ciclo de vida do ViewModel

    /**
     * ViewModel destruído de vez (a Activity terminou, não foi só uma rotação): salva o Data Logger
     * manual em andamento. A conexão Bluetooth real segue na sessão compartilhada (serviço em
     * primeiro plano com "Desconectar" na notificação); o modo simulado é encerrado como antes,
     * para não ficar gerando logs de demonstração em segundo plano. O `viewModelScope` já está
     * cancelado aqui, então usa um escopo próprio.
     */
    override fun onCleared() {
        val log = logger.parar()?.takeUnless { it.vazia }
        if (log != null) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { exportar(log) } }
        if (sessao.modo.value == ModoObd.SIMULADO && sessao.estadoSessao.value.ativa) sessao.desconectar()
        super.onCleared()
    }

    companion object {
        /** Nome de adaptador ELM327/OBD2 (o S24 costuma ter fones, relógio e carro pareados). */
        fun pareceScannerObd(dispositivo: DispositivoObd): Boolean = EscolhaScanner.pareceScannerObd(dispositivo)

        /**
         * Escolha padrão na lista de pareados: mantém a [atual] se ainda existir; senão o primeiro
         * com nome de scanner OBD; senão o primeiro da lista (o usuário pode trocar no menu).
         */
        fun escolherDispositivo(lista: List<DispositivoObd>, atual: DispositivoObd?): DispositivoObd? =
            EscolhaScanner.escolher(lista, atual)

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val handle: SavedStateHandle = createSavedStateHandle()
                val veiculoId = handle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                ObdViewModel(
                    veiculoId = veiculoId,
                    sessao = app.sessaoObd,
                    preferencias = app.preferenciasAutoConexao,
                    clientePorModo = app::obdClientDe,
                    repository = app.diagnosticoRepository,
                    catalogoPids = app.catalogoPids,
                    veiculo = { app.veiculoRepository.buscarPorId(veiculoId) },
                    salvarCsv = { nome, escrever -> app.exportadorCsv.salvar(nome, escrever) },
                    fonteCronometro = { app.preferenciasApp.config.value.fonteCronometroEfetiva },
                )
            }
        }
    }
}
