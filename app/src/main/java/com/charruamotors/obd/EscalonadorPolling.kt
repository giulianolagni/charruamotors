package com.charruamotors.obd

import com.charruamotors.obd.pid.Pids

/**
 * Polling em duas filas, para manter os mostradores fluidos num ELM327 real (cada PID custa
 * ~50–150 ms no Bluetooth; ler os ~30 PIDs suportados em todo ciclo deixaria os ponteiros
 * atualizando a cada 3–5 s):
 *
 * - **fila rápida** (todo ciclo): só os mostradores dinâmicos entre os [candidatosRapidos]
 *   suportados — RPM, MAP/turbo e velocidade (mais o MAF, quando o consumo é calculado por ele;
 *   ver [Pids.candidatosFilaRapida]);
 * - **fila lenta** (rodízio): os demais PIDs suportados (temperaturas, carga, rail, tensão...),
 *   [lentosPorVez] a cada [ciclosPorLento] ciclos, em ordem circular. Com o padrão (1 lento a
 *   cada 3 ciclos), cada ciclo de 3 comandos rápidos ganha no máximo 1 comando extra e o RPM e o
 *   turbo não perdem fluidez. Sem nenhum PID rápido suportado, a fila lenta anda em todo ciclo.
 *
 * - **fila leve** (opcional, [leves]): sensores de outro módulo CAN (câmbio `7E1`); cada visita custa
 *   `ATSH`/`ATCRA` de ida e volta, então só entram a cada [ciclosPorLeve] ciclos, sem travar a fila rápida.
 *
 * Também consolida as respostas num instantâneo com o último valor conhecido de cada PID
 * (amostra e retenção): um PID consultado que não respondeu sai do instantâneo, para a UI
 * não exibir um valor velho como se fosse atual.
 *
 * Objeto puro e não thread-safe (cada fluxo de polling tem o seu), testável na JVM.
 */
class EscalonadorPolling(
    private val lentosPorVez: Int = LENTOS_POR_VEZ,
    private val ciclosPorLento: Int = CICLOS_POR_LENTO,
    private val candidatosRapidos: List<String> = Pids.candidatosFilaRapida(consumoPorMaf = true),
    /** Sensores de outro módulo (câmbio `7E1`): fila própria, em cadência mais leve que a lenta. */
    private val leves: Set<String> = emptySet(),
    private val ciclosPorLeve: Int = CICLOS_POR_LEVE,
) {
    init {
        require(lentosPorVez >= 1) { "A fila lenta precisa andar ao menos 1 PID por vez" }
        require(ciclosPorLento >= 1) { "A fila lenta precisa andar ao menos a cada ciclo" }
        require(ciclosPorLeve >= 1) { "A fila leve precisa andar ao menos a cada ciclo" }
    }

    /** PIDs lidos em todo ciclo. */
    var filaRapida: List<String> = emptyList()
        private set

    /** PIDs lidos em rodízio (um representante por grupo, ver [atualizarSuportados]). */
    var filaLenta: List<String> = emptyList()
        private set

    /** PIDs de outro módulo, lidos juntos a cada [ciclosPorLeve] ciclos (um representante por grupo). */
    var filaLeve: List<String> = emptyList()
        private set

    private var suportados: Set<String> = emptySet()
    private var cursor = 0

    /** Ciclos já montados; a fila lenta anda quando `ciclos % ciclosPorLento == 0`. */
    private var ciclos = 0L
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
        filaRapida = candidatosRapidos.filter { it in conjunto }.distinct()
        val rapidos = filaRapida.toSet()
        filaLeve = conjunto.filter { it in leves && it !in rapidos }.map(grupoDe).distinct()
        filaLenta = conjunto.filter { it !in rapidos && it !in leves }.map(grupoDe).distinct()
        cursor = if (filaLenta.isEmpty()) 0 else cursor % filaLenta.size
        instantaneo.keys.retainAll(conjunto)
    }

    /** Comandos do próximo ciclo: toda a fila rápida + a vez da fila lenta (1 a cada [ciclosPorLento]). */
    fun proximoCiclo(): List<String> {
        val vezDaLenta = filaRapida.isEmpty() || ciclos % ciclosPorLento == 0L
        val vezDaLeve = filaLeve.isNotEmpty() && ciclos % ciclosPorLeve == 0L
        ciclos++
        val base = if (vezDaLenta) filaRapida + proximosLentos() else filaRapida
        return if (vezDaLeve) base + filaLeve else base
    }

    private fun proximosLentos(): List<String> {
        if (filaLenta.isEmpty()) return emptyList()
        if (lentosPorVez >= filaLenta.size) return filaLenta
        val vez = List(lentosPorVez) { filaLenta[(cursor + it) % filaLenta.size] }
        cursor = (cursor + lentosPorVez) % filaLenta.size
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
        get() {
            if (filaLenta.isEmpty()) return 1
            val vezes = (filaLenta.size + lentosPorVez - 1) / lentosPorVez
            return if (filaRapida.isEmpty()) vezes else vezes * ciclosPorLento
        }

    companion object {
        /** PIDs lentos lidos quando chega a vez da fila lenta. */
        const val LENTOS_POR_VEZ = 1

        /** A fila lenta anda 1 vez a cada tantos ciclos rápidos. */
        const val CICLOS_POR_LENTO = 3

        /** A fila leve (câmbio `7E1`, que exige trocar o cabeçalho) é lida 1 vez a cada tantos ciclos. */
        const val CICLOS_POR_LEVE = 4
    }
}
