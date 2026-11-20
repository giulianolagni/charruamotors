package com.charruamotors.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
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
 * - Na conexão, lê os bitmasks `0100`/`0120`/`0140` e só consulta os PIDs que a ECU suporta.
 * - PIDs do modo 01 vão com o sufixo de resposta única (`010C1`): o ELM327 devolve a resposta
 *   assim que a primeira ECU responde, sem esperar o timeout de outras. Se o adaptador não
 *   aceitar o sufixo (clones antigos respondem `?`), ele é desligado automaticamente.
 * - Um [Mutex] serializa os comandos (o ELM327 atende um comando por vez), permitindo
 *   que o polling de PIDs e a leitura de DTCs coexistam sem misturar respostas.
 * - Queda de conexão (`IOException`, vários timeouts seguidos) leva o [estado] a `Erro`
 *   e encerra o fluxo de leituras, sem exceções chegando à UI.
 *
 * As permissões `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+) devem ser pedidas pela UI
 * antes de conectar; se faltarem, a `SecurityException` é convertida em estado `Erro`.
 *
 * @param adaptador `BluetoothAdapter` do aparelho (nulo se não houver Bluetooth).
 * @param catalogo definições (fórmula, faixa) dos PIDs que o app sabe interpretar.
 * @param criarTransporte fábrica do canal para o dispositivo escolhido.
 */
@SuppressLint("MissingPermission") // SecurityException é tratada e convertida em EstadoConexaoObd.Erro
class Elm327BluetoothClient(
    private val adaptador: BluetoothAdapter?,
    private val catalogo: CatalogoPids,
    private val criarTransporte: (DispositivoObd) -> ObdTransport = { ClassicBluetoothTransport(adaptador, it.endereco) },
    private val timeoutComandoMs: Long = ObdTransport.TIMEOUT_PADRAO_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val _pidsSuportados = MutableStateFlow<Set<String>>(emptySet())
    override val pidsSuportados: StateFlow<Set<String>> = _pidsSuportados.asStateFlow()

    private val mutexComandos = Mutex()

    @Volatile private var transporte: ObdTransport? = null

    /** O adaptador aceita o sufixo de resposta única (`010C1`)? Detectado na inicialização. */
    @Volatile var usaSufixoRespostaUnica: Boolean = false
        private set

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
        try {
            val canal = criarTransporte(dispositivo)
            transporte = canal
            canal.conectar()

            inicializarAdaptador()
            _estado.value = EstadoConexaoObd.Conectado
            Result.success(Unit)
        } catch (e: CancellationException) {
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Desconectado
            throw e
        } catch (e: ObdException) {
            falharConexao(e)
        } catch (e: SecurityException) {
            falharConexao(ObdException("Permissão Bluetooth não concedida", e))
        } catch (e: IllegalArgumentException) {
            falharConexao(ObdException("Endereço do scanner inválido", e))
        } catch (e: IOException) {
            falharConexao(
                ObdException("Não foi possível conectar. Verifique se o scanner está ligado e pareado", e),
            )
        }
    }

    override fun lerDadosEmTempoReal(intervaloMs: Long, comandos: Collection<String>?): Flow<DadosObd> = flow {
        var ciclosSemResposta = 0
        while (_estado.value == EstadoConexaoObd.Conectado) {
            val dados = try {
                lerCiclo(comandos)
            } catch (e: ObdException) {
                perderConexao(e.message ?: "Conexão com o scanner perdida")
                break
            }
            if (dados == null) {
                // Todos os PIDs estouraram o timeout: o scanner parou de responder.
                if (++ciclosSemResposta >= MAX_CICLOS_SEM_RESPOSTA) {
                    perderConexao("O scanner parou de responder")
                    break
                }
            } else {
                ciclosSemResposta = 0
                emit(dados)
            }
            delay(intervaloMs)
        }
    }.flowOn(dispatcher)

    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> =
        lerDtcs("03", "ler os códigos de falha", ObdParser::parseDtcs)

    override suspend fun lerCodigosPendentes(): Result<List<DtcLido>> =
        lerDtcs("07", "ler os códigos pendentes", ObdParser::parseDtcsPendentes)

    override suspend fun lerCodigosPermanentes(): Result<List<DtcLido>> =
        lerDtcs("0A", "ler os códigos permanentes", ObdParser::parseDtcsPermanentes)

    override suspend fun lerFreezeFrame(): Result<FreezeFrame> = operacao {
        exigirConectado()
        val dtc = when (val r = ObdParser.parseDtcFreezeFrame(enviar("020200", TIMEOUT_DTC_MS))) {
            is RespostaObd.Ok -> r.valor
            RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame")
            else -> null // ECU não informa o DTC do quadro; os valores ainda podem existir
        }
        val valores = buildMap {
            for (comando in Pids.FREEZE_FRAME) {
                val definicao = catalogo[comando] ?: continue
                val bruto = enviar(ObdParser.comandoFreezeFrame(definicao))
                when (val r = ObdParser.parseFreezeFrame(bruto, definicao)) {
                    is RespostaObd.Ok -> put(definicao.comando, r.valor)
                    RespostaObd.SemConexao -> throw respostaInesperada(r, "ler o freeze frame")
                    else -> Unit // PID não congelado por esta ECU
                }
            }
        }
        FreezeFrame(dtc, valores)
    }

    override suspend fun lerVin(): Result<String> = operacao {
        exigirConectado()
        when (val r = ObdParser.parseVin(enviar("0902", TIMEOUT_DTC_MS))) {
            is RespostaObd.Ok -> r.valor
            else -> throw respostaInesperada(r, "ler o VIN")
        }
    }

    override suspend fun limparCodigosFalha(): Result<Unit> = operacao {
        exigirConectado()
        val bruto = enviar("04", TIMEOUT_DTC_MS)
        when (val r = ObdParser.parseLimpezaDtcs(bruto)) {
            is RespostaObd.Ok -> Unit
            else -> throw respostaInesperada(r, "limpar os códigos de falha")
        }
    }

    override suspend fun desconectar() {
        // NonCancellable: o fechamento do canal precisa acontecer mesmo se o chamador for cancelado
        // (ex.: ViewModel.onCleared → onDestroy definitivo da tela).
        withContext(dispatcher + NonCancellable) {
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Desconectado
        }
    }

    // ---------------------------------------------------------------- protocolo ELM327

    /** Sequência de inicialização AT, detecção dos PIDs suportados e do sufixo de resposta única. */
    private suspend fun inicializarAdaptador() {
        enviar("ATZ", TIMEOUT_RESET_MS) // reset (responde com a versão do ELM327)
        enviar("ATE0") // sem eco
        enviar("ATL0") // sem line feed
        enviar("ATSP0") // protocolo automático
        val suportados = detectarPidsSuportados()
        usaSufixoRespostaUnica = testarSufixoRespostaUnica()
        // Só o que o app sabe interpretar: PIDs do catálogo presentes no bitmask + comandos AT do adaptador.
        _pidsSuportados.value = catalogo.pids
            .filter { it.ehComandoAt || it.pid in suportados }
            .mapTo(linkedSetOf()) { it.comando }
    }

    /**
     * Lê os bitmasks `0100` → `0120` → `0140`, seguindo para a próxima faixa só se a ECU indicar
     * suporte a ela (último bit da faixa). `0100` também força a busca de protocolo e confirma
     * que a ECU está respondendo. Devolve os PIDs de dados (sem os próprios PIDs de bitmask).
     */
    private suspend fun detectarPidsSuportados(): Set<Int> {
        val pids = sortedSetOf<Int>()
        for (base in FAIXAS_BITMASK) {
            val primeira = base == FAIXAS_BITMASK.first()
            val bruto = try {
                enviar("01%02X".format(base), if (primeira) TIMEOUT_BUSCA_PROTOCOLO_MS else timeoutComandoMs)
            } catch (e: ObdTimeoutException) {
                if (primeira) throw e
                break // faixa seguinte sem resposta: fica só com o que já foi detectado
            }
            when (val r = ObdParser.parsePidsSuportados(bruto, base)) {
                is RespostaObd.Ok -> {
                    pids += r.valor
                    if (base + TAMANHO_FAIXA !in r.valor) break
                }
                RespostaObd.SemConexao, RespostaObd.SemDados -> if (primeira) {
                    throw ObdException("Scanner conectado, mas sem comunicação com a ECU. Ligue a ignição e tente novamente")
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
        if (usaSufixoRespostaUnica && !definicao.ehComandoAt) definicao.comando + SUFIXO_RESPOSTA_UNICA else definicao.comando

    /**
     * Lê uma vez os [comandos] suportados (`null` = todos). Devolve `null` se *todos* estouraram
     * o timeout (scanner mudo).
     */
    private suspend fun lerCiclo(comandos: Collection<String>?): DadosObd? {
        val suportados = _pidsSuportados.value
        val definicoes = (comandos ?: catalogo.comandos)
            .mapNotNull { catalogo[it] }
            .filter { it.comando in suportados }
            .distinct()
        if (definicoes.isEmpty()) return DadosObd()

        var timeouts = 0
        val valores = LinkedHashMap<String, Double>()
        for (definicao in definicoes) {
            try {
                lerPid(definicao)?.let { valores[definicao.comando] = it }
            } catch (e: ObdTimeoutException) {
                timeouts++
            }
        }
        return if (timeouts == definicoes.size) null else DadosObd(valores)
    }

    /** Lê um PID; `null` = indisponível neste ciclo (NO DATA, `?`, lixo ou fora da faixa). */
    private suspend fun lerPid(definicao: DefinicaoPid): Double? {
        val comando = comandoDe(definicao)
        val r = ObdParser.parsePid(enviar(comando), definicao, comando)
        if (r == RespostaObd.ComandoInvalido && comando != definicao.comando) {
            // O adaptador passou a rejeitar o sufixo: desliga e repete sem ele.
            usaSufixoRespostaUnica = false
            return lerPid(definicao)
        }
        return when (r) {
            is RespostaObd.Ok -> r.valor
            RespostaObd.SemConexao -> throw ObdException("A ECU deixou de responder")
            else -> null
        }
    }

    private suspend fun lerDtcs(
        comando: String,
        acao: String,
        interpretar: (String, String?) -> RespostaObd<List<String>>,
    ): Result<List<DtcLido>> = operacao {
        exigirConectado()
        when (val r = interpretar(enviar(comando, TIMEOUT_DTC_MS), comando)) {
            is RespostaObd.Ok -> r.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
            RespostaObd.SemDados -> emptyList() // ECU sem códigos neste modo
            else -> throw respostaInesperada(r, acao)
        }
    }

    /**
     * Envia [comando] pelo transporte e devolve a resposta até o prompt. Serializado por [mutexComandos].
     * @throws ObdTimeoutException se o prompt não chegar em [timeoutMs].
     * @throws ObdException em queda de conexão (o estado passa a `Erro`).
     */
    private suspend fun enviar(comando: String, timeoutMs: Long = timeoutComandoMs): String =
        mutexComandos.withLock {
            val canal = transporte ?: throw ObdException("Scanner não conectado")
            try {
                canal.enviarComando(comando, timeoutMs)
            } catch (e: IOException) {
                perderConexao("Conexão com o scanner perdida")
                throw ObdException("Conexão com o scanner perdida", e)
            }
        }

    // ---------------------------------------------------------------- auxiliares

    private fun exigirConectado() {
        if (_estado.value != EstadoConexaoObd.Conectado) throw ObdException("Scanner não conectado")
    }

    private fun respostaInesperada(r: RespostaObd<*>, acao: String) = when (r) {
        RespostaObd.SemConexao -> ObdException("Sem comunicação com a ECU ao $acao")
        RespostaObd.SemDados -> ObdException("A ECU não respondeu ao $acao")
        RespostaObd.ComandoInvalido -> ObdException("O scanner não suporta a operação de $acao")
        else -> ObdException("Resposta inválida do scanner ao $acao")
    }

    /** Converte falhas esperadas em `Result.failure`, deixando `CancellationException` passar. */
    private inline fun <T> operacao(bloco: () -> T): Result<T> =
        try {
            Result.success(bloco())
        } catch (e: ObdException) {
            Result.failure(e)
        }

    private fun falharConexao(erro: ObdException): Result<Unit> {
        fecharRecursos()
        _estado.value = EstadoConexaoObd.Erro(erro.message ?: "Falha na conexão")
        return Result.failure(erro)
    }

    private fun perderConexao(mensagem: String) {
        fecharRecursos()
        _estado.value = EstadoConexaoObd.Erro(mensagem)
    }

    private fun fecharRecursos() {
        transporte?.fechar()
        transporte = null
        _pidsSuportados.value = emptySet()
        usaSufixoRespostaUnica = false
    }

    companion object {
        /** Faixas de bitmask lidas na conexão: `0100` (PIDs 01–20), `0120` (21–40), `0140` (41–60). */
        private val FAIXAS_BITMASK = listOf(0x00, 0x20, 0x40)
        private const val TAMANHO_FAIXA = 0x20
        private const val SUFIXO_RESPOSTA_UNICA = "1"

        private const val MAX_CICLOS_SEM_RESPOSTA = 3
        private const val TIMEOUT_RESET_MS = 5_000L
        private const val TIMEOUT_BUSCA_PROTOCOLO_MS = 15_000L
        private const val TIMEOUT_DTC_MS = 10_000L
    }
}
