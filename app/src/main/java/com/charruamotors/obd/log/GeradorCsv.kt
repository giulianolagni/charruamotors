package com.charruamotors.obd.log

import com.charruamotors.obd.pid.DefinicaoPid
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Gera o CSV do Data Logger no formato RFC 4180: separador `,`, decimal `.`, campos com vírgula,
 * aspas ou quebra de linha entre aspas, linhas terminadas em `\r\n`. O arquivo começa com BOM
 * UTF-8 para o Excel reconhecer os acentos dos cabeçalhos.
 *
 * Colunas: `timestamp_ms` (epoch), `data_hora` (ISO 8601 local, com milissegundos),
 * `tempo_s` (segundos desde o início) e um campo por PID: `Nome (unidade) [comando]`.
 * PID sem resposta no ciclo = campo vazio.
 */
object GeradorCsv {

    const val SEPARADOR = ','
    const val FIM_DE_LINHA = "\r\n"
    const val BOM = '﻿'

    private val formatoDataHora: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val formatoNomeArquivo: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun cabecalho(colunas: List<DefinicaoPid>): String =
        (listOf("timestamp_ms", "data_hora", "tempo_s") + colunas.map { "${it.nome} (${it.unidade}) [${it.comando}]" })
            .joinToString(SEPARADOR.toString()) { escapar(it) }

    fun linha(amostra: AmostraLog, sessao: SessaoLog, zona: ZoneId): String {
        require(amostra.valores.size == sessao.colunas.size) { "Amostra com ${amostra.valores.size} valores para ${sessao.colunas.size} colunas" }
        return linha(amostra.instanteMs, sessao.inicioMs, sessao.colunas, zona) { i -> amostra.valores[i] }
    }

    /**
     * Linha de uma leitura avulsa (gravação incremental da sessão automática): o valor de cada
     * coluna vem de [valores] pelo comando; comando ausente = campo vazio.
     */
    fun linha(instanteMs: Long, inicioMs: Long, colunas: List<DefinicaoPid>, valores: Map<String, Double>, zona: ZoneId): String =
        linha(instanteMs, inicioMs, colunas, zona) { i -> valores[colunas[i].comando] ?: Double.NaN }

    private inline fun linha(
        instanteMs: Long,
        inicioMs: Long,
        colunas: List<DefinicaoPid>,
        zona: ZoneId,
        valor: (Int) -> Double,
    ): String {
        val dataHora = formatoDataHora.format(Instant.ofEpochMilli(instanteMs).atZone(zona))
        val tempo = formatarNumero((instanteMs - inicioMs) / 1000.0, 3)
        val campos = colunas.mapIndexed { i, def -> formatarNumero(valor(i), def.casasDecimais) }
        return (listOf(instanteMs.toString(), dataHora, tempo) + campos).joinToString(SEPARADOR.toString())
    }

    /** Escreve o CSV inteiro em [saida] (streaming: não monta o arquivo todo em memória). */
    fun escrever(sessao: SessaoLog, saida: Appendable, zona: ZoneId = ZoneId.systemDefault()) {
        saida.append(BOM).append(cabecalho(sessao.colunas)).append(FIM_DE_LINHA)
        sessao.amostras.forEach { saida.append(linha(it, sessao, zona)).append(FIM_DE_LINHA) }
    }

    fun gerar(sessao: SessaoLog, zona: ZoneId = ZoneId.systemDefault()): String =
        buildString { escrever(sessao, this, zona) }

    /** Ex.: `charrua_obd_2026-11-20_14-30-05.csv` (sem `:`, inválido em nomes de arquivo). */
    fun nomeArquivo(inicioMs: Long, zona: ZoneId = ZoneId.systemDefault()): String =
        "charrua_obd_${formatoNomeArquivo.format(Instant.ofEpochMilli(inicioMs).atZone(zona))}.csv"

    /** Ponto decimal, sem separador de milhar nem notação científica; NaN/infinito = vazio. */
    internal fun formatarNumero(valor: Double, casas: Int): String {
        if (!valor.isFinite()) return ""
        val texto = BigDecimal.valueOf(valor).setScale(casas, RoundingMode.HALF_UP).toPlainString()
        return if (texto.trimStart('-').all { it == '0' || it == '.' }) texto.trimStart('-') else texto // evita "-0.0"
    }

    /** Aspas no campo se ele contiver separador, aspas ou quebra de linha; aspas internas são duplicadas. */
    internal fun escapar(campo: String): String =
        if (campo.any { it == SEPARADOR || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + campo.replace("\"", "\"\"") + "\""
        } else {
            campo
        }
}
