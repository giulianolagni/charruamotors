package com.charruamotors.oficina.terminal

import com.charruamotors.obd.RespostaBruta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Uma linha do log do Terminal ELM327: o comando enviado e a resposta (ou o erro de envio). */
data class EntradaTerminal(
    val instanteMs: Long,
    val comando: String,
    /** Resposta bruta até o prompt; null se o envio falhou antes (desconectado, comando inválido). */
    val resposta: String?,
    val latenciaMs: Long?,
    val semPrompt: Boolean = false,
    val erro: String? = null,
) {
    companion object {
        fun de(resposta: RespostaBruta, instanteMs: Long) =
            EntradaTerminal(instanteMs, resposta.comando, resposta.resposta, resposta.latenciaMs, resposta.semPrompt)

        fun falha(comando: String, mensagem: String, instanteMs: Long) =
            EntradaTerminal(instanteMs, comando, null, null, erro = mensagem)
    }
}

/**
 * Formata o log do terminal em texto monoespaçado:
 * ```
 * 14:30:05.123  > ATRV                          38 ms
 *               12.4V
 * ```
 * A resposta bruta tem `\r` como separador (ELM327 com `ATL0`); vira uma linha por quadro, com os
 * caracteres de controle visíveis removidos.
 */
object FormatadorTerminal {

    private val formatoHora: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val RECUO = "              "
    private const val LARGURA_COMANDO = 28

    /** Linhas da resposta, sem linhas vazias e sem caracteres de controle (bytes nulos de clones). */
    fun linhasResposta(bruto: String): List<String> =
        bruto.replace('>', '\n')
            .split('\r', '\n')
            .map { l -> l.filter { it in ' '..'~' }.trimEnd() }
            .filter { it.isNotBlank() }

    fun formatar(entrada: EntradaTerminal, zona: ZoneId = ZoneId.systemDefault()): String = buildString {
        val hora = formatoHora.format(Instant.ofEpochMilli(entrada.instanteMs).atZone(zona))
        append(hora).append("  > ").append(entrada.comando.padEnd(LARGURA_COMANDO))
        entrada.latenciaMs?.let { append(" ").append(it).append(" ms") }
        val corpo = when {
            entrada.erro != null -> listOf("ERRO: ${entrada.erro}")
            entrada.semPrompt -> listOf("TIMEOUT: sem prompt '>' no prazo")
            else -> linhasResposta(entrada.resposta.orEmpty()).ifEmpty { listOf("(resposta vazia)") }
        }
        for (linha in corpo) append('\n').append(RECUO).append(linha)
    }

    fun formatar(entradas: List<EntradaTerminal>, zona: ZoneId = ZoneId.systemDefault()): String =
        entradas.joinToString("\n") { formatar(it, zona) }
}
