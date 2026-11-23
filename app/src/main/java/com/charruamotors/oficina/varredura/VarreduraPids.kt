package com.charruamotors.oficina.varredura

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaBruta
import com.charruamotors.obd.pid.RequisicaoEstendida
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Configuração de uma varredura: cabeçalho CAN, serviço e faixa de identificadores. */
data class ConfigVarredura(val cabecalho: String, val servico: String, val inicio: Int, val fim: Int) {
    /** Dígitos hex do identificador: 4 (DID de 2 bytes) no serviço 22, 2 nos demais. */
    val digitos: Int get() = if (servico == "22") 4 else 2

    val quantidade: Int get() = fim - inicio + 1

    /** "7E0 · 2100–21FF". */
    val descricao: String get() = "$cabecalho · $servico${hex(inicio)}–$servico${hex(fim)}"

    fun hex(id: Int): String = "%0${digitos}X".format(id)
}

/**
 * Preset de varredura para os módulos mais comuns. Com mais de uma configuração em [modulos]
 * (ex.: Varredura Completa SW4), os cabeçalhos são testados em sequência num relatório único.
 */
data class PresetVarredura(val nome: String, val modulos: List<ConfigVarredura>) {
    constructor(nome: String, config: ConfigVarredura) : this(nome, listOf(config))

    init {
        require(modulos.isNotEmpty()) { "$nome: preset sem módulo" }
    }

    /** Primeira (ou única) configuração: a que preenche os campos da tela. */
    val config: ConfigVarredura get() = modulos.first()

    val multiModulo: Boolean get() = modulos.size > 1
}

/** Classificação da resposta a um identificador. */
enum class TipoRespostaPid { POSITIVA, NEGATIVA, SEM_DADOS, ERRO }

/**
 * Resultado de um identificador: [bytes] da resposta positiva (`61 01 ...`), o [nrc] da negativa
 * (`7F 21 31` → `31`) ou o [erro] do ELM327 (`?`, `CAN ERROR`, `TIMEOUT`...).
 */
data class ResultadoPid(
    val comando: String,
    val tipo: TipoRespostaPid,
    val bytes: List<Int> = emptyList(),
    val nrc: String? = null,
    val erro: String? = null,
    val bruto: String = "",
    val latenciaMs: Long = 0,
) {
    val respondeu: Boolean get() = tipo == TipoRespostaPid.POSITIVA

    /** "61 01 00 A3 B2" */
    val bytesHex: String get() = bytes.joinToString(" ") { "%02X".format(it) }

    /** Bytes de dados depois do eco (serviço + identificador): o que interessa para mapear o sensor. */
    fun dados(digitosId: Int): List<Int> = bytes.drop(1 + digitosId / 2)
}

/** Progresso publicado a cada identificador. */
data class ProgressoVarredura(val feitos: Int, val total: Int, val responderam: Int, val atual: String?)

/**
 * Engenharia reversa de PIDs: gera a faixa de comandos, classifica cada resposta crua do ELM327
 * e monta o relatório. A execução ([executar]) recebe o envio como função, então é testável sem
 * adaptador e roda sobre a conexão Bluetooth ativa no app.
 */
object VarreduraPids {

    val CABECALHOS = listOf("7E0", "7E1", "7B0", "7C0", "7E2", "7DF")

    /** Nome do módulo de cada cabeçalho físico (Toyota), para a tela e o relatório. */
    val NOMES_MODULOS = mapOf(
        "7E0" to "Motor",
        "7E1" to "Câmbio",
        "7E2" to "Híbrido / 2º módulo",
        "7B0" to "ABS / VSC / Estabilidade",
        "7C0" to "Painel / 4WD / Carroceria",
        "7DF" to "Funcional (OBD2)",
    )

    fun nomeModulo(cabecalho: String): String = NOMES_MODULOS[cabecalho.uppercase()] ?: "Módulo $cabecalho"
    val SERVICOS = listOf("21", "01", "22")

    /** Teto de identificadores por varredura (256 no serviço 21 levam ~15–50 s no Bluetooth). */
    const val MAX_IDENTIFICADORES = 4_096

    /** Timeouts seguidos (sem prompt) até desistir: o adaptador travou ou o link caiu. */
    const val MAX_TIMEOUTS_SEGUIDOS = 5

    const val PARADA_USUARIO = "interrompida pelo usuário"

    /**
     * Varredura multi-módulo: o módulo é dado como ausente (e a varredura passa ao próximo) se os
     * primeiros identificadores forem todos `NO DATA` — ninguém respondeu no endereço.
     */
    const val NO_DATA_PARA_MODULO_AUSENTE = 16

    private val MOTOR_21 = ConfigVarredura("7E0", "21", 0x00, 0xFF)
    private val CAMBIO_21 = ConfigVarredura("7E1", "21", 0x00, 0xFF)
    private val ABS_VSC_21 = ConfigVarredura("7B0", "21", 0x00, 0xFF)
    private val PAINEL_4WD_21 = ConfigVarredura("7C0", "21", 0x00, 0xFF)

    /** Os módulos que respondem na SW4 1KD-FTV + A750F e alimentam o Painel: motor e câmbio. */
    val PRESET_COMPLETA_SW4 = PresetVarredura(
        "Varredura Completa SW4 (7E0 + 7E1)",
        listOf(MOTOR_21, CAMBIO_21),
    )

    /** ABS/VSC e painel/4WD não responderam na SW4: ficam como varredura manual opcional. */
    val PRESET_CHASSI_SW4 = PresetVarredura(
        "Chassi SW4 — opcional (7B0 + 7C0)",
        listOf(ABS_VSC_21, PAINEL_4WD_21),
    )

    val PRESETS = listOf(
        PresetVarredura("Toyota Motor (7E0 · 2100–21FF)", MOTOR_21),
        PresetVarredura("Toyota Câmbio (7E1 · 2100–21FF)", CAMBIO_21),
        PresetVarredura("Toyota ABS / VSC / Estabilidade (7B0 · 2100–21FF)", ABS_VSC_21),
        PresetVarredura("Toyota Painel / 4WD / Carroceria (7C0 · 2100–21FF)", PAINEL_4WD_21),
        PRESET_COMPLETA_SW4,
        PRESET_CHASSI_SW4,
        PresetVarredura("OBD2 padrão (7DF · 0100–01FF)", ConfigVarredura("7DF", "01", 0x00, 0xFF)),
        PresetVarredura("UDS motor (7E0 · 22F400–22F4FF)", ConfigVarredura("7E0", "22", 0xF400, 0xF4FF)),
    )

    /**
     * Valida os campos da tela e monta a configuração; [Result.failure] com a mensagem do erro.
     * Faixa em hex (`00`–`FF`; no serviço 22, DIDs de 4 dígitos `F400`–`F4FF`).
     */
    fun configurar(cabecalho: String, servico: String, inicioHex: String, fimHex: String): Result<ConfigVarredura> {
        val cab = cabecalho.trim().uppercase()
        val serv = servico.trim().uppercase()
        if (!(cab.length == 3 || cab.length == 6) || !cab.all(::ehHex)) return falha("Cabeçalho inválido: use 3 dígitos hex (ex.: 7E0)")
        if (serv.length != 2 || !serv.all(::ehHex)) return falha("Serviço inválido: use 2 dígitos hex (ex.: 21)")
        val maximo = if (serv == "22") 0xFFFF else 0xFF
        val de = inicioHex.trim().toIntOrNull(16) ?: return falha("Início da faixa inválido (hex, ex.: 00)")
        val ate = fimHex.trim().toIntOrNull(16) ?: return falha("Fim da faixa inválido (hex, ex.: FF)")
        if (de !in 0..maximo || ate !in 0..maximo) {
            return falha("Faixa fora do limite: 00–${"%X".format(maximo)} no serviço $serv")
        }
        if (de > ate) return falha("O início da faixa deve ser menor ou igual ao fim")
        if (ate - de + 1 > MAX_IDENTIFICADORES) return falha("Faixa grande demais (máximo $MAX_IDENTIFICADORES identificadores)")
        return Result.success(ConfigVarredura(cab, serv, de, ate))
    }

    /** Comandos da faixa, em ordem: `2100`, `2101`... `21FF` (ou `22F400`... no serviço 22). */
    fun comandos(config: ConfigVarredura): List<String> = (config.inicio..config.fim).map { config.servico + config.hex(it) }

    /**
     * Classifica a resposta crua a [comando]: positiva se começar com serviço+0x40 e o eco do
     * identificador (`2101` → `61 01 ...`, multi-frame CAN já unido), negativa com `7F <serviço>`,
     * `NO DATA` sem dados e o resto erro.
     */
    fun classificar(comando: String, resposta: RespostaBruta): ResultadoPid {
        val bruto = resposta.resposta
        if (resposta.semPrompt) {
            return ResultadoPid(comando, TipoRespostaPid.ERRO, erro = "TIMEOUT", bruto = bruto, latenciaMs = resposta.latenciaMs)
        }
        val servico = comando.take(2)
        val esperado = "%02X".format(servico.toInt(16) + 0x40) + comando.drop(2)
        val linhas = ObdParser.linhasLimpas(bruto, comando).map { l -> l.filter { !it.isWhitespace() } }
        val positiva = linhas.firstOrNull { it.startsWith(esperado) && it.all(::ehHex) }
        if (positiva != null) {
            return ResultadoPid(comando, TipoRespostaPid.POSITIVA, bytes = hexParaBytes(positiva), bruto = bruto, latenciaMs = resposta.latenciaMs)
        }
        ObdParser.respostaNegativa(bruto, servico, comando)?.let { nrc ->
            return ResultadoPid(comando, TipoRespostaPid.NEGATIVA, nrc = nrc, bruto = bruto, latenciaMs = resposta.latenciaMs)
        }
        val erro = ObdParser.erroElm(bruto)
        val tipo = if (erro == "NO DATA") TipoRespostaPid.SEM_DADOS else TipoRespostaPid.ERRO
        return ResultadoPid(comando, tipo, erro = erro ?: "resposta não reconhecida", bruto = bruto, latenciaMs = resposta.latenciaMs)
    }

    /** Filtro da lista: só as positivas, ou tudo com [mostrarTudo]. */
    fun filtrar(resultados: List<ResultadoPid>, mostrarTudo: Boolean): List<ResultadoPid> =
        if (mostrarTudo) resultados else resultados.filter { it.respondeu }

    /**
     * Executa a varredura: `ATSH` do cabeçalho e cada comando da faixa em sequência. O botão Parar
     * ([deveParar]) é atendido entre comandos — nunca no meio de um, o que deixaria a resposta
     * atrasada no canal; a corrotina também é cancelável entre comandos. Para sozinha depois de
     * [MAX_TIMEOUTS_SEGUIDOS] timeouts ou se o envio falhar (conexão perdida). Devolve o que foi lido.
     *
     * Módulos fora de `7E0`–`7E7` (ABS `7B0`, painel `7C0`) respondem no endereço + 8, fora do
     * filtro padrão do ELM327: o `ATCRA 7B8` vai depois do `ATSH` e é desfeito (`ATCRA`) no fim.
     * Com [desistirSemResposta], os primeiros N identificadores todos `NO DATA` encerram o módulo
     * como ausente (usado na varredura multi-módulo).
     */
    suspend fun executar(
        config: ConfigVarredura,
        enviar: suspend (String) -> Result<RespostaBruta>,
        deveParar: () -> Boolean = { false },
        desistirSemResposta: Int? = null,
        aoProgredir: (ProgressoVarredura, ResultadoPid?) -> Unit = { _, _ -> },
    ): ResultadoVarredura {
        val comandos = comandos(config)
        val resultados = mutableListOf<ResultadoPid>()
        fun parar(motivo: String, conexaoPerdida: Boolean = false) =
            ResultadoVarredura(config, resultados.toList(), comandos.size, motivoParada = motivo, conexaoPerdida = conexaoPerdida)

        val atsh = enviar("ATSH ${config.cabecalho}").getOrElse {
            return parar(it.message ?: "falha de comunicação", conexaoPerdida = true)
        }
        if ("OK" !in ObdParser.limparResposta(atsh.resposta)) {
            return parar("o adaptador recusou o ATSH ${config.cabecalho} (\"${ObdParser.limparResposta(atsh.resposta).ifEmpty { "sem resposta" }}\")")
        }
        val filtro = RequisicaoEstendida.filtroRecepcao(config.cabecalho)
        val filtroAplicado = filtro != null &&
            enviar("ATCRA $filtro").getOrNull()?.let { "OK" in ObdParser.limparResposta(it.resposta) } == true
        try {
            var timeouts = 0
            var responderam = 0
            aoProgredir(ProgressoVarredura(0, comandos.size, 0, comandos.firstOrNull()), null)
            for ((i, comando) in comandos.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (deveParar()) return parar(PARADA_USUARIO)
                val resposta = enviar(comando).getOrElse {
                    return parar(it.message ?: "falha de comunicação", conexaoPerdida = true)
                }
                val r = classificar(comando, resposta)
                resultados += r
                if (r.respondeu) responderam++
                timeouts = if (resposta.semPrompt) timeouts + 1 else 0
                aoProgredir(ProgressoVarredura(i + 1, comandos.size, responderam, comandos.getOrNull(i + 1)), r)
                if (timeouts >= MAX_TIMEOUTS_SEGUIDOS) return parar("$timeouts comandos seguidos sem resposta do adaptador")
                if (desistirSemResposta != null && resultados.size == desistirSemResposta && comandos.size > desistirSemResposta &&
                    resultados.all { it.tipo == TipoRespostaPid.SEM_DADOS }
                ) {
                    return parar("módulo ${config.cabecalho} ausente ($desistirSemResposta identificadores seguidos sem resposta)")
                }
            }
            return ResultadoVarredura(config, resultados.toList(), comandos.size)
        } finally {
            // Volta ao filtro padrão (motor/câmbio) mesmo com Parar ou cancelamento.
            if (filtroAplicado) withContext(NonCancellable) { enviar("ATCRA") }
        }
    }

    /**
     * Varredura multi-módulo (ex.: [PRESET_COMPLETA_SW4]): cada configuração em sequência, na mesma
     * sessão manual. Um módulo ausente (`NO DATA`) ou que recuse o `ATSH` não interrompe os demais;
     * Parar ou a conexão perdida encerram tudo. [aoIniciarModulo] avisa a tela de cada etapa.
     */
    suspend fun executarModulos(
        modulos: List<ConfigVarredura>,
        enviar: suspend (String) -> Result<RespostaBruta>,
        deveParar: () -> Boolean = { false },
        aoIniciarModulo: (indice: Int, config: ConfigVarredura) -> Unit = { _, _ -> },
        aoModuloConcluido: (ResultadoVarredura) -> Unit = {},
        aoProgredir: (ProgressoVarredura, ResultadoPid?) -> Unit = { _, _ -> },
    ): ResultadoVarreduraMulti {
        val feitos = mutableListOf<ResultadoVarredura>()
        for ((i, config) in modulos.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (deveParar()) return ResultadoVarreduraMulti(modulos, feitos.toList(), PARADA_USUARIO)
            aoIniciarModulo(i, config)
            val r = executar(config, enviar, deveParar, NO_DATA_PARA_MODULO_AUSENTE, aoProgredir)
            feitos += r
            aoModuloConcluido(r)
            if (r.motivoParada == PARADA_USUARIO) return ResultadoVarreduraMulti(modulos, feitos.toList(), PARADA_USUARIO)
            if (r.conexaoPerdida) return ResultadoVarreduraMulti(modulos, feitos.toList(), r.motivoParada)
        }
        return ResultadoVarreduraMulti(modulos, feitos.toList())
    }

    private fun ehHex(c: Char) = c in '0'..'9' || c in 'A'..'F'

    private fun hexParaBytes(hex: String): List<Int> = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }

    private fun falha(mensagem: String): Result<ConfigVarredura> = Result.failure(IllegalArgumentException(mensagem))
}

/** Varredura concluída, parada pelo usuário ou interrompida ([motivoParada]). */
data class ResultadoVarredura(
    val config: ConfigVarredura,
    val resultados: List<ResultadoPid>,
    val total: Int,
    val motivoParada: String? = null,
    /** O envio falhou (Bluetooth caiu): a varredura multi-módulo não segue para o próximo módulo. */
    val conexaoPerdida: Boolean = false,
) {
    val positivas: List<ResultadoPid> get() = resultados.filter { it.respondeu }
    val completa: Boolean get() = resultados.size == total && motivoParada == null
}

/**
 * Varredura de vários módulos: os [planejados] (na ordem) e o resultado de cada um já feito em
 * [modulos]. [motivoParada] só quando o conjunto parou antes do fim (Parar, conexão perdida).
 */
data class ResultadoVarreduraMulti(
    val planejados: List<ConfigVarredura>,
    val modulos: List<ResultadoVarredura>,
    val motivoParada: String? = null,
) {
    val total: Int get() = planejados.sumOf { it.quantidade }
    val consultados: Int get() = modulos.sumOf { it.resultados.size }
    val positivas: Int get() = modulos.sumOf { it.positivas.size }

    /** Módulos que responderam positivamente a ao menos um identificador. */
    val modulosPresentes: List<ResultadoVarredura> get() = modulos.filter { it.positivas.isNotEmpty() }
}

/** Relatório em texto da varredura, para copiar/compartilhar e mapear sensores novos. */
object RelatorioVarredura {
    private val formato: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun gerar(
        resultado: ResultadoVarredura,
        quando: LocalDateTime,
        veiculo: String? = null,
        protocolo: String? = null,
        duracaoMs: Long? = null,
        /** Cruzamento com o catálogo (varredura amigável): acrescenta o nome e o valor dos identificados. */
        identificacoes: List<IdentificacaoPid> = emptyList(),
    ): String = buildString {
        val c = resultado.config
        appendLine("VARREDURA DE PIDs — Charrua OBD Pro")
        appendLine(quando.format(formato) + (veiculo?.let { " · $it" } ?: ""))
        protocolo?.let { appendLine("Protocolo: $it") }
        appendLine("Faixa: ${c.descricao} (ATSH ${c.cabecalho}, serviço ${c.servico})")
        corpo(resultado, duracaoMs, identificacoes)
    }.trimEnd()

    /**
     * Relatório único da varredura multi-módulo (ex.: Varredura Completa SW4): resumo geral e uma
     * seção por módulo (`7E0` Motor, `7E1` Câmbio, `7B0` ABS/VSC, `7C0` Painel/4WD) com os bytes
     * brutos de cada identificador que respondeu — pronto para copiar e mapear marcha, alavanca,
     * 4x4 e VSC. [identificacoes] por cabeçalho (cruzamento com o catálogo).
     */
    fun gerarConsolidado(
        resultado: ResultadoVarreduraMulti,
        quando: LocalDateTime,
        veiculo: String? = null,
        protocolo: String? = null,
        duracaoMs: Long? = null,
        identificacoes: Map<String, List<IdentificacaoPid>> = emptyMap(),
    ): String = buildString {
        appendLine("VARREDURA COMPLETA MULTI-MÓDULO — Charrua OBD Pro")
        appendLine(quando.format(formato) + (veiculo?.let { " · $it" } ?: ""))
        protocolo?.let { appendLine("Protocolo: $it") }
        appendLine("Módulos: " + resultado.planejados.joinToString(" + ") { "${it.cabecalho} (${VarreduraPids.nomeModulo(it.cabecalho)})" })
        append("Consultados: ${resultado.consultados}/${resultado.total} · responderam: ${resultado.positivas}")
        append(" · módulos presentes: ${resultado.modulosPresentes.size}/${resultado.planejados.size}")
        duracaoMs?.let { append(" · %.1f s".format(LOCALE_BR, it / 1000.0)) }
        appendLine()
        resultado.motivoParada?.let { appendLine("Interrompida: $it") }
        for (config in resultado.planejados) {
            appendLine()
            appendLine("=== ${config.cabecalho} · ${VarreduraPids.nomeModulo(config.cabecalho)} (${config.descricao}) ===")
            val modulo = resultado.modulos.firstOrNull { it.config == config }
            if (modulo == null) {
                appendLine("Não consultado.")
                continue
            }
            corpo(modulo, null, identificacoes[config.cabecalho].orEmpty())
            appendLine()
        }
    }.trimEnd()

    private fun StringBuilder.corpo(resultado: ResultadoVarredura, duracaoMs: Long?, identificacoes: List<IdentificacaoPid>) {
        val c = resultado.config
        val positivas = resultado.positivas
        val negativas = resultado.resultados.count { it.tipo == TipoRespostaPid.NEGATIVA }
        val semDados = resultado.resultados.count { it.tipo == TipoRespostaPid.SEM_DADOS }
        val erros = resultado.resultados.count { it.tipo == TipoRespostaPid.ERRO }
        append("Consultados: ${resultado.resultados.size}/${resultado.total} · responderam: ${positivas.size}")
        append(" · negativas (7F): $negativas · NO DATA: $semDados · erros: $erros")
        duracaoMs?.let { append(" · %.1f s".format(LOCALE_BR, it / 1000.0)) }
        appendLine()
        resultado.motivoParada?.let { appendLine("Interrompida: $it") }
        appendLine()
        if (positivas.isEmpty()) {
            appendLine("Nenhum identificador respondeu positivamente.")
        } else {
            appendLine("PIDs que responderam (bytes brutos; dados = após o eco do serviço/identificador):")
            val porComando = identificacoes.associateBy { it.comando }
            for (r in positivas) {
                val dados = r.dados(c.digitos)
                append("${r.comando} → ${r.bytesHex}  [${dados.size} byte${if (dados.size == 1) "" else "s"} de dados]")
                porComando[r.comando]?.takeIf { it.status == StatusIdentificacao.IDENTIFICADO }?.let { id ->
                    append("  = ${id.conhecido?.nome}")
                    if (id.valorTexto.isNotEmpty()) append(": ${id.valorTexto}")
                }
                appendLine()
            }
            if (identificacoes.isNotEmpty()) {
                val m = MetricasVarredura.de(identificacoes, resultado.total)
                appendLine("Identificados no catálogo: ${m.identificados} · desconhecidos (candidatos a mapear): ${m.desconhecidos}")
            }
        }
        val nrcs = resultado.resultados.filter { it.tipo == TipoRespostaPid.NEGATIVA }.groupingBy { it.nrc }.eachCount()
        if (nrcs.isNotEmpty()) {
            appendLine()
            append("Respostas negativas: ")
            append(nrcs.entries.joinToString { (nrc, n) -> "7F ${c.servico} $nrc (${ObdParser.descricaoNrc(nrc ?: "")}) ×$n" })
        }
    }

    private val LOCALE_BR: java.util.Locale = java.util.Locale.forLanguageTag("pt-BR")
}
