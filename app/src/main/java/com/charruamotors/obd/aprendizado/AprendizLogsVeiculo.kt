package com.charruamotors.obd.aprendizado

import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.LeitorCsvLog
import com.charruamotors.oficina.replay.LogCsv
import java.io.File
import java.io.IOException
import java.io.Reader
import kotlin.math.roundToLong

/**
 * Aprende, por veículo, com os CSVs **reais** das sessões (v2.2.4), para o Modo Simulado reproduzir
 * o comportamento daquele carro.
 *
 * Isolamento estrito: um modelo só consome as [EntradaLogReal] do seu `veiculoId` no [indice] (que
 * nunca recebe sessões simuladas), e cada um vive no seu próprio `modelo_aprendido_<veiculoId>.json`.
 * Nada de um veículo chega ao outro.
 *
 * Incremental: o cache guarda os nomes dos CSVs já incorporados; [atualizar] só lê os novos. CSV
 * corrompido/vazio vai para [ModeloAprendido.ignorados] e não derruba nada; cache ilegível é refeito
 * a partir dos CSVs.
 *
 * @param abrir devolve o leitor do CSV (null = arquivo sumiu: pula, sem marcar como ignorado)
 */
class AprendizLogsVeiculo(
    private val pasta: File,
    private val indice: IndiceLogsReais,
    private val abrir: (EntradaLogReal) -> Reader?,
) {

    fun arquivoCache(veiculoId: Long): File = File(pasta, "modelo_aprendido_$veiculoId.json")

    /** Só o cache em disco (null = nunca aprendeu ou cache ilegível). Não lê CSV. */
    @Synchronized
    fun carregar(veiculoId: Long): ModeloAprendido? {
        val arquivo = arquivoCache(veiculoId)
        if (!arquivo.isFile) return null
        val modelo = try {
            ModeloAprendido.deJson(arquivo.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        }
        return modelo?.takeIf { it.veiculoId == veiculoId }
    }

    /**
     * Cache do veículo atualizado com os CSVs reais ainda não incorporados. null = o veículo não tem
     * nenhum CSV real registrado (o Modo Simulado fica sintético). Nunca lança.
     */
    @Synchronized
    fun atualizar(veiculoId: Long): ModeloAprendido? {
        if (veiculoId <= 0) return null
        val entradas = indice.entradas(veiculoId)
        if (entradas.isEmpty()) return null
        var modelo = carregar(veiculoId) ?: ModeloAprendido.vazio(veiculoId)
        val conhecidos = (modelo.logs.map { it.nome } + modelo.ignorados).toSet()
        var mudou = false
        for (entrada in entradas.filter { it.nome !in conhecidos }) {
            val leitor = try {
                abrir(entrada)
            } catch (_: Exception) {
                null
            } ?: continue
            modelo = try {
                val log = leitor.use { LeitorCsvLog.ler(it) }
                incorporar(modelo, entrada.nome, log) ?: modelo.copy(ignorados = modelo.ignorados + entrada.nome)
            } catch (_: Exception) {
                // CSV corrompido, grande demais ou ilegível: fica de fora, o resto do aprendizado segue.
                modelo.copy(ignorados = modelo.ignorados + entrada.nome)
            }
            mudou = true
        }
        if (mudou || !arquivoCache(veiculoId).isFile) salvar(modelo)
        return modelo
    }

    // ------------------------------------------------------------------ consolidação

    private class Acumulador {
        var n = 0
        var soma = 0.0
        var somaQuad = 0.0
        var min = Double.NaN
        var max = Double.NaN

        fun somar(v: Double) {
            n++
            soma += v
            somaQuad += v * v
            if (min.isNaN() || v < min) min = v
            if (max.isNaN() || v > max) max = v
        }

        fun paraEstatistica() = EstatisticaFaixa(n, soma, somaQuad, min, max)
    }

    /** null = o CSV não tem amostras/sensores utilizáveis (vira ignorado). */
    private fun incorporar(modelo: ModeloAprendido, nome: String, log: LogCsv): ModeloAprendido? {
        val colunas = log.colunas.indices.filter { !log.colunas[it].comando.isNullOrBlank() }
        if (log.amostras < MIN_AMOSTRAS || colunas.isEmpty()) return null
        fun indiceDe(cmd: String) = log.colunas.indexOfFirst { it.comando == cmd }
        val iRpm = indiceDe(Pids.RPM)
        val iVel = indiceDe(Pids.VELOCIDADE)
        val iCarga = indiceDe(Pids.CARGA_MOTOR)
        fun valor(i: Int, amostra: Int) = if (i < 0) Double.NaN else log.series[i][amostra]

        val acumuladores = HashMap<RegimeConducao, HashMap<String, Acumulador>>()
        for (a in 0 until log.amostras) {
            val regime = RegimeConducao.classificar(valor(iRpm, a), valor(iVel, a), valor(iCarga, a)) ?: continue
            val porComando = acumuladores.getOrPut(regime) { HashMap() }
            for (c in colunas) {
                val v = log.series[c][a]
                if (v.isFinite()) porComando.getOrPut(log.colunas[c].comando!!) { Acumulador() }.somar(v)
            }
        }
        val faixas = RegimeConducao.entries.associateWith { regime ->
            val antigas = modelo.faixas[regime].orEmpty()
            val novas = acumuladores[regime].orEmpty().mapValues { it.value.paraEstatistica() }
            (antigas.keys + novas.keys).associateWith { cmd ->
                (antigas[cmd] ?: EstatisticaFaixa()).unir(novas[cmd] ?: EstatisticaFaixa())
            }
        }.filterValues { it.isNotEmpty() }

        val trechos = (modelo.trechos + listOfNotNull(extrairTrecho(log, colunas, iRpm, iVel))).takeLast(MAX_TRECHOS)
        return modelo.copy(
            logs = modelo.logs + LogIncorporado(nome, log.amostras),
            faixas = faixas,
            trechos = trechos,
        )
    }

    /**
     * Melhor janela de [JANELA_S] s (a de maior variação de velocidade; sem velocidade, de RPM),
     * reamostrada a 1 s com o último valor conhecido de cada sensor. null = sem RPM ou curto demais.
     */
    private fun extrairTrecho(log: LogCsv, colunas: List<Int>, iRpm: Int, iVel: Int): TrechoReal? {
        if (iRpm < 0) return null
        val inicio = log.instantesMs.first()
        val segundos = ((log.instantesMs.last() - inicio) / 1000).toInt() + 1
        if (segundos < MIN_SEGUNDOS_TRECHO) return null

        val ultimo = DoubleArray(colunas.size) { Double.NaN }
        val grade = ArrayList<DoubleArray>(segundos)
        var r = 0
        for (k in 0 until segundos) {
            val limite = inicio + k * 1000L
            while (r < log.amostras && log.instantesMs[r] <= limite) {
                for ((j, c) in colunas.withIndex()) {
                    val v = log.series[c][r]
                    if (v.isFinite()) ultimo[j] = v
                }
                r++
            }
            grade += ultimo.copyOf()
        }

        val colRpm = colunas.indexOf(iRpm)
        val colRef = if (iVel >= 0) colunas.indexOf(iVel) else colRpm
        val tamanho = minOf(JANELA_S, grade.size)
        var melhorInicio = 0
        var melhorNota = -1.0
        var ini = 0
        while (ini + tamanho <= grade.size) {
            val valores = (ini until ini + tamanho).map { grade[it][colRef] }.filter { it.isFinite() }
            val nota = if (valores.size < tamanho / 2) -0.5 else valores.max() - valores.min()
            if (nota > melhorNota) {
                melhorNota = nota
                melhorInicio = ini
            }
            ini += PASSO_JANELA_S
        }
        val janela = grade.subList(melhorInicio, melhorInicio + tamanho)
        if (janela.none { it[colRpm].isFinite() }) return null
        val uteis = colunas.indices.filter { j -> janela.any { it[j].isFinite() } }
        return TrechoReal(
            comandos = uteis.map { log.colunas[colunas[it]].comando!! },
            linhas = janela.map { linha -> DoubleArray(uteis.size) { arredondar(linha[uteis[it]]) } },
        )
    }

    private fun arredondar(v: Double) = if (v.isFinite()) (v * 1000).roundToLong() / 1000.0 else Double.NaN

    private fun salvar(modelo: ModeloAprendido) {
        try {
            pasta.mkdirs()
            val destino = arquivoCache(modelo.veiculoId)
            val tmp = File(pasta, destino.name + ".tmp")
            tmp.writeText(modelo.paraJson(), Charsets.UTF_8)
            if (!tmp.renameTo(destino)) {
                destino.writeText(modelo.paraJson(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: IOException) {
            // Sem cache em disco o modelo vale só nesta chamada; a próxima reconstrói.
        }
    }

    companion object {
        /** CSV com menos amostras que isso não ensina nada. */
        const val MIN_AMOSTRAS = 10
        const val MIN_SEGUNDOS_TRECHO = 10
        const val JANELA_S = 90
        private const val PASSO_JANELA_S = 30

        /** Trechos guardados por veículo (os mais recentes): mantém o cache leve (~100 KB). */
        const val MAX_TRECHOS = 4
    }
}
