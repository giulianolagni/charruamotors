package com.charruamotors.oficina.checkup

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd

/**
 * Contadores de falha de ignição de um cilindro no Modo 06: [cicloAtual] (`TID 0C`, ciclo de condução
 * atual/último) e [mediaEwma] (`TID 0B`, média móvel dos últimos ~10 ciclos). null = o registro não veio.
 */
data class MisfireCilindro(val cilindro: Int, val mid: Int, val cicloAtual: Int?, val mediaEwma: Int?) {
    val respondeu: Boolean get() = cicloAtual != null || mediaEwma != null
}

data class ResultadoMisfire(val cilindros: List<MisfireCilindro>) {
    val algumaResposta: Boolean get() = cilindros.any { it.respondeu }

    /** Cilindro com mais falhas no ciclo atual (empate: o de maior média); null sem falhas. */
    val pior: MisfireCilindro?
        get() = cilindros.filter { (it.cicloAtual ?: 0) > 0 || (it.mediaEwma ?: 0) > 0 }
            .maxWithOrNull(compareBy<MisfireCilindro>({ it.cicloAtual ?: 0 }, { it.mediaEwma ?: 0 }))
}

/**
 * Falhas de Ignição (Modo 06) sob demanda, com o polling pausado: `ATST32` (espera padrão; as respostas
 * de 2+ quadros chegam sem pressa) e os MIDs padronizados dos cilindros 1–4 (`06A2`–`06A5`, CAN ISO
 * 15765-4). `NO DATA`, `7F 06`, resposta parcial ou sem prompt deixam só aquele cilindro sem valor —
 * nunca travam a leitura; duas falhas do adaptador seguidas (null) encerram. Puro (JVM).
 */
class FalhasIgnicaoModo06(private val enviar: EnvioManual) {

    suspend fun executar(): ResultadoMisfire {
        CheckupProntidao.PREPARACAO.forEach { enviar(it, CheckupProntidao.TIMEOUT_CURTO_MS) }
        val cilindros = mutableListOf<MisfireCilindro>()
        var falhasAdaptador = 0
        for ((i, mid) in MIDS_CILINDROS.withIndex()) {
            val comando = "06%02X".format(mid)
            val bruta = if (falhasAdaptador >= MAX_FALHAS_ADAPTADOR) null else enviar(comando, TIMEOUT_MS)
            if (bruta == null) falhasAdaptador++ else falhasAdaptador = 0
            val registros = bruta?.takeIf { !it.semPrompt }?.resposta
                ?.let { (ObdParser.parseModo06(it, mid) as? RespostaObd.Ok)?.valor }
                .orEmpty()
            cilindros += MisfireCilindro(
                cilindro = i + 1,
                mid = mid,
                cicloAtual = registros.firstOrNull { it.tid == TID_CICLO_ATUAL }?.valor,
                mediaEwma = registros.firstOrNull { it.tid == TID_MEDIA_EWMA }?.valor,
            )
        }
        return ResultadoMisfire(cilindros)
    }

    companion object {
        /** MIDs `A2`–`A5`: falha de ignição dos cilindros 1 a 4 (SAE J1979 / ISO 15031-5). */
        val MIDS_CILINDROS = listOf(0xA2, 0xA3, 0xA4, 0xA5)
        const val TID_MEDIA_EWMA = 0x0B
        const val TID_CICLO_ATUAL = 0x0C
        const val TIMEOUT_MS = 3_000L
        private const val MAX_FALHAS_ADAPTADOR = 2

        fun resumo(r: ResultadoMisfire, veiculo: String?, dataHora: String): String = buildString {
            appendLine("FALHAS DE IGNIÇÃO (MODO 06) — Charrua OBD Pro")
            veiculo?.let { appendLine("Veículo: $it") }
            appendLine("Data: $dataHora")
            appendLine()
            appendLine("Cilindro · ciclo atual (TID 0C) · média EWMA (TID 0B)")
            for (c in r.cilindros) {
                val texto = if (!c.respondeu) {
                    "sem resposta (MID ${"%02X".format(c.mid)})"
                } else {
                    "${c.cicloAtual ?: "—"} falha(s) · média ${c.mediaEwma ?: "—"}"
                }
                appendLine("  #${c.cilindro}: $texto")
            }
            appendLine()
            when {
                !r.algumaResposta -> appendLine("A ECU não respondeu ao Modo 06 por cilindro (MIDs A2–A5).")
                r.pior == null -> appendLine("Nenhuma falha de ignição contada nos cilindros que responderam.")
                else -> appendLine(
                    "Atenção: cilindro #${r.pior!!.cilindro} com mais falhas. Verifique vela, bobina e bico desse cilindro.",
                )
            }
            append("Os contadores zeram ao apagar as falhas ou desligar a bateria; rode alguns km antes de avaliar.")
        }
    }
}
