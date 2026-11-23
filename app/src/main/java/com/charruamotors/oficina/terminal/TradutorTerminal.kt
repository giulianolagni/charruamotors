package com.charruamotors.oficina.terminal

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.oficina.varredura.IdentificadorPids
import com.charruamotors.oficina.varredura.StatusIdentificacao
import com.charruamotors.oficina.varredura.TipoRespostaPid
import com.charruamotors.oficina.varredura.VarreduraPids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.util.comoNumero
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Botão de atalho do Terminal: o comando, um título curto e o que ele faz. */
data class AtalhoTerminal(val comando: String, val titulo: String, val descricao: String)

/**
 * Terminal didático: descreve o que cada comando faz e traduz a resposta crua do ELM327
 * (`12.4V`, `41 0C 0D 40`, `7F 21 31`) para português, ao lado do hex bruto. Objeto puro:
 * recebe o catálogo de PIDs e o [identificador] (modos 21/22 e perfis de montadora).
 */
class TradutorTerminal(
    private val catalogo: CatalogoPids? = null,
    private val identificador: IdentificadorPids? = null,
) {

    /** O que o comando faz, antes de enviar (e na linha do log); null se o app não souber. */
    fun descrever(comando: String): String? {
        val c = normalizar(comando)
        DESCRICOES_AT[c]?.let { return it }
        if (c.startsWith("ATSH")) return "Muda o foco para ${descreverModulo(c.removePrefix("ATSH"))}"
        if (c.startsWith("ATSP")) return "Força o protocolo ${c.removePrefix("ATSP")} (0 = automático)"
        if (c.startsWith("AT")) return "Configuração do adaptador ELM327"
        DESCRICOES_SERVICO[c]?.let { return it }
        if (c.length == 4 && c.startsWith("01")) {
            return catalogo?.get(c)?.let { "Lê: ${it.nome} (OBD2 padrão)" } ?: "Lê o PID ${c.drop(2)} do OBD2 padrão (modo 01)"
        }
        if (c.length >= 4 && (c.startsWith("21") || c.startsWith("22"))) {
            val conhecido = identificador?.conhecido(null, c)
            val servico = if (c.startsWith("21")) "modo 21 (KWP/Toyota)" else "modo 22 (UDS)"
            return conhecido?.let { "Lê: ${it.nome} pelo $servico" } ?: "Lê o identificador ${c.drop(2)} pelo $servico"
        }
        return null
    }

    /**
     * Resposta em linguagem simples: `Bateria: 12,4 V (boa, motor desligado)`, `Rotação do motor:
     * 851 rpm`, `Recusado pela ECU: subFunctionNotSupported`. [cabecalho] é o `ATSH` ativo (para
     * identificar `21xx`/`22xxxx` no módulo certo). Null se não houver tradução útil.
     */
    fun traduzir(
        entrada: EntradaTerminal,
        cabecalho: String? = null,
        conversor: ConversorUnidades = ConversorUnidades.PADRAO,
    ): String? {
        if (entrada.erro != null) return "Não enviado: ${entrada.erro}"
        if (entrada.semPrompt) return "Sem resposta no prazo: o adaptador ou o módulo não respondeu"
        val c = normalizar(entrada.comando)
        val bruto = entrada.resposta.orEmpty()
        val limpo = ObdParser.limparResposta(bruto)

        when {
            c == "ATRV" -> return traduzirTensao(bruto)
            c == "ATDP" -> return ObdParser.parseDescricaoProtocolo(bruto)?.let { "Protocolo em uso: $it" }
            c == "ATDPN" -> return ObdParser.parseProtocolo(bruto)?.let { "Protocolo número ${"%X".format(it)}" }
            c == "ATZ" || c == "ATI" || c == "ATWS" -> return FormatadorTerminal.linhasResposta(bruto).lastOrNull()
                ?.takeIf { it.isNotBlank() }?.let { "Adaptador: $it" }
            c.startsWith("ATSH") -> return if ("OK" in limpo) {
                "Foco alterado: agora os comandos vão para ${descreverModulo(c.removePrefix("ATSH"))}"
            } else {
                traduzirErro(bruto, c)
            }
            c.startsWith("AT") -> return if (limpo.endsWith("OK")) "Comando aceito pelo adaptador" else traduzirErro(bruto, c)
            c == "0902" -> return when (val vin = ObdParser.parseVin(bruto)) {
                is RespostaObd.Ok -> "Chassi (VIN): ${vin.valor}"
                else -> traduzirErro(bruto, c)
            }
            c == "03" || c == "07" || c == "0A" -> {
                val r = when (c) {
                    "03" -> ObdParser.parseDtcs(bruto)
                    "07" -> ObdParser.parseDtcsPendentes(bruto)
                    else -> ObdParser.parseDtcsPermanentes(bruto)
                }
                return when (r) {
                    is RespostaObd.Ok -> if (r.valor.isEmpty()) "Nenhuma falha registrada" else "Falhas: ${r.valor.joinToString()}"
                    else -> traduzirErro(bruto, c)
                }
            }
            c == "04" -> return if (ObdParser.parseLimpezaDtcs(bruto) is RespostaObd.Ok) "Falhas apagadas e luz da injeção desligada" else traduzirErro(bruto, c)
            c.length == 4 && c.startsWith("01") && c != "0100" -> {
                val def = catalogo?.get(c) ?: return traduzirErro(bruto, c)
                return when (val r = ObdParser.parsePid(bruto, def)) {
                    is RespostaObd.Ok -> {
                        val v = conversor.converter(r.valor, def.unidade, def.casasDecimais)
                        "${def.nome}: ${v.valor.comoNumero(v.casas)} ${v.unidade}".trim()
                    }
                    else -> traduzirErro(bruto, c)
                }
            }
            c == "0100" -> return if (ObdParser.respostaPositiva(bruto, c)) "A ECU respondeu: lista de PIDs suportados recebida" else traduzirErro(bruto, c)
            (c.startsWith("21") || c.startsWith("22")) && c.length >= 4 && c.all(::ehHex) -> {
                val resultado = VarreduraPids.classificar(c, com.charruamotors.obd.RespostaBruta(c, bruto, entrada.latenciaMs ?: 0))
                if (resultado.tipo != TipoRespostaPid.POSITIVA) return traduzirErro(bruto, c)
                val id = identificador?.identificar(resultado, cabecalho, conversor)
                return when {
                    id == null || id.status != StatusIdentificacao.IDENTIFICADO -> "Resposta positiva com ${resultado.dados(if (c.startsWith("22")) 4 else 2).size} byte(s) de dados (sensor ainda não mapeado)"
                    id.valorTexto.isNotEmpty() -> "${id.conhecido?.nome}: ${id.valorTexto}"
                    else -> "${id.conhecido?.nome} (valor fora da faixa esperada)"
                }
            }
        }
        return traduzirErro(bruto, c)
    }

    /** Cabeçalho `ATSH` em vigor depois das [entradas] (o último aceito com `OK`); null = padrão do app. */
    fun cabecalhoAtivo(entradas: List<EntradaTerminal>): String? =
        entradas.lastOrNull { normalizar(it.comando).startsWith("ATSH") && "OK" in ObdParser.limparResposta(it.resposta.orEmpty()) }
            ?.let { normalizar(it.comando).removePrefix("ATSH") }

    private fun traduzirTensao(bruto: String): String? {
        val r = ObdParser.parseTensao(bruto) as? RespostaObd.Ok ?: return traduzirErro(bruto, "ATRV")
        val v = r.valor
        val situacao = when {
            v < 11.8 -> "baixa: bateria descarregada ou fraca"
            v < 12.3 -> "parcial: carga incompleta"
            v < 13.0 -> "boa, motor desligado"
            v <= 14.8 -> "alternador carregando"
            else -> "alta: verifique o regulador do alternador"
        }
        return "Bateria: ${v.comoNumero(1)} V ($situacao)"
    }

    private fun traduzirErro(bruto: String, comando: String): String? {
        val servico = comando.take(2).takeIf { comando.length >= 2 && comando.all(::ehHex) }
        servico?.let { s ->
            ObdParser.respostaNegativa(bruto, s, comando)?.let { nrc ->
                return "Recusado pela ECU: ${ObdParser.descricaoNrc(nrc)} (7F $s $nrc)"
            }
        }
        return when (ObdParser.erroElm(bruto)) {
            null -> null
            "NO DATA" -> "Sem dados: o módulo não tem esta informação"
            "?" -> "Comando não reconhecido pelo ELM327"
            "UNABLE TO CONNECT" -> "Sem comunicação com a ECU (ignição ligada?)"
            "CAN ERROR", "BUS ERROR" -> "Erro no barramento: confira o protocolo e a ignição"
            "STOPPED" -> "Leitura interrompida pelo adaptador"
            else -> "Erro do adaptador: ${ObdParser.erroElm(bruto)}"
        }
    }

    companion object {
        /** Atalhos com um toque (topo do Terminal). */
        val ATALHOS = listOf(
            AtalhoTerminal("ATRV", "Bateria", "Tensão da bateria medida no pino 16"),
            AtalhoTerminal("ATDP", "Protocolo", "Protocolo OBD2 em uso"),
            AtalhoTerminal("ATSH 7E0", "Foco motor", "Envia os próximos comandos à ECU do motor"),
            AtalhoTerminal("ATSH 7E1", "Foco câmbio", "Envia os próximos comandos ao câmbio automático"),
            AtalhoTerminal("210C", "RPM (21)", "RPM pelo modo 21 da Toyota (após ATSH 7E0)"),
            AtalhoTerminal("0902", "Chassi", "Número do chassi (VIN)"),
            AtalhoTerminal("010C", "RPM (01)", "RPM pelo OBD2 padrão"),
            AtalhoTerminal("03", "Falhas", "Códigos de falha confirmados"),
            AtalhoTerminal("ATSH 7DF", "Foco geral", "Volta ao endereço funcional (todos os módulos)"),
        )

        /** Sugestões da área de ajuda. */
        val DICAS = listOf(
            "Consulte a bateria com ATRV",
            "Altere o foco com ATSH 7E0 (motor) ou ATSH 7E1 (câmbio)",
            "Leia o RPM com 210C (Toyota) ou 010C (OBD2 padrão)",
            "Veja o protocolo com ATDP e o chassi com 0902",
            "Leia as falhas com 03 (só leitura; 04 apaga)",
        )

        private val DESCRICOES_AT = mapOf(
            "ATRV" to "Lê a tensão da bateria",
            "ATDP" to "Mostra o protocolo OBD2 em uso",
            "ATDPN" to "Mostra o número do protocolo",
            "ATZ" to "Reinicia o adaptador ELM327",
            "ATI" to "Mostra a versão do adaptador",
            "ATWS" to "Reinício leve do adaptador",
            "ATE0" to "Desliga o eco dos comandos",
            "ATL0" to "Desliga as quebras de linha",
            "ATH1" to "Mostra os cabeçalhos CAN nas respostas",
            "ATH0" to "Esconde os cabeçalhos CAN",
            "ATS0" to "Remove os espaços das respostas",
            "ATSP0" to "Volta à detecção automática de protocolo",
        )

        private val DESCRICOES_SERVICO = mapOf(
            "0100" to "Pergunta à ECU quais PIDs do OBD2 ela suporta",
            "0902" to "Lê o número do chassi (VIN)",
            "03" to "Lê os códigos de falha confirmados",
            "07" to "Lê os códigos de falha pendentes",
            "0A" to "Lê os códigos de falha permanentes",
            "04" to "APAGA os códigos de falha e desliga a luz da injeção",
            "13" to "Lê as falhas pelo KWP2000 (Toyota antigas)",
        )

        /** `7E0` → "o motor (ECU 7E0)". */
        fun descreverModulo(cabecalho: String): String = when (val c = cabecalho.trim().uppercase()) {
            "7E0" -> "o motor (ECU 7E0)"
            "7E1" -> "o câmbio (TCM 7E1)"
            "7E2" -> "o módulo 7E2 (híbrido/ABS)"
            "7DF" -> "todos os módulos (endereço funcional 7DF)"
            else -> "o módulo $c"
        }

        fun normalizar(comando: String): String = comando.uppercase().filter { !it.isWhitespace() }

        private fun ehHex(c: Char) = c in '0'..'9' || c in 'A'..'F'
    }
}

/** Linha traduzida para o laudo: comando, o que ele faz e a resposta em português. */
data class LinhaLaudo(val comando: String, val descricao: String?, val bruto: String, val traducao: String?)

/**
 * Laudo do Terminal: resumo da sessão (comandos, respostas, erros), cada comando com a resposta
 * traduzida e, no fim, o log bruto com latências — para copiar/compartilhar com o mecânico.
 */
object LaudoTerminal {
    private val formato: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun linhas(
        entradas: List<EntradaTerminal>,
        tradutor: TradutorTerminal,
        conversor: ConversorUnidades = ConversorUnidades.PADRAO,
    ): List<LinhaLaudo> = entradas.mapIndexed { i, e ->
        val cabecalho = tradutor.cabecalhoAtivo(entradas.subList(0, i))
        LinhaLaudo(
            comando = e.comando,
            descricao = tradutor.descrever(e.comando),
            bruto = FormatadorTerminal.linhasResposta(e.resposta.orEmpty()).joinToString(" | ").ifEmpty { e.erro ?: "" },
            traducao = tradutor.traduzir(e, cabecalho, conversor),
        )
    }

    fun gerar(
        entradas: List<EntradaTerminal>,
        tradutor: TradutorTerminal,
        quando: LocalDateTime,
        veiculo: String? = null,
        protocolo: String? = null,
        conversor: ConversorUnidades = ConversorUnidades.PADRAO,
    ): String = buildString {
        appendLine("LAUDO DO TERMINAL ELM327 — Charrua OBD Pro")
        appendLine(quando.format(formato) + (veiculo?.let { " · $it" } ?: ""))
        protocolo?.let { appendLine("Protocolo: $it") }
        val erros = entradas.count { it.erro != null || it.semPrompt }
        val latencias = entradas.mapNotNull { it.latenciaMs }
        append("Comandos: ${entradas.size} · respondidos: ${entradas.size - erros} · sem resposta/erro: $erros")
        if (latencias.isNotEmpty()) append(" · latência média: ${latencias.average().let { "%.0f".format(Locale.ROOT, it) }} ms")
        appendLine()
        appendLine()
        if (entradas.isEmpty()) {
            appendLine("Nenhum comando enviado.")
            return@buildString
        }
        appendLine("Leituras traduzidas:")
        for (l in linhas(entradas, tradutor, conversor)) {
            append("> ").append(l.comando)
            l.descricao?.let { append(" — ").append(it) }
            appendLine()
            if (l.bruto.isNotEmpty()) appendLine("  bruto: ${l.bruto}")
            appendLine("  → ${l.traducao ?: "(sem tradução)"}")
        }
        appendLine()
        appendLine("Log bruto:")
        append(FormatadorTerminal.formatar(entradas))
    }.trimEnd()
}
