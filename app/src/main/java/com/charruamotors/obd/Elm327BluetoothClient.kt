package com.charruamotors.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.log.RegistroComunicacao
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
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
 * - Na conexão: `ATZ` (com uma segunda tentativa), `ATE0 ATL0 ATS0 ATH0 ATSP0`, bitmasks
 *   `0100`/`0120`/`0140` (a primeira com prazo longo e uma nova busca, para a inicialização de
 *   5 baud da linha K) e só depois os PIDs que a ECU suporta. `ATDP`/`ATRV` vão para o registro.
 * - PIDs do modo 01 vão com o sufixo de resposta única (`010C1`): o ELM327 devolve a resposta
 *   assim que a primeira ECU responde, sem esperar o timeout de outras. Ele só é desligado se o
 *   adaptador recusar o sufixo e aceitar o mesmo comando sem ele.
 * - Um [Mutex] serializa os comandos (o ELM327 atende um comando por vez), permitindo
 *   que o polling de PIDs e a leitura de DTCs coexistam sem misturar respostas.
 * - Telemetria em duas filas ([EscalonadorPolling]): mostradores em todo ciclo, demais PIDs em rodízio.
 * - Perfis de montadora (modo 21 Toyota / 22 UDS VW) com cabeçalho CAN físico (`ATSH 7E0`),
 *   ativados só em CAN de 11 bits e se a ECU responder; o cabeçalho funcional volta sozinho.
 * - Sujeira de protocolo: `STOPPED` repete o comando uma vez; `LV RESET` (queda de tensão na
 *   partida) reaplica a configuração; erros de barramento num ciclo não derrubam a sessão.
 * - Queda de conexão: `IOException` (link Bluetooth perdido), [MAX_TIMEOUTS_SEGUIDOS] comandos sem
 *   prompt ou [MAX_CICLOS_SEM_ECU] ciclos sem nenhuma resposta da ECU (ignição desligada) levam o
 *   [estado] a `Erro` e encerram o fluxo de leituras, sem exceções chegando à UI.
 * - [desconectar] pode ser chamado a qualquer momento, inclusive com um comando em andamento ou
 *   durante a conexão: fecha o socket na hora e a leitura interrompida termina sem virar `Erro`.
 *
 * As permissões `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+) devem ser pedidas pela UI
 * antes de conectar; se faltarem, a `SecurityException` é convertida em estado `Erro`.
 *
 * @param adaptador `BluetoothAdapter` do aparelho (nulo se não houver Bluetooth).
 * @param catalogo definições (fórmula, faixa) dos PIDs que o app sabe interpretar.
 * @param criarTransporte fábrica do canal para o dispositivo escolhido.
 * @param catalogoEstendido perfis de PIDs de montadora (`pids_estendidos.json`).
 * @param registro últimas trocas com o adaptador, para diagnóstico em campo.
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
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val _pidsSuportados = MutableStateFlow<Set<String>>(emptySet())
    override val pidsSuportados: StateFlow<Set<String>> = _pidsSuportados.asStateFlow()

    private val mutexComandos = Mutex()

    /** Canal da sessão atual; null = desconectado. Comparado por identidade para descartar falhas de sessões encerradas. */
    @Volatile private var transporte: ObdTransport? = null

    /** Cabeçalho CAN configurado no adaptador (`ATSH`); null = o funcional padrão (7DF). */
    @Volatile private var cabecalhoAtivo: String? = null

    /** PIDs de montadora ativados nesta conexão, por chave (`TOYOTA_ATF`...). */
    @Volatile private var definicoesEstendidas: Map<String, DefinicaoPid> = emptyMap()

    /** Comandos seguidos sem prompt; zera a cada resposta. */
    @Volatile private var timeoutsSeguidos = 0

    /** O adaptador aceita o sufixo de resposta única (`010C1`)? Detectado na inicialização. */
    @Volatile var usaSufixoRespostaUnica: Boolean = false
        private set

    override fun registroComunicacao(): List<EntradaRegistro> = registro.entradas()

    override suspend fun listarDispositivos(): List<DispositivoObd> = withContext(dispatcher) {
        try {
            adaptador?.bondedDevices.orEmpty()
                .map { DispositivoObd(it.name ?: it.address, it.address) }
                .sortedBy { it.nome }
        } catch (e: SecurityException) {
            emptyList() // sem BLUETOOTH_CONNECT: a UI deve pedir a permissão antes
        }
    }

    override suspend fun conectar(dispositivo: DispositivoObd): Result<Unit> = withContext(dispatcher) {
        val atual = _estado.value
        if (atual == EstadoConexaoObd.Conectando || atual == EstadoConexaoObd.Conectado) {
            return@withContext Result.failure(ObdException("Já existe uma conexão ativa ou em andamento"))
        }
        _estado.value = EstadoConexaoObd.Conectando
        registro.limpar()
        registro.evento("Conectando a ${dispositivo.nome} (${dispositivo.endereco})")
        var canal: ObdTransport? = null
        try {
            canal = criarTransporte(dispositivo)
            transporte = canal
            canal.conectar()
            registro.evento("Socket Bluetooth aberto")

            inicializarAdaptador()
            if (transporte !== canal) return@withContext Result.failure(ObdException("Conexão cancelada"))
            _estado.value = EstadoConexaoObd.Conectado
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
            val ciclo = try {
                lerCiclo(comandos)
            } catch (e: ObdException) {
                perderConexao(sessao, e.message ?: "Conexão com o scanner perdida")
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

    override fun lerTelemetria(intervaloMinimoMs: Long, lentosPorCiclo: Int): Flow<DadosObd> = flow {
        val sessao = transporte ?: return@flow
        val escalonador = EscalonadorPolling(lentosPorCiclo)
        var ciclosSemEcu = 0
        while (_estado.value == EstadoConexaoObd.Conectado && transporte === sessao) {
            val inicioNs = System.nanoTime()
            // Relido a cada ciclo: um perfil de montadora pode ser ativado com o polling já rodando.
            escalonador.atualizarSuportados(_pidsSuportados.value, ::grupoDe)
            val ciclo = try {
                lerChaves(escalonador.proximoCiclo())
            } catch (e: ObdException) {
                perderConexao(sessao, e.message ?: "Conexão com o scanner perdida")
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
            val ativos = buildList {
                for ((requisicao, definicoes) in perfil.requisicoes) {
                    val bruto = try {
                        enviar(requisicao.comando, timeoutComandoMs, requisicao.cabecalho)
                    } catch (e: ObdTimeoutException) {
                        continue // módulo ausente (ex.: carro sem câmbio automático)
                    }
                    addAll(definicoes.filter { ObdParser.parsePid(bruto, it) is RespostaObd.Ok })
                }
            }
            if (ativos.isEmpty()) {
                registro.evento("Perfil ${perfil.nome}: nenhuma requisição respondeu")
                return@withContext ResultadoPerfil.Indisponivel(perfil.nome, "a ECU não respondeu aos PIDs do perfil")
            }
            definicoesEstendidas = ativos.associateBy { it.comando }
            _pidsSuportados.value = _pidsSuportados.value + ativos.map { it.comando }
            registro.evento("Perfil ${perfil.nome} ativo: ${ativos.joinToString { it.comando }}")
            ResultadoPerfil.Ativo(perfil.nome, ativos, perfil.observacao)
        } catch (e: ObdException) {
            ResultadoPerfil.Indisponivel(perfil.nome, e.message ?: "falha de comunicação")
        }
    }

    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> =
        lerDtcs("03", "ler os códigos de falha", ObdParser::parseDtcs)

    override suspend fun lerCodigosPendentes(): Result<List<DtcLido>> =
        lerDtcs("07", "ler os códigos pendentes", ObdParser::parseDtcsPendentes)

    override suspend fun lerCodigosPermanentes(): Result<List<DtcLido>> =
        lerDtcs("0A", "ler os códigos permanentes", ObdParser::parseDtcsPermanentes)

    override suspend fun lerFreezeFrame(): Result<FreezeFrame> = operacao {
        exigirConectado()
        val brutoDtc = enviar("020200", TIMEOUT_DTC_MS)
        val dtc = when (val r = ObdParser.parseDtcFreezeFrame(brutoDtc)) {
            is RespostaObd.Ok -> r.valor
            RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame", brutoDtc)
            else -> null // ECU não informa o DTC do quadro; os valores ainda podem existir
        }
        val valores = buildMap {
            for (comando in Pids.FREEZE_FRAME) {
                val definicao = catalogo[comando] ?: continue
                val bruto = enviar(ObdParser.comandoFreezeFrame(definicao))
                when (val r = ObdParser.parseFreezeFrame(bruto, definicao)) {
                    is RespostaObd.Ok -> put(definicao.comando, r.valor)
                    RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame", bruto)
                    else -> Unit // PID não congelado por esta ECU
                }
            }
        }
        FreezeFrame(dtc, valores)
    }

    override suspend fun lerVin(): Result<String> = operacao {
        exigirConectado()
        val bruto = enviar("0902", TIMEOUT_DTC_MS)
        when (val r = ObdParser.parseVin(bruto)) {
            is RespostaObd.Ok -> r.valor
            else -> throw respostaInesperada(r, "ler o VIN", bruto)
        }
    }

    override suspend fun limparCodigosFalha(): Result<Unit> = operacao {
        exigirConectado()
        val bruto = enviar("04", TIMEOUT_DTC_MS)
        when (val r = ObdParser.parseLimpezaDtcs(bruto)) {
            is RespostaObd.Ok -> Unit
            else -> throw respostaInesperada(r, "limpar os códigos de falha", bruto)
        }
    }

    override suspend fun desconectar() {
        // NonCancellable: o fechamento do canal precisa acontecer mesmo se o chamador for cancelado
        // (ex.: ViewModel.onCleared → onDestroy definitivo da tela). Não pega o mutex: fechar o
        // socket é justamente o que destrava um comando em andamento.
        withContext(dispatcher + NonCancellable) {
            if (transporte != null) registro.evento("Desconectado pelo usuário")
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Desconectado
        }
    }

    // ---------------------------------------------------------------- protocolo ELM327

    /** Sequência de inicialização AT, detecção dos PIDs suportados e do sufixo de resposta única. */
    private suspend fun inicializarAdaptador() {
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
        enviar("ATSP0") // protocolo automático
        val suportados = detectarPidsSuportados()
        usaSufixoRespostaUnica = testarSufixoRespostaUnica()
        // Só para o registro de campo: protocolo detectado e tensão na porta OBD.
        for (comando in listOf("ATDP", "ATRV")) {
            try {
                enviar(comando)
            } catch (_: ObdTimeoutException) {
            }
        }
        // Só o que o app sabe interpretar: PIDs do catálogo presentes no bitmask + comandos AT do adaptador.
        _pidsSuportados.value = catalogo.pids
            .filter { it.ehComandoAt || it.pid in suportados }
            .mapTo(linkedSetOf()) { it.comando }
        registro.evento(
            "${_pidsSuportados.value.size} sensores do catálogo suportados; sufixo de resposta única: " +
                if (usaSufixoRespostaUnica) "sim" else "não",
        )
    }

    /** [limpa] é o texto já saneado por [ObdParser.limparResposta]. */
    private fun identificaAdaptador(limpa: String): Boolean =
        IDENTIFICADORES_ADAPTADOR.any { it in limpa }

    /**
     * Lê os bitmasks `0100` → `0120` → `0140`, seguindo para a próxima faixa só se a ECU indicar
     * suporte a ela (último bit da faixa). `0100` também força a busca de protocolo e confirma
     * que a ECU está respondendo; na linha K a inicialização de 5 baud às vezes falha na primeira
     * vez (`BUS INIT: ...ERROR`), então ela tem uma segunda tentativa. Devolve os PIDs de dados
     * (sem os próprios PIDs de bitmask).
     */
    private suspend fun detectarPidsSuportados(): Set<Int> {
        val pids = sortedSetOf<Int>()
        for (base in FAIXAS_BITMASK) {
            val primeira = base == FAIXAS_BITMASK.first()
            val comando = "01%02X".format(base)
            var bruto = try {
                enviar(comando, if (primeira) TIMEOUT_BUSCA_PROTOCOLO_MS else timeoutComandoMs)
            } catch (e: ObdTimeoutException) {
                if (primeira) throw ObdException("O scanner não concluiu a busca do protocolo do veículo (0100 sem resposta)")
                break // faixa seguinte sem resposta: fica só com o que já foi detectado
            }
            var r = ObdParser.parsePidsSuportados(bruto, base)
            if (primeira && (r == RespostaObd.SemConexao || r == RespostaObd.SemDados)) {
                registro.evento("0100 sem resposta da ECU (${ObdParser.erroElm(bruto) ?: "?"}); nova busca de protocolo")
                delay(PAUSA_NOVA_BUSCA_MS)
                bruto = enviar(comando, TIMEOUT_BUSCA_PROTOCOLO_MS)
                r = ObdParser.parsePidsSuportados(bruto, base)
            }
            when (r) {
                is RespostaObd.Ok -> {
                    pids += r.valor
                    if (base + TAMANHO_FAIXA !in r.valor) break
                }
                RespostaObd.SemConexao -> if (primeira) {
                    throw ObdException(
                        "Scanner conectado, mas sem comunicação com a ECU (${ObdParser.erroElm(bruto) ?: "sem resposta"}). " +
                            "Ligue a ignição e tente novamente. Carros anteriores ao OBD-BR2 (antes de ~2010) podem usar " +
                            "protocolo próprio da montadora, que o ELM327 não lê",
                    )
                } else {
                    break
                }
                RespostaObd.SemDados -> if (primeira) {
                    throw ObdException("Scanner conectado, mas a ECU não respondeu (NO DATA). Ligue a ignição e tente novamente")
                } else {
                    break
                }
                else -> if (primeira) {
                    throw ObdException("O dispositivo não respondeu como um adaptador ELM327")
                } else {
                    break
                }
            }
        }
        return pids.filterTo(sortedSetOf()) { it % TAMANHO_FAIXA != 0 }
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

    /**
     * Resultado de um ciclo: o que foi pedido, o que respondeu e se a ECU ficou calada
     * (ao menos um PID do modo 01 pedido e nenhum respondido — ignição desligada, barramento mudo).
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

        /** Lê uma requisição de montadora e decodifica todos os PIDs que ela traz. */
        suspend fun lerEstendido(requisicao: RequisicaoEstendida, grupo: List<DefinicaoPid>) {
            consultados += grupo.map { it.comando }
            val bruto = try {
                enviar(requisicao.comando, timeoutComandoMs, requisicao.cabecalho, registrar = false)
            } catch (e: ObdTimeoutException) {
                verificarTimeouts()
                return
            }
            for (d in grupo) {
                (ObdParser.parsePid(bruto, d) as? RespostaObd.Ok)?.let { lidos[d.comando] = it.valor }
            }
        }

        fun ciclo() = Ciclo(consultados, lidos, pedidosEcu > 0 && respostasEcu == 0, ultimoErro)
    }

    /** Lê uma vez os [comandos] suportados (`null` = todos), do jeito do polling legado. */
    private suspend fun lerCiclo(comandos: Collection<String>?): Ciclo {
        val suportados = _pidsSuportados.value
        val coletor = Coletor()
        (comandos ?: catalogo.comandos)
            .mapNotNull { catalogo[it] }
            .filter { it.comando in suportados }
            .distinct()
            .forEach { coletor.lerPadrao(it.comando, it) }
        return coletor.ciclo()
    }

    /** PIDs estendidos decodificados da mesma resposta compartilham uma posição na fila lenta. */
    private fun grupoDe(chave: String): String {
        val estendidas = definicoesEstendidas
        val requisicao = estendidas[chave]?.estendida ?: return chave
        return estendidas.values.first { it.estendida == requisicao }.comando
    }

    /** Lê as [chaves] do ciclo (PIDs estendidos do mesmo grupo numa única requisição). */
    private suspend fun lerChaves(chaves: List<String>): Ciclo {
        val coletor = Coletor()
        val estendidas = definicoesEstendidas
        for (chave in chaves) {
            val definicao = catalogo[chave] ?: estendidas[chave] ?: continue
            val requisicao = definicao.estendida
            if (requisicao == null) {
                coletor.lerPadrao(chave, definicao)
            } else {
                coletor.lerEstendido(requisicao, estendidas.values.filter { it.estendida == requisicao })
            }
        }
        return coletor.ciclo()
    }

    /** Vários comandos seguidos sem prompt: o adaptador travou ou o link morreu sem avisar. */
    private fun verificarTimeouts() {
        if (timeoutsSeguidos >= MAX_TIMEOUTS_SEGUIDOS) {
            throw ObdException("O scanner parou de responder ($timeoutsSeguidos comandos seguidos sem resposta)")
        }
    }

    /** Resposta de um PID e a mensagem de erro do ELM327, se houve. */
    private class Lido(val resposta: RespostaObd<Double>, val erro: String?)

    /** Lê um PID; resposta diferente de `Ok` = indisponível neste ciclo (NO DATA, `?`, lixo, fora da faixa). */
    private suspend fun lerPid(definicao: DefinicaoPid): Lido {
        val comando = comandoDe(definicao)
        val bruto = enviar(comando, registrar = false)
        val r = ObdParser.parsePid(bruto, definicao, comando)
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
        val bruto = enviar(comando, TIMEOUT_DTC_MS)
        when (val r = interpretar(bruto, comando)) {
            is RespostaObd.Ok -> r.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
            RespostaObd.SemDados -> emptyList() // ECU sem códigos neste modo
            else -> throw respostaInesperada(r, acao, bruto)
        }
    }

    /**
     * Envia [comando] pelo transporte e devolve a resposta até o prompt. Serializado por [mutexComandos].
     *
     * Comandos à ECU vão com o [cabecalho] CAN pedido (null = funcional 7DF): o `ATSH` necessário
     * é enviado dentro do mesmo bloqueio, então uma leitura de DTC nunca sai com o cabeçalho
     * físico que um PID de montadora deixou configurado. Se o adaptador recusar o `ATSH`, a
     * resposta dele é devolvida no lugar (o parser a classifica como erro).
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
            if (!comando.startsWith("AT") && cabecalho != cabecalhoAtivo) {
                val atsh = "ATSH" + (cabecalho ?: RequisicaoEstendida.CABECALHO_FUNCIONAL)
                val resposta = trocar(canal, atsh, timeoutComandoMs, registrar)
                if ("OK" !in resposta.uppercase()) return@withLock resposta
                cabecalhoAtivo = cabecalho
            }
            var resposta = trocar(canal, comando, timeoutMs, registrar)
            if (ObdParser.interrompido(resposta)) {
                registro.evento("$comando interrompido (STOPPED); repetindo")
                resposta = trocar(canal, comando, timeoutMs, registrar)
            }
            if (!comando.startsWith("ATZ") && !comando.startsWith("ATI") && ObdParser.adaptadorReiniciou(resposta)) {
                registro.evento("O adaptador reiniciou sozinho (queda de tensão na partida?); reaplicando a configuração")
                cabecalhoAtivo = null // o reset volta ao cabeçalho funcional
                for (c in CONFIGURACAO) runCatching { trocar(canal, c, timeoutComandoMs, true) }
            }
            resposta
        } catch (e: IOException) {
            if (transporte !== canal) throw ObdException("Scanner desconectado", e)
            perderConexao(canal, "Conexão Bluetooth com o scanner perdida")
            throw ObdException("Conexão com o scanner perdida", e)
        }
    }

    /** Uma troca no transporte, com contagem de timeouts e registro. */
    private suspend fun trocar(canal: ObdTransport, comando: String, timeoutMs: Long, registrar: Boolean): String {
        val inicio = System.nanoTime()
        fun duracao() = (System.nanoTime() - inicio) / 1_000_000
        val resposta = try {
            canal.enviarComando(comando, timeoutMs)
        } catch (e: ObdTimeoutException) {
            timeoutsSeguidos++
            registro.comando(comando, null, duracao(), "TIMEOUT: ${e.message}")
            throw e
        } catch (e: IOException) {
            registro.comando(comando, null, duracao(), "LINK PERDIDO: ${e.message}")
            throw e
        }
        timeoutsSeguidos = 0
        if (registrar || ObdParser.erroElm(resposta) != null) registro.comando(comando, resposta, duracao())
        return resposta
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
        _pidsSuportados.value = emptySet()
        definicoesEstendidas = emptyMap()
        cabecalhoAtivo = null
        usaSufixoRespostaUnica = false
        timeoutsSeguidos = 0
    }

    companion object {
        /** Faixas de bitmask lidas na conexão: `0100` (PIDs 01–20), `0120` (21–40), `0140` (41–60). */
        private val FAIXAS_BITMASK = listOf(0x00, 0x20, 0x40)
        private const val TAMANHO_FAIXA = 0x20
        private const val SUFIXO_RESPOSTA_UNICA = "1"

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
        private const val TIMEOUT_RESET_MS = 5_000L

        /**
         * Prazo de inatividade da primeira busca de protocolo (`ATSP0` + `0100`). A linha K
         * (ISO 9141 / KWP 5 baud) passa vários segundos calada entre `SEARCHING...` e a resposta.
         */
        const val TIMEOUT_BUSCA_PROTOCOLO_MS = 20_000L
        private const val PAUSA_NOVA_BUSCA_MS = 1_000L
        private const val TIMEOUT_DTC_MS = 10_000L
    }
}
