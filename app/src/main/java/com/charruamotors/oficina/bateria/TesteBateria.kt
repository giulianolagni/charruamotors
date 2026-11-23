package com.charruamotors.oficina.bateria

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import kotlin.math.roundToLong

/** Uma amostra do `ATRV` durante o teste. */
sealed interface LeituraTensao {
    data class Valida(val volts: Double) : LeituraTensao

    /** O ELM327 reiniciou por subtensão (`LV RESET`): típico do vale da partida. Não aborta o teste. */
    data object LvReset : LeituraTensao

    /** Sem resposta no prazo ou resposta ilegível (amostra perdida). */
    data object Perdida : LeituraTensao

    companion object {
        /** Interpreta a resposta crua do `ATRV`; [semPrompt] = o adaptador não respondeu no prazo. */
        fun deResposta(bruto: String, semPrompt: Boolean = false): LeituraTensao {
            if (semPrompt) return Perdida
            if (ObdParser.adaptadorReiniciou(bruto)) return LvReset
            return when (val r = ObdParser.parseTensao(bruto)) {
                is RespostaObd.Ok -> Valida(r.valor)
                else -> Perdida
            }
        }
    }
}

/** As 3 etapas do teste guiado (a 3ª em duas fases: marcha lenta e ~2.500 RPM com cargas). */
enum class EtapaBateria(val numero: Int) {
    REPOUSO(1),
    PARTIDA(2),
    ALTERNADOR_LENTA(3),
    ALTERNADOR_CARGA(3),
    CONCLUIDO(4),
}

/** Gravidade comum dos vereditos, para o resumo do laudo (0 = bom, 1 = atenção, 2 = ruim). */
interface Veredito {
    val gravidade: Int
}

enum class VereditoRepouso(override val gravidade: Int) : Veredito { SAUDAVEL(0), CARGA_BAIXA(1), CRITICA(2) }

enum class VereditoPartida(override val gravidade: Int) : Veredito { OK(0), ATENCAO(1), FRACA(2) }

enum class VereditoAlternador(override val gravidade: Int) : Veredito {
    SUBCARGA(2),
    LIMITE_INFERIOR(1),
    NORMAL(0),
    LIMITE_SUPERIOR(1),
    SOBRECARGA(2),
}

/**
 * Faixas de referência (bateria chumbo-ácido 12 V e alternador com regulador convencional):
 * - repouso: saudável ≥ 12,4 V; carga baixa 12,0–12,3 V; crítica < 12,0 V;
 * - partida (vale `V_min`): OK ≥ 10,0 V; atenção 9,6–9,9 V; fraca/comprometida < 9,6 V;
 * - alternador (média): subcarga < 13,5 V; normal 13,6–14,6 V; sobrecarga/regulador > 14,8 V. Os
 *   intervalos entre as faixas (13,5–13,6 e 14,6–14,8 V) ficam como limite inferior/superior.
 * Os valores chegam com 2 casas ([arredondar]) para a média não cair numa faixa por ruído de ponto flutuante.
 */
object ClassificadorBateria {
    const val REPOUSO_SAUDAVEL = 12.4
    const val REPOUSO_CRITICA = 12.0
    const val PARTIDA_OK = 10.0
    const val PARTIDA_FRACA = 9.6
    const val ALTERNADOR_SUBCARGA = 13.5
    const val ALTERNADOR_NORMAL_MIN = 13.6
    const val ALTERNADOR_NORMAL_MAX = 14.6
    const val ALTERNADOR_SOBRECARGA = 14.8

    /** Ripple pico a pico (estimado) acima disso sugere diodo da ponte retificadora em curto/aberto. */
    const val RIPPLE_ALTO = 0.5

    fun repouso(volts: Double): VereditoRepouso {
        val v = arredondar(volts)
        return when {
            v >= REPOUSO_SAUDAVEL -> VereditoRepouso.SAUDAVEL
            v >= REPOUSO_CRITICA -> VereditoRepouso.CARGA_BAIXA
            else -> VereditoRepouso.CRITICA
        }
    }

    fun partida(vMin: Double): VereditoPartida {
        val v = arredondar(vMin)
        return when {
            v >= PARTIDA_OK -> VereditoPartida.OK
            v >= PARTIDA_FRACA -> VereditoPartida.ATENCAO
            else -> VereditoPartida.FRACA
        }
    }

    fun alternador(media: Double): VereditoAlternador {
        val v = arredondar(media)
        return when {
            v < ALTERNADOR_SUBCARGA -> VereditoAlternador.SUBCARGA
            v < ALTERNADOR_NORMAL_MIN -> VereditoAlternador.LIMITE_INFERIOR
            v <= ALTERNADOR_NORMAL_MAX -> VereditoAlternador.NORMAL
            v <= ALTERNADOR_SOBRECARGA -> VereditoAlternador.LIMITE_SUPERIOR
            else -> VereditoAlternador.SOBRECARGA
        }
    }

    fun arredondar(v: Double, casas: Int = 2): Double {
        var fator = 1.0
        repeat(casas) { fator *= 10 }
        return (v * fator).roundToLong() / fator
    }
}

data class ResultadoRepouso(
    /** Média da segunda metade da captura (tensão já estabilizada). */
    val tensao: Double,
    val minima: Double,
    val maxima: Double,
    val amostras: Int,
    /** Variação ≤ [TesteBateria.VARIACAO_ESTAVEL] V na janela medida. */
    val estavel: Boolean,
    val veredito: VereditoRepouso,
) {
    /** Acima de ~13,2 V em "repouso" o motor (alternador) provavelmente estava ligado. */
    val motorPareceLigado: Boolean get() = tensao >= TesteBateria.TENSAO_MOTOR_LIGADO
}

data class ResultadoPartida(
    /** Tensão de referência antes da partida (a do repouso, ou a primeira amostra da captura). */
    val tensaoInicial: Double,
    /** Vale mínimo de tensão entre as amostras válidas. */
    val vMin: Double,
    /** Tempo abaixo de (referência − [TesteBateria.QUEDA_MINIMA_PARTIDA]) até a tensão se recuperar. */
    val duracaoQuedaMs: Long,
    val lvResets: Int,
    val amostrasPerdidas: Int,
    /** Depois do vale a tensão passou de [TesteBateria.TENSAO_MOTOR_LIGADO] (alternador carregando). */
    val motorPegou: Boolean,
    val veredito: VereditoPartida,
)

data class ResultadoCarga(
    val media: Double,
    val pico: Double,
    val minima: Double,
    /** Ripple pico a pico estimado (desvio em torno da média móvel, limitado pela resolução de 0,1 V do `ATRV`). */
    val ripple: Double,
    val amostras: Int,
    val veredito: VereditoAlternador,
) {
    val rippleAlto: Boolean get() = ripple > ClassificadorBateria.RIPPLE_ALTO
}

/** Estatísticas de uma série de tensões (usadas pelas etapas e testadas isoladamente). */
object EstatisticaTensao {

    fun media(valores: List<Double>): Double = valores.sum() / valores.size

    /**
     * Ripple pico a pico estimado: amplitude dos desvios em relação a uma média móvel centrada de
     * [janela] amostras, o que separa a oscilação rápida da deriva lenta (ex.: RPM subindo).
     * Com menos amostras que a janela, a própria amplitude (máx − mín).
     */
    fun ripple(valores: List<Double>, janela: Int = JANELA_RIPPLE): Double {
        if (valores.isEmpty()) return 0.0
        if (valores.size < janela) return ClassificadorBateria.arredondar(valores.max() - valores.min())
        val meia = janela / 2
        val desvios = valores.indices.map { i ->
            val de = (i - meia).coerceAtLeast(0)
            val ate = (i + meia).coerceAtMost(valores.lastIndex)
            valores[i] - valores.subList(de, ate + 1).average()
        }
        return ClassificadorBateria.arredondar(desvios.max() - desvios.min())
    }

    fun carga(valores: List<Double>): ResultadoCarga {
        require(valores.isNotEmpty()) { "Sem amostras" }
        val media = ClassificadorBateria.arredondar(media(valores))
        return ResultadoCarga(
            media = media,
            pico = valores.max(),
            minima = valores.min(),
            ripple = ripple(valores),
            amostras = valores.size,
            veredito = ClassificadorBateria.alternador(media),
        )
    }

    const val JANELA_RIPPLE = 5
}

/** O que aconteceu com a captura depois de uma amostra (null = segue capturando). */
sealed interface EventoTesteBateria {
    data class EtapaConcluida(val etapa: EtapaBateria) : EventoTesteBateria

    /** Na etapa 2 não houve queda de tensão em [TesteBateria.ESPERA_MAXIMA_PARTIDA_MS]: dá para repetir. */
    data object PartidaNaoDetectada : EventoTesteBateria

    /** Poucas amostras válidas na janela (adaptador mudo, Bluetooth instável): dá para repetir. */
    data object LeiturasInsuficientes : EventoTesteBateria
}

/**
 * Máquina de estados do Teste de Bateria e Alternador, pura (sem Android nem corrotinas): a tela
 * lê o `ATRV` em alta taxa e entrega cada amostra a [registrar] com o instante; a etapa termina
 * sozinha quando o critério dela é atingido e o teste para em [capturando] = false, esperando o
 * usuário seguir as instruções da próxima.
 *
 * 1. **Repouso** — [DURACAO_REPOUSO_MS] de captura; vale a média da segunda metade.
 * 2. **Partida** — espera a queda (≥ [QUEDA_MINIMA_PARTIDA] V abaixo da referência, ou um
 *    `LV RESET`), guarda o vale `V_min` e termina [APOS_MOTOR_PEGAR_MS] depois de a tensão passar de
 *    [TENSAO_MOTOR_LIGADO] V (alternador assumiu), ou [DURACAO_MAXIMA_QUEDA_MS] depois da queda. O
 *    `LV RESET` conta como amostra perdida, nunca aborta.
 * 3. **Alternador** — [DURACAO_LENTA_MS] em marcha lenta e [DURACAO_CARGA_MS] a ~2.500 RPM com
 *    cargas: média, pico e ripple de cada fase.
 */
class TesteBateria {

    var etapa: EtapaBateria = EtapaBateria.REPOUSO
        private set

    var capturando: Boolean = false
        private set

    var resultadoRepouso: ResultadoRepouso? = null
        private set
    var resultadoPartida: ResultadoPartida? = null
        private set
    var resultadoLenta: ResultadoCarga? = null
        private set
    var resultadoCarga: ResultadoCarga? = null
        private set

    private val _puladas = mutableSetOf<EtapaBateria>()

    /** Etapas puladas pelo usuário (aparecem como "não realizada" no laudo). */
    val puladas: Set<EtapaBateria> get() = _puladas

    private val amostras = mutableListOf<Pair<Long, Double>>()
    private var inicioCapturaMs = 0L
    private var ultimoInstanteMs = 0L
    private var lvResets = 0
    private var perdidas = 0

    // Etapa 2
    private var referenciaPartida: Double? = null
    private var inicioQuedaMs: Long? = null
    private var fimQuedaMs: Long? = null
    private var motorPegouMs: Long? = null

    /** Menor tensão válida desde o início da captura (o marcador do vale no gráfico). */
    val valeAtual: Double? get() = amostras.minOfOrNull { it.second }

    /** A queda de partida já foi detectada nesta captura. */
    val quedaDetectada: Boolean get() = inicioQuedaMs != null

    val lvResetsNaCaptura: Int get() = lvResets

    val concluido: Boolean get() = etapa == EtapaBateria.CONCLUIDO

    /** Começa (ou recomeça) a captura da etapa atual. */
    fun iniciarCaptura(agoraMs: Long) {
        check(etapa != EtapaBateria.CONCLUIDO) { "Teste já concluído" }
        amostras.clear()
        inicioCapturaMs = agoraMs
        ultimoInstanteMs = agoraMs
        lvResets = 0
        perdidas = 0
        referenciaPartida = resultadoRepouso?.tensao
        inicioQuedaMs = null
        fimQuedaMs = null
        motorPegouMs = null
        capturando = true
    }

    fun cancelarCaptura() {
        capturando = false
        amostras.clear()
    }

    /** Pula a etapa atual (sem resultado) e vai para a próxima. */
    fun pular() {
        if (etapa == EtapaBateria.CONCLUIDO) return
        capturando = false
        _puladas += etapa
        when (etapa) {
            EtapaBateria.REPOUSO -> resultadoRepouso = null
            EtapaBateria.PARTIDA -> resultadoPartida = null
            EtapaBateria.ALTERNADOR_LENTA -> resultadoLenta = null
            EtapaBateria.ALTERNADOR_CARGA -> resultadoCarga = null
            EtapaBateria.CONCLUIDO -> Unit
        }
        etapa = proxima(etapa)
    }

    /** Volta à [alvo] para medir de novo (os resultados das demais etapas ficam). */
    fun refazer(alvo: EtapaBateria) {
        require(alvo != EtapaBateria.CONCLUIDO)
        capturando = false
        _puladas -= alvo
        etapa = alvo
    }

    /** Recomeça o teste do zero. */
    fun reiniciar() {
        capturando = false
        etapa = EtapaBateria.REPOUSO
        resultadoRepouso = null
        resultadoPartida = null
        resultadoLenta = null
        resultadoCarga = null
        _puladas.clear()
        amostras.clear()
    }

    /**
     * Progresso da captura (0–1); null quando indeterminado (etapa 2 esperando a partida) ou
     * sem captura.
     */
    fun progresso(agoraMs: Long): Float? {
        if (!capturando) return null
        val decorrido = (agoraMs - inicioCapturaMs).toFloat()
        return when (etapa) {
            EtapaBateria.REPOUSO -> decorrido / DURACAO_REPOUSO_MS
            EtapaBateria.ALTERNADOR_LENTA -> decorrido / DURACAO_LENTA_MS
            EtapaBateria.ALTERNADOR_CARGA -> decorrido / DURACAO_CARGA_MS
            else -> null
        }?.coerceIn(0f, 1f)
    }

    /** Entrega uma amostra; devolve o evento se a captura terminou com ela. */
    fun registrar(leitura: LeituraTensao, agoraMs: Long): EventoTesteBateria? {
        if (!capturando) return null
        ultimoInstanteMs = agoraMs
        when (leitura) {
            is LeituraTensao.Valida -> amostras += agoraMs to leitura.volts
            LeituraTensao.LvReset -> {
                lvResets++
                perdidas++
            }
            LeituraTensao.Perdida -> perdidas++
        }
        return when (etapa) {
            EtapaBateria.REPOUSO -> avaliarRepouso(agoraMs)
            EtapaBateria.PARTIDA -> avaliarPartida(leitura, agoraMs)
            EtapaBateria.ALTERNADOR_LENTA -> avaliarCarga(agoraMs, DURACAO_LENTA_MS) { resultadoLenta = it }
            EtapaBateria.ALTERNADOR_CARGA -> avaliarCarga(agoraMs, DURACAO_CARGA_MS) { resultadoCarga = it }
            EtapaBateria.CONCLUIDO -> null
        }
    }

    private fun avaliarRepouso(agoraMs: Long): EventoTesteBateria? {
        if (agoraMs - inicioCapturaMs < DURACAO_REPOUSO_MS) return null
        val janela = amostras.filter { it.first >= inicioCapturaMs + DURACAO_REPOUSO_MS / 2 }.map { it.second }
        if (janela.size < MIN_AMOSTRAS) return falhar()
        val tensao = ClassificadorBateria.arredondar(EstatisticaTensao.media(janela))
        resultadoRepouso = ResultadoRepouso(
            tensao = tensao,
            minima = janela.min(),
            maxima = janela.max(),
            amostras = janela.size,
            estavel = janela.max() - janela.min() <= VARIACAO_ESTAVEL + 1e-9,
            veredito = ClassificadorBateria.repouso(tensao),
        )
        return concluirEtapa()
    }

    private fun avaliarPartida(leitura: LeituraTensao, agoraMs: Long): EventoTesteBateria? {
        val v = (leitura as? LeituraTensao.Valida)?.volts
        if (referenciaPartida == null && v != null) referenciaPartida = v
        val referencia = referenciaPartida
        val inicioQueda = inicioQuedaMs
        if (inicioQueda == null) {
            val caiu = leitura == LeituraTensao.LvReset ||
                (v != null && referencia != null && v <= referencia - QUEDA_MINIMA_PARTIDA + 1e-9)
            if (caiu) {
                inicioQuedaMs = agoraMs
                return null
            }
            if (agoraMs - inicioCapturaMs >= ESPERA_MAXIMA_PARTIDA_MS) {
                capturando = false
                amostras.clear()
                return EventoTesteBateria.PartidaNaoDetectada
            }
            return null
        }
        if (v != null && referencia != null && fimQuedaMs == null && v >= referencia - RECUPERACAO_PARTIDA - 1e-9) {
            fimQuedaMs = agoraMs
        }
        if (v != null && motorPegouMs == null && v >= TENSAO_MOTOR_LIGADO) motorPegouMs = agoraMs
        val pegou = motorPegouMs
        val terminou = (pegou != null && agoraMs - pegou >= APOS_MOTOR_PEGAR_MS) ||
            agoraMs - inicioQueda >= DURACAO_MAXIMA_QUEDA_MS
        if (!terminou) return null
        val vMin = valeAtual ?: return falhar()
        resultadoPartida = ResultadoPartida(
            tensaoInicial = referencia ?: vMin,
            vMin = vMin,
            duracaoQuedaMs = (fimQuedaMs ?: agoraMs) - inicioQueda,
            lvResets = lvResets,
            amostrasPerdidas = perdidas,
            motorPegou = pegou != null,
            veredito = ClassificadorBateria.partida(vMin),
        )
        return concluirEtapa()
    }

    private inline fun avaliarCarga(agoraMs: Long, duracaoMs: Long, guardar: (ResultadoCarga) -> Unit): EventoTesteBateria? {
        if (agoraMs - inicioCapturaMs < duracaoMs) return null
        val valores = amostras.map { it.second }
        if (valores.size < MIN_AMOSTRAS) return falhar()
        guardar(EstatisticaTensao.carga(valores))
        return concluirEtapa()
    }

    private fun falhar(): EventoTesteBateria {
        capturando = false
        amostras.clear()
        return EventoTesteBateria.LeiturasInsuficientes
    }

    private fun concluirEtapa(): EventoTesteBateria {
        val concluida = etapa
        capturando = false
        _puladas -= concluida
        etapa = proxima(concluida)
        return EventoTesteBateria.EtapaConcluida(concluida)
    }

    private fun proxima(e: EtapaBateria) = when (e) {
        EtapaBateria.REPOUSO -> EtapaBateria.PARTIDA
        EtapaBateria.PARTIDA -> EtapaBateria.ALTERNADOR_LENTA
        EtapaBateria.ALTERNADOR_LENTA -> EtapaBateria.ALTERNADOR_CARGA
        EtapaBateria.ALTERNADOR_CARGA, EtapaBateria.CONCLUIDO -> EtapaBateria.CONCLUIDO
    }

    companion object {
        const val DURACAO_REPOUSO_MS = 5_000L
        const val DURACAO_LENTA_MS = 8_000L
        const val DURACAO_CARGA_MS = 10_000L

        /** Sem queda até aqui na etapa 2: partida não detectada. */
        const val ESPERA_MAXIMA_PARTIDA_MS = 20_000L

        /** Depois da queda, encerra a etapa 2 mesmo sem o motor pegar (arranque longo, alternador sem carga). */
        const val DURACAO_MAXIMA_QUEDA_MS = 8_000L

        /** Mostra a recuperação no gráfico por este tempo depois de o motor pegar. */
        const val APOS_MOTOR_PEGAR_MS = 1_000L

        /** Queda mínima (V) em relação à referência para considerar que o motor de arranque entrou. */
        const val QUEDA_MINIMA_PARTIDA = 0.8

        /** A queda terminou quando a tensão volta a menos disto (V) da referência. */
        const val RECUPERACAO_PARTIDA = 0.3

        /** Acima disso (V) o alternador está carregando: motor ligado. */
        const val TENSAO_MOTOR_LIGADO = 13.2

        /** Variação máxima (V) na janela do repouso para a tensão ser considerada estabilizada. */
        const val VARIACAO_ESTAVEL = 0.2

        /** Mínimo de amostras válidas numa janela de medição. */
        const val MIN_AMOSTRAS = 5
    }
}
