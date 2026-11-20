package com.charruamotors.obd.log

import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.DefinicaoPid

/**
 * Amostra compacta do log: instante (epoch ms) e um valor por coluna da sessão
 * (`NaN` = PID sem resposta naquele ciclo). Um `DoubleArray` evita guardar um `Map` por leitura.
 */
class AmostraLog(val instanteMs: Long, val valores: DoubleArray)

/** Gravação encerrada: colunas fixadas no início e amostras em ordem cronológica. */
class SessaoLog(
    val inicioMs: Long,
    val colunas: List<DefinicaoPid>,
    val amostras: List<AmostraLog>,
) {
    val vazia: Boolean get() = amostras.isEmpty()
}

/**
 * Data Logger em memória: enquanto [gravando], cada ciclo de polling vira uma [AmostraLog].
 * As colunas são fixadas em [iniciar] (os PIDs suportados naquele momento), para que todas as
 * linhas do CSV tenham o mesmo formato.
 *
 * Não é thread-safe: use sempre do mesmo contexto (no app, a main thread do ViewModel).
 *
 * @param limiteAmostras teto de memória; ao atingi-lo, [registrar] devolve `false` e o chamador
 *   deve encerrar a gravação (36 000 amostras ≈ 10 h a 1 leitura/s, poucos MB).
 */
class DataLogger(val limiteAmostras: Int = LIMITE_PADRAO) {

    init {
        require(limiteAmostras > 0) { "limiteAmostras deve ser positivo" }
    }

    private val amostras = ArrayList<AmostraLog>()
    private var colunas: List<DefinicaoPid> = emptyList()
    private var inicioMs = 0L

    var gravando: Boolean = false
        private set

    val quantidade: Int get() = amostras.size

    val cheio: Boolean get() = amostras.size >= limiteAmostras

    /** Começa uma nova sessão, descartando qualquer gravação não encerrada. */
    fun iniciar(colunas: List<DefinicaoPid>, instanteMs: Long) {
        require(colunas.isNotEmpty()) { "O log precisa de ao menos uma coluna" }
        require(colunas.map { it.comando }.toSet().size == colunas.size) { "Colunas repetidas no log" }
        this.colunas = colunas.toList()
        amostras.clear()
        inicioMs = instanteMs
        gravando = true
    }

    /**
     * Registra uma leitura. Comandos fora das colunas da sessão são ignorados.
     * @return `false` se não estiver gravando ou se o limite foi atingido (amostra descartada).
     */
    fun registrar(dados: ValoresSensores, instanteMs: Long): Boolean {
        if (!gravando || cheio) return false
        val valores = DoubleArray(colunas.size) { i -> dados.valores[colunas[i].comando] ?: Double.NaN }
        amostras += AmostraLog(instanteMs, valores)
        return true
    }

    /** Encerra a gravação e devolve a sessão; null se não estava gravando. */
    fun parar(): SessaoLog? {
        if (!gravando) return null
        gravando = false
        val sessao = SessaoLog(inicioMs, colunas, amostras.toList())
        amostras.clear()
        return sessao
    }

    /** Descarta a gravação em andamento sem gerar sessão. */
    fun descartar() {
        gravando = false
        amostras.clear()
    }

    companion object {
        const val LIMITE_PADRAO = 36_000
    }
}
