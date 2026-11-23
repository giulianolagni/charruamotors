package com.charruamotors.oficina.atuadores

/**
 * Código de resposta negativa (NRC) da ISO 14229 (UDS), devolvido como `7F <serviço> <NRC>`, com a
 * explicação técnica exibida na tela quando a central recusa um comando.
 */
enum class Nrc(val codigo: Int, val nome: String, val explicacao: String) {
    GENERAL_REJECT(0x10, "generalReject", "A central recusou o pedido sem informar o motivo."),
    SERVICO_NAO_SUPORTADO(
        0x11, "serviceNotSupported",
        "A central não implementa este serviço (2F inputOutputControlByIdentifier). Algumas montadoras usam outro serviço " +
            "para atuadores (Toyota KWP: 30; GM Global A: AE) — use o terminal ou a ferramenta da montadora.",
    ),
    SUBFUNCAO_NAO_SUPORTADA(0x12, "subFunctionNotSupported", "A central não aceita este subparâmetro (ex.: a sessão 10 03)."),
    FORMATO_INVALIDO(0x13, "incorrectMessageLengthOrInvalidFormat", "Tamanho ou formato do pedido incorreto para este DID."),
    RESPOSTA_LONGA(0x14, "responseTooLong", "A resposta não cabe no quadro de transporte."),
    OCUPADA(0x21, "busyRepeatRequest", "A central está ocupada; tente de novo em instantes."),
    CONDICOES_INCORRETAS(
        0x22, "conditionsNotCorrect",
        "Condições do veículo não permitem o teste (motor ligado, velocidade, tensão ou temperatura fora do exigido).",
    ),
    SEQUENCIA_INCORRETA(0x24, "requestSequenceError", "Fora de sequência: a central exige outro passo antes (ex.: sessão estendida)."),
    FORA_DA_FAIXA(
        0x31, "requestOutOfRange",
        "DID ou valor de controle fora da faixa: esta central não reconhece o identificador enviado. Confira o DID do atuador.",
    ),
    SEGURANCA_NEGADA(
        0x33, "securityAccessDenied",
        "Acesso bloqueado por Seed/Key (serviço 27): a central exige desbloqueio de segurança com o algoritmo da montadora " +
            "antes de liberar o atuador. O app não quebra a proteção; use a ferramenta oficial (GDS2/Tech2).",
    ),
    CHAVE_INVALIDA(0x35, "invalidKey", "A chave do Seed/Key enviada não confere."),
    TENTATIVAS_EXCEDIDAS(0x36, "exceededNumberOfAttempts", "Tentativas de Seed/Key esgotadas; aguarde o tempo de bloqueio."),
    ESPERA_NAO_CUMPRIDA(0x37, "requiredTimeDelayNotExpired", "A central ainda está no tempo de espera do Seed/Key."),
    RESPOSTA_PENDENTE(0x78, "requestCorrectlyReceived-ResponsePending", "A central recebeu e ainda está processando."),
    SUBFUNCAO_FORA_DA_SESSAO(0x7E, "subFunctionNotSupportedInActiveSession", "Subfunção indisponível na sessão atual."),
    SERVICO_FORA_DA_SESSAO(
        0x7F, "serviceNotSupportedInActiveSession",
        "O serviço só funciona na sessão estendida: a central não aceitou (ou perdeu) a sessão 10 03.",
    ),
    RPM_ALTO(0x81, "rpmTooHigh", "RPM alto demais para o teste."),
    RPM_BAIXO(0x82, "rpmTooLow", "RPM baixo demais para o teste."),
    MOTOR_LIGADO(0x83, "engineIsRunning", "A central exige o motor desligado para este atuador."),
    MOTOR_DESLIGADO(0x84, "engineIsNotRunning", "A central exige o motor funcionando para este atuador."),
    VELOCIDADE_ALTA(0x88, "vehicleSpeedTooHigh", "O veículo precisa estar parado."),
    TENSAO_ALTA(0x92, "voltageTooHigh", "Tensão da bateria alta demais."),
    TENSAO_BAIXA(0x93, "voltageTooLow", "Tensão da bateria baixa demais para o teste."),
    ;

    /** `7F 2F 33 · securityAccessDenied`. */
    fun rotulo(servico: Int): String = "7F %02X %02X · %s".format(servico, codigo, nome)

    companion object {
        fun de(codigo: Int): Nrc? = entries.firstOrNull { it.codigo == codigo }

        /** Texto para um NRC qualquer, inclusive os que o app não conhece. */
        fun descrever(codigo: Int): String = de(codigo)?.let { "${it.nome}: ${it.explicacao}" }
            ?: "NRC 0x%02X (específico da montadora / não documentado).".format(codigo)
    }
}

/** Resposta de um pedido UDS, já interpretada a partir do texto bruto do ELM327. */
sealed interface RespostaUds {
    /** Resposta positiva (`serviço + 0x40` ...): [bytes] desde o byte do serviço. */
    data class Positiva(val bytes: List<Int>) : RespostaUds

    /** `7F <serviço> <NRC>`. */
    data class Negativa(val servico: Int, val nrc: Int) : RespostaUds {
        val conhecido: Nrc? get() = Nrc.de(nrc)
        val rotulo: String get() = "7F %02X %02X".format(servico, nrc) + (conhecido?.let { " · ${it.nome}" } ?: "")
        val explicacao: String get() = Nrc.descrever(nrc)
    }

    /** `NO DATA`, `CAN ERROR`, prazo esgotado: ninguém respondeu no endereço. */
    data class SemResposta(val motivo: String) : RespostaUds

    /** Texto que não é uma resposta UDS (`?`, lixo de linha). */
    data class Invalida(val bruto: String) : RespostaUds
}

/** Interpretação pura das respostas do ELM327 a pedidos UDS (com `ATS0`/`ATH0` ou com cabeçalhos). */
object InterpretadorUds {

    private val MARCAS_SEM_RESPOSTA = listOf("NODATA", "CANERROR", "BUSERROR", "UNABLETOCONNECT", "STOPPED", "BUFFERFULL", "ERROR")
    private val REGEX_HEX = Regex("^[0-9A-F]+$")
    private val REGEX_QUADRO = Regex("^[0-9A-F]:")

    /**
     * Interpreta [bruto] como resposta ao [servico] pedido (ex.: `0x2F`). Respostas "pendente"
     * (`7F xx 78`) são ignoradas em favor da final; sem nenhuma, a última negativa vale.
     * [semPrompt] = o adaptador não concluiu no prazo.
     */
    fun interpretar(bruto: String, servico: Int, semPrompt: Boolean = false): RespostaUds {
        if (semPrompt && bruto.isBlank()) return RespostaUds.SemResposta("sem resposta no prazo")
        val linhas = bruto.uppercase().split('\r', '\n')
            .map { l -> l.filter { !it.isWhitespace() && it != '>' } }
            .filter { it.isNotEmpty() && it != "SEARCHING..." && !it.startsWith("SEARCHING") }
        if (linhas.isEmpty()) return RespostaUds.SemResposta("resposta vazia")
        MARCAS_SEM_RESPOSTA.firstOrNull { m -> linhas.any { m in it } }?.let { return RespostaUds.SemResposta(it) }

        val hex = juntarQuadros(linhas)
        if (hex.isEmpty()) return RespostaUds.Invalida(bruto.trim())
        val positivo = "%02X".format(servico + 0x40)
        val negativo = "7F%02X".format(servico)
        var ultimaNegativa: RespostaUds.Negativa? = null
        for (h in hex) {
            val dados = semCabecalho(h)
            when {
                dados.startsWith(negativo) && dados.length >= 6 -> {
                    val nrc = dados.substring(4, 6).toInt(16)
                    if (nrc != Nrc.RESPOSTA_PENDENTE.codigo) ultimaNegativa = RespostaUds.Negativa(servico, nrc)
                }
                dados.startsWith(positivo) -> return RespostaUds.Positiva(dados.chunked(2).filter { it.length == 2 }.map { it.toInt(16) })
            }
        }
        return ultimaNegativa ?: if (hex.any { semCabecalho(it).startsWith(negativo) }) {
            RespostaUds.SemResposta("a central só respondeu \"pendente\" (7F %02X 78)".format(servico))
        } else {
            RespostaUds.Invalida(bruto.trim())
        }
    }

    /** Linhas hexadecimais; os quadros multi-frame do ELM327 (`0:` `1:`...) viram uma só, sem o tamanho. */
    private fun juntarQuadros(linhas: List<String>): List<String> {
        if (linhas.any { REGEX_QUADRO.containsMatchIn(it) }) {
            val juntos = linhas.filter { REGEX_QUADRO.containsMatchIn(it) }.joinToString("") { it.substring(2) }
            return listOf(juntos).filter { REGEX_HEX.matches(it) }
        }
        return linhas.filter { REGEX_HEX.matches(it) }
    }

    /** Remove o cabeçalho CAN (`7E8`) e o byte PCI quando o adaptador está com `ATH1`. */
    private fun semCabecalho(linha: String): String {
        if (linha.length > 5 && linha.startsWith("7E") && linha[2] in '8'..'F') {
            val pci = linha.substring(3, 5).toIntOrNull(16)
            if (pci != null && pci in 1..7) return linha.substring(5)
        }
        return linha
    }
}
