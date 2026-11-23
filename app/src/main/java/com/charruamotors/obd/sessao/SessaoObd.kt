package com.charruamotors.obd.sessao

import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.CabecalhoSessao
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.obd.log.DtcsSessao
import com.charruamotors.obd.log.GravadorLogsSessao
import com.charruamotors.obd.log.LogsSessaoSalvos
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId

/** Quem abriu a sessão: o botão Conectar ou o Bluetooth do carro. */
enum class OrigemSessao { MANUAL, AUTOMATICA }

/** Veículo vinculado à sessão (cascata de conexão, consumo e cabeçalho/nome dos logs). */
data class VeiculoSessao(
    val id: Long,
    /** `Toyota SW4 2015`. */
    val descricao: String,
    /** `Toyota SW4` (notificação e nome dos arquivos). */
    val nomeCurto: String,
    val marca: String?,
    val placa: String?,
    val combustivel: TipoCombustivel?,
    /** Modelo (a cilindrada vem dele) e a calibração do consumo, para o resumo da viagem. */
    val modelo: String? = null,
    val calibracaoConsumo: Double = 1.0,
) {
    companion object {
        fun de(v: Veiculo) = VeiculoSessao(
            id = v.id,
            descricao = v.descricao,
            nomeCurto = "${v.marca} ${v.modelo}".trim(),
            marca = v.marca,
            placa = v.placaFormatada,
            combustivel = v.tipoCombustivel,
            modelo = v.modelo,
            calibracaoConsumo = v.calibracaoConsumo,
        )
    }
}

/** Etapa da sessão (o serviço em primeiro plano vive enquanto não for [Inativa]). */
sealed interface FaseSessao {
    data object Inativa : FaseSessao

    /** Sessão automática: espera a ignição/ECU terminar o boot antes de conectar. */
    data object AguardandoEcu : FaseSessao

    data class Conectando(val tentativa: Int, val total: Int) : FaseSessao

    data object Conectada : FaseSessao

    /** Fechando a conexão e salvando os logs. */
    data object Encerrando : FaseSessao
}

data class EstadoSessao(
    val fase: FaseSessao = FaseSessao.Inativa,
    val origem: OrigemSessao? = null,
    /** Nome curto do veículo (`Toyota SW4`), quando vinculado. */
    val veiculo: String? = null,
    val scanner: String? = null,
    /** Bluetooth do carro que disparou a sessão automática. */
    val gatilho: String? = null,
    val gravandoLogs: Boolean = false,
    /**
     * O usuário assumiu a sessão (abriu uma ferramenta manual): o Bluetooth do carro deixa de
     * encerrá-la — só o botão Desconectar ou a ECU muda (ignição desligada). Zera com a sessão.
     */
    val assumidaPeloUsuario: Boolean = false,
) {
    val ativa: Boolean get() = fase != FaseSessao.Inativa
    val automatica: Boolean get() = ativa && origem == OrigemSessao.AUTOMATICA
}

sealed interface EventoSessao {
    data class Mensagem(val texto: String) : EventoSessao
    data class LogsSalvos(val logs: LogsSessaoSalvos) : EventoSessao
}

/** Tempos da sessão automática (ajustáveis nos testes). */
data class TemposSessao(
    /** Espera depois do Bluetooth do carro conectar, para a ECU terminar o boot com a ignição. */
    val esperaEcuMs: Long = 4_000,
    /** Tentativas de conexão ao adaptador na sessão automática (ele pode ainda estar inicializando). */
    val tentativasAutomaticas: Int = 3,
    val intervaloTentativasMs: Long = 5_000,
    /** Prazo do perfil de montadora antes de abrir o CSV só com os PIDs padrão. */
    val prazoPerfilMs: Long = 30_000,
)

/**
 * Sessão OBD2 única do app — o "repositório" compartilhado entre a aba OBD2 (`ObdViewModel`), o
 * HUD flutuante e o `ObdSessaoService` (primeiro plano, `connectedDevice`). Vive no Application,
 * então quem abrir a tela depois de uma conexão automática (celular no bolso) assume a sessão já
 * ativa, ao vivo, sem abrir um segundo socket Bluetooth.
 *
 * Cuida de: conexão (com espera da ECU e até 3 tentativas na automática), polling contínuo da
 * telemetria em duas filas (publicado em [dados]/[amostras] e na [TelemetriaCompartilhada] do
 * HUD), VIN, perfil de PIDs da montadora, leitura dos DTCs para o relatório e os logs automáticos
 * ([GravadorLogsSessao]: `.txt` + `.csv` em `Downloads/CharruaOBD`). A sessão termina quando o
 * usuário desconecta, quando o Bluetooth do carro cai (sessão automática) ou quando o cliente
 * publica `Erro` (link perdido ou ECU muda com a ignição desligada) — sempre fechando os arquivos.
 *
 * Use do mesmo contexto do [escopo] (no app, a main thread); a telemetria roda em [io].
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class SessaoObd(
    private val clientePorModo: (ModoObd) -> ObdClient,
    modoInicial: ModoObd,
    private val catalogoPids: CatalogoPids,
    private val catalogoDtc: CatalogoDtc,
    private val config: () -> ConfigAutoConexao,
    private val buscarVeiculo: suspend (Long) -> VeiculoSessao?,
    private val destinoLogs: DestinoLogs,
    private val pastaTemporaria: File,
    private val escopo: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val telemetria: TelemetriaCompartilhada = TelemetriaCompartilhada(),
    /** Sessão começando: o app sobe o serviço em primeiro plano (a automática já nasce dentro dele). */
    private val aoAtivar: (OrigemSessao) -> Unit = {},
    private val relogio: () -> Long = System::currentTimeMillis,
    private val tempos: TemposSessao = TemposSessao(),
    private val zona: ZoneId = ZoneId.systemDefault(),
) {
    private val _modo = MutableStateFlow(modoInicial)
    val modo: StateFlow<ModoObd> = _modo.asStateFlow()

    /** Cliente do modo atual (simulado ou Bluetooth real). */
    val cliente: ObdClient get() = clientePorModo(_modo.value)

    val estado: StateFlow<EstadoConexaoObd> = _modo
        .flatMapLatest { clientePorModo(it).estado }
        .stateIn(escopo, SharingStarted.Eagerly, clientePorModo(modoInicial).estado.value)

    val pidsSuportados: StateFlow<Set<String>> = _modo
        .flatMapLatest { clientePorModo(it).pidsSuportados }
        .stateIn(escopo, SharingStarted.Eagerly, clientePorModo(modoInicial).pidsSuportados.value)

    val infoConexao: StateFlow<InfoConexao> = _modo
        .flatMapLatest { clientePorModo(it).infoConexao }
        .stateIn(escopo, SharingStarted.Eagerly, clientePorModo(modoInicial).infoConexao.value)

    private val _dados = MutableStateFlow<DadosObd?>(null)
    /** Último instantâneo da telemetria (null = desconectado). */
    val dados: StateFlow<DadosObd?> = _dados.asStateFlow()

    private val _amostras = MutableSharedFlow<DadosObd>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Cada instantâneo da telemetria (computador de bordo, cronômetro, gráfico e Data Logger da tela). */
    val amostras: SharedFlow<DadosObd> = _amostras.asSharedFlow()

    private val _perfil = MutableStateFlow<ResultadoPerfil?>(null)
    /** Perfil de PIDs da montadora desta conexão; null = verificando ou desconectado. */
    val perfil: StateFlow<ResultadoPerfil?> = _perfil.asStateFlow()

    private val _vin = MutableStateFlow<String?>(null)
    val vin: StateFlow<String?> = _vin.asStateFlow()

    private val _vinLido = MutableStateFlow(false)
    val vinLido: StateFlow<Boolean> = _vinLido.asStateFlow()

    private val _sessao = MutableStateFlow(EstadoSessao())
    val estadoSessao: StateFlow<EstadoSessao> = _sessao.asStateFlow()

    private val _eventos = MutableSharedFlow<EventoSessao>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val eventos: SharedFlow<EventoSessao> = _eventos.asSharedFlow()

    private var sessaoJob: Job? = null
    @Volatile private var clienteDaSessao: ObdClient? = null
    @Volatile private var gravador: GravadorLogsSessao? = null
    @Volatile private var motivoPedido: String? = null
    @Volatile private var marcaAtual: String? = null

    // ------------------------------------------------------------------ modo

    /** Só troca de modo sem sessão ativa e com o scanner desconectado. */
    fun selecionarModo(modo: ModoObd): Boolean {
        if (modo == _modo.value) return true
        if (_sessao.value.ativa || cliente.estado.value.ocupado) return false
        _modo.value = modo
        return true
    }

    // ------------------------------------------------------------------ início

    /**
     * Abre uma sessão com [dispositivo]. Devolve false (sem efeito) se já houver uma ativa — por
     * exemplo, a automática do carro: a tela então só a assume.
     */
    fun conectar(dispositivo: DispositivoObd, veiculoId: Long, origem: OrigemSessao, gatilho: String? = null): Boolean {
        if (_sessao.value.ativa || cliente.estado.value.ocupado) return false
        motivoPedido = null
        val total = if (origem == OrigemSessao.AUTOMATICA) tempos.tentativasAutomaticas.coerceAtLeast(1) else 1
        _sessao.value = EstadoSessao(
            fase = if (origem == OrigemSessao.AUTOMATICA) FaseSessao.AguardandoEcu else FaseSessao.Conectando(1, total),
            origem = origem,
            scanner = dispositivo.nome,
            gatilho = gatilho,
        )
        runCatching { aoAtivar(origem) }
        val cli = cliente
        clienteDaSessao = cli
        sessaoJob = escopo.launch { executar(cli, dispositivo, veiculoId, origem, gatilho, total) }
        return true
    }

    /**
     * Sessão automática disparada pelo Bluetooth do carro: modo Bluetooth, scanner vinculado (ou o
     * primeiro pareado com nome de OBD) e último veículo. False = já havia sessão ou não há scanner.
     */
    suspend fun iniciarAutomatico(gatilho: String?): Boolean {
        if (_sessao.value.ativa) return false
        if (!selecionarModo(ModoObd.BLUETOOTH)) return false
        val cfg = config()
        val pareados = runCatching { cliente.listarDispositivos() }.getOrDefault(emptyList())
        val scanner = EscolhaScanner.paraSessaoAutomatica(pareados, cfg.scanner)
        if (scanner == null) {
            _eventos.tryEmit(EventoSessao.Mensagem("Sessão automática: nenhum scanner OBD2 pareado ou vinculado"))
            return false
        }
        return conectar(scanner, cfg.veiculoId, OrigemSessao.AUTOMATICA, gatilho ?: cfg.gatilhoNome)
    }

    // ------------------------------------------------------------------ encerramento

    /**
     * Encerra a sessão (ou cancela a conexão em andamento) salvando os logs. Fecha o socket na hora,
     * o que destrava um comando preso esperando o ELM327.
     */
    fun desconectar(motivo: String = MOTIVO_USUARIO) {
        val cli = clienteDaSessao ?: cliente
        val job = sessaoJob
        if (job?.isActive == true && motivoPedido == null) motivoPedido = motivo
        escopo.launch(io) { cli.desconectar() }
        job?.cancel()
    }

    /** Promove a sessão automática a "assumida pelo usuário" (ver [EstadoSessao.assumidaPeloUsuario]). */
    fun assumir() {
        if (_sessao.value.automatica && !_sessao.value.assumidaPeloUsuario) {
            _sessao.update { it.copy(assumidaPeloUsuario = true) }
            registrarEvento("Sessão assumida pelo usuário: só encerra em Desconectar ou quando a ECU parar de responder")
        }
    }

    /** Anota um evento no log bruto (`.txt`) da sessão em curso, se houver. */
    fun registrarEvento(texto: String) {
        gravador?.evento(texto, relogio())
    }

    /** Bluetooth do carro desconectou: encerra só a sessão que ele abriu. */
    fun encerrarPorGatilho(nome: String?): Boolean {
        if (!_sessao.value.automatica) return false
        desconectar("Bluetooth do carro (${nome ?: "gatilho"}) desconectado")
        return true
    }

    /** DTCs lidos pela tela entram no relatório da sessão (substituem a leitura automática). */
    fun registrarDtcs(confirmados: List<DtcLido>, pendentes: List<DtcLido>?, permanentes: List<DtcLido>?) {
        gravador?.registrarDtcs(DtcsSessao(confirmados, pendentes, permanentes))
    }

    /**
     * Definições de todos os sensores suportados, na ordem do catálogo + montadora. Nos modos 21/22
     * o PID padrão dá lugar ao espelho da mesma chave semântica (rótulo real `7E0 · 210C`).
     */
    fun definicoesSuportadas(
        suportados: Set<String>,
        conexao: InfoConexao = infoConexao.value,
        perfil: ResultadoPerfil? = _perfil.value,
    ): List<DefinicaoPid> {
        val espelhos = conexao.espelhos.associateBy { it.comando }
        val montadora = (perfil as? ResultadoPerfil.Ativo)?.pids.orEmpty()
        return (catalogoPids.pids.map { espelhos[it.comando] ?: it } + montadora).filter { it.comando in suportados }
    }

    // ------------------------------------------------------------------ execução

    private suspend fun executar(
        cli: ObdClient,
        dispositivo: DispositivoObd,
        veiculoId: Long,
        origem: OrigemSessao,
        gatilho: String?,
        tentativas: Int,
    ) {
        val inicioMs = relogio()
        var conectou = false
        var motivoFim = "Sessão encerrada"
        var g: GravadorLogsSessao? = null
        try {
            val veiculo = if (veiculoId > 0) runCatching { buscarVeiculo(veiculoId) }.getOrNull() else null
            marcaAtual = veiculo?.marca
            if (config().gravarLogsAutomaticos) {
                g = withContext(io) { criarGravador(dispositivo, veiculo, origem, gatilho, inicioMs) }
                gravador = g
                cli.observarRegistro(g::registrarEntrada)
            }
            _sessao.update { it.copy(veiculo = veiculo?.nomeCurto, gravandoLogs = g != null) }
            g?.evento("Iniciada (${descreverOrigem(origem, gatilho)}) com ${dispositivo.nome} (${dispositivo.endereco})", relogio())

            if (origem == OrigemSessao.AUTOMATICA && tempos.esperaEcuMs > 0) {
                g?.evento("Aguardando ${tempos.esperaEcuMs / 1000} s para a ignição/ECU terminar o boot", relogio())
                delay(tempos.esperaEcuMs)
            }
            var erro: String? = null
            for (tentativa in 1..tentativas) {
                _sessao.update { it.copy(fase = FaseSessao.Conectando(tentativa, tentativas)) }
                if (tentativas > 1) g?.evento("Tentativa de conexão $tentativa/$tentativas", relogio())
                val resultado = cli.conectar(dispositivo, veiculo?.marca)
                if (resultado.isSuccess) {
                    conectou = true
                    break
                }
                erro = resultado.exceptionOrNull()?.message ?: "falha desconhecida"
                g?.evento(if (tentativas > 1) "Tentativa $tentativa/$tentativas falhou: $erro" else "Conexão falhou: $erro", relogio())
                if (tentativa < tentativas) delay(tempos.intervaloTentativasMs)
            }
            if (!conectou) {
                motivoFim = "Falha na conexão: $erro"
                return
            }
            _sessao.update { it.copy(fase = FaseSessao.Conectada) }
            g?.conexaoEstabelecida(cli.infoConexao.value)
            motivoFim = acompanharConexao(cli, veiculo, g)
        } finally {
            withContext(NonCancellable) { encerrar(cli, g, conectou, motivoPedido ?: motivoFim) }
        }
    }

    /** Conectado: telemetria, VIN, perfil → CSV → DTCs, até o cliente sair de `Conectado`. Devolve o motivo do fim. */
    private suspend fun acompanharConexao(cli: ObdClient, veiculo: VeiculoSessao?, g: GravadorLogsSessao?): String = coroutineScope {
        launch(io) {
            // MAF em todos os combustíveis: o diesel também estima o consumo por ele (v1.7).
            cli.lerTelemetria(consumoPorMaf = true)
                .catch { e -> g?.evento("Telemetria interrompida: ${e.message}", relogio()) }
                .collect { dados -> receberAmostra(dados, g) }
        }
        launch {
            _vinLido.value = false
            _vin.value = cli.lerVin().getOrNull()
            _vinLido.value = true
        }
        launch {
            _perfil.value = null
            val perfil = withTimeoutOrNull(tempos.prazoPerfilMs) { cli.ativarPerfilFabricante(veiculo?.marca) }
                ?: ResultadoPerfil.Indisponivel(veiculo?.marca ?: "montadora", "a ECU não respondeu no prazo")
            _perfil.value = perfil
            if (perfil is ResultadoPerfil.Ativo) {
                _eventos.tryEmit(EventoSessao.Mensagem("Perfil ${perfil.fabricante} ativado: ${perfil.pids.size} PIDs da montadora"))
            }
            if (g != null) {
                val colunas = definicoesSuportadas(cli.pidsSuportados.value, cli.infoConexao.value, perfil)
                withContext(io) { g.iniciarCsv(colunas) }
                g.evento("CSV de telemetria aberto com ${colunas.size} sensores", relogio())
                lerDtcsParaRelatorio(cli, g)
            }
        }
        val fim = cli.estado.first { it !is EstadoConexaoObd.Conectado }
        coroutineContext.cancelChildren()
        (fim as? EstadoConexaoObd.Erro)?.mensagem ?: "Desconectado"
    }

    private fun receberAmostra(dados: DadosObd, g: GravadorLogsSessao?) {
        _dados.value = dados
        _amostras.tryEmit(dados)
        telemetria.publicar(dados, conectado = true)
        g?.registrar(dados, relogio())
    }

    /** Uma leitura de confirmados/pendentes/permanentes por sessão, só para o relatório (não mexe no histórico). */
    private suspend fun lerDtcsParaRelatorio(cli: ObdClient, g: GravadorLogsSessao) {
        val confirmados = cli.lerCodigosFalha().getOrNull()
        if (confirmados == null) {
            g.evento("Leitura automática de DTCs sem resposta da ECU", relogio())
            return
        }
        val pendentes = cli.lerCodigosPendentes().getOrNull()
        val permanentes = cli.lerCodigosPermanentes().getOrNull()
        g.registrarDtcs(DtcsSessao(descrever(confirmados), descrever(pendentes), descrever(permanentes)))
        g.evento("DTCs lidos: ${confirmados.size} confirmados, ${pendentes?.size ?: "-"} pendentes, ${permanentes?.size ?: "-"} permanentes", relogio())
    }

    private fun descrever(lista: List<DtcLido>?): List<DtcLido>? =
        lista?.map { DtcLido(it.codigo, catalogoDtc.descrever(it.codigo, marcaAtual).titulo) }

    private suspend fun encerrar(cli: ObdClient, g: GravadorLogsSessao?, conectou: Boolean, motivo: String) {
        _sessao.update { it.copy(fase = FaseSessao.Encerrando) }
        if (cli.estado.value.ocupado) runCatching { withContext(io) { cli.desconectar() } }
        _dados.value = null
        _perfil.value = null
        _vin.value = null
        _vinLido.value = false
        telemetria.publicar(null, conectado = false)
        if (g != null) {
            g.evento("Encerrada: $motivo", relogio())
            cli.observarRegistro(null)
            val logs = withContext(io) { g.finalizar(relogio(), conectou, motivo) }
            _eventos.tryEmit(EventoSessao.LogsSalvos(logs))
        }
        gravador = null
        clienteDaSessao = null
        sessaoJob = null
        _sessao.value = EstadoSessao()
    }

    private fun criarGravador(
        dispositivo: DispositivoObd,
        veiculo: VeiculoSessao?,
        origem: OrigemSessao,
        gatilho: String?,
        inicioMs: Long,
    ): GravadorLogsSessao {
        val simulado = _modo.value == ModoObd.SIMULADO
        val cabecalho = CabecalhoSessao(
            veiculo = veiculo?.descricao,
            veiculoArquivo = veiculo?.nomeCurto,
            placa = veiculo?.placa,
            modoInicio = descreverOrigem(origem, gatilho) + if (simulado) " · scanner simulado" else "",
            scanner = "${dispositivo.nome} (${dispositivo.endereco})",
            inicioMs = inicioMs,
            simulado = simulado,
            motor = veiculo?.let { ParametrosMotor.deVeiculo(it.modelo, it.combustivel, it.calibracaoConsumo) },
        )
        return GravadorLogsSessao(destinoLogs, cabecalho, pastaTemporaria, zona)
    }

    private fun descreverOrigem(origem: OrigemSessao, gatilho: String?): String = when (origem) {
        OrigemSessao.MANUAL -> "Manual (botão Conectar)"
        OrigemSessao.AUTOMATICA -> "Automático (Bluetooth do carro: ${gatilho ?: "?"})"
    }

    private val EstadoConexaoObd.ocupado: Boolean
        get() = this is EstadoConexaoObd.Conectado || this is EstadoConexaoObd.Conectando

    companion object {
        const val MOTIVO_USUARIO = "Desconectado pelo usuário"

        /** Texto da notificação / do card: `Conectado · Toyota SW4 · Gravando log`. */
        fun descreverStatus(e: EstadoSessao): String {
            val alvo = e.veiculo ?: e.gatilho
            val partes = when (val f = e.fase) {
                FaseSessao.Inativa -> listOf("Sem sessão ativa")
                FaseSessao.AguardandoEcu -> listOf("Aguardando a ECU", alvo)
                is FaseSessao.Conectando -> {
                    val scanner = e.scanner ?: "scanner"
                    val tentativa = if (f.total > 1) " (tentativa ${f.tentativa}/${f.total})" else ""
                    listOf("Conectando ao $scanner$tentativa", alvo)
                }
                FaseSessao.Conectada -> listOf("Conectado", alvo, if (e.gravandoLogs) "Gravando log" else null)
                FaseSessao.Encerrando -> listOf("Encerrando", "salvando logs")
            }
            return partes.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}
