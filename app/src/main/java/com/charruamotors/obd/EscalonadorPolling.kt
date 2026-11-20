package com.charruamotors.obd

import com.charruamotors.obd.pid.Pids

/**
 * Polling em duas filas, para manter os mostradores fluidos num ELM327 real (cada PID custa
 * ~50–150 ms no Bluetooth; ler os ~30 PIDs suportados em todo ciclo deixaria os ponteiros
 * atualizando a cada 3–5 s):
 *
 * - **fila rápida** (todo ciclo): os [maxRapidos] primeiros [candidatosRapidos] suportados —
 *   RPM, velocidade, MAP/turbo e MAF (ou a borboleta, se faltar MAF ou MAP);
 * - **fila lenta** (rodízio): os demais PIDs suportados, [lentosPorCiclo] por ciclo, em ordem
 *   circular; toda a grade é atualizada a cada `ceil(lentos / lentosPorCiclo)` ciclos.
 *
 * Também consolida as respostas num instantâneo com o último valor conhecido de cada PID
 * (amostra e retenção): um PID consultado que não respondeu sai do instantâneo, para a UI
 * não exibir um valor velho como se fosse atual.
 *
 * Objeto puro e não thread-safe (cada fluxo de polling tem o seu), testável na JVM.
 */
class EscalonadorPolling(
    private val lentosPorCiclo: Int = LENTOS_POR_CICLO,
    private val maxRapidos: Int = MAX_RAPIDOS,
    private val candidatosRapidos: List<String> = Pids.CANDIDATOS_FILA_RAPIDA,
) {
    init {
        require(lentosPorCiclo >= 1) { "A fila lenta precisa andar ao menos 1 PID por ciclo" }
        require(maxRapidos >= 0) { "Tamanho da fila rápida inválido" }
    }

    /** PIDs lidos em todo ciclo. */
    var filaRapida: List<String> = emptyList()
        private set

    /** PIDs lidos em rodízio (um representante por grupo, ver [atualizarSuportados]). */
    var filaLenta: List<String> = emptyList()
        private set

    private var suportados: Set<String> = emptySet()
    private var cursor = 0
    private val instantaneo = LinkedHashMap<String, Double>()

    /**
     * Define os comandos suportados (a ordem é a da fila lenta). Pode ser chamado a qualquer
     * momento (ex.: um perfil de montadora ativado com o polling já rodando); o rodízio continua
     * de onde estava e valores de PIDs que deixaram de ser suportados são descartados.
     *
     * @param grupoDe representante do grupo do comando: PIDs decodificados da mesma resposta
     *   (ex.: injetores 1–4 do Toyota D-4D) ocupam uma única posição na fila lenta.
     */
    fun atualizarSuportados(suportados: Collection<String>, grupoDe: (String) -> String = { it }) {
        val conjunto = suportados.toCollection(linkedSetOf())
        if (conjunto == this.suportados) return
        this.suportados = conjunto
        filaRapida = candidatosRapidos.filter { it in conjunto }.take(maxRapidos)
        val rapidos = filaRapida.toSet()
        filaLenta = conjunto.filter { it !in rapidos }.map(grupoDe).distinct()
        cursor = if (filaLenta.isEmpty()) 0 else cursor % filaLenta.size
        instantaneo.keys.retainAll(conjunto)
    }

    /** Comandos do próximo ciclo: toda a fila rápida + a vez da fila lenta. */
    fun proximoCiclo(): List<String> = filaRapida + proximosLentos()

    private fun proximosLentos(): List<String> {
        if (filaLenta.isEmpty()) return emptyList()
        if (lentosPorCiclo >= filaLenta.size) return filaLenta
        val vez = List(lentosPorCiclo) { filaLenta[(cursor + it) % filaLenta.size] }
        cursor = (cursor + lentosPorCiclo) % filaLenta.size
        return vez
    }

    /**
     * Junta o resultado de um ciclo ao instantâneo: [lidos] atualizam o valor; comandos
     * [consultados] sem resposta são removidos; os não consultados mantêm o último valor.
     */
    fun consolidar(consultados: Collection<String>, lidos: Map<String, Double>): Map<String, Double> {
        for (comando in consultados) {
            val valor = lidos[comando]
            if (valor == null) instantaneo.remove(comando) else instantaneo[comando] = valor
        }
        lidos.forEach { (comando, valor) -> if (comando in suportados) instantaneo[comando] = valor }
        return LinkedHashMap(instantaneo)
    }

    /** Ciclos para varrer toda a fila lenta uma vez. */
    val ciclosPorVolta: Int
        get() = if (filaLenta.isEmpty()) 1 else (filaLenta.size + lentosPorCiclo - 1) / lentosPorCiclo

    companion object {
        const val MAX_RAPIDOS = 4
        const val LENTOS_POR_CICLO = 2
    }
}
