package com.charruamotors.direcao.desempenho

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.round

/** Fase do cronômetro de aceleração. */
enum class FaseDesempenho {
    /** Ainda em movimento (ou sem leitura): só arma quando o veículo parar. */
    AGUARDANDO_PARADA,

    /** Parado (0 km/h): a contagem dispara sozinha quando sair do lugar. */
    PRONTO,
    MEDINDO,
    CONCLUIDO,

    /** Desacelerou antes de chegar aos 100 km/h. */
    CANCELADO,
}

/** Estado exibido: tempos em segundos com 2 casas; [tempo060] sai assim que cruzar 60 km/h. */
data class EstadoDesempenho(
    val fase: FaseDesempenho = FaseDesempenho.AGUARDANDO_PARADA,
    val tempo060: Double? = null,
    val tempo0100: Double? = null,
    /** Tempo corrido desde a saída, durante [FaseDesempenho.MEDINDO]. */
    val decorrido: Double? = null,
) {
    /** Texto único para o card do Cluster e da Oficina. */
    val texto: String
        get() = when (fase) {
            FaseDesempenho.AGUARDANDO_PARADA -> "Pronto — pare o veículo"
            FaseDesempenho.PRONTO -> "Parado — acelere quando quiser"
            FaseDesempenho.MEDINDO -> buildString {
                append("Medindo...")
                decorrido?.let { append(" ${formatarTempo(it)}") }
                tempo060?.let { append(" · 0–60: ${formatarTempo(it)}") }
            }
            FaseDesempenho.CONCLUIDO -> "0–60: ${formatarTempo(tempo060)} · 0–100: ${formatarTempo(tempo0100)}"
            FaseDesempenho.CANCELADO -> "Teste cancelado" + (tempo060?.let { " · 0–60: ${formatarTempo(it)}" } ?: "")
        }

    companion object {
        fun formatarTempo(segundos: Double?): String =
            if (segundos == null) "—" else String.format(java.util.Locale("pt", "BR"), "%.2f s", segundos)
    }
}

/**
 * Máquina de estados do 0–60 / 0–100 km/h (pura, sem Android): alimentada com a velocidade em
 * km/h e o instante da amostra ([amostrar]). Dispara quando a velocidade sai de 0 para > 0 e
 * interpola linearmente entre as duas amostras que cruzam 60 e 100 km/h, para o tempo não
 * depender da cadência do polling. Cancela se a velocidade cair [TOLERANCIA_KMH] abaixo do pico
 * (troca de marcha causa só um pequeno recuo) ou se passar de [LIMITE_MS].
 */
class CronometroDesempenho(
    private val aoParcial060: (Double) -> Unit = {},
    private val aoConcluir0100: (tempo060: Double, tempo0100: Double) -> Unit = { _, _ -> },
) {
    private val _estado = MutableStateFlow(EstadoDesempenho())
    val estado: StateFlow<EstadoDesempenho> = _estado.asStateFlow()

    private var inicioMs = 0L
    private var ultimaVel = 0.0
    private var ultimoMs = 0L
    private var pico = 0.0
    private var t060Ms: Double? = null

    /** Toque no card: descarta o resultado/teste atual e espera nova parada. */
    @Synchronized
    fun reiniciar() {
        parado = false
        _estado.value = EstadoDesempenho()
    }

    /** Houve uma parada (0 km/h) desde o último teste/conexão: o próximo movimento dispara a contagem. */
    private var parado = false

    /** [velocidadeKmh] null = sem leitura/desconectado: zera o teste. */
    @Synchronized
    fun amostrar(velocidadeKmh: Double?, instanteMs: Long) {
        if (velocidadeKmh == null || !velocidadeKmh.isFinite()) {
            parado = false
            _estado.value = EstadoDesempenho()
            return
        }
        val v = velocidadeKmh
        if (_estado.value.fase == FaseDesempenho.MEDINDO) {
            medir(v, instanteMs)
        } else if (v <= 0.0) {
            parado = true
            // Resultado do último teste segue na tela até o próximo começar.
            if (_estado.value.fase == FaseDesempenho.AGUARDANDO_PARADA) _estado.value = EstadoDesempenho(FaseDesempenho.PRONTO)
        } else if (parado) {
            parado = false
            inicioMs = instanteMs
            pico = v
            t060Ms = null
            ultimaVel = v
            ultimoMs = instanteMs
            _estado.value = EstadoDesempenho(FaseDesempenho.MEDINDO, decorrido = 0.0)
        }
    }

    private fun medir(v: Double, agora: Long) {
        val decorridoMs = (agora - inicioMs).toDouble()
        if (v < pico - TOLERANCIA_KMH || decorridoMs > LIMITE_MS) {
            cancelar()
            return
        }
        if (t060Ms == null && v >= 60.0) {
            t060Ms = cruzamento(60.0, v, agora)
            aoParcial060(arredondar(t060Ms!! / 1000.0))
        }
        if (v >= 100.0) {
            val t100 = cruzamento(100.0, v, agora)
            val t60 = arredondar(t060Ms!! / 1000.0)
            val t100s = arredondar(t100 / 1000.0)
            _estado.value = EstadoDesempenho(FaseDesempenho.CONCLUIDO, t60, t100s)
            aoConcluir0100(t60, t100s)
            return
        }
        if (v > pico) pico = v
        ultimaVel = v
        ultimoMs = agora
        _estado.value = EstadoDesempenho(
            FaseDesempenho.MEDINDO,
            tempo060 = t060Ms?.let { arredondar(it / 1000.0) },
            decorrido = arredondar(decorridoMs / 1000.0),
        )
    }

    private fun cancelar() {
        _estado.value = EstadoDesempenho(
            FaseDesempenho.CANCELADO,
            tempo060 = t060Ms?.let { arredondar(it / 1000.0) },
        )
        // Só um novo 0 km/h libera o próximo teste.
        parado = false
    }

    /** Instante (ms desde a saída) em que a velocidade cruzou [alvo], interpolando entre a amostra anterior e a atual. */
    private fun cruzamento(alvo: Double, v: Double, agora: Long): Double {
        val t = (agora - inicioMs).toDouble()
        if (ultimaVel >= alvo || v <= ultimaVel) return t
        val fracao = (alvo - ultimaVel) / (v - ultimaVel)
        return (ultimoMs - inicioMs) + fracao * (agora - ultimoMs)
    }

    private fun arredondar(s: Double) = round(s * 100.0) / 100.0

    companion object {
        /** Recuo aceitável abaixo do pico (troca de marcha) antes de cancelar. */
        const val TOLERANCIA_KMH = 3.0

        /** Teste maior que isso é abandono. */
        const val LIMITE_MS = 60_000L
    }
}
