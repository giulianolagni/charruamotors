package com.charruamotors.oficina.bateria

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Veredito resumido de um componente no laudo (bateria ou alternador). */
data class VereditoResumo(val titulo: String, val gravidade: Int?) {
    val avaliado: Boolean get() = gravidade != null
}

/**
 * Laudo Resumido do Teste de Bateria e Alternador: veredito da bateria (repouso + partida), do
 * alternador (marcha lenta + carga) e os valores medidos, em texto simples para copiar ou
 * compartilhar (WhatsApp, e-mail). Puro: testável na JVM.
 */
object LaudoBateria {

    private val localeBr: Locale = Locale.forLanguageTag("pt-BR")
    private val formatoDataHora: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun rotulo(v: VereditoRepouso) = when (v) {
        VereditoRepouso.SAUDAVEL -> "Saudável"
        VereditoRepouso.CARGA_BAIXA -> "Carga baixa"
        VereditoRepouso.CRITICA -> "Crítica"
    }

    fun rotulo(v: VereditoPartida) = when (v) {
        VereditoPartida.OK -> "OK"
        VereditoPartida.ATENCAO -> "Atenção"
        VereditoPartida.FRACA -> "Bateria fraca/comprometida"
    }

    fun rotulo(v: VereditoAlternador) = when (v) {
        VereditoAlternador.SUBCARGA -> "Subcarga"
        VereditoAlternador.LIMITE_INFERIOR -> "Limite inferior"
        VereditoAlternador.NORMAL -> "Normal"
        VereditoAlternador.LIMITE_SUPERIOR -> "Limite superior"
        VereditoAlternador.SOBRECARGA -> "Sobrecarga/regulador"
    }

    /** Bateria: o pior entre repouso e partida; não avaliada se as duas etapas foram puladas. */
    fun vereditoBateria(teste: TesteBateria): VereditoResumo {
        val r = teste.resultadoRepouso
        val p = teste.resultadoPartida
        val gravidade = listOfNotNull(r?.veredito?.gravidade, p?.veredito?.gravidade).maxOrNull()
            ?: return VereditoResumo("Não avaliada", null)
        val titulo = when (gravidade) {
            0 -> "SAUDÁVEL"
            1 -> "ATENÇÃO"
            else -> "COMPROMETIDA"
        }
        return VereditoResumo(titulo, gravidade)
    }

    /**
     * Alternador: o pior entre marcha lenta e carga (no empate, a fase com carga, que é a mais
     * exigente); o título diz qual problema (subcarga/sobrecarga).
     */
    fun vereditoAlternador(teste: TesteBateria): VereditoResumo {
        val fases = listOfNotNull(teste.resultadoLenta, teste.resultadoCarga)
        if (fases.isEmpty()) return VereditoResumo("Não avaliado", null)
        val pior = fases.reversed().maxBy { it.veredito.gravidade }.veredito
        val titulo = when (pior) {
            VereditoAlternador.NORMAL -> "NORMAL"
            VereditoAlternador.LIMITE_INFERIOR, VereditoAlternador.LIMITE_SUPERIOR -> "ATENÇÃO (${rotulo(pior).lowercase(localeBr)})"
            VereditoAlternador.SUBCARGA -> "SUBCARGA"
            VereditoAlternador.SOBRECARGA -> "SOBRECARGA (regulador)"
        }
        return VereditoResumo(titulo, pior.gravidade)
    }

    fun gerar(teste: TesteBateria, veiculo: String?, quando: LocalDateTime): String = buildString {
        appendLine("LAUDO — TESTE DE BATERIA E ALTERNADOR")
        appendLine("Charrua OBD Pro · ${quando.format(formatoDataHora)}")
        if (!veiculo.isNullOrBlank()) appendLine("Veículo: $veiculo")
        appendLine()

        appendLine("BATERIA: ${vereditoBateria(teste).titulo}")
        val r = teste.resultadoRepouso
        if (r == null) {
            appendLine("• Repouso: não realizado")
        } else {
            append("• Repouso (motor desligado): ${v(r.tensao)} — ${rotulo(r.veredito)}")
            appendLine(if (r.estavel) "" else " (tensão instável: ${v(r.minima)} a ${v(r.maxima)})")
            if (r.motorPareceLigado) appendLine("  Atenção: acima de 13,2 V o motor parecia ligado; refaça com o motor desligado")
        }
        val p = teste.resultadoPartida
        if (p == null) {
            appendLine("• Partida: não realizada")
        } else {
            appendLine("• Partida: vale de ${v(p.vMin)} (antes ${v(p.tensaoInicial)}) — ${rotulo(p.veredito)}")
            appendLine("  " + textoQueda(p.duracaoQuedaMs, p.motorPegou))
            if (p.lvResets > 0) {
                appendLine(
                    "  O adaptador reiniciou por subtensão (LV RESET) ${p.lvResets}× durante a partida: " +
                        "o vale real pode ter sido menor que o registrado",
                )
            }
        }
        appendLine()

        appendLine("ALTERNADOR: ${vereditoAlternador(teste).titulo}")
        linhaCarga("Marcha lenta", teste.resultadoLenta)
        linhaCarga("~2.500 RPM com cargas", teste.resultadoCarga)
        appendLine()

        appendLine("Referências: repouso ≥ 12,4 V saudável, 12,0–12,3 V carga baixa, < 12,0 V crítica;")
        appendLine("partida ≥ 10,0 V OK, 9,6–9,9 V atenção, < 9,6 V fraca;")
        appendLine("alternador 13,6–14,6 V normal, < 13,5 V subcarga, > 14,8 V sobrecarga.")
        append("Tensões lidas pelo ELM327 (ATRV, resolução de 0,1 V); ripple estimado pela variação entre amostras.")
    }

    private fun StringBuilder.linhaCarga(nome: String, c: ResultadoCarga?) {
        if (c == null) {
            appendLine("• $nome: não realizado")
            return
        }
        appendLine("• $nome: média ${v(c.media)} · pico ${v(c.pico)} · ripple ~${v(c.ripple)} — ${rotulo(c.veredito)}")
        if (c.rippleAlto) appendLine("  Ripple alto: possível diodo da ponte retificadora ou regulador com defeito")
    }

    /** "12,52 V". */
    fun v(volts: Double): String = String.format(localeBr, "%.2f V", volts)

    /** Queda abaixo de 1 amostra (< 0,3 s) vira "Queda rápida"; senão a duração medida. */
    internal fun textoQueda(duracaoMs: Long, motorPegou: Boolean): String {
        val desfecho = if (motorPegou) "motor pegou" else "motor não pegou no tempo do teste"
        val queda = if (duracaoMs < QUEDA_RAPIDA_MS) "Queda rápida (< 0,3 s)" else "Queda de ${segundos(duracaoMs)}"
        return "$queda; $desfecho"
    }

    private const val QUEDA_RAPIDA_MS = 300L

    private fun segundos(ms: Long): String = String.format(localeBr, "%.1f s", ms / 1000.0)
}
