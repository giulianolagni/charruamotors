package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrato de comunicação com o scanner OBD2. A UI/ViewModel depende apenas desta interface,
 * então pode usar o [ObdClientFake] (emulador/apresentação) ou o `Elm327BluetoothClient` (scanner real).
 *
 * Operações que podem falhar devolvem [Result] com [ObdException]; elas nunca lançam
 * na main thread e a UI nunca é bloqueada (toda I/O roda fora dela).
 */
interface ObdClient {

    /** Estado atual da conexão; a UI observa para habilitar/desabilitar ações. */
    val estado: StateFlow<EstadoConexaoObd>

    /**
     * Comandos do catálogo que o veículo conectado suporta (ex.: `010C`, `015C`, `ATRV`),
     * detectados na conexão pelos bitmasks `0100`/`0120`/`0140`. Vazio enquanto desconectado.
     */
    val pidsSuportados: StateFlow<Set<String>>

    /** Scanners disponíveis para escolha (pareados, no caso do Bluetooth real). */
    suspend fun listarDispositivos(): List<DispositivoObd>

    /** Abre a conexão e inicializa o adaptador. Atualiza [estado] (Conectando → Conectado/Erro). */
    suspend fun conectar(dispositivo: DispositivoObd): Result<Unit>

    /**
     * Fluxo frio de leituras em tempo real, emitindo um [DadosObd] a cada [intervaloMs] com os
     * [comandos] pedidos que o veículo suporta (`null` = todos os suportados). Menos comandos =
     * ciclos mais rápidos no ELM327. Termina se a conexão for perdida.
     * Cancelar a coleta (ex.: `onPause`/`onStop`) interrompe o polling.
     */
    fun lerDadosEmTempoReal(
        intervaloMs: Long = INTERVALO_PADRAO_MS,
        comandos: Collection<String>? = Pids.PAINEL,
    ): Flow<DadosObd>

    /** Lê os códigos de falha armazenados/confirmados (modo 03). Lista vazia = nenhum código. */
    suspend fun lerCodigosFalha(): Result<List<DtcLido>>

    /** Lê os códigos pendentes, ainda não confirmados pela ECU (modo 07). */
    suspend fun lerCodigosPendentes(): Result<List<DtcLido>>

    /** Lê os códigos permanentes, que o modo 04 não apaga (modo 0A). */
    suspend fun lerCodigosPermanentes(): Result<List<DtcLido>>

    /** Lê o Freeze Frame (modo 02): RPM, velocidade, temperatura e carga no momento da falha. */
    suspend fun lerFreezeFrame(): Result<FreezeFrame>

    /** Lê o VIN / número do chassi (modo 09, PID 02). */
    suspend fun lerVin(): Result<String>

    /** Limpa os códigos de falha (modo 04). A UI deve sempre pedir confirmação antes. */
    suspend fun limparCodigosFalha(): Result<Unit>

    /** Fecha a conexão e libera recursos. Seguro chamar mais de uma vez. */
    suspend fun desconectar()

    companion object {
        const val INTERVALO_PADRAO_MS = 1000L
    }
}
