package com.charruamotors.oficina.replay

import java.io.Reader
import java.io.StringReader

/**
 * Coluna de sensor de um log: o cabeçalho do Data Logger é `Nome (unidade) [comando]`; em CSVs de
 * outras fontes, o texto inteiro vira o [nome].
 */
data class ColunaLog(val nome: String, val unidade: String, val comando: String?, val cabecalho: String) {
    /** Rótulo curto para chips e legendas: "RPM do motor (rpm)". */
    val rotulo: String get() = if (unidade.isBlank()) nome else "$nome ($unidade)"

    companion object {
        private val regexCabecalho = Regex("""^(.*?)\s*\(([^()]*)\)\s*(?:\[([^\]]*)])?\s*$""")

        fun deCabecalho(texto: String): ColunaLog {
            val limpo = texto.trim()
            val m = regexCabecalho.matchEntire(limpo)
                ?: return ColunaLog(limpo, "", null, limpo)
            val nome = m.groupValues[1].ifBlank { limpo }
            return ColunaLog(nome, m.groupValues[2].trim(), m.groupValues[3].trim().ifEmpty { null }, limpo)
        }
    }
}

/**
 * Log carregado: [instantesMs] em ordem cronológica (epoch, ou relativo quando o CSV só tem
 * `tempo_s`) e uma série por coluna de sensor (`NaN` = sem leitura naquele ciclo).
 */
class LogCsv(
    val colunas: List<ColunaLog>,
    val instantesMs: LongArray,
    val series: List<DoubleArray>,
    /** O CSV tinha `timestamp_ms` (relógio real) e não só o tempo relativo. */
    val tempoAbsoluto: Boolean,
    /** Linhas descartadas por estarem malformadas (campos a menos, tempo ilegível). */
    val linhasIgnoradas: Int = 0,
) {
    val amostras: Int get() = instantesMs.size
    val inicioMs: Long get() = instantesMs.first()
    val fimMs: Long get() = instantesMs.last()

    /** Índices das colunas com pelo menos uma leitura (as que valem a pena virar chip). */
    val colunasComDados: List<Int> get() = series.indices.filter { i -> series[i].any { !it.isNaN() } }
}

class CsvInvalidoException(mensagem: String) : Exception(mensagem)

/**
 * Lê um CSV do Data Logger do app (RFC 4180, `,` e ponto decimal, BOM UTF-8, `\r\n`) e também os
 * reexportados pelo Excel em pt-BR (`;` e vírgula decimal). O cabeçalho é lido dinamicamente:
 * `timestamp_ms`, `data_hora` e `tempo_s` são colunas de tempo; as demais, sensores. Leitura em
 * streaming (o Data Logger chega a dezenas de milhares de linhas).
 */
object LeitorCsvLog {

    const val MAX_LINHAS = 200_000

    private val COLUNAS_TEMPO = setOf("timestamp_ms", "data_hora", "tempo_s")

    fun ler(texto: String): LogCsv = ler(StringReader(texto))

    fun ler(leitor: Reader): LogCsv {
        val registros = RegistrosCsv(leitor)
        val primeira = registros.proximoBruto() ?: throw CsvInvalidoException("Arquivo vazio")
        val separador = detectarSeparador(primeira)
        val cabecalho = dividir(primeira, separador).map { it.trim().removePrefix("\uFEFF").trim() }
        if (cabecalho.isEmpty() || cabecalho.all { it.isEmpty() }) throw CsvInvalidoException("Cabeçalho vazio")

        val indiceTimestamp = cabecalho.indexOfFirst { it.equals("timestamp_ms", ignoreCase = true) }
        val indiceTempo = cabecalho.indexOfFirst { it.equals("tempo_s", ignoreCase = true) }
        val indicesSensores = cabecalho.indices.filter { cabecalho[it].lowercase() !in COLUNAS_TEMPO && cabecalho[it].isNotEmpty() }
        if (indicesSensores.isEmpty()) throw CsvInvalidoException("Nenhuma coluna de sensor no cabeçalho")
        val decimalVirgula = separador == ';'

        val instantes = LongLista()
        val valores = List(indicesSensores.size) { DoubleLista() }
        var ignoradas = 0
        var linha = 0
        while (true) {
            val bruto = registros.proximoBruto() ?: break
            if (bruto.isBlank()) continue
            if (++linha > MAX_LINHAS) throw CsvInvalidoException("Log grande demais (mais de $MAX_LINHAS linhas)")
            val campos = dividir(bruto, separador)
            val instante = when {
                indiceTimestamp >= 0 -> campos.getOrNull(indiceTimestamp)?.trim()?.toLongOrNull()
                indiceTempo >= 0 -> numero(campos.getOrNull(indiceTempo), decimalVirgula)
                    .takeUnless { it.isNaN() }?.let { Math.round(it * 1000) }
                else -> (linha - 1) * 1000L // sem coluna de tempo: 1 amostra por segundo
            }
            // Campos finais faltando viram NaN; uma linha de um campo só (lixo, rodapé) não é amostra.
            if (instante == null || (cabecalho.size > 1 && campos.size < 2)) {
                ignoradas++
                continue
            }
            instantes += instante
            indicesSensores.forEachIndexed { s, i -> valores[s] += numero(campos.getOrNull(i), decimalVirgula) }
        }
        if (instantes.tamanho == 0) throw CsvInvalidoException("O arquivo não tem amostras")

        val ordenado = ordenarSeNecessario(instantes.paraArray(), valores.map { it.paraArray() })
        return LogCsv(
            colunas = indicesSensores.map { ColunaLog.deCabecalho(cabecalho[it]) },
            instantesMs = ordenado.first,
            series = ordenado.second,
            tempoAbsoluto = indiceTimestamp >= 0,
            linhasIgnoradas = ignoradas,
        )
    }

    /** `;` quando o cabeçalho só tem ponto e vírgula fora de aspas (Excel pt-BR); senão `,`. */
    internal fun detectarSeparador(cabecalho: String): Char {
        var aspas = false
        var virgulas = 0
        var pontoVirgulas = 0
        for (c in cabecalho) {
            when {
                c == '"' -> aspas = !aspas
                aspas -> Unit
                c == ',' -> virgulas++
                c == ';' -> pontoVirgulas++
            }
        }
        return if (pontoVirgulas > virgulas) ';' else ','
    }

    /** Divide um registro em campos respeitando aspas (`""` dentro de aspas = uma aspa). */
    internal fun dividir(registro: String, separador: Char): List<String> {
        val campos = mutableListOf<String>()
        val atual = StringBuilder()
        var aspas = false
        var i = 0
        while (i < registro.length) {
            val c = registro[i]
            when {
                aspas && c == '"' && registro.getOrNull(i + 1) == '"' -> {
                    atual.append('"')
                    i++
                }
                c == '"' -> aspas = !aspas
                !aspas && c == separador -> {
                    campos += atual.toString()
                    atual.setLength(0)
                }
                else -> atual.append(c)
            }
            i++
        }
        campos += atual.toString()
        return campos
    }

    /** Campo numérico; vazio/ilegível = `NaN`. Com vírgula decimal, `1.234,5` também é aceito. */
    internal fun numero(campo: String?, decimalVirgula: Boolean): Double {
        val t = campo?.trim()?.takeIf { it.isNotEmpty() } ?: return Double.NaN
        val normalizado = if (decimalVirgula) t.replace(".", "").replace(',', '.') else t
        return normalizado.toDoubleOrNull() ?: Double.NaN
    }

    /** Logs concatenados ou editados podem vir fora de ordem: ordena por tempo, preservando as linhas. */
    private fun ordenarSeNecessario(instantes: LongArray, series: List<DoubleArray>): Pair<LongArray, List<DoubleArray>> {
        val ordenado = (1 until instantes.size).all { instantes[it] >= instantes[it - 1] }
        if (ordenado) return instantes to series
        val ordem = instantes.indices.sortedBy { instantes[it] }
        return LongArray(ordem.size) { instantes[ordem[it]] } to series.map { s -> DoubleArray(ordem.size) { s[ordem[it]] } }
    }

    /**
     * Quebra o fluxo em registros: `\r\n`, `\n` ou `\r` fora de aspas encerram o registro; dentro
     * de aspas a quebra faz parte do campo.
     */
    private class RegistrosCsv(private val leitor: Reader) {
        private val buffer = CharArray(8192)
        private var tamanho = 0
        private var posicao = 0
        private var fim = false

        private fun proximoChar(): Int {
            if (posicao >= tamanho) {
                if (fim) return -1
                tamanho = leitor.read(buffer)
                posicao = 0
                if (tamanho <= 0) {
                    fim = true
                    return -1
                }
            }
            return buffer[posicao++].code
        }

        private var pendente = -1

        fun proximoBruto(): String? {
            val sb = StringBuilder()
            var aspas = false
            var leuAlgo = false
            while (true) {
                val c = if (pendente >= 0) pendente.also { pendente = -1 } else proximoChar()
                if (c < 0) return if (leuAlgo) sb.toString() else null
                leuAlgo = true
                val ch = c.toChar()
                if (ch == '"') aspas = !aspas
                if (!aspas && (ch == '\n' || ch == '\r')) {
                    if (ch == '\r') {
                        val prox = proximoChar()
                        if (prox >= 0 && prox.toChar() != '\n') pendente = prox
                    }
                    return sb.toString()
                }
                sb.append(ch)
            }
        }
    }

    private class LongLista {
        private var dados = LongArray(1024)
        var tamanho = 0
            private set

        operator fun plusAssign(v: Long) {
            if (tamanho == dados.size) dados = dados.copyOf(dados.size * 2)
            dados[tamanho++] = v
        }

        fun paraArray(): LongArray = dados.copyOf(tamanho)
    }

    private class DoubleLista {
        private var dados = DoubleArray(1024)
        private var tamanho = 0

        operator fun plusAssign(v: Double) {
            if (tamanho == dados.size) dados = dados.copyOf(dados.size * 2)
            dados[tamanho++] = v
        }

        fun paraArray(): DoubleArray = dados.copyOf(tamanho)
    }
}
