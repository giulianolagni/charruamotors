package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
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

    /** Scanners disponíveis para escolha (pareados, no caso do Bluetooth real). */
    suspend fun listarDispositivos(): List<DispositivoObd>

    /** Abre a conexão e inicializa o adaptador. Atualiza [estado] (Conectando → Conectado/Erro). */
    suspend fun conectar(dispositivo: DispositivoObd): Result<Unit>

    /**
     * Fluxo frio de leituras em tempo real (RPM, velocidade, temperatura, carga, tensão),
     * emitindo um [DadosObd] a cada [intervaloMs]. Termina se a conexão for perdida.
     * Cancelar a coleta (ex.: `onPause`/`onStop`) interrompe o polling.
     */
    fun lerDadosEmTempoReal(intervaloMs: Long = INTERVALO_PADRAO_MS): Flow<DadosObd>

    /** Lê os códigos de falha armazenados (modo 03). Lista vazia = nenhum código. */
    suspend fun lerCodigosFalha(): Result<List<DtcLido>>

    /** Limpa os códigos de falha (modo 04). A UI deve sempre pedir confirmação antes. */
    suspend fun limparCodigosFalha(): Result<Unit>

    /** Fecha a conexão e libera recursos. Seguro chamar mais de uma vez. */
    suspend fun desconectar()

    companion object {
        const val INTERVALO_PADRAO_MS = 1000L
    }
}
