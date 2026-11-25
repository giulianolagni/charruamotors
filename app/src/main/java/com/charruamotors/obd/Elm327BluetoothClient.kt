package com.charruamotors.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.log.RegistroComunicacao
import com.charruamotors.obd.pid.CatalogoCascata
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.ModoServico
import com.charruamotors.obd.pid.PerfilFabricante
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.pid.ProtocoloFisico
import com.charruamotors.obd.pid.RequisicaoEstendida
import com.charruamotors.obd.transporte.ClassicBluetoothTransport
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Cliente OBD2 real para adaptadores ELM327. Implementa o protocolo (comandos AT, PIDs, DTCs);
 * o meio físico fica num [ObdTransport] — hoje [ClassicBluetoothTransport] (RFCOMM), amanhã BLE.
 *
 * **Cascata de conexão em 3 camadas**, dirigida pelo [catalogoCascata] (`cascata_conexao.json`):
 * 1. **OBD2 padrão** — `ATZ` (com uma segunda tentativa), `ATE0 ATL0 ATS0 ATH0 ATSP0`, bitmasks
 *    `0100`/`0120`/`0140` (a primeira com prazo longo e uma nova busca, para a inicialização de
 *    5 baud da linha K) e só depois os PIDs que a ECU suporta. `ATDP`/`ATRV` vão para o registro
 *    logo depois do `0100`, qualquer que seja a resposta dele.
 * 2. **Fallback de protocolo físico** — se o `0100` do `ATSP0` não achar a ECU (`UNABLE TO
 *    CONNECT`, `NO DATA`, sem prompt), força cada protocolo do catálogo (`ATSP6` CAN 11, `ATSP7`
 *    CAN 29, `ATSP5` KWP Fast Init, `ATSP3` ISO 9141) com um novo `0100`: clones v2.1 falham no
 *    automático em Honda, Fiat/PSA/Renault pré-CAN e asiáticos.
 * 3. **Fallback de serviço** — ECU que recusa o modo 01 (`7F 01 xx`) ou só dá `NO DATA`: os
 *    modos de serviço do catálogo, em ordem (os preferidos pela marca primeiro): `ATSH 7E0` (uma
 *    vez só) e sondagem dos PIDs espelhados do SAE — modo 21 (Toyota M-OBD, Mitsubishi, Suzuki,
 *    KWP: `210C`, `2105`...) e depois modo 22 UDS (VW/Audi, Fiat/Jeep, Ford, GM: `22F40C`...).
 *    Os espelhos publicam o valor na chave canônica do sensor (`210C` → `010C`), então
 *    mostradores, gráfico, computador de bordo, 0–100, log CSV e filas funcionam igual. Nada de
 *    `0120`/`0140`/`0902`/`7E1` nesse modo: a conexão fica rápida e o v2.1 não engasga.
 *
 * Baixa latência (cada milissegundo por comando vira fluidez nos ponteiros):
 * - **Sufixo de resposta única** (`010C1`, `210C1`, `22F40C1`): o ELM327 devolve a resposta assim
 *   que a primeira ECU responde, sem esperar o timeout de outras. No modo 01 é testado com o
 *   `01001`; nos modos 21/22, no primeiro PID espelhado que respondeu. Recusado (`?`, erro, sem
 *   prompt) = desligado na sessão. Uma requisição de montadora que volte incompleta com o sufixo
 *   (resposta multi-frame cortada no 1º quadro) é conferida sem ele uma vez e, se for o caso,
 *   segue sem sufixo só ela.
 * - **Timing agressivo**: `ATAT2` logo depois do `ATSP0` e, com a ECU achada, uma espera curta no
 *   lugar dos 200 ms padrão: `ATST0C` (~49 ms) só no modo 21 (SW4) e `ATST19` (~100 ms) no resto —
 *   ECUs GM no CAN devolvem `NO DATA` prematuro com o `0C`. Validada relendo o primeiro PID que
 *   respondeu — se a ECU emudecer com ela, volta ao `ATST32`. Comandos de prazo longo (DTCs, VIN,
 *   limpeza) trocam para o `ATST32` antes e o polling volta ao `ATST0C` depois (como o `ATSH`, só
 *   quando muda). Clone que responde `?` a qualquer um deles segue no padrão, em silêncio.
 * - **Sem pausa fixa entre comandos**: o prompt `>` já confirma que o adaptador terminou. Só
 *   depois de um timeout ou `STOPPED`/`BUFFER FULL` a sessão passa a dar [intervaloSeguroMs] de
 *   respiro, para clones v2.1 de buffer pequeno.
 *
 * Demais garantias:
 * - **CAN pelo `ATDPN`** (v2.3.1): `6`–`9`/`A6`–`A9` é ISO 15765-4 mesmo com o `ATDP` devolvendo
 *   `AUTO, `; e depois de uma leitura multi-frame (`0902`/`0904`/`0906`) o protocolo é fixado
 *   (`ATSP6`...) e voltam o `ATSH` funcional e o `ATCRA` padrão (clones v2.1 travam o filtro).
 * - Um [Mutex] serializa os comandos (o ELM327 atende um comando por vez), permitindo
 *   que o polling de PIDs e a leitura de DTCs coexistam sem misturar respostas.
 * - Estabilidade do v2.1: `ATSH` só quando o cabeçalho realmente muda (o ciclo de polling é
 *   ordenado por cabeçalho).
 * - Telemetria em duas filas ([EscalonadorPolling]): RPM, MAP/turbo e velocidade em todo ciclo,
 *   1 PID lento a cada 3 ciclos.
 * - Perfis de montadora (`pids_estendidos.json`) com cabeçalho CAN físico, ativados só em CAN de
 *   11 bits e se a ECU responder; no modo 21/22, só os do mesmo módulo e serviço da camada ativa.
 * - Falhas: `03`/`07`/`0A`/`04` no padrão; no modo 21 `13` (cai para `18 00 FF 00`) e `14`
 *   (cai para `14 FF 00`); no modo 22 `19 02 FF` e `14 FF FF FF`.
 * - Sujeira de protocolo: `STOPPED` repete o comando uma vez; `LV RESET` (queda de tensão na
 *   partida) reaplica a configuração; erros de barramento num ciclo não derrubam a sessão.
 * - Queda de conexão: `IOException` (link Bluetooth perdido), [MAX_TIMEOUTS_SEGUIDOS] comandos sem
 *   prompt ou [MAX_CICLOS_SEM_ECU] ciclos sem nenhuma resposta da ECU (ignição desligada) levam o
 *   [estado] a `Erro` e encerram o fluxo de leituras, sem exceções chegando à UI.
 * - [desconectar] pode ser chamado a qualquer momento, inclusive com um comando em andamento ou
 *   durante a conexão: fecha o socket na hora e a leitura interrompida termina sem virar `Erro`.
 * - Concorrência (v2.2.1): `conectar` reserva o estado com compare-and-set (nunca dois sockets),
 *   respostas de um canal que já não é o da sessão são descartadas em [trocar] (nada da sessão
 *   velha vaza para a reconexão) e `RuntimeException` da pilha Bluetooth vira link perdido. Um
 *   ciclo de polling que falhe por qualquer motivo encerra a sessão em `Erro`, nunca a deixa zumbi.
 *
 * As permissões `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+) devem ser pedidas pela UI
 * antes de conectar; se faltarem, a `SecurityException` é convertida em estado `Erro`.
 *
 * @param adaptador `BluetoothAdapter` do aparelho (nulo se não houver Bluetooth).
 * @param catalogo definições (fórmula, faixa, chave semântica) dos PIDs que o app sabe interpretar.
 * @param criarTransporte fábrica do canal para o dispositivo escolhido.
 * @param catalogoEstendido perfis de PIDs de montadora (`pids_estendidos.json`).
 * @param registro últimas trocas com o adaptador, para diagnóstico em campo.
 * @param catalogoCascata camadas 2 e 3 da cascata (`cascata_conexao.json`); vazio = só OBD2 padrão.
 * @param intervaloEntreComandosMs respiro entre o fim de uma resposta e o próximo comando (0 = nenhum).
 * @param intervaloSeguroMs respiro adotado pelo resto da sessão depois de um timeout/`STOPPED`.
 */
@SuppressLint("MissingPermission") // SecurityException é tratada e convertida em EstadoConexaoObd.Erro
class Elm327BluetoothClient(
    private val adaptador: BluetoothAdapter?,
    private val catalogo: CatalogoPids,
    private val criarTransporte: (DispositivoObd) -> ObdTransport = { ClassicBluetoothTransport(adaptador, it.endereco) },
    private val timeoutComandoMs: Long = ObdTransport.TIMEOUT_PADRAO_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalogoEstendido: CatalogoEstendido = CatalogoEstendido.VAZIO,
    private val registro: RegistroComunicacao = RegistroComunicacao(),
    private val catalogoCascata: CatalogoCascata = CatalogoCascata.VAZIO,
    private val intervaloEntreComandosMs: Long = INTERVALO_ENTRE_COMANDOS_MS,
    private val intervaloSeguroMs: Long = INTERVALO_SEGURO_MS,
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val _pidsSuportados = MutableStateFlow<Set<String>>(emptySet())
    override val pidsSuportados: StateFlow<Set<String>> = _pidsSuportados.asStateFlow()

    private val _infoConexao = MutableStateFlow(InfoConexao())
    override val infoConexao: StateFlow<InfoConexao> = _infoConexao.asStateFlow()

    private val mutexComandos = Mutex()

    /**
     * Um ciclo de polling inteiro por vez: [iniciarSessaoManual] espera o ciclo em andamento
     * terminar, para nenhum `ATSH` do polling cair no meio dos comandos manuais.
     */
    private val mutexCiclo = Mutex()

    private val _sessaoManual = MutableStateFlow(false)

    /** Sessões manuais já encerradas (v2.2.6): o polling invalida o `ATRV` em cache quando o número muda. */
    private val sessoesManuaisEncerradas = java.util.concurrent.atomic.AtomicInteger(0)
    override val sessaoManualAtiva: StateFlow<Boolean> = _sessaoManual.asStateFlow()

    /**
     * v2.3.0: identificação do adaptador nesta conexão (`ELM327 v2.1`), mantida depois de uma falha
     * com a ECU (o card mostra "adaptador respondeu, ECU sem resposta"); zera no [desconectar] e no
     * início de cada [conectar].
     */
    @Volatile private var adaptadorDaConexao: String? = null

    /** Servico que a camada 3 deve tentar primeiro (`21`); null = a ordem da marca. Ver [definirServicoPreferido]. */
    @Volatile private var servicoPreferido: String? = null

    override fun definirServicoPreferido(servico: String?) {
        servicoPreferido = servico?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    }

    /** Cabeçalho do adaptador quando a sessão manual começou (restaurado no fim). */
    @Volatile private var cabecalhoEntradaManual: String? = null

    /** Um comando manual (`ATH1`, `ATZ`...) ou um `LV RESET` mudou a configuração do adaptador. */
    @Volatile private var configuracaoAlteradaManual = false

    /** Camada 3 ativa (modo 21 / 22); null = OBD2 padrão (camadas 1/2). */
    @Volatile private var modoServico: ModoServico? = null

    /**
     * Cabeçalho dos pedidos ao [modoServico]: o físico do catálogo (`7E0`) em CAN de 11 bits;
     * null (o padrão do adaptador) nos protocolos da linha K e no CAN de 29 bits.
     */
    @Volatile private var cabecalhoServico: String? = null

    /** Cabeçalho dos pedidos de diagnóstico (DTC, freeze frame): o do modo de serviço, ou o funcional no padrão. */
    private val cabecalhoDiagnostico: String? get() = if (modoServico != null) cabecalhoServico else null

    /**
     * O câmbio `7E1` respondeu à sondagem (`21D9`) no modo 21: o polling o lê em cadência
     * leve, alternando `ATSH 7E1`/`ATCRA 7E9` e restaurando o cabeçalho do motor logo em seguida.
     */
    @Volatile private var cambioAtivo = false

    /** Canal da sessão atual; null = desconectado. Comparado por identidade para descartar falhas de sessões encerradas. */
    @Volatile private var transporte: ObdTransport? = null

    /** Cabeçalho CAN configurado no adaptador (`ATSH`); null = o funcional padrão (7DF). */
    @Volatile private var cabecalhoAtivo: String? = null

    /**
     * PIDs lidos por requisição de montadora nesta conexão, por chave: perfis (`TOYOTA_INJ1`...)
     * e, nos modos 21/22, os espelhos na chave canônica (`010C` lido por `210C`).
     */
    @Volatile private var definicoesEstendidas: Map<String, DefinicaoPid> = emptyMap()

    /** Comandos seguidos sem prompt; zera a cada resposta. */
    @Volatile private var timeoutsSeguidos = 0

    /**
     * Filtro de recepção (`ATCRA`) ativo no adaptador: null = o padrão (respostas `7E8`–`7EF`);
     * `7B8` com o ABS/VSC (`7B0`), `7C8` com o painel/4WD (`7C0`).
     */
    @Volatile private var filtroRecepcaoAtivo: String? = null

    /** O adaptador recusou o `ATCRA` nesta conexão: não insiste a cada troca de cabeçalho. */
    @Volatile private var semFiltroRecepcao = false

    /** Fim da última troca com o adaptador (`System.nanoTime`); 0 = nenhuma nesta sessão. */
    @Volatile private var fimUltimoComandoNs = 0L

    /**
     * O adaptador aceita o sufixo de resposta única (`010C1`, `210C1`)? Detectado na
     * inicialização; vale para os PIDs do modo 01 e para as requisições de montadora.
     */
    @Volatile var usaSufixoRespostaUnica: Boolean = false
        private set

    /** Requisições de montadora que voltaram incompletas com o sufixo e são lidas sem ele. */
    @Volatile private var requisicoesSemSufixo: Set<RequisicaoEstendida> = emptySet()

    /** Requisições já conferidas sem o sufixo (a conferência acontece uma vez por requisição). */
    @Volatile private var requisicoesConferidas: Set<RequisicaoEstendida> = emptySet()

    /** O adaptador aceitou o `ATAT2` (reaplicado se ele reiniciar sozinho). */
    @Volatile var usaTimingAdaptativoAgressivo: Boolean = false
        private set

    /** Sessão com a espera curta validada: comandos à ECU de prazo normal saem com [timeoutCurto]. */
    @Volatile var usaTimeoutCurto: Boolean = false
        private set

    /** Valor do `ATST` curto desta sessão: `19` (modos 01/22) ou `0C` (modo 21). */
    @Volatile private var timeoutCurto: String = TIMEOUT_ADAPTADOR_CAN

    /** CAN confirmado pelo `ATDPN` no OBD2 padrão (v2.3.1); null fora dele ou fora do CAN. */
    @Volatile private var protocoloCanAtivo: ProtocoloAtivo? = null

    /** `ATSH` do cabeçalho funcional do protocolo em uso. */
    private val cabecalhoFuncional: String
        get() = protocoloCanAtivo?.cabecalhoFuncional ?: RequisicaoEstendida.CABECALHO_FUNCIONAL

    /** Valor do `ATST` configurado no adaptador; null = o padrão (`32`, 200 ms). */
    @Volatile private var timeoutAdaptadorAtivo: String? = null

    /**
     * v2.2.9: Multi-PID em lote (`010C0D0B`) validado no handshake — só no OBD2 padrão sobre CAN
     * (ISO 15765-4), nunca nos modos 21/22. Os PIDs rápidos do ciclo saem num único comando; ver
     * [lerChaves] e [registrarFalhaLote].
     */
    @Volatile var usaLoteMultiPid: Boolean = false
        private set

    /** Falhas seguidas de cada comando de lote no polling (ver [registrarFalhaLote]). */
    private val falhasLote = mutableMapOf<String, Int>()

    /**
     * No OBD2 padrão, o câmbio `7E1` também sai com `ATCRA 7E9` explícito (v2.2.9: perfil GM/Cruze e a
     * leitura de falhas do TCM). Nos modos 21/22 o filtro do câmbio já é sempre explícito.
     */
    @Volatile private var filtroCambioPadrao = false

    /** Respiro atual entre comandos: [intervaloEntreComandosMs], ou [intervaloSeguroMs] depois de um erro. */
    @Volatile private var respiroMs: Long = intervaloEntreComandosMs

    override fun registroComunicacao(): List<EntradaRegistro> = registro.entradas()

    override fun definirLogCompleto(ativo: Boolean) {
        registro.logCompleto = ativo
    }

    override fun observarRegistro(ouvinte: ((EntradaRegistro) -> Unit)?) {
        registro.ouvinte = ouvinte
    }

    override suspend fun listarDispositivos(): List<DispositivoObd> = withContext(dispatcher) {
        try {
            adaptador?.bondedDevices.orEmpty()
                .map { DispositivoObd(it.name ?: it.address, it.address) }
                .sortedBy { it.nome }
        } catch (e: SecurityException) {
            emptyList() // sem BLUETOOTH_CONNECT: a UI deve pedir a permissão antes
        }
    }

    override suspend fun conectar(dispositivo: DispositivoObd, marcaVeiculo: String?): Result<Unit> = withContext(dispatcher) {
        // Compare-and-set: dois conectar() simultâneos (tela + Bluetooth do carro) nunca abrem dois sockets.
        while (true) {
            val atual = _estado.value
            if (atual == EstadoConexaoObd.Conectando || atual == EstadoConexaoObd.Conectado) {
                return@withContext Result.failure(ObdException("Já existe uma conexão ativa ou em andamento"))
            }
            if (_estado.compareAndSet(atual, EstadoConexaoObd.Conectando)) break
        }
        // Nada da sessão anterior (ou de um comando dela que terminou depois do desconectar) vaza para esta.
        adaptadorDaConexao = null
        reiniciarEstadoSessao()
        registro.limpar()
        registro.evento("Conectando a ${dispositivo.nome} (${dispositivo.endereco})")
        var canal: ObdTransport? = null
        try {
            canal = criarTransporte(dispositivo)
            transporte = canal
            canal.conectar()
            registro.evento("Socket Bluetooth aberto")

            inicializarAdaptador(marcaVeiculo)
            // desconectar() pode chegar entre o fim da inicialização e aqui: o estado nunca fica
            // "Conectado" sem canal (a próxima reconexão seria recusada para sempre).
            val confirmado = transporte === canal &&
                _estado.compareAndSet(EstadoConexaoObd.Conectando, EstadoConexaoObd.Conectado)
            if (!confirmado || transporte !== canal) {
                if (transporte == null) _estado.compareAndSet(EstadoConexaoObd.Conectado, EstadoConexaoObd.Desconectado)
                canal.fechar()
                return@withContext Result.failure(ObdException("Conexão cancelada"))
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (canal == null || transporte === canal) {
                fecharRecursos()
                _estado.value = EstadoConexaoObd.Desconectado
            }
            throw e
        } catch (e: ObdException) {
            falharConexao(canal, e)
        } catch (e: SecurityException) {
            falharConexao(canal, ObdException("Permissão Bluetooth não concedida", e))
        } catch (e: IllegalArgumentException) {
            falharConexao(canal, ObdException("Endereço do scanner inválido", e))
        } catch (e: IOException) {
            falharConexao(
                canal,
                ObdException("Não foi possível conectar. Verifique se o scanner está ligado na porta OBD e pareado", e),
            )
        } catch (e: RuntimeException) {
            // Pilha Bluetooth de alguns aparelhos lança exceções não documentadas ao desligar o rádio.
            falharConexao(canal, ObdException("Falha inesperada do Bluetooth: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    override fun lerDadosEmTempoReal(intervaloMs: Long, comandos: Collection<String>?): Flow<DadosObd> = flow {
        val sessao = transporte ?: return@flow
        var ciclosSemEcu = 0
        while (_estado.value == EstadoConexaoObd.Conectado && transporte === sessao) {
            if (_sessaoManual.value) {
                _sessaoManual.first { !it }
                continue
            }
            val ciclo = try {
                cicloForaDaSessaoManual { lerCiclo(comandos) } ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                perderConexao(sessao, mensagemFalhaCiclo(e))
                break
            }
            ciclosSemEcu = if (ciclo.semRespostaDaEcu) ciclosSemEcu + 1 else 0
            if (ciclosSemEcu >= MAX_CICLOS_SEM_ECU) {
                perderConexao(sessao, mensagemEcuMuda(ciclo.ultimoErro))
                break
            }
            emit(DadosObd(ciclo.lidos))
            delay(intervaloMs)
        }
    }.flowOn(dispatcher)

    override fun lerTelemetria(
        intervaloMinimoMs: Long,
        consumoPorMaf: Boolean,
        lentosPorVez: Int,
        ciclosPorLento: Int,
    ): Flow<DadosObd> = flow {
        val sessao = transporte ?: return@flow
        val rapidos = Pids.candidatosFilaRapida(consumoPorMaf)
        val escalonador = EscalonadorPolling(lentosPorVez, ciclosPorLento, rapidos, CHAVES_CAMBIO)
        var geracaoManualVista = sessoesManuaisEncerradas.get()
        var ciclosSemEcu = 0
        while (_estado.value == EstadoConexaoObd.Conectado && transporte === sessao) {
            if (_sessaoManual.value) {
                _sessaoManual.first { !it }
                continue
            }
            val geracao = sessoesManuaisEncerradas.get()
            if (geracao != geracaoManualVista) {
                // v2.2.6: a tensão em cache é de antes da sessão manual (ex.: Teste de Bateria com o
                // motor desligado); o ALTERNADOR BAIXO só é avaliado de novo com um ATRV fresco.
                // Pelo contador (e não pelo ramo acima): a sessão pode abrir e fechar durante o delay.
                geracaoManualVista = geracao
                escalonador.invalidar(Pids.TENSAO_BATERIA)
            }
            val inicioNs = System.nanoTime()
            // Relido a cada ciclo: um perfil de montadora pode ser ativado com o polling já rodando.
            escalonador.atualizarSuportados(_pidsSuportados.value, ::grupoDe)
            // Com o lote ativo, o pedal vai junto no mesmo comando (arco PED do HUD fluido); sem ele, volta à fila lenta.
            escalonador.trocarCandidatosRapidos(if (usaLoteMultiPid) rapidos + Pids.PEDAL_ACELERADOR else rapidos)
            val ciclo = try {
                cicloForaDaSessaoManual { lerChaves(escalonador.proximoCiclo()) } ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Qualquer falha encerra a sessão de forma limpa (socket fechado, estado Erro): um
                // polling morto com o estado ainda "Conectado" deixaria a sessão zumbi, sem reconexão.
                perderConexao(sessao, mensagemFalhaCiclo(e))
                break
            }
            ciclosSemEcu = if (ciclo.semRespostaDaEcu) ciclosSemEcu + 1 else 0
            if (ciclosSemEcu >= MAX_CICLOS_SEM_ECU) {
                perderConexao(sessao, mensagemEcuMuda(ciclo.ultimoErro))
                break
            }
            emit(DadosObd(escalonador.consolidar(ciclo.consultados, ciclo.lidos)))
            val decorridoMs = (System.nanoTime() - inicioNs) / 1_000_000
            delay((intervaloMinimoMs - decorridoMs).coerceAtLeast(INTERVALO_MINIMO_ENTRE_CICLOS_MS))
        }
    }.flowOn(dispatcher)

    override suspend fun ativarPerfilFabricante(marca: String?): ResultadoPerfil = withContext(dispatcher) {
        modoServico?.let { return@withContext ativarPerfilNoModoServico(it, marca) }
        val perfil = catalogoEstendido.perfilPara(marca) ?: return@withContext ResultadoPerfil.SemPerfil
        try {
            exigirConectado()
            val protocolo = ObdParser.parseProtocolo(enviar("ATDPN"))
            if (protocolo !in ObdParser.PROTOCOLOS_CAN_11_BITS) {
                registro.evento("Perfil ${perfil.nome} não ativado: protocolo ${protocolo ?: "desconhecido"} não é CAN 11 bits")
                return@withContext ResultadoPerfil.Indisponivel(
                    perfil.nome,
                    "o perfil exige CAN de 11 bits (o veículo usa o protocolo ${protocolo ?: "desconhecido"})",
                )
            }
            // v2.2.9: no Cruze (perfil GM) o TCM 7E1 é sondado com ATSH 7E1 + ATCRA 7E9 e o cabeçalho volta ao funcional.
            filtroCambioPadrao = perfil.atende(MARCA_GM)
            val ativos = try {
                semAlavancaRedundante(
                    buildList {
                        for ((requisicao, definicoes) in perfil.requisicoes) {
                            val bruto = try {
                                enviar(requisicao.comando, timeoutComandoMs, requisicao.cabecalho)
                            } catch (e: ObdTimeoutException) {
                                continue // módulo ausente (ex.: carro sem câmbio automático)
                            }
                            // A faixa do JSON já descarta leituras implausíveis (ATF GM: −20 a 150 °C).
                            addAll(definicoes.filter { ObdParser.parsePid(bruto, it) is RespostaObd.Ok })
                        }
                    },
                )
            } finally {
                if (filtroCambioPadrao) withContext(NonCancellable) { restaurarCabecalho(null) }
            }
            if (ativos.isEmpty()) {
                registro.evento("Perfil ${perfil.nome}: nenhuma requisição respondeu")
                return@withContext ResultadoPerfil.Indisponivel(perfil.nome, "a ECU não respondeu aos PIDs do perfil")
            }
            incluirEstendidos(ativos)
            registro.evento("Perfil ${perfil.nome} ativo: ${ativos.joinToString { it.comando }}")
            ResultadoPerfil.Ativo(perfil.nome, ativos, perfil.observacao)
        } catch (e: ObdException) {
            ResultadoPerfil.Indisponivel(perfil.nome, e.message ?: "falha de comunicação")
        }
    }

    /**
     * Perfil de montadora com a camada 3 ativa: só as requisições do mesmo módulo e serviço do
     * modo (ex.: `7E0 · 2113` no modo 21). Outros módulos (`7E1 · 2182`) não respondem nesse modo
     * e só atrasariam a conexão.
     */
    private suspend fun ativarPerfilNoModoServico(modo: ModoServico, marca: String?): ResultadoPerfil {
        val perfil = catalogoEstendido.perfilPara(marca) ?: return ResultadoPerfil.SemPerfil
        val compativeis = perfil.requisicoes.filterKeys { it.cabecalho == modo.cabecalho && it.servico == modo.servico }
        if (compativeis.isEmpty() && requisicoesCambio(modo, perfil).isEmpty()) {
            registro.evento("Perfil ${perfil.nome}: nenhuma requisição ${modo.cabecalho} · ${modo.servico}xx para o ${modo.nome}")
            return ResultadoPerfil.Indisponivel(perfil.nome, "o perfil não tem PIDs do ${modo.nome} no módulo do motor")
        }
        return try {
            exigirConectado()
            val ativos = semAlavancaRedundante(
                buildList {
                    for ((requisicao, definicoes) in compativeis) {
                        val bruto = sondar(requisicao.comando, cabecalhoServico) ?: continue
                        addAll(definicoes.filter { ObdParser.parsePid(bruto, it) is RespostaObd.Ok })
                    }
                    addAll(sondarCambio(modo, perfil))
                },
            )
            if (ativos.isEmpty()) {
                registro.evento("Perfil ${perfil.nome}: nenhuma requisição respondeu no ${modo.nome}")
                return ResultadoPerfil.Indisponivel(perfil.nome, "a ECU não respondeu aos PIDs do perfil no ${modo.nome}")
            }
            incluirEstendidos(ativos)
            registro.evento("Perfil ${perfil.nome} ativo no ${modo.nome}: ${ativos.joinToString { it.comando }}")
            ResultadoPerfil.Ativo(perfil.nome, ativos, perfil.observacao)
        } catch (e: ObdException) {
            ResultadoPerfil.Indisponivel(perfil.nome, e.message ?: "falha de comunicação")
        }
    }

    /** Requisições do câmbio (`7E1 · 21D9`) que o polling lê no modo 21, vindas do perfil. */
    private fun requisicoesCambio(modo: ModoServico, perfil: PerfilFabricante): Map<RequisicaoEstendida, List<DefinicaoPid>> =
        if (modo.cabecalho == CABECALHO_CAMBIO) {
            emptyMap()
        } else {
            perfil.requisicoes.filterKeys {
                it.cabecalho == CABECALHO_CAMBIO && it.servico == modo.servico && it.identificador in IDS_CAMBIO_POLLING
            }
        }

    /**
     * Sondagem do câmbio no modo 21: `ATSH 7E1` + `ATCRA 7E9` + `21D9`. Se o `7E1` responder,
     * [cambioAtivo] liga e o polling passa a lê-lo; em qualquer caso o cabeçalho do motor volta
     * (`ATSH 7E0` + `ATCRA`) antes de seguir, e um módulo mudo não atrapalha a conexão.
     */
    private suspend fun sondarCambio(modo: ModoServico, perfil: PerfilFabricante): List<DefinicaoPid> {
        val requisicoes = requisicoesCambio(modo, perfil)
        if (requisicoes.isEmpty() || cabecalhoServico == null) return emptyList()
        val ativos = mutableListOf<DefinicaoPid>()
        try {
            for ((requisicao, definicoes) in requisicoes) {
                val bruto = sondar(requisicao.comando, CABECALHO_CAMBIO) ?: continue
                ativos += definicoes.filter { ObdParser.parsePid(bruto, it) is RespostaObd.Ok }
            }
        } finally {
            withContext(NonCancellable) { restaurarCabecalhoServico() }
        }
        cambioAtivo = ativos.isNotEmpty()
        registro.evento(
            if (cambioAtivo) {
                "Câmbio 7E1 respondeu (${ativos.joinToString { it.comando }}): lido em cadência leve no polling"
            } else {
                "Câmbio 7E1 não respondeu ao 21D9: polling segue só no motor"
            },
        )
        return ativos
    }

    /**
     * Devolve o adaptador ao cabeçalho do [modoServico] (`ATSH 7E0` e `ATCRA` padrão) depois de uma
     * visita ao câmbio. Só age se o cabeçalho ativo for outro. Se o `ATSH` não confirmar, o
     * cabeçalho fica indefinido e o próximo comando o reenvia.
     */
    private suspend fun restaurarCabecalhoServico() {
        val alvo = cabecalhoServico
        if (modoServico == null || alvo == null || cabecalhoAtivo == alvo) return
        mutexComandos.withLock {
            val canal = transporte ?: return
            try {
                val resposta = try {
                    trocar(canal, "ATSH$alvo", timeoutComandoMs, false)
                } catch (_: ObdTimeoutException) {
                    ""
                }
                if ("OK" in resposta.uppercase()) {
                    cabecalhoAtivo = alvo
                    ajustarFiltroRecepcao(canal, alvo, false)
                } else {
                    cabecalhoAtivo = CABECALHO_INDEFINIDO
                }
            } catch (e: IOException) {
                if (transporte !== canal) throw ObdException("Scanner desconectado", e)
                perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
                throw ObdException("Conexão com o scanner perdida", e)
            }
        }
    }

    /**
     * v2.2.6: com a alavanca do câmbio (`7E1 · 21D9`, [CHAVE_ALAVANCA_CAMBIO]) respondendo, o espelho
     * no motor (`7E0 · 21D9`, [CHAVE_ALAVANCA_ECM]) sai do polling: na SW4 2013 ele devolve sempre P e
     * só gastaria um comando por volta da fila lenta.
     */
    private fun semAlavancaRedundante(ativos: List<DefinicaoPid>): List<DefinicaoPid> {
        val filtrados = filtrarAlavancaRedundante(ativos)
        if (filtrados.size != ativos.size) {
            registro.evento("Alavanca lida no câmbio 7E1: $CHAVE_ALAVANCA_ECM (7E0 · 21D9) fora do polling")
        }
        return filtrados
    }

    private fun incluirEstendidos(ativos: List<DefinicaoPid>) {
        definicoesEstendidas = definicoesEstendidas + ativos.associateBy { it.comando }
        _pidsSuportados.value = _pidsSuportados.value + ativos.map { it.comando }
    }

    /** Padrão: modo 03. Modo 21/22: os comandos de leitura do catálogo (`13` → `18 00 FF 00`; `19 02 FF`). */
    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> {
        val modo = modoServico ?: return lerDtcs("03", "ler os códigos de falha", ObdParser::parseDtcs)
        return lerDtcsDoModo(modo)
    }

    /**
     * v2.2.9: falhas do câmbio no TCM `7E1` (OBD2 padrão em CAN de 11 bits): `ATSH 7E1` + `ATCRA 7E9`,
     * `03` e, se o TCM não responder a ele, o UDS `19 02 FF`. O cabeçalho funcional (`ATSH 7DF`) e o
     * `ATCRA` padrão voltam sempre. Nos modos 21/22 (SW4) e fora do CAN 11 bits devolve null sem enviar
     * nada; TCM mudo também é null (não é erro).
     */
    override suspend fun lerCodigosFalhaCambio(): Result<List<DtcLido>?> = withContext(dispatcher) {
        if (modoServico != null || !protocoloCan11Bits(_infoConexao.value.protocolo)) return@withContext Result.success(null)
        operacao {
            exigirConectado()
            val filtroAntes = filtroCambioPadrao
            filtroCambioPadrao = true
            try {
                lerDtcsTcm()
            } finally {
                withContext(NonCancellable) {
                    restaurarCabecalho(null)
                    filtroCambioPadrao = filtroAntes
                }
            }
        }
    }

    private suspend fun lerDtcsTcm(): List<DtcLido>? {
        val bruto03 = sondar("03", CABECALHO_CAMBIO, TIMEOUT_DTC_MS)
        val r03 = bruto03?.let { ObdParser.parseDtcs(it, "03") }
        if (r03 is RespostaObd.Ok) return r03.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
        val brutoUds = sondar(COMANDO_DTC_UDS, CABECALHO_CAMBIO, TIMEOUT_DTC_MS)
        val rUds = brutoUds?.let { ObdParser.parseDtcsUds(it, COMANDO_DTC_UDS) }
        if (rUds is RespostaObd.Ok) return rUds.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
        registro.evento(
            "TCM 7E1 sem falhas legíveis (03: ${bruto03?.let { ObdParser.erroElm(it) ?: RegistroComunicacao.visivel(it) } ?: "sem prompt"}; " +
                "19 02 FF: ${brutoUds?.let { ObdParser.erroElm(it) ?: RegistroComunicacao.visivel(it) } ?: "sem prompt"})",
        )
        return null
    }

    /** CAN de 11 bits pela descrição do `ATDP` (o TCM `7E1` só existe nele). */
    private fun protocoloCan11Bits(descricao: String?): Boolean {
        val d = descricao?.uppercase() ?: return false
        return ("CAN" in d || "15765" in d) && "29" !in d
    }

    override suspend fun lerCodigosPendentes(): Result<List<DtcLido>> {
        indisponivelNoModoServico("O modo 07 (códigos pendentes)")?.let { return it }
        return lerDtcs("07", "ler os códigos pendentes", ObdParser::parseDtcsPendentes)
    }

    override suspend fun lerCodigosPermanentes(): Result<List<DtcLido>> {
        indisponivelNoModoServico("O modo 0A (códigos permanentes)")?.let { return it }
        return lerDtcs("0A", "ler os códigos permanentes", ObdParser::parseDtcsPermanentes)
    }

    override suspend fun lerFreezeFrame(): Result<FreezeFrame> {
        indisponivelNoModoServico("O freeze frame (modo 02)")?.let { return it }
        return operacao {
            exigirConectado()
            val brutoDtc = enviar("020200", TIMEOUT_DTC_MS, cabecalhoDiagnostico)
            val dtc = when (val r = ObdParser.parseDtcFreezeFrame(brutoDtc)) {
                is RespostaObd.Ok -> r.valor
                RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame", brutoDtc)
                else -> null // ECU não informa o DTC do quadro; os valores ainda podem existir
            }
            val valores = buildMap {
                for (comando in Pids.FREEZE_FRAME) {
                    val definicao = catalogo[comando] ?: continue
                    val bruto = enviar(ObdParser.comandoFreezeFrame(definicao), timeoutComandoMs, cabecalhoDiagnostico)
                    when (val r = ObdParser.parseFreezeFrame(bruto, definicao)) {
                        is RespostaObd.Ok -> put(definicao.comando, r.valor)
                        RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame", bruto)
                        else -> Unit // PID não congelado por esta ECU
                    }
                }
            }
            FreezeFrame(dtc, valores)
        }
    }

    /** No modo 21/22 o `0902` não existe: falha na hora, sem gastar um comando no adaptador. */
    override suspend fun lerVin(): Result<String> {
        indisponivelNoModoServico("O VIN (modo 09)")?.let { return it }
        return operacao {
            exigirConectado()
            val bruto = enviar("0902", TIMEOUT_DTC_MS, cabecalhoDiagnostico)
            when (val r = ObdParser.parseVin(bruto)) {
                is RespostaObd.Ok -> r.valor
                else -> throw respostaInesperada(r, "ler o VIN", bruto)
            }
        }
    }

    /**
     * Impressão digital da ECU (v2.2.2): `0151` (combustível) e `0904` (CALID; fora no ELM327 v2.1) com o cabeçalho de
     * diagnóstico e, se pedido, o TCM (`0100` em `ATSH 7E1`, depois `7E2`). Nada aqui derruba a
     * sessão: `NO DATA`, `7F` e timeout só deixam o campo nulo (só a queda do adaptador propaga, pelo
     * caminho normal do estado). O cabeçalho/filtro anteriores voltam sempre ao fim da visita ao TCM.
     * No modo 21/22 (sem OBD2 padrão) não há o que sondar.
     */
    override suspend fun sondarEcu(sondarTcm: Boolean): ImpressaoEcu = withContext(dispatcher) {
        if (_estado.value != EstadoConexaoObd.Conectado || modoServico != null) return@withContext ImpressaoEcu.VAZIA
        var combustivel: Int? = null
        var calid: String? = null
        var tcm: Boolean? = null
        var tcmCabecalho: String? = null
        // v2.3.2: no clone v2.1 o `0904` multi-frame trava o firmware ~10 s e derruba o Bluetooth.
        val calidPulado = adaptadorDaConexao?.contains(VERSAO_SEM_CALID_AUTOMATICO, ignoreCase = true) == true
        try {
            combustivel = (ObdParser.parseCombustivelEcu(sondar("0151", cabecalhoDiagnostico).orEmpty()) as? RespostaObd.Ok)?.valor
            if (!calidPulado) {
                calid = (ObdParser.parseCalid(sondar("0904", cabecalhoDiagnostico, TIMEOUT_DTC_MS).orEmpty()) as? RespostaObd.Ok)?.valor
            }
            if (sondarTcm) {
                tcm = false
                val anterior = cabecalhoAtivo.takeIf { it != CABECALHO_INDEFINIDO }
                try {
                    for (cabecalho in CABECALHOS_TCM) {
                        val bruto = sondar("0100", cabecalho).orEmpty()
                        if (ObdParser.parsePidsSuportados(bruto, 0x00, "0100") is RespostaObd.Ok) {
                            tcm = true
                            tcmCabecalho = cabecalho
                            break
                        }
                    }
                } finally {
                    withContext(NonCancellable) { restaurarCabecalho(anterior) }
                }
            }
        } catch (e: ObdException) {
            registro.evento("Sondagem da ECU interrompida: ${e.message}")
        }
        registro.evento(
            "Impressão da ECU: combustível 0151=${combustivel?.let { "%02X".format(it) } ?: "sem resposta"}, " +
                "CALID 0904=${calid ?: if (calidPulado) "não sondado (ELM327 v2.1)" else "sem resposta"}" +
                (tcm?.let { ", TCM=${if (it) "detectado em $tcmCabecalho" else "não detectado (7E1/7E2)"}" }.orEmpty()),
        )
        ImpressaoEcu(combustivel, calid, tcm, tcmCabecalho, calidPulado)
    }

    /**
     * Devolve o adaptador ao cabeçalho [alvo] (null = funcional `7DF`) e ao filtro de recepção que
     * ele pede (`ATCRA` padrão), depois de uma visita a outro módulo. Se o `ATSH` não confirmar, o
     * cabeçalho fica indefinido e o próximo comando à ECU o reenvia.
     */
    private suspend fun restaurarCabecalho(alvo: String?) {
        mutexComandos.withLock {
            val canal = transporte ?: return
            try {
                if (cabecalhoAtivo != alvo) {
                    val resposta = try {
                        trocar(canal, "ATSH" + (alvo ?: cabecalhoFuncional), timeoutComandoMs, true)
                    } catch (_: ObdTimeoutException) {
                        ""
                    }
                    cabecalhoAtivo = if ("OK" in resposta.uppercase()) alvo else CABECALHO_INDEFINIDO
                }
                if (cabecalhoAtivo == alvo) ajustarFiltroRecepcao(canal, alvo, true)
            } catch (e: IOException) {
                if (transporte !== canal) throw ObdException("Scanner desconectado", e)
                perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
                throw ObdException("Conexão com o scanner perdida", e)
            }
        }
    }

    /** Padrão: `04`. Modo 21/22: os comandos de limpeza do catálogo, em ordem, até a ECU confirmar (`54`). */
    override suspend fun limparCodigosFalha(): Result<Unit> = operacao {
        exigirConectado()
        val modo = modoServico
        val comandos = (modo?.dtc?.limpar ?: listOf("04")).toMutableList()
        var ultimo: Pair<RespostaObd<Unit>, String>? = null
        var servico = comandos.last().take(2)
        var i = 0
        while (i < comandos.size) {
            val comando = comandos[i++]
            servico = comando.take(2)
            val bruto = enviar(comando, TIMEOUT_DTC_MS, cabecalhoDiagnostico)
            val r = ObdParser.parseLimpezaDtcs(bruto, comando)
            if (r is RespostaObd.Ok) {
                modo?.let { registro.evento("Códigos de falha limpos com $comando (${it.nome})") }
                return@operacao
            }
            ultimo = r to bruto
            if (modo != null) {
                // v2.2.3: `7F 14 11` (serviceNotSupported) → tenta o `04` OBD2 (espera `44`) antes do `14 FF 00` (espera `54`).
                if (servico == "14" && ObdParser.respostaNegativa(bruto, "14", comando) == "11" && "04" !in comandos) {
                    comandos.add(i, "04")
                }
                comandos.getOrNull(i)?.let { proximo ->
                    registro.evento("Limpeza $comando recusada na ${modo.nome} (${bruto.trim().replace(Regex("\\s+"), " ")}); tentando $proximo")
                }
            }
        }
        val (r, bruto) = ultimo!!
        ObdParser.respostaNegativa(bruto, servico)?.let { nrc ->
            throw ObdException(
                "A ECU recusou a limpeza dos códigos (7F $servico $nrc, ${ObdParser.descricaoNrc(nrc)}). " +
                    "Com o motor desligado e a ignição ligada, tente novamente",
            )
        }
        throw respostaInesperada(r, "limpar os códigos de falha", bruto)
    }

    // ---------------------------------------------------------------- sessão manual (Ferramentas de Oficina)

    override suspend fun iniciarSessaoManual(): Result<Unit> = withContext(dispatcher) {
        if (_estado.value != EstadoConexaoObd.Conectado || transporte == null) {
            return@withContext Result.failure(ObdException("Scanner não conectado"))
        }
        mutexCiclo.withLock {
            if (!_sessaoManual.value) {
                cabecalhoEntradaManual = cabecalhoAtivo
                configuracaoAlteradaManual = false
                _sessaoManual.value = true
                registro.evento(
                    "Sessão manual iniciada (Ferramentas de Oficina): polling pausado" +
                        cabecalhoAtivo?.let { " com ATSH $it" }.orEmpty(),
                )
            }
        }
        Result.success(Unit)
    }

    override suspend fun encerrarSessaoManual() {
        withContext(dispatcher + NonCancellable) {
            if (!_sessaoManual.value) return@withContext
            try {
                mutexComandos.withLock {
                    val canal = transporte ?: return@withLock
                    try {
                        restaurarAposSessaoManual(canal)
                    } catch (e: IOException) {
                        perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
                    }
                }
            } catch (e: ObdException) {
                registro.evento("Falha ao restaurar o adaptador depois da sessão manual: ${e.message}")
            } finally {
                sessoesManuaisEncerradas.incrementAndGet()
                _sessaoManual.value = false
            }
        }
    }

    override suspend fun enviarComandoManual(comando: String, timeoutMs: Long, registrar: Boolean): Result<RespostaBruta> =
        withContext(dispatcher) {
            val normalizado = ObdClient.normalizarComandoManual(comando)
                ?: return@withContext Result.failure(ObdException("Comando vazio, longo demais ou com caracteres inválidos"))
            val compacto = normalizado.filter { !it.isWhitespace() }
            if (COMANDOS_MANUAIS_BLOQUEADOS.any { compacto.startsWith(it) }) {
                return@withContext Result.failure(
                    ObdException("$normalizado não é permitido no terminal (modo monitor/baixo consumo travaria a sessão)"),
                )
            }
            try {
                exigirConectado()
                mutexComandos.withLock {
                    val canal = transporte ?: throw ObdException("Scanner não conectado")
                    val inicio = System.nanoTime()
                    fun latencia() = (System.nanoTime() - inicio) / 1_000_000
                    val resposta = try {
                        trocar(canal, normalizado, timeoutMs, registrar)
                    } catch (e: ObdTimeoutException) {
                        if (compacto in COMANDOS_MULTI_FRAME) runCatching { blindarPosMultiFrame(canal, registrar) }
                        return@withLock Result.success(RespostaBruta(normalizado, "", latencia(), semPrompt = true))
                    } catch (e: IOException) {
                        if (transporte !== canal) throw ObdException("Scanner desconectado", e)
                        perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
                        throw ObdException("Conexão com o scanner perdida", e)
                    }
                    val duracao = latencia()
                    acompanharComandoManual(compacto, resposta)
                    if (compacto in COMANDOS_MULTI_FRAME) {
                        try {
                            blindarPosMultiFrame(canal, registrar)
                        } catch (e: IOException) {
                            if (transporte !== canal) throw ObdException("Scanner desconectado", e)
                            perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
                            throw ObdException("Conexão com o scanner perdida", e)
                        }
                    }
                    Result.success(RespostaBruta(normalizado, resposta, duracao))
                }
            } catch (e: ObdException) {
                Result.failure(e)
            }
        }

    /**
     * Mantém o estado conhecido do adaptador fiel ao que o comando manual fez: o `ATSH`/`ATST`
     * digitado vira o cabeçalho/timeout ativo (o polling reenvia só o que mudar); `ATZ`/`ATD`/
     * `ATWS` e um `LV RESET` (queda de tensão na partida) voltam aos padrões e marcam a
     * configuração para ser reaplicada no fim da sessão; os demais AT que mudam algo também.
     */
    private fun acompanharComandoManual(compacto: String, resposta: String) {
        val ok = "OK" in ObdParser.limparResposta(resposta)
        val reiniciou = compacto in COMANDOS_REINICIO ||
            (!compacto.startsWith("ATI") && ObdParser.adaptadorReiniciou(resposta))
        when {
            reiniciou -> {
                if (compacto !in COMANDOS_REINICIO) registro.evento("$compacto: o adaptador reiniciou (LV RESET) durante a sessão manual")
                cabecalhoAtivo = null
                timeoutAdaptadorAtivo = null
                filtroRecepcaoAtivo = null
                configuracaoAlteradaManual = true
            }
            compacto.startsWith("ATCRA") -> if (ok) {
                filtroRecepcaoAtivo = compacto.removePrefix("ATCRA").ifEmpty { null }
            }
            compacto.startsWith("ATSH") -> if (ok) {
                val cabecalho = compacto.removePrefix("ATSH")
                cabecalhoAtivo = cabecalho.takeUnless { it == RequisicaoEstendida.CABECALHO_FUNCIONAL }
            }
            compacto.startsWith("ATST") -> if (ok) {
                timeoutAdaptadorAtivo = compacto.removePrefix("ATST").takeUnless { it == TIMEOUT_ADAPTADOR_PADRAO }
            }
            compacto.startsWith("ATSP") || compacto.startsWith("ATTP") -> if (ok) {
                registro.evento("$compacto: protocolo alterado manualmente; o app não o restaura ao sair")
            }
            compacto.startsWith("AT") && compacto !in COMANDOS_AT_SOMENTE_LEITURA -> configuracaoAlteradaManual = true
        }
    }

    /**
     * Fim da sessão manual: reaplica a [CONFIGURACAO] (e o `ATAT2`) se ela mudou e devolve o
     * cabeçalho da entrada ao adaptador. Se o `ATSH` não confirmar, o cabeçalho fica indefinido e
     * o próximo comando do polling o reenvia.
     */
    private suspend fun restaurarAposSessaoManual(canal: ObdTransport) {
        val alvo = cabecalhoEntradaManual
        val reconfigurar = configuracaoAlteradaManual
        if (reconfigurar) {
            val reconfiguracao = if (usaTimingAdaptativoAgressivo) CONFIGURACAO + TIMING_ADAPTATIVO_AGRESSIVO else CONFIGURACAO
            for (c in reconfiguracao) {
                try {
                    trocar(canal, c, timeoutComandoMs, true)
                } catch (_: ObdTimeoutException) {
                }
            }
        }
        if (reconfigurar || cabecalhoAtivo != alvo) {
            cabecalhoAtivo = CABECALHO_INDEFINIDO
            val resposta = try {
                trocar(canal, "ATSH" + (alvo ?: cabecalhoFuncional), timeoutComandoMs, true)
            } catch (_: ObdTimeoutException) {
                ""
            }
            if ("OK" in resposta.uppercase()) cabecalhoAtivo = alvo
        }
        if (filtroRecepcaoAtivo != filtroRecepcaoPara(alvo)) ajustarFiltroRecepcao(canal, alvo, registrar = true)
        configuracaoAlteradaManual = false
        registro.evento(
            "Sessão manual encerrada: polling retomado" +
                (if (reconfigurar) ", configuração reaplicada" else "") +
                (alvo?.let { ", ATSH $it" } ?: ", cabeçalho funcional"),
        )
    }

    /**
     * Filtro do [cabecalho]: o do catálogo, mais `7E9` explícito para o câmbio `7E1` no modo 21 (o A750F
     * responde nele) e, no OBD2 padrão, com o [filtroCambioPadrao] (TCM GM do Cruze, falhas do TCM).
     */
    private fun filtroRecepcaoPara(cabecalho: String?): String? =
        if (cabecalho == CABECALHO_CAMBIO && (modoServico != null || filtroCambioPadrao)) {
            FILTRO_CAMBIO
        } else {
            RequisicaoEstendida.filtroRecepcao(cabecalho)
        }

    /**
     * Ajusta o `ATCRA` ao [cabecalho] recém-fixado (só se mudar): `ATCRA 7B8` para o ABS/VSC,
     * `ATCRA` (sem endereço = filtro padrão) ao voltar ao motor/funcional. Um clone que não aceite
     * *aplicar* o filtro segue sem ele (as requisições desse módulo apenas não respondem), mas o
     * adaptador nunca fica preso num filtro anterior: a remoção tem alternativa (`ATAR`) e, se
     * falhar, o filtro fica indefinido e é tentado de novo no próximo comando.
     */
    private suspend fun ajustarFiltroRecepcao(canal: ObdTransport, cabecalho: String?, registrar: Boolean) {
        val alvo = filtroRecepcaoPara(cabecalho)
        if (alvo == filtroRecepcaoAtivo) return
        if (alvo == null) {
            removerFiltroRecepcao(canal, registrar)
            return
        }
        if (semFiltroRecepcao) {
            // Este adaptador não aplica filtros: garante ao menos o padrão (nunca o de outro módulo).
            if (filtroRecepcaoAtivo != null) removerFiltroRecepcao(canal, registrar)
            return
        }
        var expirou = false
        val resposta = try {
            trocar(canal, "ATCRA$alvo", timeoutComandoMs, registrar)
        } catch (_: ObdTimeoutException) {
            expirou = true
            ""
        }
        if ("OK" in resposta.uppercase()) {
            filtroRecepcaoAtivo = alvo
        } else {
            semFiltroRecepcao = true
            registro.evento("ATCRA $alvo recusado pelo adaptador: \"${RegistroComunicacao.visivel(resposta)}\"")
            // Sem prompt, não se sabe o que o adaptador aplicou; recusado, continua o filtro anterior.
            if (expirou) filtroRecepcaoAtivo = FILTRO_INDEFINIDO
            if (filtroRecepcaoAtivo != null) removerFiltroRecepcao(canal, registrar)
        }
    }

    /**
     * Volta ao filtro de recepção padrão: `ATCRA` sem endereço e, se o clone não aceitar essa forma,
     * `ATAR` (recepção automática, que também desfaz o `ATCRA`). Se nenhum confirmar, o filtro fica
     * [FILTRO_INDEFINIDO] e o próximo comando à ECU tenta de novo, em vez de seguir surdo ao motor.
     */
    private suspend fun removerFiltroRecepcao(canal: ObdTransport, registrar: Boolean) {
        var ultima = ""
        for (comando in COMANDOS_FILTRO_PADRAO) {
            ultima = try {
                trocar(canal, comando, timeoutComandoMs, registrar)
            } catch (_: ObdTimeoutException) {
                ""
            }
            if ("OK" in ultima.uppercase()) {
                filtroRecepcaoAtivo = null
                return
            }
        }
        if (filtroRecepcaoAtivo != FILTRO_INDEFINIDO) {
            registro.evento(
                "Filtro de recepção não voltou ao padrão (ATCRA/ATAR: \"${RegistroComunicacao.visivel(ultima)}\"); " +
                    "nova tentativa no próximo comando",
            )
        }
        filtroRecepcaoAtivo = FILTRO_INDEFINIDO
    }

    /** Um ciclo de polling sob [mutexCiclo]; null = uma sessão manual começou (o ciclo é pulado). */
    private suspend fun cicloForaDaSessaoManual(ler: suspend () -> Ciclo): Ciclo? =
        mutexCiclo.withLock { if (_sessaoManual.value) null else ler() }

    override suspend fun desconectar() {
        // NonCancellable: o fechamento do canal precisa acontecer mesmo se o chamador for cancelado
        // (ex.: ViewModel.onCleared → onDestroy definitivo da tela). Não pega o mutex: fechar o
        // socket é justamente o que destrava um comando em andamento.
        withContext(dispatcher + NonCancellable) {
            if (transporte != null) registro.evento("Desconectado pelo usuário")
            adaptadorDaConexao = null
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Desconectado
        }
    }

    // ---------------------------------------------------------------- cascata de conexão

    /** Resposta ao `0100` num protocolo ([protocolo] null = automático, `ATSP0`); [resposta] null = sem prompt. */
    private class Busca(val protocolo: ProtocoloFisico?, val bruto: String?, val resposta: RespostaObd<Set<Int>>?) {
        /** NRC da resposta negativa ao modo 01 (`7F 01 11` → `11`): a ECU respondeu, mas recusa o OBD2 padrão. */
        val nrc: String? = bruto?.let { ObdParser.respostaNegativa(it, "01", COMANDO_BUSCA) }

        val ok: Boolean get() = resposta is RespostaObd.Ok

        /** Ninguém respondeu: `UNABLE TO CONNECT`/`CAN ERROR`/`BUS INIT: ERROR`, `NO DATA` ou o `0100` sem prompt. */
        val semEcu: Boolean
            get() = nrc == null && (resposta == null || resposta == RespostaObd.SemConexao || resposta == RespostaObd.SemDados)

        val rotulo: String get() = protocolo?.comando ?: "ATSP0"
    }

    /** Identificação, configuração e a cascata (camadas 1 → 2 → 3) até a ECU responder. */
    private suspend fun inicializarAdaptador(marcaVeiculo: String?) {
        val adaptador = identificarAdaptador()
        // v2.3.0: o card já mostra "Adaptador ELM327 conectado (v2.1)" enquanto a ECU ainda não respondeu.
        adaptadorDaConexao = adaptador
        _infoConexao.value = InfoConexao(adaptador = adaptador)

        // Camada 1: OBD2 padrão com o protocolo automático.
        enviar("ATSP0")
        configurarTimingAdaptativo()
        // Daqui em diante o adaptador já está provado (identificado + AT com OK): nenhuma resposta
        // da ECU vira "não é um ELM327".
        val automatica = buscarProtocolo()
        // Sempre, qualquer que seja o 0100: é o que mais ajuda a diagnosticar no carro.
        var protocolo = consultarProtocoloETensao()
        _infoConexao.value = InfoConexao(protocolo = protocolo, adaptador = adaptador)
        val tentativas = mutableListOf(automatica)
        var busca = automatica

        // Camada 2: fallback de protocolo físico.
        if (automatica.semEcu && catalogoCascata.protocolosFisicos.isNotEmpty()) {
            registro.evento(
                "Camada 2 (fallback de protocolo físico): o ATSP0 não achou a ECU no 0100 " +
                    "(${automatica.bruto?.let(ObdParser::erroElm) ?: "sem resposta"}); tentando " +
                    catalogoCascata.protocolosFisicos.joinToString { it.comando },
            )
            for (fisico in catalogoCascata.protocolosFisicos) {
                val b = tentarProtocolo(fisico)
                tentativas += b
                if (b.ok || b.nrc != null) {
                    busca = b
                    break
                }
            }
        }
        var forcado = busca.protocolo?.let { descreverForcado(it) }
        if (busca !== automatica) {
            protocolo = consultarDescricaoProtocolo() ?: busca.protocolo?.nome
            registro.evento("Camada 2: a ECU respondeu com ${busca.rotulo} (${protocolo ?: "protocolo não informado"})")
        }

        val resposta = busca.resposta
        val nrc = busca.nrc
        when {
            resposta is RespostaObd.Ok -> iniciarObd2Padrao(resposta.valor, protocolo, forcado)
            nrc != null -> {
                val motivo = "0100 → 7F 01 $nrc (${ObdParser.descricaoNrc(nrc)}) com ${busca.rotulo}: " +
                    "a ECU respondeu, mas não suporta OBD2 padrão (Modo 01)"
                registro.evento(motivo)
                iniciarModoServico(motivo, adaptador, protocolo, forcado, marcaVeiculo, busca.protocolo)
            }
            else -> {
                // Barramento vivo mas calado no modo 01 (NO DATA): ainda vale a camada 3, de
                // preferência num protocolo CAN de 11 bits (onde o ATSH 7E0 faz sentido).
                val semDados = tentativas.filter { it.resposta == RespostaObd.SemDados }
                val candidato = semDados.firstOrNull { it.protocolo?.ehCan11Bits == true } ?: semDados.firstOrNull()
                if (candidato == null || catalogoCascata.modosServico.isEmpty()) throw falhaSemEcu(automatica, tentativas)
                if (candidato !== tentativas.last()) {
                    enviar(candidato.protocolo?.comando ?: "ATSP0")
                    if (candidato.protocolo != null) protocolo = consultarDescricaoProtocolo() ?: candidato.protocolo.nome
                }
                forcado = candidato.protocolo?.let { descreverForcado(it) }
                val motivo = "0100 → NO DATA com ${candidato.rotulo} (${protocolo ?: "protocolo não informado"})"
                registro.evento(motivo)
                iniciarModoServico(motivo, adaptador, protocolo, forcado, marcaVeiculo, candidato.protocolo)
            }
        }
    }

    /** `ATZ` tolerante a clones, `ATI` se preciso e a [CONFIGURACAO]; devolve "ELM327 v2.1" (ou o que o adaptador disse). */
    private suspend fun identificarAdaptador(): String {
        val respostaAtz = try {
            enviar("ATZ", TIMEOUT_RESET_MS) // reset (responde com a versão do ELM327)
        } catch (e: ObdTimeoutException) {
            // Alguns clones ignoram o primeiro comando depois que o link RFCOMM abre.
            registro.evento("Sem resposta ao ATZ; tentando de novo")
            try {
                enviar("ATZ", TIMEOUT_RESET_MS)
            } catch (e: ObdTimeoutException) {
                throw ObdException(
                    "O dispositivo não respondeu ao ATZ. Confira se escolheu o adaptador OBD2 e se ele está na porta OBD do carro",
                )
            }
        }
        // Identificação tolerante: clones respondem ao ATZ com lixo, bytes nulos ou sem "ELM327".
        var identificado = ObdParser.limparResposta(respostaAtz).contains("ELM")
        var identificacao = respostaAtz
        if (!identificado) {
            registro.evento("ATZ sem 'ELM' (${RegistroComunicacao.visivel(respostaAtz)}); consultando ATI")
            val respostaAti = try {
                enviar("ATI")
            } catch (e: ObdTimeoutException) {
                ""
            }
            identificacao = respostaAti
            identificado = identificaAdaptador(ObdParser.limparResposta(respostaAti))
        }
        for (comando in CONFIGURACAO) {
            val resposta = enviar(comando)
            // ATE0 respondendo OK prova que o interpretador AT está ativo, mesmo sem identificação.
            if (comando == "ATE0" && !identificado && "OK" in ObdParser.limparResposta(resposta)) {
                registro.evento("Adaptador sem identificação reconhecível, mas o ATE0 respondeu OK; seguindo")
                identificado = true
            }
            if (!identificado) {
                registro.evento(
                    "Adaptador não reconhecido. ATZ=\"${RegistroComunicacao.visivel(respostaAtz)}\" " +
                        "ATI=\"${RegistroComunicacao.visivel(identificacao)}\" ATE0=\"${RegistroComunicacao.visivel(resposta)}\"",
                )
                throw ObdException("O dispositivo não respondeu como um adaptador ELM327")
            }
        }
        return identificacaoAdaptador(respostaAtz, identificacao)
    }

    /** Modo 01 respondeu: faixas seguintes do bitmask, sufixo de resposta única e PIDs do catálogo. */
    private suspend fun iniciarObd2Padrao(primeiraFaixa: Set<Int>, descricaoAtdp: String?, forcado: String?) {
        // v2.3.1: o ATDPN confirma o CAN mesmo quando o ATDP de um clone v2.1 devolve só "AUTO, ".
        val ativo = consultarProtocoloAtivo()
        protocoloCanAtivo = ativo?.takeIf { it.ehCan }
        val protocolo = if (ativo != null) ativo.descrever(descricaoAtdp) else descricaoAtdp
        if (ativo != null && protocolo != descricaoAtdp) {
            registro.evento("ATDPN ${ativo.numero}: protocolo reconhecido como $protocolo (ATDP: \"${descricaoAtdp.orEmpty()}\")")
        }
        _infoConexao.value = InfoConexao(protocolo = protocolo, adaptador = adaptadorDaConexao)
        val suportados = detectarFaixasSeguintes(primeiraFaixa)
        usaSufixoRespostaUnica = testarSufixoRespostaUnica()
        ativarTimeoutCurto(TIMEOUT_ADAPTADOR_CAN) {
            val comando = COMANDO_BUSCA + if (usaSufixoRespostaUnica) SUFIXO_RESPOSTA_UNICA else ""
            ObdParser.parsePidsSuportados(enviar(comando), 0x00, comando) is RespostaObd.Ok
        }
        testarLoteMultiPid(suportados, protocolo)
        // Só o que o app sabe interpretar: PIDs do catálogo presentes no bitmask + comandos AT do adaptador.
        val doCatalogo = catalogo.pids.filter { it.ehComandoAt || it.pid in suportados }
        _pidsSuportados.value = doCatalogo.mapTo(linkedSetOf()) { it.comando }
        _infoConexao.value = InfoConexao(
            protocolo = protocolo,
            protocoloForcado = forcado,
            sensoresResponderam = doCatalogo.count { it.ehModo01 },
            adaptador = adaptadorDaConexao,
        )
        registro.evento(
            "Conectado em ${InfoConexao.NOME_OBD2_PADRAO}${forcado?.let { " via $it" }.orEmpty()}: " +
                "${_pidsSuportados.value.size} sensores do catálogo suportados; ${descreverOtimizacoes()}",
        )
    }

    /** Camada 2: força o [fisico] e repete o `0100` (uma vez: a conexão precisa continuar rápida). */
    private suspend fun tentarProtocolo(fisico: ProtocoloFisico): Busca {
        val selecao = try {
            enviar(fisico.comando)
        } catch (e: ObdTimeoutException) {
            verificarTimeouts()
            return Busca(fisico, null, null)
        }
        if ("OK" !in ObdParser.limparResposta(selecao)) {
            registro.evento("${fisico.comando} recusado pelo adaptador: \"${RegistroComunicacao.visivel(selecao)}\"")
            return Busca(fisico, selecao, RespostaObd.ComandoInvalido)
        }
        val bruto = try {
            enviar(COMANDO_BUSCA, TIMEOUT_BUSCA_PROTOCOLO_MS)
        } catch (e: ObdTimeoutException) {
            verificarTimeouts()
            return Busca(fisico, null, null)
        }
        return Busca(fisico, bruto, ObdParser.parsePidsSuportados(bruto, 0x00))
    }

    private fun descreverForcado(fisico: ProtocoloFisico) =
        if (fisico.nome.isBlank()) fisico.comando else "${fisico.comando} · ${fisico.nome}"

    /** Nenhuma camada achou a ECU: a mensagem segue a resposta do `ATSP0` e lista os protocolos tentados. */
    private fun falhaSemEcu(automatica: Busca, tentativas: List<Busca>): ObdException {
        val tentados = if (tentativas.size > 1) " Protocolos tentados: ${tentativas.joinToString { it.rotulo }}." else ""
        val bruto = automatica.bruto
        return when (automatica.resposta) {
            null -> ObdException("O scanner não concluiu a busca do protocolo do veículo (0100 sem resposta).$tentados")
            RespostaObd.SemDados ->
                ObdException("Scanner conectado, mas a ECU não respondeu (NO DATA). Ligue a ignição e tente novamente.$tentados")
            RespostaObd.SemConexao -> ObdException(
                "Scanner conectado, mas sem comunicação com a ECU (${bruto?.let(ObdParser::erroElm) ?: "sem resposta"}). " +
                    "Ligue a ignição e tente novamente. Carros anteriores ao OBD-BR2 (antes de ~2010) podem usar " +
                    "protocolo próprio da montadora, que o ELM327 não lê.$tentados",
            )
            else -> ObdException(
                "O adaptador respondeu, mas a resposta da ECU ao 0100 não foi reconhecida " +
                    "(\"${RegistroComunicacao.visivel(bruto.orEmpty())}\"). Copie o Log de comunicação para análise",
            )
        }
    }

    /**
     * Camada 3: os modos de serviço do catálogo, na ordem de [CatalogoCascata.modosPara]. O
     * cabeçalho físico (`ATSH 7E0`) é enviado uma única vez (os modos compartilham o módulo do
     * motor); se o adaptador o recusar, encerra explicando. O primeiro modo com algum PID
     * espelhado respondendo positivamente vira a sessão.
     */
    private suspend fun iniciarModoServico(
        motivo: String,
        adaptador: String,
        protocolo: String?,
        forcado: String?,
        marca: String?,
        fisico: ProtocoloFisico?,
    ) {
        val modos = catalogoCascata.modosPara(marca, servicoPreferido)
        if (modos.isEmpty()) {
            throw ObdException("$motivo. O catálogo da cascata (${CatalogoCascata.ARQUIVO_PADRAO}) não tem modos alternativos (21/22)")
        }
        val can11 = fisico?.ehCan11Bits ?: protocoloPareceCan11(protocolo)
        registro.evento(
            "Camada 3 (fallback de serviço): tentando ${modos.joinToString(" → ") { it.nome }}" +
                if (can11) "" else " com o cabeçalho padrão do adaptador (o protocolo não é CAN de 11 bits)",
        )
        for (modo in modos) {
            val cabecalho = modo.cabecalho.takeIf { can11 }
            if (cabecalho != null) fixarCabecalho(cabecalho, adaptador)
            val (ativos, responderam) = sondarModo(modo, cabecalho)
            if (ativos.isNotEmpty()) {
                otimizarModoServico(modo, ativos.first(), cabecalho)
                ativarModoServico(modo, cabecalho, ativos, responderam, protocolo, forcado)
                return
            }
            registro.evento("${modo.nome}: nenhum PID espelhado respondeu positivamente")
        }
        val cabecalhos = modos.map { it.cabecalho }.distinct().joinToString("/")
        throw ObdException(
            "A ECU não aceitou o OBD2 padrão (Modo 01) e também não respondeu ao " +
                modos.joinToString(" nem ao ") { it.nome } + (if (can11) " com ATSH $cabecalhos" else "") +
                ". Copie o Log de comunicação (ATDP, ATSH, 21xx/22F4xx) para análise",
        )
    }

    /** Sem o número do protocolo: lê a descrição do `ATDP` (desconhecida = tenta o cabeçalho físico mesmo assim). */
    private fun protocoloPareceCan11(descricao: String?): Boolean {
        val d = descricao?.uppercase() ?: return true
        return when {
            "CAN 29" in d || "29/" in d -> false
            "CAN" in d || "15765" in d -> true
            "9141" in d || "14230" in d || "KWP" in d || "J1850" in d -> false
            else -> true
        }
    }

    /** `ATSH` explícito (só se mudar), com erro claro se o adaptador não aceitar cabeçalhos customizados. */
    private suspend fun fixarCabecalho(cabecalho: String, adaptador: String) {
        if (cabecalhoAtivo == cabecalho) return
        val resposta = try {
            enviar("ATSH$cabecalho")
        } catch (e: ObdTimeoutException) {
            throw ObdException(
                "O adaptador $adaptador não respondeu ao ATSH $cabecalho, exigido pelos modos de diagnóstico da " +
                    "montadora (Modo 21 M-OBD / Modo 22 UDS). Copie o Log de comunicação para análise",
            )
        }
        val limpa = ObdParser.limparResposta(resposta)
        if ("OK" !in limpa) {
            registro.evento("ATSH $cabecalho recusado pelo adaptador: \"${RegistroComunicacao.visivel(resposta)}\"")
            throw ObdException(
                "Este adaptador $adaptador não suporta cabeçalhos customizados (ATSH $cabecalho respondeu " +
                    "\"${limpa.ifEmpty { "vazio" }}\"), exigidos pelos modos de diagnóstico da montadora " +
                    "(Modo 21 M-OBD / Modo 22 UDS, ex.: Toyota D-4D). " +
                    "Use um adaptador ELM327 genuíno ou compatível de verdade (ex.: OBDLink, Vgate iCar)",
            )
        }
        cabecalhoAtivo = cabecalho
    }

    /**
     * Sonda os espelhos do [modo] e devolve os que responderam (e os comandos). Desiste cedo, para
     * a conexão continuar rápida: `7F <serviço> 11` (serviceNotSupported) antes de qualquer
     * resposta positiva encerra o modo na hora, e [SONDAGENS_ATE_DESISTIR] PIDs seguidos sem
     * resposta positiva logo no início também.
     */
    private suspend fun sondarModo(modo: ModoServico, cabecalho: String?): Pair<List<DefinicaoPid>, List<String>> {
        val ativos = mutableListOf<DefinicaoPid>()
        val responderam = mutableListOf<String>()
        var semResposta = 0
        for (espelho in modo.pids) {
            val comando = espelho.estendida!!.comando
            val bruto = sondar(comando, cabecalho)
            if (bruto == null) verificarTimeouts()
            if (bruto != null && ObdParser.parsePid(bruto, espelho) is RespostaObd.Ok) {
                ativos += espelho
                responderam += comando
                continue
            }
            if (ativos.isNotEmpty()) continue
            if (bruto != null && ObdParser.respostaNegativa(bruto, modo.servico, comando) == NRC_SERVICO_NAO_SUPORTADO) {
                registro.evento("${modo.nome}: $comando → 7F ${modo.servico} 11 (serviceNotSupported); serviço indisponível nesta ECU")
                break
            }
            if (++semResposta >= SONDAGENS_ATE_DESISTIR) {
                registro.evento("${modo.nome}: os $semResposta primeiros PIDs não responderam; desistindo do modo")
                break
            }
        }
        return ativos to responderam
    }

    private fun ativarModoServico(
        modo: ModoServico,
        cabecalho: String?,
        ativos: List<DefinicaoPid>,
        responderam: List<String>,
        protocolo: String?,
        forcado: String?,
    ) {
        modoServico = modo
        cabecalhoServico = cabecalho
        definicoesEstendidas = ativos.associateBy { it.comando }
        _pidsSuportados.value = catalogo.pids.filter { it.ehComandoAt }.mapTo(linkedSetOf()) { it.comando } +
            ativos.map { it.comando }
        _infoConexao.value = InfoConexao(
            protocolo = protocolo,
            modo = modo.tipo,
            nomeModo = modo.nome,
            protocoloForcado = forcado,
            sensoresResponderam = ativos.size,
            comandosServico = responderam,
            espelhos = ativos,
            adaptador = adaptadorDaConexao,
        )
        registro.evento(
            "Conectado em ${modo.nome} (${protocolo ?: "protocolo não informado"}" +
                "${cabecalho?.let { ", ATSH $it" }.orEmpty()}): ${ativos.size} sensores responderam — " +
                ativos.joinToString { "${it.estendida!!.comando} → ${it.chave}" } + "; ${descreverOtimizacoes()}",
        )
    }

    /**
     * Modo 21/22: sufixo de resposta única testado no [primeiro] PID que respondeu (`210C1`) e o
     * espera curta (`ATST0C` no modo 21, `ATST19` no 22) validada com a mesma leitura (já com o sufixo).
     */
    private suspend fun otimizarModoServico(modo: ModoServico, primeiro: DefinicaoPid, cabecalho: String?) {
        val requisicao = primeiro.estendida!!
        val comSufixo = requisicao.comando + SUFIXO_RESPOSTA_UNICA
        usaSufixoRespostaUnica = sondar(comSufixo, cabecalho)
            ?.let { ObdParser.parsePid(it, primeiro, comSufixo) is RespostaObd.Ok } == true
        if (!usaSufixoRespostaUnica) registro.evento("$comSufixo sem resposta válida: sufixo de resposta única desligado")
        val espera = if (modo.tipo == ModoComunicacaoEcu.MODO_21) TIMEOUT_ADAPTADOR_CURTO else TIMEOUT_ADAPTADOR_CAN
        ativarTimeoutCurto(espera) {
            val comando = if (usaSufixoRespostaUnica) comSufixo else requisicao.comando
            sondar(comando, cabecalho)?.let { ObdParser.parsePid(it, primeiro, comando) is RespostaObd.Ok } == true
        }
    }

    /** "sufixo de resposta única: sim; ATAT2: sim; ATST 0C: não", para o registro de campo. */
    private fun descreverOtimizacoes(): String {
        fun simNao(b: Boolean) = if (b) "sim" else "não"
        return "sufixo de resposta única: ${simNao(usaSufixoRespostaUnica)}; " +
            "ATAT2: ${simNao(usaTimingAdaptativoAgressivo)}; ATST $timeoutCurto: ${simNao(usaTimeoutCurto)}" +
            if (modoServico == null) "; Multi-PID em lote: ${simNao(usaLoteMultiPid)}" else ""
    }

    // ---------------------------------------------------------------- Multi-PID em lote (v2.2.9)

    /**
     * Handshake do lote: só no OBD2 padrão sobre CAN (ISO 15765-4) e com ao menos 2 dos PIDs de
     * [PIDS_TESTE_LOTE] no bitmask. Um único pedido conservador (`010C0D0B`, resposta de 2 quadros) liga
     * o lote se vierem **todos** os PIDs pedidos; `?`, `ERROR`, `BUFFER FULL`, `NO DATA`, `7F`, resposta
     * truncada ou sem prompt deixam o polling em 1 PID por vez, com o motivo no registro.
     */
    private suspend fun testarLoteMultiPid(suportados: Set<Int>, protocolo: String?) {
        usaLoteMultiPid = false
        falhasLote.clear()
        if (modoServico != null) return
        if (!protocoloCan(protocolo)) {
            registro.evento("Multi-PID em lote desligado: o protocolo (${protocolo ?: "não informado"}) não é CAN ISO 15765-4")
            return
        }
        val candidatos = PIDS_TESTE_LOTE.mapNotNull { catalogo[it] }.filter { it.ehModo01 && it.pid in suportados }
        if (candidatos.size < 2) {
            registro.evento("Multi-PID em lote desligado: a ECU não suporta PIDs rápidos suficientes (${candidatos.size})")
            return
        }
        val comando = ObdParser.comandoLote(candidatos)
        val bruto = sondar(comando, null)
        val resultados = bruto?.let { ObdParser.parseLote(it, candidatos, comando) }
        usaLoteMultiPid = resultados != null && resultados.values.all { it is RespostaObd.Ok }
        registro.evento(
            if (usaLoteMultiPid) {
                "Multi-PID em lote ativo: $comando respondeu com todos os PIDs; os PIDs rápidos do painel saem num só comando"
            } else {
                "Multi-PID em lote desligado: $comando → ${motivoFalhaLote(bruto, resultados)}; polling segue 1 PID por vez"
            },
        )
    }

    /** "NO DATA", "?", "BUFFER FULL", "7F 01 12", "resposta truncada" ou "sem prompt (timeout)", para o registro. */
    private fun motivoFalhaLote(bruto: String?, resultados: Map<DefinicaoPid, RespostaObd<Double>>?): String {
        if (bruto == null) return "sem prompt (timeout)"
        ObdParser.erroElm(bruto)?.let { return it }
        ObdParser.respostaNegativa(bruto, "01")?.let { return "7F 01 $it (${ObdParser.descricaoNrc(it)})" }
        val faltaram = resultados.orEmpty().filterValues { it !is RespostaObd.Ok }.keys.joinToString { it.comando }
        return "resposta truncada ou incompleta (sem $faltaram: \"${RegistroComunicacao.visivel(bruto)}\")"
    }

    /** O protocolo do `ATDP` é CAN (ISO 15765-4, 11 ou 29 bits)? Desconhecido = não (o lote fica desligado). */
    private fun protocoloCan(descricao: String?): Boolean {
        val d = descricao?.uppercase() ?: return false
        return "CAN" in d || "15765" in d
    }

    /**
     * Uma falha do lote no polling: na [MAX_FALHAS_LOTE_SEGUIDAS]ª seguida do mesmo comando o lote é
     * desligado na sessão. Um TIMEOUT ([semPrompt]) desliga na primeira (v2.3.2): no clone v2.1 é o
     * buffer estourado, e cada nova tentativa custa o prazo inteiro.
     */
    private fun registrarFalhaLote(comando: String, motivo: String, semPrompt: Boolean) {
        val falhas = (falhasLote[comando] ?: 0) + 1
        falhasLote[comando] = falhas
        if (!usaLoteMultiPid || (!semPrompt && falhas < MAX_FALHAS_LOTE_SEGUIDAS)) return
        usaLoteMultiPid = false
        val quando = if (semPrompt) "no TIMEOUT" else "após $falhas falhas seguidas"
        registro.evento("Multi-PID em lote desligado $quando ($comando → $motivo); polling volta a 1 PID por vez")
    }

    /**
     * Divide os PIDs do modo 01 do ciclo em lotes de até [MAX_PIDS_LOTE] PIDs e
     * [MAX_BYTES_RESPOSTA_LOTE] bytes (2 quadros CAN, o que o handshake validou). Lote de 1 PID não é
     * lote: vai pela leitura normal.
     */
    private fun montarLotes(definicoes: List<DefinicaoPid>): List<List<DefinicaoPid>> {
        val lotes = mutableListOf<MutableList<DefinicaoPid>>()
        for (d in definicoes) {
            val atual = lotes.lastOrNull()
            if (atual != null && atual.size < MAX_PIDS_LOTE && ObdParser.bytesRespostaLote(atual + d) <= MAX_BYTES_RESPOSTA_LOTE) {
                atual += d
            } else {
                lotes += mutableListOf(d)
            }
        }
        return lotes.filter { it.size >= 2 }
    }

    // ---------------------------------------------------------------- protocolo ELM327

    /** [limpa] é o texto já saneado por [ObdParser.limparResposta]. */
    private fun identificaAdaptador(limpa: String): Boolean =
        IDENTIFICADORES_ADAPTADOR.any { it in limpa }

    /** "ELM327 v2.1" (ou o que o adaptador disse de si), para as mensagens ao usuário. */
    private fun identificacaoAdaptador(respostaAtz: String, respostaAti: String): String =
        listOf(respostaAtz, respostaAti)
            .map(ObdParser::limparResposta)
            .firstNotNullOfOrNull { REGEX_VERSAO_ELM.find(it)?.value?.replace(Regex("\\s*V"), " v") }
            ?: "ELM327"

    /**
     * `0100` com prazo longo no `ATSP0`: força a busca de protocolo e confirma que a ECU está
     * respondendo. Na linha K a inicialização de 5 baud às vezes falha na primeira vez (`BUS INIT:
     * ...ERROR`), então `NO DATA`/sem comunicação têm uma segunda tentativa. Uma resposta negativa
     * (`7F 01 xx`) não: a ECU já respondeu. Sem prompt = [Busca.resposta] null.
     */
    private suspend fun buscarProtocolo(): Busca {
        var bruto = try {
            enviar(COMANDO_BUSCA, TIMEOUT_BUSCA_PROTOCOLO_MS)
        } catch (e: ObdTimeoutException) {
            return Busca(null, null, null)
        }
        var r = ObdParser.parsePidsSuportados(bruto, 0x00)
        if (r == RespostaObd.SemConexao || r == RespostaObd.SemDados) {
            registro.evento("0100 sem resposta da ECU (${ObdParser.erroElm(bruto) ?: "?"}); nova busca de protocolo")
            delay(PAUSA_NOVA_BUSCA_MS)
            bruto = try {
                enviar(COMANDO_BUSCA, TIMEOUT_BUSCA_PROTOCOLO_MS)
            } catch (e: ObdTimeoutException) {
                return Busca(null, null, null)
            }
            r = ObdParser.parsePidsSuportados(bruto, 0x00)
        }
        return Busca(null, bruto, r)
    }

    /** `ATDP` e `ATRV` para o registro de campo; devolve a descrição do protocolo (null se ilegível). */
    private suspend fun consultarProtocoloETensao(): String? {
        val protocolo = consultarDescricaoProtocolo()
        try {
            enviar("ATRV")
        } catch (_: ObdTimeoutException) {
        }
        return protocolo
    }

    private suspend fun consultarProtocoloAtivo(): ProtocoloAtivo? = try {
        ObdParser.parseProtocoloAtivo(enviar("ATDPN"))
    } catch (_: ObdTimeoutException) {
        null
    }

    private suspend fun consultarDescricaoProtocolo(): String? = try {
        ObdParser.parseDescricaoProtocolo(enviar("ATDP"))
    } catch (_: ObdTimeoutException) {
        null
    }

    /**
     * Bitmasks `0120` → `0140` depois do `0100`, seguindo para a próxima faixa só se a ECU indicar
     * suporte a ela (último bit da faixa). Devolve os PIDs de dados (sem os próprios PIDs de bitmask).
     */
    private suspend fun detectarFaixasSeguintes(primeiraFaixa: Set<Int>): Set<Int> {
        val pids = sortedSetOf<Int>().apply { addAll(primeiraFaixa) }
        var anterior = primeiraFaixa
        for (base in FAIXAS_BITMASK.drop(1)) {
            if (base !in anterior) break // o último PID da faixa anterior indica a próxima
            val bruto = try {
                enviar("01%02X".format(base))
            } catch (e: ObdTimeoutException) {
                break // faixa seguinte sem resposta: fica só com o que já foi detectado
            }
            val r = ObdParser.parsePidsSuportados(bruto, base) as? RespostaObd.Ok ?: break
            pids += r.valor
            anterior = r.valor
        }
        return pids.filterTo(sortedSetOf()) { it % TAMANHO_FAIXA != 0 }
    }

    /** Um pedido de sondagem; null se o comando ficou sem resposta (timeout). */
    private suspend fun sondar(comando: String, cabecalho: String?, timeoutMs: Long = timeoutComandoMs): String? =
        try {
            enviar(comando, timeoutMs, cabecalho)
        } catch (e: ObdTimeoutException) {
            null
        }

    /** `ATAT2` (timing adaptativo agressivo); o clone que recusar segue no `ATAT1` padrão, em silêncio. */
    private suspend fun configurarTimingAdaptativo() {
        usaTimingAdaptativoAgressivo = aceitaComandoAt(TIMING_ADAPTATIVO_AGRESSIVO)
        if (!usaTimingAdaptativoAgressivo) {
            registro.evento("$TIMING_ADAPTATIVO_AGRESSIVO recusado pelo adaptador; mantendo o timing adaptativo padrão")
        }
    }

    private suspend fun aceitaComandoAt(comando: String): Boolean = try {
        "OK" in ObdParser.limparResposta(enviar(comando))
    } catch (e: ObdTimeoutException) {
        false
    }

    /**
     * Liga o `ATST` curto [valor] (o primeiro comando à ECU já sai com ele, por [ajustarTimeoutAdaptador]) e
     * confere com [validar] que a ECU continua respondendo no prazo curto; se não, volta ao
     * `ATST32` padrão. `?` ao `ATST` curto desliga sem mais nada.
     */
    private suspend fun ativarTimeoutCurto(valor: String, validar: suspend () -> Boolean) {
        timeoutCurto = valor
        usaTimeoutCurto = true
        val respondeu = try {
            validar()
        } catch (e: ObdTimeoutException) {
            false
        }
        if (!usaTimeoutCurto || respondeu) return
        usaTimeoutCurto = false
        registro.evento("Com ATST $timeoutCurto a ECU não respondeu no prazo; voltando ao ATST $TIMEOUT_ADAPTADOR_PADRAO")
        if (aceitaComandoAt("ATST$TIMEOUT_ADAPTADOR_PADRAO")) timeoutAdaptadorAtivo = null
    }

    /** O ELM327 v1.3+ aceita `01001`; clones antigos respondem `?` (ou nem respondem). */
    private suspend fun testarSufixoRespostaUnica(): Boolean = try {
        ObdParser.parsePidsSuportados(enviar("01001"), 0x00, "01001") is RespostaObd.Ok
    } catch (e: ObdTimeoutException) {
        false
    }

    /** Comando efetivamente enviado: PIDs do modo 01 ganham o sufixo `1` quando suportado. */
    private fun comandoDe(definicao: DefinicaoPid): String =
        if (usaSufixoRespostaUnica && definicao.ehModo01) definicao.comando + SUFIXO_RESPOSTA_UNICA else definicao.comando

    /** Requisição de montadora com o sufixo `1` (`210C1`), salvo as multi-frame (`21D9`, `21AD`) e as que voltaram incompletas com ele. */
    private fun comandoDe(requisicao: RequisicaoEstendida): String =
        if (usaSufixoRespostaUnica && !requisicao.multiFrame && requisicao !in requisicoesSemSufixo) {
            requisicao.comando + SUFIXO_RESPOSTA_UNICA
        } else {
            requisicao.comando
        }

    /** Cabeçalho de uma requisição de montadora: no modo 21/22, o da sessão (null fora do CAN 11 bits), salvo o câmbio `7E1` já sondado. */
    private fun cabecalhoDe(requisicao: RequisicaoEstendida): String? {
        val modo = modoServico ?: return requisicao.cabecalho
        val visitaAoCambio = cambioAtivo && requisicao.cabecalho == CABECALHO_CAMBIO &&
            requisicao.servico == modo.servico && requisicao.identificador in IDS_CAMBIO_POLLING
        return if (visitaAoCambio) CABECALHO_CAMBIO else cabecalhoServico
    }

    /**
     * Resultado de um ciclo: o que foi pedido, o que respondeu e se a ECU ficou calada
     * (ao menos um PID pedido à ECU e nenhum respondido — ignição desligada, barramento mudo).
     */
    private class Ciclo(
        val consultados: List<String>,
        val lidos: Map<String, Double>,
        val semRespostaDaEcu: Boolean = false,
        val ultimoErro: String? = null,
    )

    /** Acumula um ciclo; o mesmo critério serve à telemetria e ao polling legado. */
    private inner class Coletor {
        val consultados = mutableListOf<String>()
        val lidos = LinkedHashMap<String, Double>()
        private var pedidosEcu = 0
        private var respostasEcu = 0
        private var ultimoErro: String? = null

        /** Lê um PID padrão (ou AT) e anota se a ECU respondeu. */
        suspend fun lerPadrao(chave: String, definicao: DefinicaoPid) {
            consultados += chave
            if (definicao.ehModo01) pedidosEcu++
            val lido = try {
                lerPid(definicao)
            } catch (e: ObdTimeoutException) {
                ultimoErro = "TIMEOUT"
                verificarTimeouts()
                return
            }
            when (val r = lido.resposta) {
                is RespostaObd.Ok -> {
                    lidos[chave] = r.valor
                    if (definicao.ehModo01) respostasEcu++
                }
                else -> lido.erro?.let { ultimoErro = it }
            }
        }

        /**
         * Lê uma requisição de montadora (perfil ou espelho do modo 21/22) e decodifica todos os
         * PIDs que ela traz, cada um na sua chave (o espelho do `210C` vai para `010C`). Conta como
         * pedido à ECU: nos modos 21/22 são os únicos, e é por eles que se nota a ignição desligada.
         */
        suspend fun lerEstendido(requisicao: RequisicaoEstendida, grupo: List<DefinicaoPid>) {
            consultados += grupo.map { it.comando }
            pedidosEcu++
            val (bruto, resultados) = try {
                lerRequisicao(requisicao, grupo)
            } catch (e: ObdTimeoutException) {
                ultimoErro = "TIMEOUT"
                verificarTimeouts()
                return
            }
            var algum = false
            for ((d, r) in resultados) {
                if (r is RespostaObd.Ok) {
                    lidos[d.comando] = r.valor
                    algum = true
                }
            }
            if (algum) respostasEcu++ else ObdParser.erroElm(bruto)?.let { ultimoErro = it }
        }

        /**
         * Lê os PIDs do modo 01 de um [lote] num único comando (`010C0D0B`). Devolve null se todos
         * vieram; senão, a [FalhaLote] (os que faltaram ficam para a leitura individual do ciclo).
         */
        suspend fun lerLote(lote: List<DefinicaoPid>): FalhaLote? {
            consultados += lote.map { it.comando }
            pedidosEcu++
            val comando = ObdParser.comandoLote(lote)
            val bruto = try {
                enviar(comando, registrar = false)
            } catch (e: ObdTimeoutException) {
                ultimoErro = "TIMEOUT"
                verificarTimeouts()
                null
            }
            val resultados = bruto?.let { ObdParser.parseLote(it, lote, comando) }
            resultados?.let { registrarDecodificados(comando, it) }
            var algum = false
            resultados?.forEach { (d, r) ->
                if (r is RespostaObd.Ok) {
                    lidos[d.comando] = r.valor
                    algum = true
                }
            }
            if (algum) respostasEcu++ else bruto?.let(ObdParser::erroElm)?.let { ultimoErro = it }
            if (resultados != null && resultados.values.all { it is RespostaObd.Ok }) return null
            return FalhaLote(motivoFalhaLote(bruto, resultados), semPrompt = bruto == null)
        }

        fun ciclo() = Ciclo(consultados, lidos, pedidosEcu > 0 && respostasEcu == 0, ultimoErro)
    }

    /** Por que um lote falhou; [semPrompt] = TIMEOUT. */
    private class FalhaLote(val motivo: String, val semPrompt: Boolean)

    /**
     * Uma requisição de montadora, decodificada para cada PID do [grupo]. Com o sufixo `1`, uma
     * resposta incompleta ou recusada (sem erro do ELM327 no meio: `NO DATA` não conta) é
     * conferida sem o sufixo uma única vez; se assim vierem mais PIDs, a requisição segue sem ele.
     * Devolve a resposta bruta usada e o resultado de cada PID.
     */
    private suspend fun lerRequisicao(
        requisicao: RequisicaoEstendida,
        grupo: List<DefinicaoPid>,
    ): Pair<String, Map<DefinicaoPid, RespostaObd<Double>>> {
        val comando = comandoDe(requisicao)
        val cabecalho = cabecalhoDe(requisicao)
        val bruto = enviar(comando, timeoutComandoMs, cabecalho, registrar = false)
        val resultados = grupo.associateWith { ObdParser.parsePid(bruto, it, comando) }
        registrarDecodificados(comando, resultados)
        if (comando == requisicao.comando || requisicao in requisicoesConferidas) return bruto to resultados
        val erro = ObdParser.erroElm(bruto)
        val suspeita = (erro == null || erro == "?") &&
            resultados.values.any { it is RespostaObd.Invalido || it == RespostaObd.ComandoInvalido }
        if (!suspeita) return bruto to resultados

        requisicoesConferidas = requisicoesConferidas + requisicao
        val puro = enviar(requisicao.comando, timeoutComandoMs, cabecalho, registrar = false)
        val semSufixo = grupo.associateWith { ObdParser.parsePid(puro, it, requisicao.comando) }
        if (semSufixo.values.count { it is RespostaObd.Ok } <= resultados.values.count { it is RespostaObd.Ok }) {
            return bruto to resultados
        }
        requisicoesSemSufixo = requisicoesSemSufixo + requisicao
        registro.evento("$comando voltou incompleto com o sufixo de resposta única; ${requisicao.comando} segue sem ele")
        return puro to semSufixo
    }

    /** Lê uma vez os [comandos] suportados (`null` = todos), do jeito do polling legado. */
    private suspend fun lerCiclo(comandos: Collection<String>?): Ciclo {
        val suportados = _pidsSuportados.value
        val estendidas = definicoesEstendidas
        val chaves = (comandos ?: catalogo.comandos).map { it.uppercase() }.filter { it in suportados }.distinct()
        return lerChaves(chaves.filter { it in catalogo || it in estendidas }, estendidas)
    }

    /** PIDs estendidos decodificados da mesma resposta compartilham uma posição na fila lenta. */
    private fun grupoDe(chave: String): String {
        val estendidas = definicoesEstendidas
        val requisicao = estendidas[chave]?.estendida ?: return chave
        return estendidas.values.first { it.estendida == requisicao }.comando
    }

    /**
     * Lê as [chaves] do ciclo (PIDs estendidos do mesmo grupo numa única requisição). Um espelho
     * do modo 21/22 tem prioridade sobre o PID padrão da mesma chave (`010C` é lido pelo `210C`).
     */
    private suspend fun lerChaves(chaves: List<String>, estendidas: Map<String, DefinicaoPid> = definicoesEstendidas): Ciclo {
        val coletor = Coletor()
        val lidas = mutableSetOf<RequisicaoEstendida>()
        val ordenadas = ordenarPorCabecalho(chaves, estendidas)
        val lotePorChave = lotesDoCiclo(ordenadas, estendidas)
        val lotesLidos = mutableSetOf<List<DefinicaoPid>>()
        for (chave in ordenadas) {
            val definicao = estendidas[chave] ?: catalogo[chave] ?: continue
            val requisicao = definicao.estendida
            val lote = lotePorChave[chave]
            if (lote != null) {
                if (lotesLidos.add(lote)) lerLoteComFallback(coletor, lote)
            } else if (requisicao == null) {
                coletor.lerPadrao(chave, definicao)
            } else if (lidas.add(requisicao)) {
                coletor.lerEstendido(requisicao, estendidas.values.filter { it.estendida == requisicao })
            }
        }
        if (cambioAtivo) {
            restaurarCabecalhoServico()
        } else if (filtroCambioPadrao && modoServico == null && cabecalhoAtivo == CABECALHO_CAMBIO) {
            // GM/Cruze: ao sair do bloco do TCM 7E1, volta ao funcional (ATSH 7DF) e ao ATCRA padrão.
            restaurarCabecalho(null)
        }
        return coletor.ciclo()
    }

    /**
     * Lotes do ciclo (v2.2.9): com o [usaLoteMultiPid], os PIDs de [CHAVES_LOTE] do modo 01 (fila
     * rápida: RPM, MAP, velocidade, MAF e pedal) agrupados por [montarLotes]. Vazio nos modos 21/22.
     */
    private fun lotesDoCiclo(chaves: List<String>, estendidas: Map<String, DefinicaoPid>): Map<String, List<DefinicaoPid>> {
        if (!usaLoteMultiPid || modoServico != null) return emptyMap()
        // Os do handshake primeiro, na mesma ordem: o 1º lote é o `010C0D0B` já validado.
        val loteaveis = chaves.filter { it in CHAVES_LOTE && it !in estendidas }
            .mapNotNull { catalogo[it]?.takeIf { d -> d.ehModo01 } }
            .sortedBy { d -> PIDS_TESTE_LOTE.indexOf(d.comando).takeIf { it >= 0 } ?: PIDS_TESTE_LOTE.size }
        return montarLotes(loteaveis).flatMap { lote -> lote.map { it.comando to lote } }.toMap()
    }

    /**
     * Um lote no polling; se não vierem todos os PIDs, os que faltaram são lidos 1 a 1 no mesmo ciclo
     * (o painel não perde a leitura) e a falha conta para o fallback ([registrarFalhaLote]). Se um
     * lote anterior do mesmo ciclo já desligou o recurso, este vai direto 1 a 1.
     */
    private suspend fun lerLoteComFallback(coletor: Coletor, lote: List<DefinicaoPid>) {
        if (usaLoteMultiPid) {
            val comando = ObdParser.comandoLote(lote)
            val falha = coletor.lerLote(lote)
            if (falha == null) {
                falhasLote.remove(comando)
                return
            }
            registrarFalhaLote(comando, falha.motivo, falha.semPrompt)
        }
        for (d in lote) if (d.comando !in coletor.lidos) coletor.lerPadrao(d.comando, d)
    }

    /**
     * Agrupa o ciclo por cabeçalho, começando pelo que já está no adaptador: cada `ATSH` custa um
     * comando a mais e é onde o v2.1 mais engasga. Comandos AT não dependem do cabeçalho e ficam
     * onde estão; a ordem relativa dentro de cada grupo é mantida.
     */
    private fun ordenarPorCabecalho(chaves: List<String>, estendidas: Map<String, DefinicaoPid>): List<String> {
        val grupos = chaves.map { chave ->
            val definicao = estendidas[chave] ?: catalogo[chave]
            chave to when {
                definicao == null || definicao.ehComandoAt -> NEUTRO
                definicao.estendida != null -> cabecalhoDe(definicao.estendida)
                else -> null // modo 01: cabeçalho funcional
            }
        }
        val ordem = (listOf(cabecalhoAtivo) + grupos.map { it.second }.filter { it != NEUTRO }).distinct()
        return grupos.sortedBy { (_, g) -> if (g == NEUTRO) 0 else ordem.indexOf(g) }.map { it.first }
    }

    /** Vários comandos seguidos sem prompt: o adaptador travou ou o link morreu sem avisar. */
    private fun verificarTimeouts() {
        if (timeoutsSeguidos >= MAX_TIMEOUTS_SEGUIDOS) {
            throw ObdException("O scanner parou de responder ($timeoutsSeguidos comandos seguidos sem resposta)")
        }
    }

    /** Log completo: o valor decodificado de cada PID ao lado da resposta bruta do [comando]. */
    private fun registrarDecodificados(comando: String, resultados: Map<DefinicaoPid, RespostaObd<Double>>) {
        if (!registro.logCompleto) return
        for ((def, r) in resultados) {
            val texto = when (r) {
                is RespostaObd.Ok -> "${r.valor} ${def.unidade}".trim()
                RespostaObd.SemDados -> "NO DATA"
                else -> "sem valor válido"
            }
            registro.decodificado("$comando ${def.nome} = $texto")
        }
    }

    /** Resposta de um PID e a mensagem de erro do ELM327, se houve. */
    private class Lido(val resposta: RespostaObd<Double>, val erro: String?)

    /** Lê um PID; resposta diferente de `Ok` = indisponível neste ciclo (NO DATA, `?`, lixo, fora da faixa). */
    private suspend fun lerPid(definicao: DefinicaoPid): Lido {
        val comando = comandoDe(definicao)
        val bruto = enviar(comando, registrar = false)
        val r = ObdParser.parsePid(bruto, definicao, comando)
        registrarDecodificados(comando, mapOf(definicao to r))
        if (r == RespostaObd.ComandoInvalido && comando != definicao.comando) {
            // Recusou o sufixo: confere sem ele. Só desliga o sufixo se o comando puro funcionar
            // (um "?" isolado pode ser resíduo de um comando interrompido, não falta de suporte).
            val semSufixo = enviar(definicao.comando, registrar = false)
            val r2 = ObdParser.parsePid(semSufixo, definicao, definicao.comando)
            if (r2 != RespostaObd.ComandoInvalido) {
                usaSufixoRespostaUnica = false
                registro.evento("O adaptador passou a recusar o sufixo de resposta única; desligado")
            }
            return Lido(r2, ObdParser.erroElm(semSufixo))
        }
        return Lido(r, ObdParser.erroElm(bruto))
    }

    private suspend fun lerDtcs(
        comando: String,
        acao: String,
        interpretar: (String, String?) -> RespostaObd<List<String>>,
    ): Result<List<DtcLido>> = operacao {
        exigirConectado()
        val bruto = enviar(comando, TIMEOUT_DTC_MS, cabecalhoDiagnostico)
        when (val r = interpretar(bruto, comando)) {
            is RespostaObd.Ok -> r.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
            RespostaObd.SemDados -> emptyList() // ECU sem códigos neste modo
            else -> throw respostaInesperada(r, acao, bruto)
        }
    }

    /**
     * Falhas no modo 21/22: cada comando de leitura do catálogo, em ordem, até um responder
     * positivamente (`53 ...`, `58 ...`, `59 02 ...`). Só `NO DATA`/negativas = nenhum código.
     */
    private suspend fun lerDtcsDoModo(modo: ModoServico): Result<List<DtcLido>> = operacao {
        exigirConectado()
        var semDados = false
        var erro: Pair<RespostaObd<List<String>>, String>? = null
        for (comando in modo.dtc.ler) {
            val bruto = enviar(comando, TIMEOUT_DTC_MS, cabecalhoServico)
            when (val r = ObdParser.parseDtcsServico(bruto, comando)) {
                is RespostaObd.Ok -> return@operacao r.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
                RespostaObd.SemDados -> semDados = true
                else -> if (erro == null) erro = r to bruto
            }
        }
        val falha = erro
        if (semDados || falha == null) {
            emptyList()
        } else {
            throw respostaInesperada(falha.first, "ler os códigos de falha (${modo.nome})", falha.second)
        }
    }

    /** Operações que não existem nos modos 21/22: falham na hora, sem gastar comandos no adaptador. */
    private fun indisponivelNoModoServico(operacao: String): Result<Nothing>? = modoServico?.let {
        Result.failure(ObdException("$operacao não existe no ${it.nome}: a ECU só responde ao serviço ${it.servico} da montadora"))
    }

    /**
     * Envia [comando] pelo transporte e devolve a resposta até o prompt. Serializado por [mutexComandos].
     *
     * Comandos à ECU vão com o [cabecalho] CAN pedido (null = funcional 7DF): o `ATSH` necessário
     * é enviado dentro do mesmo bloqueio — e só quando o cabeçalho realmente muda —, então uma
     * leitura de DTC nunca sai com o cabeçalho físico que um PID de montadora deixou configurado.
     * Se o adaptador recusar o `ATSH`, a resposta dele é devolvida no lugar (o parser a classifica
     * como erro).
     *
     * @param registrar grava a troca no registro mesmo sem erro (o polling só grava as falhas).
     * @throws ObdTimeoutException se o prompt não chegar em [timeoutMs].
     * @throws ObdException em queda de conexão (o estado passa a `Erro`) ou se a sessão foi encerrada.
     */
    private suspend fun enviar(
        comando: String,
        timeoutMs: Long = timeoutComandoMs,
        cabecalho: String? = null,
        registrar: Boolean = true,
    ): String = mutexComandos.withLock {
        val canal = transporte ?: throw ObdException("Scanner não conectado")
        try {
            if (!comando.startsWith("AT")) ajustarTimeoutAdaptador(canal, folgado = timeoutMs > timeoutComandoMs, registrar)
            if (!comando.startsWith("AT") && cabecalho != cabecalhoAtivo) {
                val atsh = "ATSH" + (cabecalho ?: cabecalhoFuncional)
                val resposta = trocar(canal, atsh, timeoutComandoMs, registrar)
                if ("OK" !in resposta.uppercase()) return@withLock resposta
                cabecalhoAtivo = cabecalho
                ajustarFiltroRecepcao(canal, cabecalho, registrar)
            } else if (!comando.startsWith("AT") && filtroRecepcaoAtivo == FILTRO_INDEFINIDO) {
                ajustarFiltroRecepcao(canal, cabecalho, registrar) // a volta ao padrão falhou antes: insiste
            }
            val multiFrame = comando in COMANDOS_MULTI_FRAME
            var resposta = try {
                trocar(canal, comando, timeoutMs, registrar)
            } catch (e: ObdTimeoutException) {
                if (multiFrame) blindarPosMultiFrame(canal, registrar)
                throw e
            }
            if (ObdParser.interrompido(resposta)) {
                registro.evento("$comando interrompido (STOPPED); repetindo")
                resposta = trocar(canal, comando, timeoutMs, registrar)
            }
            if (!comando.startsWith("ATZ") && !comando.startsWith("ATI") && ObdParser.adaptadorReiniciou(resposta)) {
                registro.evento("O adaptador reiniciou sozinho (queda de tensão na partida?); reaplicando a configuração")
                cabecalhoAtivo = null // o reset volta ao cabeçalho funcional
                filtroRecepcaoAtivo = null // e ao filtro de recepção padrão
                timeoutAdaptadorAtivo = null // e ao ATST padrão (o próximo comando à ECU reaplica o curto)
                val reconfiguracao = if (usaTimingAdaptativoAgressivo) CONFIGURACAO + TIMING_ADAPTATIVO_AGRESSIVO else CONFIGURACAO
                for (c in reconfiguracao) runCatching { trocar(canal, c, timeoutComandoMs, true) }
            }
            if (multiFrame) blindarPosMultiFrame(canal, registrar)
            resposta
        } catch (e: IOException) {
            if (transporte !== canal) throw ObdException("Scanner desconectado", e)
            perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
            throw ObdException("Conexão com o scanner perdida", e)
        }
    }

    /**
     * v2.3.1: clones ELM327 v2.1 podem sair de uma recepção multi-frame ISO-TP (`0902`/`0904`/`0906`)
     * com o filtro CAN travado. No OBD2 padrão sobre CAN, fixa o protocolo do `ATDPN` e devolve o
     * `ATSH` funcional e o `ATCRA` padrão. Chamado com o [mutexComandos] já tomado.
     */
    private suspend fun blindarPosMultiFrame(canal: ObdTransport, registrar: Boolean) {
        val can = protocoloCanAtivo ?: return
        if (modoServico != null) return
        suspend fun at(comando: String): Boolean = try {
            "OK" in trocar(canal, comando, timeoutComandoMs, registrar).uppercase()
        } catch (_: ObdTimeoutException) {
            false
        }
        at(can.comandoFixar)
        cabecalhoAtivo = if (at("ATSH" + can.cabecalhoFuncional)) null else CABECALHO_INDEFINIDO
        val filtroAntes = filtroRecepcaoAtivo
        removerFiltroRecepcao(canal, registrar)
        // Clone sem ATCRA/ATAR e sem filtro aplicado: não há o que insistir a cada comando.
        if (filtroAntes == null && filtroRecepcaoAtivo == FILTRO_INDEFINIDO) filtroRecepcaoAtivo = null
    }

    /**
     * Com o [usaTimeoutCurto], deixa o `ATST` do adaptador como o comando pede: o [timeoutCurto] no
     * polling e nas sondagens, o padrão `ATST32` nos de prazo longo ([folgado]: DTCs, VIN,
     * limpeza), que podem ter `7F xx 78` (responsePending) antes da resposta. Só envia quando
     * muda; `?` desliga o timeout curto na sessão (fallback silencioso).
     */
    private suspend fun ajustarTimeoutAdaptador(canal: ObdTransport, folgado: Boolean, registrar: Boolean) {
        if (!usaTimeoutCurto) return
        val alvo = if (folgado) TIMEOUT_ADAPTADOR_PADRAO else timeoutCurto
        if ((timeoutAdaptadorAtivo ?: TIMEOUT_ADAPTADOR_PADRAO) == alvo) return
        val resposta = trocar(canal, "ATST$alvo", timeoutComandoMs, registrar)
        if ("OK" in resposta.uppercase()) {
            timeoutAdaptadorAtivo = alvo
        } else {
            usaTimeoutCurto = false
            registro.evento("ATST $alvo recusado pelo adaptador; mantendo o timeout padrão")
        }
    }

    /** Uma troca no transporte, com o respiro entre comandos, contagem de timeouts e registro. */
    private suspend fun trocar(canal: ObdTransport, comando: String, timeoutMs: Long, registrar: Boolean): String {
        aguardarEspacamento()
        val inicio = System.nanoTime()
        fun duracao() = (System.nanoTime() - inicio) / 1_000_000
        val resposta = try {
            canal.enviarComando(comando, timeoutMs)
        } catch (e: ObdTimeoutException) {
            // Sessão encerrada enquanto o comando esperava: o timeout não conta para a sessão nova.
            if (transporte !== canal) throw IOException("Sessão encerrada durante o $comando", e)
            timeoutsSeguidos++
            registro.comando(comando, null, duracao(), "TIMEOUT: ${e.message}")
            ativarRespiroSeguro("timeout no $comando")
            throw e
        } catch (e: IOException) {
            registro.comando(comando, null, duracao(), "LINK PERDIDO: ${e.message}")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Pilhas Bluetooth de alguns aparelhos lançam NPE/IllegalStateException no socket depois de
            // uma queda de sinal: é link perdido, tratado como IOException (sessão encerrada, não crash).
            registro.comando(comando, null, duracao(), "FALHA DO BLUETOOTH: ${e.javaClass.simpleName}: ${e.message}")
            throw IOException("Falha inesperada do Bluetooth no $comando", e)
        } finally {
            fimUltimoComandoNs = System.nanoTime()
        }
        // Resposta de um canal que já não é o da sessão (desconectar/reconexão no meio do comando):
        // descartada, para nenhuma configuração da sessão velha (ATSH, ATST, filtro) cair na nova.
        if (transporte !== canal) throw IOException("Sessão encerrada durante o $comando")
        timeoutsSeguidos = 0
        val erro = ObdParser.erroElm(resposta)
        if (registrar || erro != null) registro.comando(comando, resposta, duracao()) else registro.comandoSomenteLog(comando, resposta, duracao())
        if (erro == "STOPPED" || erro == "BUFFER FULL") ativarRespiroSeguro("$erro no $comando")
        return resposta
    }

    /**
     * Garante [respiroMs] desde a última resposta. Normalmente zero (o prompt `>` já confirma que o
     * adaptador terminou); depois de um erro, [intervaloSeguroMs] — ver [ativarRespiroSeguro].
     */
    private suspend fun aguardarEspacamento() {
        val fim = fimUltimoComandoNs
        val respiro = respiroMs
        if (respiro <= 0 || fim == 0L) return
        val decorridoMs = (System.nanoTime() - fim) / 1_000_000
        if (decorridoMs < respiro) delay(respiro - decorridoMs)
    }

    /**
     * O ELM327 v2.1 (clone) tem buffer pequeno: um comando colado no prompt do anterior às vezes
     * é descartado ou vira `STOPPED`/timeout. No primeiro sinal disso, a sessão passa a dar
     * [intervaloSeguroMs] de respiro entre comandos.
     */
    private fun ativarRespiroSeguro(motivo: String) {
        if (respiroMs >= intervaloSeguroMs) return
        respiroMs = intervaloSeguroMs
        registro.evento("Respiro de $intervaloSeguroMs ms entre comandos ativado ($motivo)")
    }

    // ---------------------------------------------------------------- auxiliares

    private fun exigirConectado() {
        if (_estado.value != EstadoConexaoObd.Conectado) throw ObdException("Scanner não conectado")
    }

    private fun respostaInesperada(r: RespostaObd<*>, acao: String, bruto: String? = null): ObdException {
        val detalhe = bruto?.let(ObdParser::erroElm)?.let { " ($it)" }.orEmpty()
        return when (r) {
            RespostaObd.SemConexao -> ObdException("Sem comunicação com a ECU ao $acao$detalhe")
            RespostaObd.SemDados -> ObdException("A ECU não respondeu ao $acao")
            RespostaObd.ComandoInvalido -> ObdException("O scanner não suporta a operação de $acao")
            else -> ObdException("Resposta inválida do scanner ao $acao$detalhe")
        }
    }

    /** Motivo da queda quando um ciclo de polling falha: a mensagem da [ObdException] ou o tipo da falha inesperada. */
    private fun mensagemFalhaCiclo(e: Exception): String = when (e) {
        is ObdException -> e.message ?: "Conexão com o scanner perdida"
        else -> "Falha inesperada no polling (${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()})"
    }

    private fun mensagemEcuMuda(erro: String?): String =
        "A ECU parou de responder${erro?.let { " ($it)" }.orEmpty()}. A ignição foi desligada?"

    /** Converte falhas esperadas em `Result.failure`, deixando `CancellationException` passar. */
    private inline fun <T> operacao(bloco: () -> T): Result<T> =
        try {
            Result.success(bloco())
        } catch (e: ObdException) {
            Result.failure(e)
        }

    /** Falha durante [conectar]; se o usuário já desconectou (cancelou), não vira `Erro`. */
    private fun falharConexao(canal: ObdTransport?, erro: ObdException): Result<Unit> {
        registro.evento("Falha na conexão: ${erro.message}")
        if (canal == null || transporte === canal) {
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Erro(erro.message ?: "Falha na conexão")
        } else {
            canal.fechar()
        }
        return Result.failure(erro)
    }

    /** Queda da sessão [canal]; ignorada se ela já foi encerrada (desconectar, nova conexão). */
    private fun perderConexao(canal: ObdTransport, mensagem: String) {
        if (transporte !== canal) return
        registro.evento("Conexão perdida: $mensagem")
        fecharRecursos()
        _estado.value = EstadoConexaoObd.Erro(mensagem)
    }

    /** Solta o canal antes de fechá-lo: falhas da leitura interrompida já não pertencem à sessão. */
    private fun fecharRecursos() {
        val canal = transporte
        transporte = null
        canal?.fechar()
        reiniciarEstadoSessao()
    }

    /**
     * Volta todo o estado conhecido do adaptador e da sessão aos padrões. Chamado ao fechar e de
     * novo no início de cada [conectar]: um comando da sessão anterior que terminou depois do
     * fechamento não deixa cabeçalho, filtro ou timeout velhos para a próxima.
     */
    private fun reiniciarEstadoSessao() {
        _pidsSuportados.value = emptySet()
        _infoConexao.value = InfoConexao(adaptador = adaptadorDaConexao)
        definicoesEstendidas = emptyMap()
        cabecalhoAtivo = null
        filtroRecepcaoAtivo = null
        semFiltroRecepcao = false
        modoServico = null
        cabecalhoServico = null
        cambioAtivo = false
        usaLoteMultiPid = false
        falhasLote.clear()
        filtroCambioPadrao = false
        usaSufixoRespostaUnica = false
        requisicoesSemSufixo = emptySet()
        requisicoesConferidas = emptySet()
        usaTimingAdaptativoAgressivo = false
        usaTimeoutCurto = false
        timeoutCurto = TIMEOUT_ADAPTADOR_CAN
        protocoloCanAtivo = null
        timeoutAdaptadorAtivo = null
        respiroMs = intervaloEntreComandosMs
        timeoutsSeguidos = 0
        fimUltimoComandoNs = 0L
        cabecalhoEntradaManual = null
        configuracaoAlteradaManual = false
        _sessaoManual.value = false // libera o polling suspenso, que então encerra
    }

    companion object {
        /** Faixas de bitmask lidas na conexão: `0100` (PIDs 01–20), `0120` (21–40), `0140` (41–60). */
        private val FAIXAS_BITMASK = listOf(0x00, 0x20, 0x40)
        private const val TAMANHO_FAIXA = 0x20
        private const val SUFIXO_RESPOSTA_UNICA = "1"

        /** Bitmask que também inicia a busca de protocolo. */
        private const val COMANDO_BUSCA = "0100"

        /** Câmbio automático (A750F): cabeçalho `7E1`, resposta em `7E9`. */
        private const val CABECALHO_CAMBIO = "7E1"

        /** TCM (câmbio automático) no padrão ISO 15765-4: `7E1` e, se mudo, `7E2`. */
        private val CABECALHOS_TCM = listOf("7E1", "7E2")
        private const val FILTRO_CAMBIO = "7E9"

        /** Perfil de montadora que lê o TCM `7E1` com filtro explícito no OBD2 padrão (Cruze). */
        private const val MARCA_GM = "gm"

        /** Fallback UDS das falhas do TCM: *reportDTCByStatusMask* com todas as máscaras. */
        private const val COMANDO_DTC_UDS = "1902FF"

        /** Filtro de recepção desconhecido (a volta ao padrão falhou): difere de qualquer alvo, então o próximo comando insiste. */
        private const val FILTRO_INDEFINIDO = "?"

        /** Volta ao filtro padrão: `ATCRA` sem endereço; `ATAR` para clones que só aceitam a forma com endereço. */
        private val COMANDOS_FILTRO_PADRAO = listOf("ATCRA", "ATAR")

        /**
         * Requisições do câmbio lidas no polling do modo 21: alavanca + ATF (`21D9`). O `2130` ficou
         * de fora (v2.1.1): na SW4 2013 ele devolve `0xFF` fixo mesmo a 110 km/h e custava ~150 ms por
         * ciclo; a marcha é estimada pelo app (`CambioA750f`).
         */
        private val IDS_CAMBIO_POLLING = setOf("D9")

        /** Alavanca lida no câmbio (`7E1 · 21D9`) e o espelho redundante no motor (`7E0 · 21D9`). */
        const val CHAVE_ALAVANCA_CAMBIO = "TOYOTA_ALAVANCA"
        const val CHAVE_ALAVANCA_ECM = "TOYOTA_ALAVANCA_ECM"

        /** Tira [CHAVE_ALAVANCA_ECM] de [ativos] quando [CHAVE_ALAVANCA_CAMBIO] também respondeu (v2.2.6). */
        internal fun filtrarAlavancaRedundante(ativos: List<DefinicaoPid>): List<DefinicaoPid> =
            if (ativos.any { it.comando == CHAVE_ALAVANCA_CAMBIO }) ativos.filter { it.comando != CHAVE_ALAVANCA_ECM } else ativos

        /**
         * Sensores do câmbio lidos em cadência leve (ver [EscalonadorPolling]): A750F no modo 21 e, desde a
         * v2.2.9, o ATF do TCM GM (`22 1940` no `7E1`/`7E2`) no OBD2 padrão do Cruze.
         */
        private val CHAVES_CAMBIO = setOf("TOYOTA_ALAVANCA", "TOYOTA_ATF_CAMBIO", "TOYOTA_MARCHA", "GM_ATF", "GM_ATF_7E2")

        /** Handshake do lote: RPM + velocidade + MAP (`010C0D0B`, 8 bytes de resposta = 2 quadros CAN). */
        private val PIDS_TESTE_LOTE = listOf(Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR)

        /** PIDs do modo 01 lidos em lote quando ele está ativo: os da fila rápida e o pedal (arco PED do HUD). */
        val CHAVES_LOTE: Set<String> = (Pids.candidatosFilaRapida(consumoPorMaf = true) + Pids.PEDAL_ACELERADOR).toSet()

        /** ISO 15765-4: até 6 PIDs por pedido do modo 01. */
        const val MAX_PIDS_LOTE = 3

        /** Resposta de até 2 quadros CAN (6 + 7 bytes), como a do pedido de teste do handshake. */
        const val MAX_BYTES_RESPOSTA_LOTE = 13

        /** Falhas seguidas do lote no polling até voltar a 1 PID por vez. */
        const val MAX_FALHAS_LOTE_SEGUIDAS = 2

        /** Adaptador em que o `0904` não é sondado na conexão (só no Checkup manual). */
        const val VERSAO_SEM_CALID_AUTOMATICO = "v2.1"

        /** Marcador de "qualquer cabeçalho" (comandos AT) em [ordenarPorCabecalho]. */
        private const val NEUTRO = "*"

        /** Cabeçalho desconhecido (restauração falhou): difere de qualquer pedido, então o próximo reenvia o `ATSH`. */
        private const val CABECALHO_INDEFINIDO = "?"

        /** Comandos manuais que reiniciam o adaptador (voltam aos padrões: eco, cabeçalho, timeout). */
        private val COMANDOS_REINICIO = setOf("ATZ", "ATWS", "ATD")

        /** Comandos AT manuais que só consultam: não exigem reaplicar a configuração no fim da sessão. */
        private val COMANDOS_AT_SOMENTE_LEITURA = setOf(
            "ATRV", "ATDP", "ATDPN", "ATI", "AT@1", "AT@2", "ATCS", "ATIGN", "ATBD", "ATRD", "ATPPS",
        )

        /** Modo monitor (fluxo contínuo sem prompt) e baixo consumo (desliga o adaptador): fora do terminal. */
        private val COMANDOS_MANUAIS_BLOQUEADOS = listOf("ATMA", "ATMR", "ATMT", "ATLP")

        /** `7F xx 11`: a ECU não tem o serviço — não adianta sondar os outros PIDs do modo. */
        private const val NRC_SERVICO_NAO_SUPORTADO = "11"

        /** PIDs seguidos sem resposta positiva, no início de um modo de serviço, até desistir dele. */
        const val SONDAGENS_ATE_DESISTIR = 3

        /** "ELM327 V2.1" na resposta já saneada do ATZ/ATI. */
        private val REGEX_VERSAO_ELM = Regex("ELM ?327\\s*V?\\d+(\\.\\d+)?")

        /** Trechos (em maiúsculas) da resposta ao `ATI` de adaptadores ELM327 e compatíveis. */
        private val IDENTIFICADORES_ADAPTADOR = listOf("ELM", "OBD", "STN", "VGATE", "V1.", "V2.")

        /** Sem eco, sem line feed, sem espaços (metade dos bytes no Bluetooth), sem cabeçalhos CAN. */
        private val CONFIGURACAO = listOf("ATE0", "ATL0", "ATS0", "ATH0")

        /** Comandos seguidos sem prompt até considerar o adaptador perdido (≈ 3 × 3 s). */
        const val MAX_TIMEOUTS_SEGUIDOS = 3

        /** Ciclos seguidos sem nenhuma resposta da ECU (só NO DATA/CAN ERROR) até encerrar a sessão. */
        const val MAX_CICLOS_SEM_ECU = 8

        /** Respiro mínimo entre ciclos, para comandos de DTC/VIN conseguirem o mutex. */
        private const val INTERVALO_MINIMO_ENTRE_CICLOS_MS = 10L

        /** Sem pausa fixa entre comandos: o prompt `>` já confirma que o adaptador terminou. */
        const val INTERVALO_ENTRE_COMANDOS_MS = 0L

        /** Respiro depois de um timeout/`STOPPED`, para o buffer pequeno do ELM327 v2.1 (clone). */
        const val INTERVALO_SEGURO_MS = 15L

        /** Timing adaptativo agressivo do ELM327 (o padrão é `ATAT1`). */
        const val TIMING_ADAPTATIVO_AGRESSIVO = "ATAT2"

        /** `ATST0C`: 12 × 4,096 ms ≈ 49 ms de espera pela ECU. Só no modo 21 (SW4). */
        const val TIMEOUT_ADAPTADOR_CURTO = "0C"

        /** `ATST19`: 25 × 4,096 ms ≈ 100 ms, a espera curta do modo 01 (e do modo 22). */
        const val TIMEOUT_ADAPTADOR_CAN = "19"

        /** Respostas multi-frame do modo 09 que pedem o reset de protocolo/filtro no CAN. */
        private val COMANDOS_MULTI_FRAME = setOf("0902", "0904", "0906")

        /** `ATST32`: o padrão do ELM327, 50 × 4,096 ms ≈ 200 ms. */
        const val TIMEOUT_ADAPTADOR_PADRAO = "32"
        private const val TIMEOUT_RESET_MS = 5_000L

        /**
         * Prazo de inatividade de cada busca de protocolo (`ATSPx` + `0100`). A linha K
         * (ISO 9141 / KWP 5 baud) passa vários segundos calada entre `SEARCHING...` e a resposta.
         */
        const val TIMEOUT_BUSCA_PROTOCOLO_MS = 20_000L
        private const val PAUSA_NOVA_BUSCA_MS = 1_000L
        private const val TIMEOUT_DTC_MS = 10_000L
    }
}
