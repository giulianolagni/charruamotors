package com.charruamotors.obd

import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.StatusCodigoFalha
import java.time.LocalDateTime

/** Scanner Bluetooth pareado (ou simulado) que pode ser escolhido para conexão. */
data class DispositivoObd(val nome: String, val endereco: String)

/**
 * Instantâneo dos PIDs lidos em um ciclo de polling.
 * Campos nulos = PID não suportado ou sem resposta (`NO DATA`).
 */
data class DadosObd(
    val rpm: Int? = null,
    val velocidade: Int? = null,
    val temperaturaMotor: Int? = null,
    val cargaMotor: Double? = null,
    val tensaoBateria: Double? = null,
) {
    /** Converte para a entidade persistida no histórico do veículo. */
    fun paraLeitura(veiculoId: Long, dataHora: LocalDateTime = LocalDateTime.now()) = LeituraObd(
        veiculoId = veiculoId,
        dataHora = dataHora,
        rpm = rpm,
        velocidade = velocidade,
        temperaturaMotor = temperaturaMotor,
        cargaMotor = cargaMotor,
        tensaoBateria = tensaoBateria,
    )
}

/** Código de falha (DTC) lido da ECU, já com descrição em português. */
data class DtcLido(val codigo: String, val descricao: String) {
    fun paraCodigoFalha(veiculoId: Long, dataHora: LocalDateTime = LocalDateTime.now()) = CodigoFalha(
        veiculoId = veiculoId,
        dataHora = dataHora,
        codigo = codigo,
        descricao = descricao,
        status = StatusCodigoFalha.ATIVO,
    )
}

/** Falha de comunicação com o scanner/ECU (timeout, conexão perdida, resposta inválida...). */
open class ObdException(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/** O scanner não respondeu dentro do tempo limite (a conexão em si pode continuar válida). */
class ObdTimeoutException(mensagem: String) : ObdException(mensagem)

/** Modo de operação do app: scanner simulado (emulador/apresentação) ou Bluetooth real. */
enum class ModoObd { SIMULADO, BLUETOOTH }
