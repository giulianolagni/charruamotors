package com.charruamotors.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.charruamotors.domain.model.EstadoConexaoObd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Cliente OBD2 real para adaptadores ELM327 em Bluetooth Clássico (perfil SPP / RFCOMM).
 *
 * - Toda I/O roda em `Dispatchers.IO`; a main thread nunca é bloqueada.
 * - Um [Mutex] serializa os comandos (o ELM327 atende um comando por vez), permitindo
 *   que o polling de PIDs e a leitura de DTCs coexistam sem misturar respostas.
 * - Leituras usam polling de `available()` com timeout, então são canceláveis
 *   (pausar o polling em `onPause` realmente interrompe a leitura).
 * - Queda de conexão (`IOException`, vários timeouts seguidos) leva o [estado] a `Erro`
 *   e encerra o fluxo de leituras, sem exceções chegando à UI.
 *
 * As permissões `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` (Android 12+) devem ser pedidas pela UI
 * antes de conectar; se faltarem, a `SecurityException` é convertida em estado `Erro`.
 *
 * @param adaptador `BluetoothAdapter` do aparelho (nulo se não houver Bluetooth).
 */
@SuppressLint("MissingPermission") // SecurityException é tratada e convertida em EstadoConexaoObd.Erro
class Elm327BluetoothClient(
    private val adaptador: BluetoothAdapter?,
    private val timeoutConexaoMs: Long = 10_000,
    private val timeoutComandoMs: Long = 3_000,
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val mutexComandos = Mutex()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var entrada: InputStream? = null
    @Volatile private var saida: OutputStream? = null

    override suspend fun listarDispositivos(): List<DispositivoObd> = withContext(Dispatchers.IO) {
        try {
            adaptador?.bondedDevices.orEmpty()
                .map { DispositivoObd(it.name ?: it.address, it.address) }
                .sortedBy { it.nome }
        } catch (e: SecurityException) {
            emptyList() // sem BLUETOOTH_CONNECT: a UI deve pedir a permissão antes
        }
    }

    override suspend fun conectar(dispositivo: DispositivoObd): Result<Unit> = withContext(Dispatchers.IO) {
        val atual = _estado.value
        if (atual == EstadoConexaoObd.Conectando || atual == EstadoConexaoObd.Conectado) {
            return@withContext Result.failure(ObdException("Já existe uma conexão ativa ou em andamento"))
        }
        _estado.value = EstadoConexaoObd.Conectando
        try {
            val bt = adaptador ?: throw ObdException("Este aparelho não possui Bluetooth")
            if (!bt.isEnabled) throw ObdException("Bluetooth desligado. Ligue-o e tente novamente")

            bt.cancelDiscovery() // descoberta ativa deixa a conexão RFCOMM lenta/instável
            val novo = bt.getRemoteDevice(dispositivo.endereco).createRfcommSocketToServiceRecord(SPP_UUID)
            socket = novo
            conectarSocket(novo)
            entrada = novo.inputStream
            saida = novo.outputStream

            inicializarAdaptador()
            _estado.value = EstadoConexaoObd.Conectado
            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            falharConexao(ObdException("Tempo esgotado ao conectar com o scanner", e))
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

    override fun lerDadosEmTempoReal(intervaloMs: Long): Flow<DadosObd> = flow {
        var ciclosSemResposta = 0
        while (_estado.value == EstadoConexaoObd.Conectado) {
            val dados = try {
                lerCiclo()
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
    }.flowOn(Dispatchers.IO)

    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> = operacao {
        exigirConectado()
        val bruto = enviar("03", TIMEOUT_DTC_MS)
        when (val r = ObdParser.parseDtcs(bruto)) {
            is RespostaObd.Ok -> r.valor.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) }
            RespostaObd.SemDados -> emptyList() // ECU sem códigos armazenados
            else -> throw respostaInesperada(r, "ler os códigos de falha")
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
        // NonCancellable: o fechamento do socket precisa acontecer mesmo se o chamador for cancelado
        // (ex.: ViewModel.onCleared → onDestroy definitivo da tela).
        withContext(Dispatchers.IO + NonCancellable) {
            fecharRecursos()
            _estado.value = EstadoConexaoObd.Desconectado
        }
    }

    // ---------------------------------------------------------------- protocolo ELM327

    /** Sequência de inicialização AT e validação do link com a ECU. */
    private suspend fun inicializarAdaptador() {
        enviar("ATZ", TIMEOUT_RESET_MS) // reset (responde com a versão do ELM327)
        enviar("ATE0") // sem eco
        enviar("ATL0") // sem line feed
        enviar("ATSP0") // protocolo automático
        // 0100 força a busca de protocolo e confirma que a ECU está respondendo.
        when (ObdParser.parsePidsSuportados(enviar("0100", TIMEOUT_BUSCA_PROTOCOLO_MS))) {
            is RespostaObd.Ok -> Unit
            RespostaObd.SemConexao, RespostaObd.SemDados -> throw ObdException(
                "Scanner conectado, mas sem comunicação com a ECU. Ligue a ignição e tente novamente",
            )
            else -> throw ObdException("O dispositivo não respondeu como um adaptador ELM327")
        }
    }

    /** Lê todos os PIDs uma vez. Devolve `null` se *todos* estouraram o timeout. */
    private suspend fun lerCiclo(): DadosObd? {
        var timeouts = 0
        suspend fun <T> pid(comando: String, interpretar: (String) -> RespostaObd<T>): T? = try {
            when (val r = interpretar(enviar(comando))) {
                is RespostaObd.Ok -> r.valor
                RespostaObd.SemConexao -> throw ObdException("A ECU deixou de responder")
                else -> null // NO DATA, "?" ou lixo: PID indisponível neste ciclo
            }
        } catch (e: ObdTimeoutException) {
            timeouts++
            null
        }

        val dados = DadosObd(
            rpm = pid("010C") { ObdParser.parseRpm(it) },
            velocidade = pid("010D") { ObdParser.parseVelocidade(it) },
            temperaturaMotor = pid("0105") { ObdParser.parseTemperaturaMotor(it) },
            cargaMotor = pid("0104") { ObdParser.parseCargaMotor(it) },
            tensaoBateria = pid("ATRV") { ObdParser.parseTensao(it) },
        )
        return if (timeouts == TOTAL_PIDS) null else dados
    }

    /**
     * Envia [comando] + `\r` e lê até o prompt `>` do ELM327. Serializado por [mutexComandos].
     * @throws ObdTimeoutException se o prompt não chegar em [timeoutMs].
     * @throws ObdException em queda de conexão (o estado passa a `Erro`).
     */
    private suspend fun enviar(comando: String, timeoutMs: Long = timeoutComandoMs): String =
        mutexComandos.withLock {
            withContext(Dispatchers.IO) {
                val ent = entrada ?: throw ObdException("Scanner não conectado")
                val sai = saida ?: throw ObdException("Scanner não conectado")
                try {
                    val pendentes = ent.available()
                    if (pendentes > 0) ent.skip(pendentes.toLong()) // descarta resíduo de respostas antigas
                    sai.write("$comando\r".toByteArray(Charsets.US_ASCII))
                    sai.flush()
                    lerAtePrompt(ent, comando, timeoutMs)
                } catch (e: IOException) {
                    perderConexao("Conexão com o scanner perdida")
                    throw ObdException("Conexão com o scanner perdida", e)
                }
            }
        }

    /** Lê bytes até o prompt `>`; usa polling de `available()` para respeitar timeout e cancelamento. */
    private suspend fun lerAtePrompt(ent: InputStream, comando: String, timeoutMs: Long): String {
        val resposta = StringBuilder()
        val limite = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (ent.available() > 0) {
                val byte = ent.read()
                if (byte < 0) throw IOException("Fim do fluxo")
                if (byte.toChar() == '>') return resposta.toString()
                resposta.append(byte.toChar())
            } else {
                if (System.nanoTime() > limite) {
                    throw ObdTimeoutException("O scanner não respondeu ao comando $comando")
                }
                delay(INTERVALO_POLLING_LEITURA_MS) // cede a thread e permite cancelamento
            }
        }
    }

    // ---------------------------------------------------------------- auxiliares

    /** `connect()` é bloqueante e não responde a cancelamento: fechamos o socket para destravá-lo. */
    private suspend fun conectarSocket(s: BluetoothSocket) {
        withTimeout(timeoutConexaoMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                cont.invokeOnCancellation { runCatching { s.close() } }
                thread(isDaemon = true, name = "obd-connect") {
                    cont.resumeWith(runCatching { s.connect() })
                }
            }
        }
    }

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
        runCatching { entrada?.close() }
        runCatching { saida?.close() }
        runCatching { socket?.close() }
        entrada = null
        saida = null
        socket = null
    }

    companion object {
        /** UUID do perfil Serial Port (SPP), usado pelos adaptadores ELM327 Bluetooth Clássico. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val TOTAL_PIDS = 5
        private const val MAX_CICLOS_SEM_RESPOSTA = 3
        private const val TIMEOUT_RESET_MS = 5_000L
        private const val TIMEOUT_BUSCA_PROTOCOLO_MS = 15_000L
        private const val TIMEOUT_DTC_MS = 10_000L
        private const val INTERVALO_POLLING_LEITURA_MS = 15L
    }
}
