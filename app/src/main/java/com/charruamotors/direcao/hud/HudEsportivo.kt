package com.charruamotors.direcao.hud

import com.charruamotors.domain.calculo.AlavancaGmTcm
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Decodificadores
import com.charruamotors.obd.pid.Pids
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Escala do conta-giros (×1.000 rpm): números de 1 até [fimMil] e faixa vermelha a partir de
 * [redlineRpm]. Diesel/redline baixo (SW4 1KD-FTV: 4.200) vai até 6; acima de 5.500 rpm (gasolina/
 * flex) a escala cresce para 7 ou 8.
 */
data class EscalaRpm(val redlineRpm: Double, val fimMil: Int) {
    val fimRpm: Double get() = fimMil * 1_000.0

    /** Posição de [rpm] no arco (0–1), presa nos extremos. */
    fun fracao(rpm: Double?): Float =
        if (rpm == null || !rpm.isFinite()) 0f else (rpm / fimRpm).coerceIn(0.0, 1.0).toFloat()

    /** Onde começa o vermelho no arco (0–1). */
    val fracaoRedline: Float get() = (redlineRpm / fimRpm).coerceIn(0.0, 1.0).toFloat()

    companion object {
        /** Sem perfil com RPM máximo: o redline típico de um flex. */
        const val REDLINE_PADRAO_RPM = 6_000.0
        private const val LIMITE_ESCALA_CURTA_RPM = 5_500.0

        fun para(redlineRpm: Double?): EscalaRpm {
            val r = redlineRpm?.takeIf { it.isFinite() && it > 0 } ?: REDLINE_PADRAO_RPM
            val fim = if (r <= LIMITE_ESCALA_CURTA_RPM) 6 else (ceil(r / 1_000.0).toInt() + 1).coerceIn(7, 8)
            return EscalaRpm(r, fim)
        }
    }
}

/**
 * Escala do mostrador de turbo em bar de pressão positiva (boost = MAP − barômetro), sempre a partir
 * de `0.0` (sem vácuo). Números a cada 0,4 bar até [rotuloMaximo]; o arco vai um pouco além para
 * mostrar o vermelho a partir de [redlineBar]. Dinâmica pelo teto de boost do perfil ativo (v2.2.9):
 * SW4 `0.0 · 0.4 · 0.8 · 1.2` (vermelho acima de 1,25), Cruze 1.4T até `1.6`. Num motor [aspirado]
 * (MAP máximo do perfil ≈ atmosférico) o ponteiro fica travado em `0.0`.
 */
data class EscalaTurbo(val rotuloMaximo: Double, val redlineBar: Double, val fimBar: Double, val aspirado: Boolean = false) {

    val rotulos: List<Double> get() = generateSequence(0.0) { it + PASSO }.takeWhile { it <= rotuloMaximo + 1e-9 }.toList()

    fun fracao(bar: Double): Float = (bar / fimBar).coerceIn(0.0, 1.0).toFloat()

    val fracaoRedline: Float get() = fracao(redlineBar)

    companion object {
        const val PASSO = 0.4
        private const val ROTULO_MINIMO = 1.2
        private const val ROTULO_MAXIMO = 2.4

        /** Teto de boost do perfil abaixo do qual o motor conta como aspirado (MAP máx. ≈ 1 atm). */
        const val BOOST_MAXIMO_ASPIRADO_BAR = 0.2

        /** Escala pelo MAP máximo do perfil (kPa absolutos); sem perfil, a da SW4 (até 1,2 bar). */
        fun para(mapMaximoKpa: Double?): EscalaTurbo {
            val mapMax = mapMaximoKpa?.takeIf { it.isFinite() }
            val boostMax = mapMax?.let { (it - FormatadorHudEsportivo.BAROMETRO_PADRAO_KPA) / 100.0 } ?: 0.0
            val passos = ceil(boostMax / PASSO - 1e-9).coerceAtLeast(0.0)
            val rotulo = (Math.round(passos * PASSO * 10) / 10.0).coerceIn(ROTULO_MINIMO, ROTULO_MAXIMO)
            return EscalaTurbo(
                rotuloMaximo = rotulo,
                redlineBar = Math.round((rotulo + 0.05) * 100) / 100.0,
                fimBar = Math.round((rotulo + 0.2) * 10) / 10.0,
                aspirado = mapMax != null && boostMax < BOOST_MAXIMO_ASPIRADO_BAR,
            )
        }
    }
}

/**
 * Pedal do acelerador auto-calibrado (v2.2.7): o menor valor visto é o repouso (`0 %`, ex.: 16,1 % na
 * SW4) e o maior pisado até agora é o `100 %`, que só cresce (pisou 15 % → 15 % enche a barra; pisou
 * 40 % → 40 % vira o novo 100 % fixo). Leituras a menos de [DELTA_MINIMO] do repouso contam como
 * pé fora (ruído do sensor parado) e nunca viram teto. Um por HUD aberto; não é thread-safe.
 */
class CalibradorPedal {
    var pedalMin: Double? = null
        private set
    var pedalMax: Double? = null
        private set

    /** Registra a leitura bruta (%) e devolve a fração da barra (0–1); null sem leitura. */
    fun registrar(valor: Double?): Float? {
        if (valor == null || !valor.isFinite()) return null
        val repouso = pedalMin?.coerceAtMost(valor) ?: valor
        pedalMin = repouso
        val acima = valor - repouso
        if (acima < DELTA_MINIMO) return 0f
        val teto = pedalMax?.coerceAtLeast(valor) ?: valor
        pedalMax = teto
        val curso = teto - repouso
        return if (curso < DELTA_MINIMO) 0f else (acima / curso).coerceIn(0.0, 1.0).toFloat()
    }

    fun reiniciar() {
        pedalMin = null
        pedalMax = null
    }

    companion object {
        /** Só conta como pisado a partir de 1,0 ponto percentual acima do repouso. */
        const val DELTA_MINIMO = 1.0

        /**
         * Sensores de pedal em ordem de preferência: Toyota (2149), OBD2 (0149, o do Cruze — no Multi-PID em
         * lote ele vem junto da fila rápida) e a borboleta (0111).
         */
        val COMANDOS = listOf("TOYOTA_PEDAL_D", Pids.PEDAL_ACELERADOR, Pids.BORBOLETA)
    }
}

/** Tudo o que o [com.charruamotors.ui.componentes.HudEsportivoView] desenha, já calculado. */
data class EstadoHudEsportivo(
    val conectado: Boolean,
    val escalaRpm: EscalaRpm,
    val rpm: Double?,
    /** `P`, `R`, `N`, `1`–`6`, `1L`–`5L` ou `–`. */
    val marcha: String,
    /** Triângulo branco do shift-light: o RPM chegou perto do redline. */
    val shiftLight: Boolean,
    /** Velocidade em 3 dígitos (`000`–`999`) ou `---`. */
    val velocidade: String,
    val escalaTurbo: EscalaTurbo,
    /** Boost em bar, nunca negativo. */
    val boostBar: Double,
    /** Fração do pedal (0–1) auto-calibrada; null = sem sensor de pedal. */
    val pedal: Float?,
)

/** Monta o [EstadoHudEsportivo] a partir de um instantâneo da telemetria. Puro, exceto o [CalibradorPedal]. */
object FormatadorHudEsportivo {
    const val BAROMETRO_PADRAO_KPA = 101.0
    const val ROTULO_VELOCIDADE = "KPH"
    const val ROTULO_PEDAL = "PED"
    const val SEM_MARCHA = "–"
    const val SEM_VELOCIDADE = "---"

    /** Shift-light acende a partir desta fração do redline. */
    const val FRACAO_SHIFT_LIGHT = 0.9

    private val COMANDOS_BAROMETRO = listOf("TOYOTA_BARO", "0133")
    private val FAIXA_BAROMETRO_KPA = 50.0..115.0

    fun montar(
        dados: ValoresSensores?,
        conectado: Boolean,
        faixas: FaixasVeiculo?,
        calibrador: CalibradorPedal,
    ): EstadoHudEsportivo {
        val valores = dados.takeIf { conectado }
        val escalaRpm = EscalaRpm.para(faixas?.get(ParametroFaixa.RPM_MAXIMO)?.max)
        val rpm = valores?.get(Pids.RPM)?.takeIf { it.isFinite() && it >= 0 }
        val escalaTurbo = EscalaTurbo.para(faixas?.get(ParametroFaixa.MAP_TURBO)?.max)
        return EstadoHudEsportivo(
            conectado = conectado,
            escalaRpm = escalaRpm,
            rpm = rpm,
            marcha = marcha(valores),
            shiftLight = rpm != null && rpm >= escalaRpm.redlineRpm * FRACAO_SHIFT_LIGHT,
            velocidade = velocidade(valores),
            escalaTurbo = escalaTurbo,
            // Aspirado: travado no 0,0 (o MAP em plena carga pode passar um pouco do barômetro lido).
            boostBar = if (escalaTurbo.aspirado) 0.0 else boostBar(valores),
            pedal = calibrador.registrar(CalibradorPedal.COMANDOS.firstNotNullOfOrNull { c -> valores?.get(c)?.takeIf { it.isFinite() } }),
        )
    }

    /**
     * Boost (bar) = (MAP − barômetro real) / 100, preso em `0.0` (vácuo e aspirados ficam no zero).
     * Barômetro do Toyota (`2133`) ou OBD2 (`0133`); sem leitura válida, [BAROMETRO_PADRAO_KPA].
     */
    fun boostBar(valores: ValoresSensores?): Double {
        val map = valores?.get(Pids.PRESSAO_COLETOR)?.takeIf { it.isFinite() } ?: return 0.0
        val baro = COMANDOS_BAROMETRO.firstNotNullOfOrNull { c -> valores[c]?.takeIf { it.isFinite() && it in FAIXA_BAROMETRO_KPA } }
            ?: BAROMETRO_PADRAO_KPA
        return ((map - baro) / 100.0).coerceAtLeast(0.0)
    }

    /** Velocidade pelo GPS do celular e, sem fix, a da ECU (`010D`/`210D`), em 3 dígitos: `007`, `118`. */
    fun velocidade(valores: ValoresSensores?): String {
        val kmh = listOf(Pids.VELOCIDADE_GPS, Pids.VELOCIDADE).firstNotNullOfOrNull { c -> valores?.get(c)?.takeIf { it.isFinite() && it >= 0 } }
            ?: return SEM_VELOCIDADE
        return String.format(Locale.ROOT, "%03d", kmh.roundToInt().coerceAtMost(999))
    }

    /**
     * Marcha do quadrante: `R`; `1`–`6` ou `1L`–`5L` (reduzida 4L) andando; parado com a alavanca à
     * frente, `1` (ou `1L`). A alavanca `P/N` da A750F não separa P de N: parado é `P` (não dá para
     * engatar P andando) e andando é `N`. Sem alavanca nem marcha, `–`.
     */
    fun marcha(valores: ValoresSensores?): String {
        AlavancaGmTcm.rotulo(valores?.get(AlavancaGmTcm.CHAVE_POSICAO))?.let { return it }
        val alavanca = listOf(CambioA750f.CHAVE_ALAVANCA, "TOYOTA_ALAVANCA_ECM").firstNotNullOfOrNull { c -> valores?.get(c)?.takeIf { it.isFinite() } }
        val codigo = valores?.get(CambioA750f.CHAVE_MARCHA)?.takeIf { it.isFinite() }?.roundToInt()
        val velocidade = listOf(Pids.VELOCIDADE, Pids.VELOCIDADE_GPS).firstNotNullOfOrNull { c -> valores?.get(c)?.takeIf { it.isFinite() } }
        val parado = velocidade == null || velocidade < CambioA750f.VELOCIDADE_MINIMA_KMH
        when (alavanca) {
            Decodificadores.ALAVANCA_P -> return "P"
            Decodificadores.ALAVANCA_R -> return "R"
            Decodificadores.ALAVANCA_N -> return "N"
            Decodificadores.ALAVANCA_P_N -> return if (parado) "P" else "N"
        }
        return when {
            codigo == null -> if (alavanca != null && CambioA750f.alavancaAFrente(alavanca)) "1" else SEM_MARCHA
            codigo == CambioA750f.MARCHA_RE -> "R"
            codigo == CambioA750f.MARCHA_NEUTRO -> "N"
            codigo in 1..6 -> codigo.toString()
            CambioA750f.ehReduzida(codigo) -> "${codigo - CambioA750f.BASE_REDUZIDA}L"
            codigo == CambioA750f.MARCHA_PARADO -> if (alavanca != null && CambioA750f.alavancaAFrente(alavanca)) "1" else "N"
            else -> SEM_MARCHA
        }
    }
}
