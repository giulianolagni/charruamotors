package com.charruamotors.obd.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uma linha do registro de comunicação com o ELM327. */
sealed interface EntradaRegistro {
    val instanteMs: Long

    /** Comando enviado e o texto bruto devolvido até o prompt (ou a falha, se não houve resposta). */
    data class Comando(
        override val instanteMs: Long,
        val comando: String,
        val resposta: String?,
        val duracaoMs: Long,
        val falha: String? = null,
    ) : EntradaRegistro

    /** Fato observado pelo cliente: protocolo detectado, sufixo desligado, conexão perdida... */
    data class Evento(override val instanteMs: Long, val texto: String) : EntradaRegistro
}

/**
 * Registro em memória das últimas trocas com o adaptador, para diagnóstico em campo: se algo não
 * ler no carro, dá para ver exatamente o que o ELM327 e a ECU responderam (`ATZ`, `ATDP`, `ATRV`,
 * `0100`, perfil de montadora, erros do polling). Buffer circular de [capacidade] entradas:
 * horas de telemetria não crescem a memória. Thread-safe (o cliente grava de `Dispatchers.IO`,
 * a UI lê da main thread).
 */
class RegistroComunicacao(
    val capacidade: Int = CAPACIDADE_PADRAO,
    private val relogio: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacidade > 0) { "capacidade deve ser positiva" }
    }

    private val entradas = ArrayDeque<EntradaRegistro>(capacidade)
    private val trava = Any()

    /**
     * Recebe cada entrada nova, sem o limite do buffer circular: a sessão OBD usa para gravar o log
     * bruto completo no relatório `.txt`. Chamado na thread de quem registrou (normalmente IO);
     * falhas do ouvinte são ignoradas para nunca derrubar a comunicação.
     */
    @Volatile var ouvinte: ((EntradaRegistro) -> Unit)? = null

    fun comando(comando: String, resposta: String?, duracaoMs: Long, falha: String? = null) =
        adicionar(EntradaRegistro.Comando(relogio(), comando, resposta, duracaoMs, falha))

    fun evento(texto: String) = adicionar(EntradaRegistro.Evento(relogio(), texto))

    /**
     * Log completo de tráfego (auditoria): liga a entrega, só ao [ouvinte], das trocas do polling
     * contínuo e dos valores decodificados. Ficam fora do buffer circular da tela para não expulsar
     * dele o handshake e os eventos (um ciclo de telemetria enche o buffer em segundos).
     */
    @Volatile var logCompleto: Boolean = false

    /** Troca do loop de telemetria: vai só para o [ouvinte] (arquivo `.txt`), nunca para o buffer. */
    fun comandoSomenteLog(comando: String, resposta: String?, duracaoMs: Long) {
        if (!logCompleto) return
        ouvinte?.let { runCatching { it(EntradaRegistro.Comando(relogio(), comando, resposta, duracaoMs)) } }
    }

    /** Valor decodificado de uma resposta do polling, ao lado do hexadecimal bruto (só no arquivo). */
    fun decodificado(texto: String) {
        if (!logCompleto) return
        ouvinte?.let { runCatching { it(EntradaRegistro.Evento(relogio(), "  ↳ $texto")) } }
    }

    fun limpar() = synchronized(trava) { entradas.clear() }

    /** Cópia das entradas, da mais antiga para a mais recente. */
    fun entradas(): List<EntradaRegistro> = synchronized(trava) { entradas.toList() }

    private fun adicionar(entrada: EntradaRegistro) {
        synchronized(trava) {
            if (entradas.size == capacidade) entradas.removeFirst()
            entradas.addLast(entrada)
        }
        ouvinte?.let { runCatching { it(entrada) } }
    }

    companion object {
        const val CAPACIDADE_PADRAO = 5_000

        /**
         * Texto para exibir/copiar: uma linha por troca, com `\r` visível, para colar numa mensagem
         * ou comparar com o datasheet do ELM327.
         *
         * ```
         * 14:02:07.120  ATZ → "\r\rELM327 v1.5\r\r" (812 ms)
         * 14:02:09.880  0100 → TIMEOUT: O scanner não respondeu ao comando 0100 (20000 ms)
         * 14:02:10.002  ● Protocolo: AUTO, ISO 9141-2
         * ```
         */
        fun formatar(entradas: List<EntradaRegistro>, fuso: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
            val hora = formatoHora(fuso)
            return entradas.joinToString("\n") { formatarLinha(it, hora) }
        }

        /** Formato `HH:mm:ss.SSS` das linhas do registro (não é thread-safe: um por usuário). */
        fun formatoHora(fuso: java.util.TimeZone = java.util.TimeZone.getDefault()): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).apply { timeZone = fuso }

        /** Uma linha do registro, no mesmo formato de [formatar]. */
        fun formatarLinha(e: EntradaRegistro, hora: SimpleDateFormat): String {
            val instante = hora.format(Date(e.instanteMs))
            return when (e) {
                is EntradaRegistro.Evento -> "$instante  ● ${e.texto}"
                is EntradaRegistro.Comando -> {
                    val resultado = when {
                        e.falha != null -> e.falha
                        else -> "\"${visivel(e.resposta.orEmpty())}\""
                    }
                    "$instante  ${e.comando} → $resultado (${e.duracaoMs} ms)"
                }
            }
        }

        /** Torna caracteres de controle visíveis (`\r`, `\n`, `\x00`). */
        fun visivel(texto: String): String = buildString {
            for (c in texto) {
                when {
                    c == '\r' -> append("\\r")
                    c == '\n' -> append("\\n")
                    c < ' ' || c.code > 0x7E -> append("\\x%02X".format(c.code and 0xFF))
                    else -> append(c)
                }
            }
        }
    }
}
