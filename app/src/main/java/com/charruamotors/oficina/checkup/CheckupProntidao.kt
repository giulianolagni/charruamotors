package com.charruamotors.oficina.checkup

import com.charruamotors.obd.EstadoMonitor
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaBruta
import com.charruamotors.obd.RespostaObd
import com.charruamotors.obd.StatusProntidao
import com.charruamotors.obd.log.RelatorioSessaoTxt

/** Envia um comando manual ao ELM327 e devolve a resposta crua; null = falha do adaptador/conexão. */
typealias EnvioManual = suspend (comando: String, timeoutMs: Long) -> RespostaBruta?

/** Quando as ferramentas sob demanda do OBD2 padrão (Checkup, Modo 06) podem rodar nesta conexão. */
object DisponibilidadeCheckup {

    /**
     * Motivo de a ferramenta não rodar (para a tela), ou null se pode. Nos modos 21/22 (SW4 D-4D) não há
     * modo 01/06/09; o Modo 06 por MID ([exigirCan]) só existe no CAN (ISO 15765-4).
     */
    fun motivoIndisponivel(info: InfoConexao, exigirCan: Boolean): String? {
        if (info.modo != ModoComunicacaoEcu.OBD2_PADRAO) {
            return "Indisponível no ${info.nomeModo}: esta ECU não responde ao OBD2 padrão (Modos 01/06/09)."
        }
        if (exigirCan) {
            val p = info.protocolo?.uppercase().orEmpty()
            if ("CAN" !in p && "15765" !in p) {
                return "O Modo 06 por cilindro exige protocolo CAN (ISO 15765-4); este veículo usa ${info.protocolo ?: "outro protocolo"}."
            }
        }
        return null
    }
}

/** Tudo o que o Checkup de Pré-Compra leu; campos null = a ECU não respondeu ou não suporta. */
data class ResultadoCheckup(
    val prontidao: StatusProntidao?,
    val kmComMil: Int?,
    val ciclosAquecimento: Int?,
    val kmDesdeLimpeza: Int?,
    val minutosComMil: Int?,
    val minutosDesdeLimpeza: Int?,
    val vin: String?,
    val calid: String?,
    val cvn: String?,
    /** Comandos sem resposta útil (`NO DATA`, `7F`, sem prompt), para o rodapé do resumo. */
    val semResposta: List<String> = emptyList(),
) {
    val algumaResposta: Boolean
        get() = listOf(prontidao, kmComMil, ciclosAquecimento, kmDesdeLimpeza, minutosComMil, minutosDesdeLimpeza, vin, calid, cvn)
            .any { it != null }

    /**
     * Pontos de atenção de uma pré-compra: luz acesa, códigos guardados, monitores pendentes e sinais
     * de falhas apagadas há pouco (poucos km/ciclos desde a limpeza).
     */
    val alertas: List<String>
        get() = buildList {
            val p = prontidao
            if (p != null && p.milAcesa) add("Luz de injeção ACESA com ${p.quantidadeDtcs} código(s): leia as falhas no Diagnóstico de Erros.")
            if (p != null && !p.milAcesa && p.quantidadeDtcs > 0) add("${p.quantidadeDtcs} código(s) armazenado(s) com a luz apagada.")
            kmComMil?.takeIf { it > 0 }?.let { add("O carro rodou ${km(it)} com a luz de injeção acesa.") }
            val recente = (kmDesdeLimpeza != null && kmDesdeLimpeza < LIMPEZA_RECENTE_KM) ||
                (ciclosAquecimento != null && ciclosAquecimento < LIMPEZA_RECENTE_CICLOS)
            if (recente) {
                add(
                    "Falhas apagadas recentemente: ${kmDesdeLimpeza?.let(::km) ?: "?"} e ${ciclosAquecimento ?: "?"} ciclo(s) de " +
                        "aquecimento desde a última limpeza (ou bateria desligada). Na pré-compra, pergunte o motivo.",
                )
            }
            val pendentes = p?.pendentes.orEmpty()
            if (pendentes.isNotEmpty()) {
                add(
                    "${pendentes.size} monitor(es) pendente(s) (${pendentes.joinToString { it.nome }}): normal logo depois de " +
                        "apagar falhas ou desligar a bateria; o carro ainda não completou os autotestes.",
                )
            }
        }

    companion object {
        /** Menos que isto desde a limpeza dos códigos = limpeza recente. */
        const val LIMPEZA_RECENTE_KM = 200
        const val LIMPEZA_RECENTE_CICLOS = 20

        private fun km(valor: Int) = "${RelatorioSessaoTxt.numero(valor.toDouble(), 0)} km"
    }
}

/**
 * Checkup OBD2 / Prontidão (aba Oficina, sob demanda, polling pausado pela sessão manual): só comandos
 * curtos e seguros para o ELM327 v2.1 — `0101` (MIL, DTCs e monitores), `0121`, `0130`, `0131`, `014D`,
 * `014E` e o modo 09 (`0902` VIN, `0904` CALID, `0906` CVN). Começa com `ATST32` (espera padrão, as
 * respostas multi-frame do modo 09 precisam) e `ATSH7DF`; a sessão manual devolve o adaptador ao fim.
 * Nenhum `NO DATA`/`7F`/timeout interrompe: o campo só fica sem valor. Puro (JVM).
 */
class CheckupProntidao(private val enviar: EnvioManual) {

    suspend fun executar(): ResultadoCheckup {
        PREPARACAO.forEach { enviar(it, TIMEOUT_CURTO_MS) }
        val semResposta = mutableListOf<String>()
        suspend fun <T> ler(comando: String, timeoutMs: Long = TIMEOUT_CURTO_MS, parse: (String) -> RespostaObd<T>): T? {
            val r = enviar(comando, timeoutMs)?.takeIf { !it.semPrompt }?.resposta?.let(parse)
            return (r as? RespostaObd.Ok)?.valor.also { if (it == null) semResposta += comando }
        }
        val prontidao = ler("0101") { ObdParser.parseProntidao(it) }
        val kmComMil = ler("0121") { ObdParser.parseContador(it, "21", 2) }
        val ciclos = ler("0130") { ObdParser.parseContador(it, "30", 1) }
        val kmLimpeza = ler("0131") { ObdParser.parseContador(it, "31", 2) }
        val minComMil = ler("014D") { ObdParser.parseContador(it, "4D", 2) }
        val minLimpeza = ler("014E") { ObdParser.parseContador(it, "4E", 2) }
        val vin = ler("0902", TIMEOUT_MODO_09_MS) { ObdParser.parseVin(it) }
        val calid = ler("0904", TIMEOUT_MODO_09_MS) { ObdParser.parseCalid(it) }
        val cvn = ler("0906", TIMEOUT_MODO_09_MS) { ObdParser.parseCvn(it) }
        return ResultadoCheckup(prontidao, kmComMil, ciclos, kmLimpeza, minComMil, minLimpeza, vin, calid, cvn, semResposta)
    }

    companion object {
        /** Espera padrão do ELM327 (200 ms) e o cabeçalho funcional, como num scanner genérico. */
        val PREPARACAO = listOf("ATST32", "ATSH7DF")
        const val TIMEOUT_CURTO_MS = 2_000L
        const val TIMEOUT_MODO_09_MS = 4_000L

        private const val NAO_SUPORTADO = "não suportado"

        /** Resumo em texto limpo para copiar/compartilhar (laudo de pré-compra). */
        fun resumo(r: ResultadoCheckup, veiculo: String?, dataHora: String): String = buildString {
            appendLine("CHECKUP OBD2 / PRONTIDÃO — Charrua OBD Pro")
            veiculo?.let { appendLine("Veículo: $it") }
            appendLine("Data: $dataHora")
            appendLine()
            val p = r.prontidao
            if (p == null) {
                appendLine("Status da luz de injeção e monitores (0101): $NAO_SUPORTADO")
            } else {
                appendLine("Luz de injeção (MIL): ${if (p.milAcesa) "ACESA" else "apagada"} · códigos armazenados: ${p.quantidadeDtcs}")
                appendLine("Ignição: ${if (p.diesel) "por compressão (diesel)" else "por centelha (gasolina/etanol/flex)"}")
                appendLine()
                appendLine("MONITORES DE PRONTIDÃO")
                for (m in p.monitores) {
                    val marca = when (m.estado) {
                        EstadoMonitor.PRONTO -> "[OK]"
                        EstadoMonitor.PENDENTE -> "[..]"
                        EstadoMonitor.NAO_APLICAVEL -> "[--]"
                    }
                    appendLine("  $marca ${m.nome} — ${m.estado.texto}")
                }
                appendLine("  Resumo: ${p.prontos.size} pronto(s) · ${p.pendentes.size} pendente(s) · ${p.monitores.size - p.suportados.size} não aplicável(is)")
            }
            appendLine()
            appendLine("DESDE A ÚLTIMA LIMPEZA DE FALHAS")
            appendLine("  Distância (0131): ${r.kmDesdeLimpeza?.let { "${RelatorioSessaoTxt.numero(it.toDouble(), 0)} km" } ?: NAO_SUPORTADO}")
            appendLine("  Ciclos de aquecimento (0130): ${r.ciclosAquecimento ?: NAO_SUPORTADO}")
            appendLine("  Tempo de motor (014E): ${r.minutosDesdeLimpeza?.let { "${RelatorioSessaoTxt.numero(it.toDouble(), 0)} min" } ?: NAO_SUPORTADO}")
            appendLine("  Distância com a luz acesa (0121): ${r.kmComMil?.let { "${RelatorioSessaoTxt.numero(it.toDouble(), 0)} km" } ?: NAO_SUPORTADO}")
            appendLine("  Tempo com a luz acesa (014D): ${r.minutosComMil?.let { "${RelatorioSessaoTxt.numero(it.toDouble(), 0)} min" } ?: NAO_SUPORTADO}")
            appendLine()
            appendLine("IDENTIFICAÇÃO DA ECU")
            appendLine("  VIN (0902): ${r.vin ?: NAO_SUPORTADO}")
            appendLine("  CALID (0904): ${r.calid ?: NAO_SUPORTADO}")
            appendLine("  CVN (0906): ${r.cvn ?: NAO_SUPORTADO}")
            val alertas = r.alertas
            appendLine()
            if (alertas.isEmpty()) {
                appendLine("Nenhum ponto de atenção nos dados do OBD2.")
            } else {
                appendLine("PONTOS DE ATENÇÃO")
                alertas.forEach { appendLine("  • $it") }
            }
            if (r.semResposta.isNotEmpty()) {
                appendLine()
                appendLine("Sem resposta da ECU: ${r.semResposta.joinToString()}")
            }
        }.trimEnd()
    }
}
