package com.charruamotors.oficina.atuadores

import com.charruamotors.domain.faixas.PresetVeiculo

/** Seções da lista de atuadores na tela. */
enum class GrupoAtuador(val titulo: String) {
    COMBUSTIVEL("Combustível"),
    ARREFECIMENTO("Arrefecimento e A/C"),
    EMISSOES("Emissões (EVAP)"),
    INJECAO("Bicos injetores"),
    IGNICAO("Bobinas de ignição"),
}

/**
 * Atuador testável pela ferramenta. [critico] = mexe com combustível, ignição ou compressor: só é
 * liberado com o motor parado (RPM = 0). [observar] é a dica didática exibida durante o teste.
 */
enum class Atuador(
    val titulo: String,
    val grupo: GrupoAtuador,
    val critico: Boolean,
    val duracaoMs: Long,
    val observar: String,
) {
    BOMBA_COMBUSTIVEL(
        "Bomba de Combustível", GrupoAtuador.COMBUSTIVEL, true, 3_000,
        "Ignição ligada e motor desligado: ouça o zumbido da bomba no tanque por ~3 s e a pressão da linha subir.",
    ),
    ELETROVENTILADOR_BAIXA(
        "Eletroventilador — Baixa", GrupoAtuador.ARREFECIMENTO, false, 5_000,
        "A ventoinha do radiador gira na velocidade baixa (resistor/PWM reduzido).",
    ),
    ELETROVENTILADOR_ALTA(
        "Eletroventilador — Alta", GrupoAtuador.ARREFECIMENTO, false, 5_000,
        "A ventoinha do radiador gira na velocidade máxima: mantenha mãos e ferramentas longe.",
    ),
    EMBREAGEM_AC(
        "Embreagem do A/C", GrupoAtuador.ARREFECIMENTO, true, 3_000,
        "Clique da embreagem eletromagnética do compressor ao acoplar e ao soltar.",
    ),
    CANISTER_EVAP(
        "Válvula do Cânister (EVAP)", GrupoAtuador.EMISSOES, false, 4_000,
        "Cliques rápidos e regulares da válvula de purga do cânister (sinal PWM).",
    ),
    BICO_1("Pulso do Bico Injetor #1", GrupoAtuador.INJECAO, true, 1_000, BICO_DICA),
    BICO_2("Pulso do Bico Injetor #2", GrupoAtuador.INJECAO, true, 1_000, BICO_DICA),
    BICO_3("Pulso do Bico Injetor #3", GrupoAtuador.INJECAO, true, 1_000, BICO_DICA),
    BICO_4("Pulso do Bico Injetor #4", GrupoAtuador.INJECAO, true, 1_000, BICO_DICA),
    BOBINA_1("Pulso da Bobina de Ignição #1", GrupoAtuador.IGNICAO, true, 1_000, BOBINA_DICA),
    BOBINA_2("Pulso da Bobina de Ignição #2", GrupoAtuador.IGNICAO, true, 1_000, BOBINA_DICA),
    BOBINA_3("Pulso da Bobina de Ignição #3", GrupoAtuador.IGNICAO, true, 1_000, BOBINA_DICA),
    BOBINA_4("Pulso da Bobina de Ignição #4", GrupoAtuador.IGNICAO, true, 1_000, BOBINA_DICA),
}

private const val BICO_DICA = "Motor desligado: com um estetoscópio no bico, ouça os cliques do pulso. Bico mudo = bobina do bico, chicote ou driver da ECU."
private const val BOBINA_DICA = "Motor desligado: com centelhador/caneta de faísca, confira o pulso. Sem faísca = bobina, chicote ou driver da ECU."

/**
 * Comando UDS `2F` (inputOutputControlByIdentifier) de um atuador: `2F <DID> 03 <estado>`
 * (shortTermAdjustment) para ativar e `2F <DID> 00` (returnControlToECU) para devolver o controle.
 */
data class ComandoAtuador(
    val cabecalho: String,
    val did: String,
    /** Bytes do estado de controle (`01` = ligado, `02` = velocidade alta, `80` = 50 % PWM). */
    val estado: String = "01",
    val parametroControle: String = CONTROLE_AJUSTE_CURTO_PRAZO,
) {
    init {
        require(Regex("^[0-7][0-9A-F]{2}$").matches(cabecalho)) { "Cabeçalho CAN inválido: $cabecalho" }
        require(Regex("^[0-9A-F]{4}$").matches(did)) { "DID inválido: $did (4 dígitos hexadecimais)" }
        require(Regex("^([0-9A-F]{2})*$").matches(estado) && estado.length <= 8) { "Estado de controle inválido: $estado" }
        require(Regex("^[0-9A-F]{2}$").matches(parametroControle)) { "Parâmetro de controle inválido: $parametroControle" }
    }

    val ativar: String get() = "2F$did$parametroControle$estado"
    val devolver: String get() = "2F${did}$CONTROLE_DEVOLVER_ECU"

    companion object {
        const val CONTROLE_DEVOLVER_ECU = "00"
        const val CONTROLE_AJUSTE_CURTO_PRAZO = "03"

        /**
         * Comando customizado digitado na tela: `2F 0101 03 01`, `2F01010301` ou só `0101 03 01`.
         * Null se não for um pedido 2F válido (DID de 2 bytes + parâmetro de controle).
         */
        fun customizado(texto: String, cabecalho: String): ComandoAtuador? {
            val hex = texto.uppercase().filter { !it.isWhitespace() }.removePrefix("2F")
            if (!Regex("^[0-9A-F]+$").matches(hex) || hex.length % 2 != 0 || hex.length < 6) return null
            return runCatching {
                ComandoAtuador(
                    cabecalho = cabecalho.uppercase().trim(),
                    did = hex.substring(0, 4),
                    parametroControle = hex.substring(4, 6),
                    estado = hex.substring(6),
                )
            }.getOrNull()
        }
    }
}

/**
 * Tabela de DIDs de atuadores de uma plataforma. [observacao] é exibida na tela: os endereços
 * mudam por central e software e precisam ser confirmados com a ferramenta da montadora.
 */
class MapeamentoAtuadores(
    val nome: String,
    val cabecalhoPadrao: String,
    val observacao: String,
    val comandos: Map<Atuador, ComandoAtuador>,
) {
    operator fun get(atuador: Atuador): ComandoAtuador? = comandos[atuador]

    companion object {
        /**
         * Chevrolet / GM — Cruze 1.4 Turbo (LE2), ECM `7E0`. Tabela **de referência didática**: os
         * DIDs seguem uma numeração ilustrativa (01xx) e não foram confirmados em uma central real;
         * a ECU responde `7F 2F 31` se não reconhecer o identificador. Ajuste pelo comando customizado.
         */
        val GM_CRUZE = MapeamentoAtuadores(
            nome = "Chevrolet / GM — Cruze 1.4T (LE2)",
            cabecalhoPadrao = "7E0",
            observacao = "DIDs de referência didática (numeração ilustrativa 01xx, não confirmada em central real). " +
                "Confirme os identificadores no GDS2/Tech2 e use o comando customizado para a sua central.",
            comandos = mapOf(
                Atuador.BOMBA_COMBUSTIVEL to ComandoAtuador("7E0", "0101"),
                Atuador.ELETROVENTILADOR_BAIXA to ComandoAtuador("7E0", "0102", estado = "01"),
                Atuador.ELETROVENTILADOR_ALTA to ComandoAtuador("7E0", "0102", estado = "02"),
                Atuador.EMBREAGEM_AC to ComandoAtuador("7E0", "0103"),
                Atuador.CANISTER_EVAP to ComandoAtuador("7E0", "0104", estado = "80"),
                Atuador.BICO_1 to ComandoAtuador("7E0", "0111"),
                Atuador.BICO_2 to ComandoAtuador("7E0", "0112"),
                Atuador.BICO_3 to ComandoAtuador("7E0", "0113"),
                Atuador.BICO_4 to ComandoAtuador("7E0", "0114"),
                Atuador.BOBINA_1 to ComandoAtuador("7E0", "0121"),
                Atuador.BOBINA_2 to ComandoAtuador("7E0", "0122"),
                Atuador.BOBINA_3 to ComandoAtuador("7E0", "0123"),
                Atuador.BOBINA_4 to ComandoAtuador("7E0", "0124"),
            ),
        )

        /**
         * Sem tabela da montadora (Toyota SW4 e demais): o mesmo envio UDS 2F nos DIDs de referência,
         * para a central responder o que suporta — a SW4 1KD-FTV (KWP2000) devolve `7F 2F 11`.
         */
        val GENERICO = MapeamentoAtuadores(
            nome = "Genérico UDS (ISO 14229)",
            cabecalhoPadrao = "7E0",
            observacao = "Sem tabela da montadora: os DIDs de referência são enviados e a central informa o que suporta. " +
                "Centrais KWP2000 (ex.: Toyota 1KD-FTV) respondem 7F 2F 11 (serviço não suportado).",
            comandos = GM_CRUZE.comandos,
        )

        /** Tabela pela marca do veículo (GM/Chevrolet → Cruze; demais → genérico). */
        fun paraMarca(marca: String?): MapeamentoAtuadores = if (PresetVeiculo.marcaGm(marca)) GM_CRUZE else GENERICO
    }
}
