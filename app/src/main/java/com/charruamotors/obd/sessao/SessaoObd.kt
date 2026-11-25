package com.charruamotors.obd.sessao

import com.charruamotors.carros.PerfilCarro
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.EstequiometriaFlex
import com.charruamotors.domain.calculo.EstimadorCambioA750f
import com.charruamotors.domain.calculo.EstimadorCambioGm6T
import com.charruamotors.domain.calculo.EstimadorMarchaPerfil
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.faixas.ResolucaoPerfil
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.domain.perfis.DecodificadorVin
import com.charruamotors.domain.perfis.IdentificadorPerfil
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.domain.perfis.OrigemPerfil
import com.charruamotors.domain.perfis.PerfilIdentificado
import com.charruamotors.domain.perfis.PerfilCambio
import com.charruamotors.domain.perfis.VinDecodificado
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.aprendizado.FonteSimulacao
import com.charruamotors.obd.aprendizado.ModeloAprendido
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.log.ArquivoLogSalvo
import com.charruamotors.obd.log.CabecalhoSessao
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.obd.log.DtcsSessao
import com.charruamotors.obd.log.GravadorLogsSessao
import com.charruamotors.obd.log.LogsSessaoSalvos
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.sensores.HistoricoSessao
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
    /** Faixas mínimas/máximas esperadas (perfil do catálogo ou genéricas do cadastro), para o relatório. */
    val faixas: FaixasVeiculo? = null,
    val ano: Int? = null,
    /** Onde o cadastro caiu na cascata de perfis (exato → genérico → OBD2 padrão); null = não resolvido. */
    val resolucao: ResolucaoPerfil? = null,
    /** Limites do cadastro (água, ATF, alternador), reaplicados se a sessão trocar o perfil genérico (v2.2.2). */
    val limites: LimitesProtecao? = null,
    /**
     * v2.3.0: marca usada nos recursos da montadora (fallback 21/22 pela marca, PIDs estendidos,
     * estimador de marcha GM); null no perfil OBD2 Genérico/padrão — o carro não herda nada da SW4 nem do Cruze.
     */
    val marcaMontadora: String? = marca,
    /** v2.3.0: serviço que a camada 3 tenta primeiro (`21` com a camada Modo 21 do perfil). */
    val servicoPreferido: String? = null,
    /** v2.3.0: câmbio escolhido no perfil técnico do carro (vale sobre o do perfil exato). */
    private val cambioEscolhido: PerfilCambio? = null,
) {
    /** Câmbio do perfil exato (estima a marcha dos automáticos sem regra própria); null nos genéricos. */
    val cambio: PerfilCambio?
        get() = cambioEscolhido ?: resolucao?.takeIf { !it.generico }?.preset?.perfilCambio

    companion object {
        /** Veículo do cadastro com o [perfil] isolado dele (v2.3.0: faixas, câmbio e camada escolhidos). */
        fun de(v: Veiculo, perfil: PerfilCarro = PerfilCarro(v.id)): VeiculoSessao {
            val resolucao = perfil.resolver(v)
            return VeiculoSessao(
                id = v.id,
                descricao = v.descricao,
                nomeCurto = "${v.marca} ${v.modelo}".trim(),
                marca = v.marca,
                placa = v.placaFormatada,
                combustivel = v.tipoCombustivel,
                modelo = v.modelo,
                calibracaoConsumo = v.calibracaoConsumo,
                faixas = resolucao.faixas,
                ano = v.ano,
                resolucao = resolucao,
                limites = LimitesProtecao.deVeiculo(v),
                marcaMontadora = perfil.marcaMontadora(v.marca),
                servicoPreferido = perfil.servicoPreferido,
                cambioEscolhido = perfil.perfilCambioId?.let { perfil.cambio(resolucao) },
            )
        }
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
    /** v2.3.0: id do veículo da sessão (recordes e perfil isolado); 0 = sem veículo vinculado. */
    val veiculoId: Long = 0,
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
    /** Prazo da identificação (VIN + sondagem da ECU) antes de seguir com o perfil do cadastro. */
    val prazoIdentificacaoMs: Long = 20_000,
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
    /** Velocidade GPS ativa nas Configurações? (coluna no CSV e mescla nos instantâneos). */
    private val gpsAtivo: () -> Boolean = { false },
    /** Velocidade GPS atual em km/h, já filtrada (null = sem fix confiável: a chave fica ausente). */
    private val velocidadeGps: () -> Double? = { null },
    /**
     * v2.2.4: modelo aprendido dos CSVs reais **deste** `veiculoId` (null = sem logs reais), usado só
     * no Modo Simulado. Roda na [io]; nunca recebe nem devolve dados de outro veículo.
     */
    private val modeloAprendidoDe: suspend (Long) -> ModeloAprendido? = { null },
    /** v2.2.4: CSV de uma sessão **real** (nunca simulada) fechado para o `veiculoId`; alimenta o aprendizado. */
    private val aoSalvarLogReal: suspend (veiculoId: Long, csv: ArquivoLogSalvo) -> Unit = { _, _ -> },
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

    private val _fonteSimulacao = MutableStateFlow<FonteSimulacao?>(null)

    /** Modo Simulado conectado: logs reais do veículo ou simulador sintético; null = fora do Modo Simulado/desconectado. */
    val fonteSimulacao: StateFlow<FonteSimulacao?> = _fonteSimulacao.asStateFlow()

    private val _sessao = MutableStateFlow(EstadoSessao())
    val estadoSessao: StateFlow<EstadoSessao> = _sessao.asStateFlow()

    private val _eventos = MutableSharedFlow<EventoSessao>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val eventos: SharedFlow<EventoSessao> = _eventos.asSharedFlow()

    /** v2.3.1: amostras recentes e mínimo/máximo da sessão (Todos os Sensores e Gráficos). Zera a cada sessão. */
    val historico = HistoricoSessao()

    /** Marca usada nos recursos da montadora na sessão em curso (ou na última); null no OBD2 Genérico. */
    val marcaMontadora: String? get() = marcaMontadoraAtual

    private var sessaoJob: Job? = null
    @Volatile private var clienteDaSessao: ObdClient? = null
    @Volatile private var gravador: GravadorLogsSessao? = null
    @Volatile private var motivoPedido: String? = null
    @Volatile private var marcaAtual: String? = null

    /** v2.3.0: marca para os recursos da montadora (null no OBD2 Genérico); ver [VeiculoSessao.marcaMontadora]. */
    @Volatile private var marcaMontadoraAtual: String? = null

    /** Veículo e modo da sessão em curso, para o aprendizado de logs reais no encerramento. */
    @Volatile private var veiculoIdSessao: Long = 0
    @Volatile private var sessaoSimulada: Boolean = false

    /** Combustível do veículo da sessão (Auto Flex por padrão): decide a estequiometria da A/F real. */
    @Volatile private var combustivelAtual: TipoCombustivel? = null

    /**
     * Marcha estimada da A750F (SW4) e, v2.2.6, a reduzida (4L) detectada pela razão rpm/velocidade
     * com o `fatorReduzida` do câmbio em uso (relido a cada amostra).
     */
    private val estimadorCambio = EstimadorCambioA750f { _cambioAtivo.value }

    /** Marcha 1ª–6ª estimada do câmbio GM 6T35/6T40 (Cruze) pela razão rpm/velocidade. */
    private val estimadorCambioGm = EstimadorCambioGm6T()

    /** v2.2: marcha pelas razões do perfil de câmbio do catálogo (ex.: Aisin 6 marchas dos VW TSI). */
    @Volatile private var estimadorPerfil: EstimadorMarchaPerfil? = null

    private val _vinDecodificado = MutableStateFlow<VinDecodificado?>(null)
    /** Montadora (WMI) e ano-modelo do VIN desta conexão; null = não lido ou formato inválido. */
    val vinDecodificado: StateFlow<VinDecodificado?> = _vinDecodificado.asStateFlow()

    private val _perfilAutomatico = MutableStateFlow<PerfilIdentificado?>(null)

    /**
     * Perfil que a sessão aplicou sozinho (VDS do VIN ou sondagem da ECU) e o usuário ainda não
     * respondeu: a aba OBD mostra o aviso com [manterPerfilAutomatico] / [ignorarPerfilAutomatico].
     */
    val perfilAutomatico: StateFlow<PerfilIdentificado?> = _perfilAutomatico.asStateFlow()

    private val _impressaoEcu = MutableStateFlow<ImpressaoEcu?>(null)

    /** Impressão digital da ECU desta conexão (`0151`, `0904`, TCM); null = ainda não sondada. */
    val impressaoEcu: StateFlow<ImpressaoEcu?> = _impressaoEcu.asStateFlow()

    private val _cambioAtivo = MutableStateFlow<PerfilCambio?>(null)

    /** Câmbio do perfil exato em uso (o automático ou o do cadastro); null = genérico/sem sessão (v2.2.3, rótulo da marcha). */
    val cambioAtivo: StateFlow<PerfilCambio?> = _cambioAtivo.asStateFlow()

    private val travaPerfil = Any()
    @Volatile private var veiculoAtual: VeiculoSessao? = null

    /** Perfil aplicado automaticamente em uso (null = vale o do cadastro). */
    @Volatile private var perfilAplicado: PerfilIdentificado? = null

    /** A identificação já terminou (o refino tardio por MAP só vale depois dela). */
    @Volatile private var identificacaoConcluida = false

    /** O usuário ignorou o perfil automático: a sessão não tenta de novo. */
    @Volatile private var perfilIgnorado = false

    /** Maior MAP lido na sessão (kPa), para distinguir turbo de aspirado. */
    @Volatile private var mapPicoKpa: Double? = null

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

    /**
     * Colunas de log que não vêm da ECU: a velocidade GPS (quando ativa) e a relação A/F real
     * calculada (motores de ciclo Otto com λ suportado: `0124`/`0134` ou `0144`) e a marcha estimada
     * da A750F (alavanca do `7E1 · 21D9` lida e o `2130` fora do polling).
     */
    fun colunasDoAparelho(suportados: Set<String> = emptySet()): List<DefinicaoPid> = buildList {
        if (gpsAtivo()) add(DefinicaoPid.VELOCIDADE_GPS)
        val temLambda = (Pids.COMANDOS_LAMBDA_MEDIDO + Pids.LAMBDA_COMANDADO).any { it in suportados }
        if (temLambda && combustivelAtual != TipoCombustivel.DIESEL) add(DefinicaoPid.RELACAO_AF_REAL)
        if (CambioA750f.CHAVE_ALAVANCA in suportados && CambioA750f.CHAVE_MARCHA !in suportados) add(DefinicaoPid.MARCHA_ESTIMADA_A750F)
        else if ((PresetVeiculo.marcaGm(marcaMontadoraAtual) || estimadorPerfil != null) && CambioA750f.CHAVE_MARCHA !in suportados) {
            add(DefinicaoPid.MARCHA_ESTIMADA_A750F)
        }
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
        veiculoIdSessao = veiculoId
        historico.limpar()
        sessaoSimulada = _modo.value == ModoObd.SIMULADO
        var conectou = false
        var motivoFim = "Sessão encerrada"
        var g: GravadorLogsSessao? = null
        try {
            val veiculo = if (veiculoId > 0) runCatching { buscarVeiculo(veiculoId) }.getOrNull() else null
            marcaAtual = veiculo?.marca
            marcaMontadoraAtual = veiculo?.marcaMontadora
            veiculoAtual = veiculo
            _cambioAtivo.value = veiculo?.cambio
            estimadorCambio.reiniciar()
            combustivelAtual = veiculo?.combustivel
            // A750F (alavanca no 7E1) e GM têm estimador próprio; os demais automáticos usam as razões do perfil.
            estimadorPerfil = veiculo?.cambio?.takeIf { it.estimaMarcha && it.mapaAlavanca == null }?.let(::EstimadorMarchaPerfil)
            if (sessaoSimulada && cli is ObdClientFake) {
                // Só o modelo deste veículo; sem veículo ou sem logs reais, segue o simulador sintético.
                val modelo = if (veiculoId > 0) runCatching { withContext(io) { modeloAprendidoDe(veiculoId) } }.getOrNull() else null
                cli.definirModeloAprendido(modelo?.takeIf { it.veiculoId == veiculoId })
            }
            // v2.2.5: sessão simulada nunca grava .csv/.txt (nem entra no índice de aprendizado).
            if (config().gravarLogsAutomaticos && !sessaoSimulada) {
                val logBruto = config().logCompletoTrafego
                g = withContext(io) { criarGravador(dispositivo, veiculo, origem, gatilho, inicioMs, logBruto) }
                gravador = g
                cli.definirLogCompleto(logBruto)
                cli.observarRegistro(g::registrarEntrada)
            }
            _sessao.update { it.copy(veiculo = veiculo?.nomeCurto, gravandoLogs = g != null, veiculoId = if (veiculo != null) veiculoId else 0) }
            g?.evento("Iniciada (${descreverOrigem(origem, gatilho)}) com ${dispositivo.nome} (${dispositivo.endereco})", relogio())

            if (origem == OrigemSessao.AUTOMATICA && tempos.esperaEcuMs > 0) {
                g?.evento("Aguardando ${tempos.esperaEcuMs / 1000} s para a ignição/ECU terminar o boot", relogio())
                delay(tempos.esperaEcuMs)
            }
            var erro: String? = null
            for (tentativa in 1..tentativas) {
                _sessao.update { it.copy(fase = FaseSessao.Conectando(tentativa, tentativas)) }
                if (tentativas > 1) g?.evento("Tentativa de conexão $tentativa/$tentativas", relogio())
                cli.definirServicoPreferido(veiculo?.servicoPreferido)
                val resultado = cli.conectar(dispositivo, veiculo?.marcaMontadora)
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
            if (sessaoSimulada && cli is ObdClientFake) {
                val fonte = cli.fonteSimulacao.value
                _fonteSimulacao.value = fonte
                g?.evento(
                    when (fonte) {
                        is FonteSimulacao.LogsReais -> "Modo Simulado baseado em ${fonte.quantidade} log(s) real(is) deste veículo"
                        FonteSimulacao.Sintetica -> "Modo Simulado sintético (sem logs reais deste veículo)"
                    },
                    relogio(),
                )
            }
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
        if (g != null && config().logCompletoTrafego) {
            // Log bruto: cada mudança de estado do Bluetooth/ELM327 fica registrada com o instante.
            launch { cli.estado.collect { g.evento("Estado da conexão Bluetooth/ELM327: ${descreverEstado(it)}", relogio()) } }
        }
        launch { identificarVeiculo(cli, veiculo, g) }
        launch {
            _perfil.value = null
            // v2.3.0: no OBD2 Genérico/padrão a marca não vai (nenhum PID proprietário da montadora).
            val perfil = withTimeoutOrNull(tempos.prazoPerfilMs) { cli.ativarPerfilFabricante(veiculo?.marcaMontadora) }
                ?: ResultadoPerfil.Indisponivel(veiculo?.marca ?: "montadora", "a ECU não respondeu no prazo")
            _perfil.value = perfil
            if (perfil is ResultadoPerfil.Ativo) {
                _eventos.tryEmit(EventoSessao.Mensagem("Perfil ${perfil.fabricante} ativado: ${perfil.pids.size} PIDs da montadora"))
            }
            if (g != null) {
                val suportados = cli.pidsSuportados.value
                val colunas = definicoesSuportadas(suportados, cli.infoConexao.value, perfil) + colunasDoAparelho(suportados)
                withContext(io) { g.iniciarCsv(colunas) }
                g.evento("CSV de telemetria aberto com ${colunas.size} sensores", relogio())
                lerDtcsParaRelatorio(cli, g)
            }
        }
        val fim = cli.estado.first { it !is EstadoConexaoObd.Conectado }
        coroutineContext.cancelChildren()
        (fim as? EstadoConexaoObd.Erro)?.mensagem ?: "Desconectado"
    }

    private fun receberAmostra(daEcu: DadosObd, g: GravadorLogsSessao?) {
        acompanharMap(daEcu[Pids.PRESSAO_COLETOR], g)
        val base = comCalculados(comVelocidadeGps(daEcu), combustivelAtual)
        val porPerfil = estimadorPerfil
        val dados = when {
            PresetVeiculo.marcaGm(marcaMontadoraAtual) -> estimadorCambioGm.aplicar(base)
            porPerfil != null -> porPerfil.aplicar(base)
            else -> estimadorCambio.aplicar(base)
        }
        historico.registrar(dados.valores, relogio())
        _dados.value = dados
        _amostras.tryEmit(dados)
        telemetria.publicar(dados, conectado = true)
        g?.registrar(dados, relogio())
    }

    // ------------------------------------------------------------------ identificação automática (v2.2.2)

    /** O cadastro está num perfil genérico (ou não há veículo vinculado): vale tentar identificar o carro. */
    private fun cadastroGenerico(veiculo: VeiculoSessao?): Boolean = veiculo?.resolucao?.generico != false

    /**
     * Identificação na conexão, em paralelo à telemetria e sem nunca derrubar a sessão: lê o VIN,
     * tenta o match exato por WMI+VDS (`padroesVin`), faz a sondagem leve da ECU (`0151`, `0904`,
     * TCM) e, sem match, refina o perfil genérico pela impressão. `NO DATA`/`7F`/timeout só deixam o
     * campo vazio; perfil exato do cadastro nunca é trocado ([OrigemPerfil.MANUAL]).
     */
    private suspend fun identificarVeiculo(cli: ObdClient, veiculo: VeiculoSessao?, g: GravadorLogsSessao?) {
        _vinLido.value = false
        val vin = cli.lerVin().getOrNull()
        _vin.value = vin
        val info = DecodificadorVin.decodificar(vin)
        _vinDecodificado.value = info
        val generico = cadastroGenerico(veiculo)
        val catalogo = CarregadorPerfis.catalogo
        val porVds = if (generico) IdentificadorPerfil.porVds(catalogo, info) else null
        if (porVds != null) aplicarPerfilAutomatico(porVds, g)
        registrarVin(vin, veiculo, g, sugerir = porVds == null)
        _vinLido.value = true

        val impressao = withTimeoutOrNull(tempos.prazoIdentificacaoMs) {
            runCatching { cli.sondarEcu(sondarTcm = generico && porVds == null) }.getOrNull()
        } ?: ImpressaoEcu.VAZIA
        _impressaoEcu.value = impressao
        g?.registrarImpressao(impressao)
        g?.evento(
            "Impressão da ECU: combustível ${impressao.descricaoCombustivel ?: "sem resposta"}, " +
                "CALID ${impressao.calid ?: "sem resposta"}, TCM " +
                (impressao.tcmDetectado?.let { if (it) "detectado (${impressao.tcmCabecalho})" else "não detectado" } ?: "não sondado"),
            relogio(),
        )
        if (porVds == null && generico) {
            IdentificadorPerfil.porSondagem(catalogo, impressao, cli.pidsSuportados.value, mapPicoKpa)
                ?.let { aplicarPerfilAutomatico(it, g) }
        }
        identificacaoConcluida = true
        acompanharMap(null, g)
    }

    /**
     * Aplica [novo] à sessão: faixas do relatório, estimador de marcha e combustível do cálculo de A/F.
     * O aviso da aba OBD só aparece quando o perfil muda de fato em relação ao que já valia.
     */
    private fun aplicarPerfilAutomatico(novo: PerfilIdentificado, g: GravadorLogsSessao?): Unit = synchronized(travaPerfil) {
        if (perfilIgnorado) return
        val veiculo = veiculoAtual
        val anterior = perfilAplicado?.preset ?: veiculo?.resolucao?.preset
        perfilAplicado = novo
        val preset = novo.preset
        _cambioAtivo.value = preset.perfilCambio.takeIf { !preset.generico }
        val limites = veiculo?.limites
        val faixas = if (preset.generico && limites != null) FaixasVeiculo.comLimitesDoCadastro(preset, limites) else preset.faixas
        estimadorPerfil = preset.perfilCambio.takeIf { !preset.generico && it.estimaMarcha && it.mapaAlavanca == null }?.let(::EstimadorMarchaPerfil)
        combustivelAtual = combustivelPara(preset.combustivel, veiculo?.combustivel)
        g?.registrarPerfil(novo.origem, "${preset.titulo} (${novo.motivo})", faixas)
        g?.evento("Perfil automático (${novo.origem.rotulo}): ${preset.titulo} - ${novo.motivo}", relogio())
        if (anterior?.id != preset.id) _perfilAutomatico.value = novo
    }

    /** Combustível para o cálculo de A/F: o do cadastro, salvo se faltar ou divergir de diesel x ciclo Otto do perfil. */
    private fun combustivelPara(doPerfil: TipoCombustivel, cadastro: TipoCombustivel?): TipoCombustivel =
        if (cadastro == null || (cadastro == TipoCombustivel.DIESEL) != (doPerfil == TipoCombustivel.DIESEL)) doPerfil else cadastro

    /** O usuário aceitou o aviso: o perfil automático segue valendo até o fim da sessão. */
    fun manterPerfilAutomatico() {
        _perfilAutomatico.value?.let { registrarEvento("Perfil automático mantido pelo usuário: ${it.preset.titulo}") }
        _perfilAutomatico.value = null
    }

    /** O usuário recusou o aviso: volta ao perfil do cadastro e a sessão não tenta identificar de novo. */
    fun ignorarPerfilAutomatico(): Unit = synchronized(travaPerfil) {
        val aplicado = perfilAplicado ?: return
        perfilIgnorado = true
        perfilAplicado = null
        _perfilAutomatico.value = null
        val veiculo = veiculoAtual
        _cambioAtivo.value = veiculo?.cambio
        estimadorPerfil = veiculo?.cambio?.takeIf { it.estimaMarcha && it.mapaAlavanca == null }?.let(::EstimadorMarchaPerfil)
        combustivelAtual = veiculo?.combustivel
        gravador?.registrarPerfil(OrigemPerfil.MANUAL, null, veiculo?.faixas)
        registrarEvento("Perfil automático ignorado pelo usuário (${aplicado.preset.titulo}): vale o do cadastro")
    }

    /**
     * Pico de MAP da sessão. Passada a identificação, um genérico aspirado cujo MAP ultrapassa a
     * atmosférica (> 115 kPa) é promovido a Flex Turbo (só vale para cadastro genérico).
     */
    private fun acompanharMap(map: Double?, g: GravadorLogsSessao?) {
        if (map != null && map.isFinite()) mapPicoKpa = maxOf(mapPicoKpa ?: map, map)
        if (!identificacaoConcluida || perfilIgnorado || !cadastroGenerico(veiculoAtual)) return
        val pico = mapPicoKpa ?: return
        if (pico <= IdentificadorPerfil.MAP_TURBO_KPA) return
        val atual = perfilAplicado?.preset ?: veiculoAtual?.resolucao?.preset
        IdentificadorPerfil.refinarPorMap(CarregadorPerfis.catalogo, atual, pico)?.let { aplicarPerfilAutomatico(it, g) }
    }

    /**
     * VIN lido (`0902`): montadora pelo WMI e ano-modelo vão para o cabeçalho do relatório. Se o
     * veículo cadastrado ainda estiver num perfil genérico e o catálogo tiver perfis da montadora
     * do VIN para aquele ano, sugere (discretamente) aplicar um deles em Presets.
     */
    private fun registrarVin(vin: String?, veiculo: VeiculoSessao?, g: GravadorLogsSessao?, sugerir: Boolean = true) {
        val info = DecodificadorVin.decodificar(vin)
        val sugestao = if (sugerir) sugestaoPorVin(info, veiculo) else null
        g?.registrarVin(vin, info, sugestao)
        if (info != null) g?.evento("VIN $vin: ${info.descricao}", relogio())
        if (sugestao != null) {
            g?.evento("Sugestão: $sugestao", relogio())
            _eventos.tryEmit(EventoSessao.Mensagem(sugestao))
        }
    }

    /** Texto da sugestão de perfil pelo VIN (só para cadastro em perfil genérico); null = nada a sugerir. */
    private fun sugestaoPorVin(info: VinDecodificado?, veiculo: VeiculoSessao?): String? {
        if (info?.montadora == null || veiculo == null) return null
        if (veiculo.resolucao?.generico != true) return null
        val perfis = CarregadorPerfis.catalogo.sugestoesPara(info.montadora, info.anoModelo ?: veiculo.ano)
        if (perfis.isEmpty()) return null
        val nomes = perfis.take(3).joinToString(" / ") { it.titulo }
        val ano = info.anoModelo?.let { " $it" }.orEmpty()
        return "VIN indica ${info.montadora}$ano: perfil disponível em Presets ($nomes)"
    }

    /** Mescla a velocidade GPS ([Pids.VELOCIDADE_GPS]) ao instantâneo da ECU; sem fix (ou desativada), ele segue igual. */
    private fun comVelocidadeGps(dados: DadosObd): DadosObd {
        if (!gpsAtivo()) return dados
        val kmh = runCatching { velocidadeGps() }.getOrNull()?.takeIf { it.isFinite() } ?: return dados
        return DadosObd(dados.valores + (Pids.VELOCIDADE_GPS to kmh))
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
        _vinDecodificado.value = null
        _vinLido.value = false
        estimadorPerfil = null
        _perfilAutomatico.value = null
        _impressaoEcu.value = null
        perfilAplicado = null
        veiculoAtual = null
        _cambioAtivo.value = null
        identificacaoConcluida = false
        perfilIgnorado = false
        mapPicoKpa = null
        telemetria.publicar(null, conectado = false)
        if (g != null) {
            g.evento("Encerrada: $motivo", relogio())
            cli.observarRegistro(null)
            cli.definirLogCompleto(false)
            val logs = withContext(io) { g.finalizar(relogio(), conectou, motivo) }
            _eventos.tryEmit(EventoSessao.LogsSalvos(logs))
            // Só sessão real (nunca a simulada) alimenta o aprendizado do veículo.
            val csv = logs.csv
            if (conectou && !sessaoSimulada && veiculoIdSessao > 0 && csv != null) {
                runCatching { withContext(io) { aoSalvarLogReal(veiculoIdSessao, csv) } }
            }
        }
        _fonteSimulacao.value = null
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
        logBruto: Boolean = false,
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
            faixas = veiculo?.faixas,
        )
        return GravadorLogsSessao(destinoLogs, cabecalho, pastaTemporaria, zona, logBruto = logBruto)
    }

    private fun descreverEstado(e: EstadoConexaoObd): String = when (e) {
        is EstadoConexaoObd.Erro -> "Erro (${e.mensagem})"
        else -> e.toString()
    }

    private fun descreverOrigem(origem: OrigemSessao, gatilho: String?): String = when (origem) {
        OrigemSessao.MANUAL -> "Manual (botão Conectar)"
        OrigemSessao.AUTOMATICA -> "Automático (Bluetooth do carro: ${gatilho ?: "?"})"
    }

    private val EstadoConexaoObd.ocupado: Boolean
        get() = this is EstadoConexaoObd.Conectado || this is EstadoConexaoObd.Conectando

    companion object {
        const val MOTIVO_USUARIO = "Desconectado pelo usuário"

        /**
         * Mescla os valores calculados pelo app ao instantâneo: a relação A/F real
         * ([Pids.RELACAO_AF_REAL] = λ × estequiometria do combustível em uso, flex pelo `0152`).
         * Sem λ (ou no diesel), o instantâneo segue igual.
         */
        fun comCalculados(dados: DadosObd, combustivel: TipoCombustivel?): DadosObd {
            val af = EstequiometriaFlex.relacaoAfReal(dados, combustivel) ?: return dados
            return DadosObd(dados.valores + (Pids.RELACAO_AF_REAL to af))
        }

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
