package com.charruamotors.oficina.varredura

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaBruta
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Preset de varredura para os módulos mais comuns. */
data class PresetVarredura(val nome: String, val config: ConfigVarredura)

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

    val CABECALHOS = listOf("7E0", "7E1", "7E2", "7DF")
    val SERVICOS = listOf("21", "01", "22")

    /** Teto de identificadores por varredura (256 no serviço 21 levam ~15–50 s no Bluetooth). */
    const val MAX_IDENTIFICADORES = 4_096

    /** Timeouts seguidos (sem prompt) até desistir: o adaptador travou ou o link caiu. */
    const val MAX_TIMEOUTS_SEGUIDOS = 5

    const val PARADA_USUARIO = "interrompida pelo usuário"

    val PRESETS = listOf(
        PresetVarredura("Toyota Motor (7E0 · 2100–21FF)", ConfigVarredura("7E0", "21", 0x00, 0xFF)),
        PresetVarredura("Toyota Câmbio (7E1 · 2100–21FF)", ConfigVarredura("7E1", "21", 0x00, 0xFF)),
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
     */
    suspend fun executar(
        config: ConfigVarredura,
        enviar: suspend (String) -> Result<RespostaBruta>,
        deveParar: () -> Boolean = { false },
        aoProgredir: (ProgressoVarredura, ResultadoPid?) -> Unit = { _, _ -> },
    ): ResultadoVarredura {
        val comandos = comandos(config)
        val resultados = mutableListOf<ResultadoPid>()
        val atsh = enviar("ATSH ${config.cabecalho}").getOrElse {
            return ResultadoVarredura(config, resultados, comandos.size, motivoParada = it.message ?: "falha de comunicação")
        }
        if ("OK" !in ObdParser.limparResposta(atsh.resposta)) {
            return ResultadoVarredura(
                config, resultados, comandos.size,
                motivoParada = "o adaptador recusou o ATSH ${config.cabecalho} (\"${ObdParser.limparResposta(atsh.resposta).ifEmpty { "sem resposta" }}\")",
            )
        }
        var timeouts = 0
        var responderam = 0
        aoProgredir(ProgressoVarredura(0, comandos.size, 0, comandos.firstOrNull()), null)
        for ((i, comando) in comandos.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (deveParar()) return ResultadoVarredura(config, resultados, comandos.size, motivoParada = PARADA_USUARIO)
            val resposta = enviar(comando).getOrElse {
                return ResultadoVarredura(config, resultados, comandos.size, motivoParada = it.message ?: "falha de comunicação")
            }
            val r = classificar(comando, resposta)
            resultados += r
            if (r.respondeu) responderam++
            timeouts = if (resposta.semPrompt) timeouts + 1 else 0
            aoProgredir(ProgressoVarredura(i + 1, comandos.size, responderam, comandos.getOrNull(i + 1)), r)
            if (timeouts >= MAX_TIMEOUTS_SEGUIDOS) {
                return ResultadoVarredura(config, resultados, comandos.size, motivoParada = "$timeouts comandos seguidos sem resposta do adaptador")
            }
        }
        return ResultadoVarredura(config, resultados, comandos.size)
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
) {
    val positivas: List<ResultadoPid> get() = resultados.filter { it.respondeu }
    val completa: Boolean get() = resultados.size == total && motivoParada == null
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
        val positivas = resultado.positivas
        val negativas = resultado.resultados.count { it.tipo == TipoRespostaPid.NEGATIVA }
        val semDados = resultado.resultados.count { it.tipo == TipoRespostaPid.SEM_DADOS }
        val erros = resultado.resultados.count { it.tipo == TipoRespostaPid.ERRO }
        append("Consultados: ${resultado.resultados.size}/${resultado.total} · responderam: ${positivas.size}")
        append(" · negativas (7F): $negativas · NO DATA: $semDados · erros: $erros")
        duracaoMs?.let { append(" · %.1f s".format(java.util.Locale.forLanguageTag("pt-BR"), it / 1000.0)) }
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
    }.trimEnd()
}
