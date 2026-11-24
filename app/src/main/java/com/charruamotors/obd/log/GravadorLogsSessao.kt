package com.charruamotors.obd.log

import com.charruamotors.domain.perfis.VinDecodificado
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids
import java.io.File
import java.io.IOException
import java.io.Writer
import java.time.ZoneId
import java.util.TimeZone

/** Resultado do fechamento dos logs de uma sessão. */
data class LogsSessaoSalvos(val arquivos: List<ArquivoLogSalvo>, val erros: List<String>) {
    val txt: ArquivoLogSalvo? get() = arquivos.firstOrNull { it.mime == DestinoLogs.MIME_TXT }
    val csv: ArquivoLogSalvo? get() = arquivos.firstOrNull { it.mime == DestinoLogs.MIME_CSV }
}

/**
 * Logs automáticos de uma sessão OBD ("caixa-preta"), gravados em toda conexão:
 *
 * - **CSV de telemetria** (`<nomeBase>.csv`): mesmo formato do Data Logger ([GeradorCsv]), então o
 *   Visualizador de Logs CSV da v1.2 abre sem mudança. As colunas são fixadas em [iniciarCsv]
 *   (depois do perfil da montadora); as amostras anteriores ficam numa fila e entram no arquivo
 *   na abertura. Cada linha vai direto para o arquivo, com [ArquivoLogAberto.sincronizar] a cada
 *   [intervaloFlushMs] ou [amostrasPorFlush] amostras: se a conexão cair ou o celular reiniciar,
 *   perde-se no máximo esse trecho.
 * - **Relatório de texto** (`<nomeBase>.txt`): gerado em [finalizar] com o cabeçalho, os DTCs, o
 *   resumo estatístico ([EstatisticasViagem]) e o log bruto completo de comunicação e eventos, que
 *   durante a sessão vai sendo escrito num arquivo temporário (horas de log não ocupam memória).
 *   Sai também quando o handshake falha, para o diagnóstico.
 *
 * Thread-safe: o registro de comunicação chega das threads de IO do cliente e a telemetria do
 * coletor da sessão.
 */
class GravadorLogsSessao(
    private val destino: DestinoLogs,
    private val cabecalho: CabecalhoSessao,
    pastaTemporaria: File,
    private val zona: ZoneId = ZoneId.systemDefault(),
    private val intervaloFlushMs: Long = INTERVALO_FLUSH_MS,
    private val amostrasPorFlush: Int = AMOSTRAS_POR_FLUSH,
    private val limitePendentes: Int = LIMITE_PENDENTES,
) {
    val nomeBase: String = RelatorioSessaoTxt.nomeBase(cabecalho.inicioMs, cabecalho.veiculoArquivo, zona, cabecalho.simulado)

    private val trava = Any()
    private val hora = RegistroComunicacao.formatoHora(TimeZone.getTimeZone(zona))

    private val arquivoBruto: File? = runCatching {
        pastaTemporaria.mkdirs()
        File.createTempFile("$nomeBase-", ".bruto", pastaTemporaria)
    }.getOrNull()
    private var bruto: Writer? = arquivoBruto?.let { runCatching { it.bufferedWriter(Charsets.UTF_8) }.getOrNull() }
    private var entradasBrutas = 0

    private var csv: ArquivoLogAberto? = null
    private var nomeCsv: String? = null
    private var colunas: List<DefinicaoPid> = emptyList()
    private var avisoCsv: String? = null
    private val pendentes = ArrayDeque<Pair<Long, Map<String, Double>>>()
    private var amostrasCsv = 0
    private var desdeFlush = 0
    private var ultimoFlushMs = cabecalho.inicioMs

    private val estatisticas = EstatisticasViagem(cabecalho.motor, cabecalho.faixas)
    private var tensaoInicial: Double? = null
    private var conexao: InfoConexao? = null
    private var dtcs: DtcsSessao? = null
    private var vin: String? = null
    private var vinDecodificado: VinDecodificado? = null
    private var sugestaoPerfil: String? = null
    private var finalizado = false

    /** Amostras já gravadas no CSV (as da fila ainda não contam). */
    val amostrasGravadas: Int get() = synchronized(trava) { amostrasCsv }

    /** O CSV desta sessão já foi aberto? */
    val csvAberto: Boolean get() = synchronized(trava) { csv != null }

    // ------------------------------------------------------------------ log bruto

    /** Uma troca/evento do registro de comunicação do cliente (todas, sem o limite do buffer). */
    fun registrarEntrada(entrada: EntradaRegistro): Unit = synchronized(trava) {
        if (finalizado) return@synchronized
        val saida = bruto ?: return@synchronized
        try {
            saida.append(RegistroComunicacao.formatarLinha(entrada, hora)).append('\n')
            entradasBrutas++
        } catch (e: IOException) {
            bruto = null // disco cheio/sem acesso: o relatório avisa
        }
    }

    /** Evento da própria sessão (aguardando ECU, tentativas, encerramento...). */
    fun evento(texto: String, instanteMs: Long): Unit = registrarEntrada(EntradaRegistro.Evento(instanteMs, "Sessão: $texto"))

    // ------------------------------------------------------------------ conexão, DTCs e telemetria

    fun conexaoEstabelecida(info: InfoConexao): Unit = synchronized(trava) { conexao = info }

    fun registrarDtcs(dtcs: DtcsSessao): Unit = synchronized(trava) { this.dtcs = dtcs }

    /** VIN lido (`0902`), o que o WMI/10º caractere revelam e a sugestão de perfil, para o cabeçalho do relatório. */
    fun registrarVin(vin: String?, decodificado: VinDecodificado?, sugestao: String?): Unit = synchronized(trava) {
        this.vin = vin
        this.vinDecodificado = decodificado
        this.sugestaoPerfil = sugestao
    }

    /**
     * Abre o CSV com as [colunas] definitivas da sessão e grava a fila acumulada até aqui.
     * Idempotente (só a primeira chamada vale). Sem colunas não há CSV (o relatório avisa).
     */
    fun iniciarCsv(colunas: List<DefinicaoPid>): Unit = synchronized(trava) {
        if (finalizado || csv != null || avisoCsv != null) return@synchronized
        val unicas = colunas.distinctBy { it.comando }
        if (unicas.isEmpty()) {
            avisoCsv = "a ECU não informou sensores suportados"
            pendentes.clear()
            return@synchronized
        }
        val nome = "$nomeBase.csv"
        val arquivo = try {
            destino.criar(nome, DestinoLogs.MIME_CSV)
        } catch (e: IOException) {
            avisoCsv = "não foi possível criar o arquivo (${e.message})"
            pendentes.clear()
            return@synchronized
        }
        this.colunas = unicas
        csv = arquivo
        nomeCsv = arquivo.nome
        escreverCsv { w -> w.append(GeradorCsv.BOM).append(GeradorCsv.cabecalho(unicas)).append(GeradorCsv.FIM_DE_LINHA) }
        while (pendentes.isNotEmpty()) {
            val (instante, valores) = pendentes.removeFirst()
            escreverLinha(instante, valores)
        }
        sincronizarCsv(ultimoFlushMs)
    }

    /** Uma amostra da telemetria: entra nas estatísticas e no CSV (ou na fila, antes de [iniciarCsv]). */
    fun registrar(dados: ValoresSensores, instanteMs: Long): Unit = synchronized(trava) {
        if (finalizado) return@synchronized
        estatisticas.registrar(dados, instanteMs)
        if (tensaoInicial == null) tensaoInicial = dados[Pids.TENSAO_BATERIA]
        when {
            csv != null -> {
                escreverLinha(instanteMs, dados.valores)
                if (++desdeFlush >= amostrasPorFlush || instanteMs - ultimoFlushMs >= intervaloFlushMs) sincronizarCsv(instanteMs)
            }
            avisoCsv == null -> {
                if (pendentes.size >= limitePendentes) pendentes.removeFirst()
                pendentes.addLast(instanteMs to dados.valores)
            }
        }
    }

    private fun escreverLinha(instanteMs: Long, valores: Map<String, Double>) {
        escreverCsv { w ->
            w.append(GeradorCsv.linha(instanteMs, cabecalho.inicioMs, colunas, valores, zona)).append(GeradorCsv.FIM_DE_LINHA)
        }
        if (csv != null) amostrasCsv++
    }

    private fun sincronizarCsv(instanteMs: Long) {
        val arquivo = csv ?: return
        try {
            arquivo.sincronizar()
        } catch (e: IOException) {
            falhaCsv(e)
        }
        desdeFlush = 0
        ultimoFlushMs = instanteMs
    }

    private inline fun escreverCsv(bloco: (Writer) -> Unit) {
        val arquivo = csv ?: return
        try {
            bloco(arquivo.writer)
        } catch (e: IOException) {
            falhaCsv(e)
        }
    }

    /** Falha de escrita no meio da viagem: fecha o que já foi gravado e segue só com o relatório. */
    private fun falhaCsv(e: IOException) {
        avisoCsv = "gravação interrompida após $amostrasCsv amostras (${e.message})"
        runCatching { csv?.fechar() }
        csv = null
    }

    // ------------------------------------------------------------------ encerramento

    /**
     * Fecha o CSV (sincronizado) e grava o relatório `.txt` com o log bruto. Chamado uma vez, ao fim
     * da sessão (inclusive quando o handshake falhou: [conectou] = false). Nunca lança: os
     * problemas vêm em [LogsSessaoSalvos.erros].
     */
    fun finalizar(fimMs: Long, conectou: Boolean, motivoFim: String): LogsSessaoSalvos = synchronized(trava) {
        if (finalizado) return@synchronized LogsSessaoSalvos(emptyList(), listOf("logs já finalizados"))
        val salvos = mutableListOf<ArquivoLogSalvo>()
        val erros = mutableListOf<String>()

        csv?.let { arquivo ->
            try {
                salvos += arquivo.fechar()
            } catch (e: IOException) {
                erros += "CSV: ${e.message}"
                avisoCsv = avisoCsv ?: "falha ao fechar o arquivo (${e.message})"
            }
        }
        csv = null
        if (conectou && nomeCsv == null && avisoCsv == null) avisoCsv = "sessão encerrada antes da definição das colunas"
        pendentes.clear()

        // Fecha o temporário do log bruto antes de copiá-lo para o relatório.
        val brutoOk = bruto != null
        runCatching { bruto?.close() }
        bruto = null
        finalizado = true

        val resumo = ResumoSessao(
            cabecalho = cabecalho,
            fimMs = fimMs,
            conectou = conectou,
            motivoFim = motivoFim,
            protocolo = conexao?.protocolo,
            camada = conexao?.nomeModo,
            protocoloForcado = conexao?.protocoloForcado,
            tensaoInicial = tensaoInicial,
            dtcs = dtcs,
            estatisticas = estatisticas,
            arquivoCsv = nomeCsv,
            amostrasCsv = amostrasCsv,
            avisoCsv = avisoCsv,
            vin = vin,
            vinDecodificado = vinDecodificado,
            sugestaoPerfil = sugestaoPerfil,
        )
        var txt: ArquivoLogAberto? = null
        try {
            val arquivo = destino.criar("$nomeBase.txt", DestinoLogs.MIME_TXT)
            txt = arquivo
            val w = arquivo.writer
            RelatorioSessaoTxt.escreverResumo(resumo, w, zona)
            when {
                !brutoOk || arquivoBruto == null -> w.append("(log bruto indisponível: não foi possível gravar o arquivo temporário)\n")
                entradasBrutas == 0 -> w.append("(nenhuma troca registrada)\n")
                else -> arquivoBruto.bufferedReader(Charsets.UTF_8).use { it.copyTo(w) }
            }
            w.append('\n').append("— fim do relatório (${entradasBrutas} linhas de log) —\n")
            salvos += arquivo.fechar()
        } catch (e: IOException) {
            txt?.descartar()
            erros += "TXT: ${e.message}"
        } finally {
            arquivoBruto?.delete()
        }
        LogsSessaoSalvos(salvos, erros)
    }

    companion object {
        /** Sincronização periódica do CSV (no máximo ~10 s de trajeto em risco numa queda brusca). */
        const val INTERVALO_FLUSH_MS = 10_000L

        /** ...ou a cada bloco de amostras, o que vier antes (≈ 10 s a 10 leituras/s). */
        const val AMOSTRAS_POR_FLUSH = 100

        /** Amostras guardadas antes de as colunas do CSV serem definidas (≈ 5 min a 10 leituras/s). */
        const val LIMITE_PENDENTES = 3_000
    }
}
