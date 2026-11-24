package com.charruamotors.obd.log

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Arquivo de log listável (CSV ou relatório `.txt`), independente de Android para ser testável na JVM. */
interface ItemLog {
    val nome: String

    /** Data de modificação em milissegundos (fallback quando o nome não traz o horário). */
    val modificadoMs: Long
}

/** Linha da lista de logs: o cabeçalho retrátil "Simulados (X arquivos)" ou um arquivo. */
sealed interface LinhaLog<out T : ItemLog> {
    data class CabecalhoSimulados(val quantidade: Int, val expandido: Boolean) : LinhaLog<Nothing>
    data class Arquivo<T : ItemLog>(val item: T, val simulado: Boolean) : LinhaLog<T>
}

/**
 * Regras das listas de logs: reconhece os arquivos gerados no Modo Simulado (marca `_SIMULADO_` no
 * nome), ordena do mais recente para o mais antigo e monta a lista com a seção retrátil dos
 * simulados no topo e os logs reais logo abaixo.
 *
 * Nomes de sessão: `CharruaOBD_[SIMULADO_]YYYYMMDD_HHMMSS_<Veiculo>.csv|txt`; Data Logger manual:
 * `charrua_obd_[SIMULADO_]YYYY-MM-DD_HH-MM-SS.csv`.
 */
object OrganizadorLogs {

    /** Trecho que sinaliza um log gravado com o scanner simulado. */
    const val MARCA_SIMULADO = "SIMULADO"

    private val regexSimulado = Regex("_${MARCA_SIMULADO}_", RegexOption.IGNORE_CASE)
    private val regexSessao = Regex("(\\d{8}_\\d{6})")
    private val regexDataLogger = Regex("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})")
    private val formatoSessao = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val formatoDataLogger = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun ehSimulado(nome: String): Boolean = regexSimulado.containsMatchIn(nome)

    /**
     * Instante do log: o horário gravado no nome (início da sessão) quando existir — o
     * `DATE_MODIFIED` do MediaStore muda se o arquivo for reindexado ou copiado —, senão a data de modificação.
     */
    fun instante(item: ItemLog, zona: ZoneId = ZoneId.systemDefault()): Long =
        instanteDoNome(item.nome, zona) ?: item.modificadoMs

    fun instanteDoNome(nome: String, zona: ZoneId = ZoneId.systemDefault()): Long? {
        val sessao = regexSessao.find(nome)?.value?.let { analisar(it, formatoSessao, zona) }
        return sessao ?: regexDataLogger.find(nome)?.value?.let { analisar(it, formatoDataLogger, zona) }
    }

    private fun analisar(texto: String, formato: DateTimeFormatter, zona: ZoneId): Long? = try {
        LocalDateTime.parse(texto, formato).atZone(zona).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** Do mais recente (última viagem) para o mais antigo; empate (`.csv` e `.txt` da mesma sessão) pelo nome. */
    fun <T : ItemLog> ordenar(lista: List<T>, zona: ZoneId = ZoneId.systemDefault()): List<T> =
        lista.sortedWith(
            compareByDescending<T> { instante(it, zona) }
                .thenByDescending { it.nome },
        )

    /** Logs reais e simulados, cada grupo ordenado do mais recente para o mais antigo. */
    fun <T : ItemLog> separar(lista: List<T>, zona: ZoneId = ZoneId.systemDefault()): Pair<List<T>, List<T>> {
        val (simulados, reais) = ordenar(lista, zona).partition { ehSimulado(it.nome) }
        return reais to simulados
    }

    /**
     * Linhas da lista: com simulados, o cabeçalho "Simulados (X arquivos)" no topo (seguido dos
     * arquivos simulados se [expandido]); depois, os logs reais.
     */
    fun <T : ItemLog> montarLinhas(lista: List<T>, expandido: Boolean, zona: ZoneId = ZoneId.systemDefault()): List<LinhaLog<T>> {
        val (reais, simulados) = separar(lista, zona)
        return buildList {
            if (simulados.isNotEmpty()) {
                add(LinhaLog.CabecalhoSimulados(simulados.size, expandido))
                if (expandido) simulados.forEach { add(LinhaLog.Arquivo(it, simulado = true)) }
            }
            reais.forEach { add(LinhaLog.Arquivo(it, simulado = false)) }
        }
    }
}
