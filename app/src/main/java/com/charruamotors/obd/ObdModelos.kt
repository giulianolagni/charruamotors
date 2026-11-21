package com.charruamotors.obd

import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.StatusCodigoFalha
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Scanner Bluetooth pareado (ou simulado) que pode ser escolhido para conexão. */
data class DispositivoObd(val nome: String, val endereco: String)

/**
 * Conjunto de valores de sensores indexados pelo comando do catálogo (`010C`, `ATRV`...),
 * já convertidos para a unidade da [com.charruamotors.obd.pid.DefinicaoPid].
 * Ausência de chave = PID não suportado ou sem resposta (`NO DATA`).
 */
interface ValoresSensores {
    val valores: Map<String, Double>

    operator fun get(comando: String): Double? = valores[comando]

    val rpm: Int? get() = valores[Pids.RPM]?.roundToInt()
    val velocidade: Int? get() = valores[Pids.VELOCIDADE]?.roundToInt()
    val temperaturaMotor: Int? get() = valores[Pids.TEMPERATURA_MOTOR]?.roundToInt()
    val cargaMotor: Double? get() = valores[Pids.CARGA_MOTOR]
    val tensaoBateria: Double? get() = valores[Pids.TENSAO_BATERIA]
}

/** Instantâneo dos sensores lidos em um ciclo de polling (qualquer PID do catálogo). */
data class DadosObd(override val valores: Map<String, Double> = emptyMap()) : ValoresSensores {
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

/**
 * Freeze Frame (modo 02, quadro 0): valores congelados pela ECU no instante em que a falha
 * [dtc] foi registrada. As chaves de [valores] são os comandos do modo 01 (`010C`...).
 */
data class FreezeFrame(
    /** DTC que disparou o congelamento (PID 02); null se a ECU não informou. */
    val dtc: String? = null,
    override val valores: Map<String, Double> = emptyMap(),
) : ValoresSensores {
    /** Nenhum quadro armazenado (ECU sem falhas ou memória limpa). */
    val vazio: Boolean get() = dtc == null && valores.isEmpty()
}

/** Resultado da ativação do perfil de PIDs de montadora (modos 21/22). */
sealed interface ResultadoPerfil {
    /** A marca do veículo não tem perfil no catálogo estendido (ou não foi informada). */
    data object SemPerfil : ResultadoPerfil

    /** Há perfil, mas não deu para usá-lo: protocolo não CAN, ECU sem resposta, desconectado... */
    data class Indisponivel(val fabricante: String, val motivo: String) : ResultadoPerfil

    /** PIDs do perfil que a ECU respondeu, já incluídos nos suportados. */
    data class Ativo(val fabricante: String, val pids: List<DefinicaoPid>, val observacao: String = "") : ResultadoPerfil
}

/**
 * Como o app conversa com a ECU nesta conexão — a camada da cascata que foi ativada
 * (ver `Elm327BluetoothClient` e `assets/cascata_conexao.json`).
 */
enum class ModoComunicacaoEcu(val servico: String) {
    /** Camadas 1/2: OBD2 genérico (SAE J1979), modo 01 pelo cabeçalho funcional. */
    OBD2_PADRAO("01"),

    /**
     * Camada 3, tentativa A: KWP2000 *readDataByLocalIdentifier* (serviço 21) endereçado ao motor
     * (`ATSH 7E0`) — Toyota M-OBD (Hilux/SW4 D-4D Denso, que recusa o modo 01 com `7F 01 11`),
     * Mitsubishi, Suzuki e centrais KWP.
     */
    MODO_21("21"),

    /** Camada 3, tentativa B: UDS *readDataByIdentifier* (serviço 22, DIDs `F4xx`) — VW/Audi, Fiat/Jeep, Ford, GM. */
    MODO_22("22"),
    ;

    companion object {
        /** Serviço do catálogo (`"21"`, `"22"`) → modo; null se o app não souber falar esse serviço. */
        fun doServico(servico: String): ModoComunicacaoEcu? = entries.firstOrNull { it.servico == servico.uppercase() }
    }
}

/** Detalhes da conexão atual para o card da ECU. */
data class InfoConexao(
    /** Protocolo informado pelo `ATDP` (ex.: `AUTO, ISO 15765-4 (CAN 11/500)`); null = não lido. */
    val protocolo: String? = null,
    val modo: ModoComunicacaoEcu = ModoComunicacaoEcu.OBD2_PADRAO,
    /** Nome da camada para o card (`OBD2 Padrão`, `Modo 21 (M-OBD / KWP)`, `Modo 22 (UDS)`). */
    val nomeModo: String = NOME_OBD2_PADRAO,
    /** Protocolo físico forçado pela camada 2 (ex.: `ATSP6 · ISO 15765-4 CAN 11 bits / 500 kbps`); null = o ATSP0 resolveu. */
    val protocoloForcado: String? = null,
    /** Quantos sensores do catálogo responderam na conexão (bitmask no padrão, sondagem nos modos 21/22). */
    val sensoresResponderam: Int = 0,
    /** Comandos do serviço da montadora que responderam positivamente (`210C`, `2105`...); vazio no padrão. */
    val comandosServico: List<String> = emptyList(),
    /** Espelhos ativos nos modos 21/22, já com o comando canônico do sensor (`010C`...) e o rótulo real (`7E0 · 210C`). */
    val espelhos: List<DefinicaoPid> = emptyList(),
) {
    companion object {
        const val NOME_OBD2_PADRAO = "OBD2 Padrão"
    }
}

/** Falha de comunicação com o scanner/ECU (timeout, conexão perdida, resposta inválida...). */
open class ObdException(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/** O scanner não respondeu dentro do tempo limite (a conexão em si pode continuar válida). */
class ObdTimeoutException(mensagem: String) : ObdException(mensagem)

/** Modo de operação do app: scanner simulado (emulador/apresentação) ou Bluetooth real. */
enum class ModoObd { SIMULADO, BLUETOOTH }
