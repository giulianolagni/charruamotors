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

    /** Na tela de Erros, avisa que os modos 07/0A dependem de OBD2 padrão (só o KWP, Modo 21). */
    val avisaModos07e0A: Boolean get() = this == MODO_21

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
    /**
     * v2.3.0: o adaptador como ele se identificou no ATZ/ATI (`ELM327 v2.1`), publicado logo
     * depois da identificação (ainda conectando à ECU); null = ainda não respondeu nesta conexão.
     */
    val adaptador: String? = null,
) {
    companion object {
        const val NOME_OBD2_PADRAO = "OBD2 Padrão"
    }
}

/**
 * Protocolo ativo informado pelo `ATDPN` (v2.3.1): `A6` = automático que achou o 6. Os números 6 a 9
 * são o CAN ISO 15765-4, mesmo quando o `ATDP` de um clone v2.1 devolve só `AUTO, `.
 */
data class ProtocoloAtivo(val numero: Int, val automatico: Boolean) {
    val ehCan: Boolean get() = numero in NOMES_CAN

    /** `ATSP6`..`ATSP9`: fixa o protocolo no adaptador. */
    val comandoFixar: String get() = "ATSP%X".format(numero)

    /** Cabeçalho funcional para o `ATSH`: `7DF` no CAN de 11 bits; `DB33F1` no de 29 bits (7 e 9). */
    val cabecalhoFuncional: String get() = if (numero == 7 || numero == 9) "DB33F1" else "7DF"

    /**
     * Descrição para o card e o relatório: a do `ATDP` quando ela já nomeia o protocolo (CAN ou
     * linha K); senão o nome completo (`AUTO, ISO 15765-4 (CAN 11/500)`). Números sem nome
     * mapeado ficam com a do `ATDP`.
     */
    fun descrever(atdp: String?): String? {
        val nome = NOMES_CAN[numero] ?: NOMES_LINHA_K[numero] ?: return atdp
        val d = atdp?.uppercase().orEmpty()
        val marcas = if (ehCan) MARCAS_CAN else MARCAS_LINHA_K
        if (marcas.any { it in d }) return atdp
        return (if (automatico) "AUTO, " else "") + nome
    }

    private companion object {
        val NOMES_CAN = mapOf(
            6 to "ISO 15765-4 (CAN 11/500)",
            7 to "ISO 15765-4 (CAN 29/500)",
            8 to "ISO 15765-4 (CAN 11/250)",
            9 to "ISO 15765-4 (CAN 29/250)",
        )
        val NOMES_LINHA_K = mapOf(
            3 to "ISO 9141-2",
            4 to "ISO 14230-4 (KWP 5-baud)",
            5 to "ISO 14230-4 (KWP Fast Init)",
        )
        val MARCAS_CAN = listOf("CAN", "15765")
        val MARCAS_LINHA_K = listOf("9141", "14230", "KWP")
    }
}

/**
 * Troca manual com o adaptador (Ferramentas de Oficina: terminal, varredura, teste de bateria):
 * o [comando] como foi enviado, a [resposta] bruta até o prompt `>` (sem ele) e a [latenciaMs]
 * medida em volta da troca. [semPrompt] = o adaptador não concluiu no prazo ([resposta] vazia).
 */
data class RespostaBruta(
    val comando: String,
    val resposta: String,
    val latenciaMs: Long,
    val semPrompt: Boolean = false,
)

/** Falha de comunicação com o scanner/ECU (timeout, conexão perdida, resposta inválida...). */
open class ObdException(mensagem: String, causa: Throwable? = null) : Exception(mensagem, causa)

/** O scanner não respondeu dentro do tempo limite (a conexão em si pode continuar válida). */
class ObdTimeoutException(mensagem: String) : ObdException(mensagem)

/** Modo de operação do app: scanner simulado (emulador/apresentação) ou Bluetooth real. */
enum class ModoObd { SIMULADO, BLUETOOTH }
