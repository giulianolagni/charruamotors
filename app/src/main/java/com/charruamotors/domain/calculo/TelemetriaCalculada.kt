package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids

/** Como o consumo instantâneo foi obtido. */
enum class MetodoConsumo {
    /** PID `015E` informado pela própria ECU (usado nos motores diesel). */
    SENSOR_ECU,

    /** Massa de ar medida pelo sensor MAF (`0110`). */
    MAF,

    /** Massa de ar estimada por *Speed-Density*: RPM, MAP (`010B`) e temperatura do ar (`010F`). */
    SPEED_DENSITY,
}

/** Consumo instantâneo; [kmPorLitro] é null com o veículo parado (só faz sentido em movimento). */
data class ConsumoInstantaneo(
    val litrosPorHora: Double,
    val kmPorLitro: Double?,
    val metodo: MetodoConsumo,
)

/**
 * Propriedades de um combustível para converter massa de ar em litros.
 * @property relacaoEstequiometrica massa de ar por massa de combustível para queima completa (λ = 1).
 * @property densidadeGL densidade em g/L.
 */
data class Combustivel(val relacaoEstequiometrica: Double, val densidadeGL: Double) {
    init {
        require(relacaoEstequiometrica > 0 && densidadeGL > 0) { "Propriedades do combustível inválidas" }
    }

    companion object {
        val GASOLINA_PURA = Combustivel(14.7, 740.0)

        /** Etanol hidratado vendido na bomba (~7 % de água). */
        val ETANOL_HIDRATADO = Combustivel(8.7, 809.0)

        val DIESEL = Combustivel(14.5, 835.0)

        /**
         * Diesel Common Rail turbo (Toyota 1KD-FTV 3.0 e similares) estimado pelo MAF. O diesel
         * queima com excesso de ar (λ ≈ 1,3 em plena carga a 4+ em marcha lenta), então a massa de
         * ar não fixa a de combustível: este valor é a relação ar/combustível *média efetiva* de um
         * uso misto (λ ≈ 1,85 × 14,5). Ajuste fino por veículo na calibração do cadastro.
         */
        val DIESEL_COMMON_RAIL = Combustivel(27.0, 835.0)

        /** Gasolina comum brasileira, com 27 % de etanol anidro. */
        val GASOLINA_C: Combustivel = mistura(0.27)

        /**
         * Mistura gasolina + etanol pela fração volumétrica de etanol (0..1), como a ECU informa no
         * PID `0152`: a densidade se mistura por volume e a relação estequiométrica por massa.
         */
        fun mistura(fracaoEtanol: Double): Combustivel {
            val e = fracaoEtanol.coerceIn(0.0, 1.0)
            val g = GASOLINA_PURA
            val a = ETANOL_HIDRATADO
            val massaEtanol = e * a.densidadeGL
            val massaGasolina = (1 - e) * g.densidadeGL
            val fracaoMassica = massaEtanol / (massaEtanol + massaGasolina)
            return Combustivel(
                relacaoEstequiometrica = fracaoMassica * a.relacaoEstequiometrica + (1 - fracaoMassica) * g.relacaoEstequiometrica,
                densidadeGL = massaEtanol + massaGasolina,
            )
        }
    }
}

/**
 * Dados do motor para os cálculos que a ECU não informa.
 * @property cilindradaLitros cilindrada, usada no Speed-Density.
 * @property eficienciaVolumetrica fração do cilindro realmente preenchida (aspirado ≈ 0,80–0,90).
 */
data class ParametrosMotor(
    val cilindradaLitros: Double = CILINDRADA_PADRAO_L,
    val eficienciaVolumetrica: Double = EFICIENCIA_VOLUMETRICA_PADRAO,
    val tipoCombustivel: TipoCombustivel = TipoCombustivel.FLEX,
    /** Calibração do veículo: multiplica o consumo estimado (1,0 = padrão do combustível). */
    val calibracao: Double = 1.0,
) {
    init {
        require(calibracao in CALIBRACAO_MIN..CALIBRACAO_MAX) { "Calibração do consumo fora da faixa: $calibracao" }
        require(cilindradaLitros in CILINDRADA_MIN_L..CILINDRADA_MAX_L) { "Cilindrada fora da faixa: $cilindradaLitros L" }
        require(eficienciaVolumetrica in 0.3..1.3) { "Eficiência volumétrica fora da faixa: $eficienciaVolumetrica" }
    }

    /**
     * Combustível em uso. No flex, a fração de etanol vem da ECU (`0152`) quando ela informa;
     * sem essa leitura, assume gasolina comum (E27). GNV usa a gasolina: os carros a GNV no Brasil
     * são bicombustível e o PID de massa de ar não distingue o combustível em uso.
     */
    fun combustivel(percentualEtanol: Double? = null): Combustivel = when (tipoCombustivel) {
        TipoCombustivel.GASOLINA, TipoCombustivel.GNV -> Combustivel.GASOLINA_C
        TipoCombustivel.ETANOL -> Combustivel.ETANOL_HIDRATADO
        TipoCombustivel.DIESEL -> Combustivel.DIESEL_COMMON_RAIL
        TipoCombustivel.FLEX -> percentualEtanol?.let { Combustivel.mistura(it / 100.0) } ?: Combustivel.GASOLINA_C
    }

    companion object {
        const val CILINDRADA_PADRAO_L = 1.6
        const val EFICIENCIA_VOLUMETRICA_PADRAO = 0.85
        private const val CILINDRADA_MIN_L = 0.6
        private const val CILINDRADA_MAX_L = 8.0
        const val CALIBRACAO_MIN = 0.3
        const val CALIBRACAO_MAX = 3.0

        /** "1.6", "2,8", "1.0 TSI": número com uma casa decimal isolado no texto do modelo. */
        private val REGEX_CILINDRADA = Regex("(?<![\\d.,])(\\d)[.,](\\d)(?![\\d.,])")

        /**
         * Parâmetros a partir do cadastro: a cilindrada é lida do modelo (ex.: "Gol 1.6 MSI",
         * "Hilux 2.8 D-4D"); sem ela, usa [CILINDRADA_PADRAO_L].
         */
        fun deVeiculo(modelo: String?, tipoCombustivel: TipoCombustivel?, calibracao: Double = 1.0): ParametrosMotor {
            val cilindrada = modelo?.let { REGEX_CILINDRADA.find(it) }
                ?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDouble() }
                ?.takeIf { it in CILINDRADA_MIN_L..CILINDRADA_MAX_L }
            return ParametrosMotor(
                cilindradaLitros = cilindrada ?: CILINDRADA_PADRAO_L,
                tipoCombustivel = tipoCombustivel ?: TipoCombustivel.FLEX,
                calibracao = calibracao.takeIf { it in CALIBRACAO_MIN..CALIBRACAO_MAX } ?: 1.0,
            )
        }
    }
}

/**
 * Cálculos derivados da telemetria (computador de bordo). Objeto puro, testável na JVM.
 *
 * Consumo de motores a gasolina/etanol/flex, por massa de ar:
 * `combustível (g/s) = ar (g/s) / (AFR · λ) · (1 + (STFT + LTFT) / 100)` e `L/h = g/s · 3600 / densidade`,
 * com o ar vindo do MAF quando a ECU o suporta e, sem MAF, do Speed-Density. Diesel trabalha
 * com excesso de ar: o MAF é convertido pela relação ar/combustível *efetiva* do Common Rail
 * ([Combustivel.DIESEL_COMMON_RAIL], ajustável pela calibração do veículo) e, sem MAF, vale o `015E`.
 */
object TelemetriaCalculada {

    /** Constante do ar seco: 0,287 kPa·L/(g·K). */
    const val CONSTANTE_AR = 0.287

    /** Abaixo disso o carro está parado e km/L não é exibido. */
    const val VELOCIDADE_MINIMA_KM_L = 3.0

    private const val ZERO_ABSOLUTO_C = 273.15

    /**
     * Massa de ar admitida por Speed-Density (motor 4 tempos: uma admissão a cada 2 voltas):
     * `ar (g/s) = RPM / 120 · cilindrada (L) · EV · MAP (kPa) / (R · T)`.
     * Devolve null se faltar alguma leitura ou se ela for fisicamente impossível.
     */
    fun massaArSpeedDensity(rpm: Double?, mapKpa: Double?, temperaturaArC: Double?, motor: ParametrosMotor): Double? {
        if (rpm == null || mapKpa == null || temperaturaArC == null) return null
        val temperaturaK = temperaturaArC + ZERO_ABSOLUTO_C
        if (rpm < 0 || mapKpa < 0 || temperaturaK <= 0) return null
        return rpm / 120.0 * motor.cilindradaLitros * motor.eficienciaVolumetrica * mapKpa / (CONSTANTE_AR * temperaturaK)
    }

    /**
     * Consumo instantâneo a partir de uma leitura; null se não houver dados suficientes
     * (sem MAF e sem RPM/MAP/IAT, ou diesel sem `015E`).
     */
    fun consumoInstantaneo(dados: ValoresSensores, motor: ParametrosMotor): ConsumoInstantaneo? {
        val litrosPorHora: Double
        val metodo: MetodoConsumo
        val maf = dados[Pids.MAF]
        if (motor.tipoCombustivel == TipoCombustivel.DIESEL) {
            if (maf != null) {
                litrosPorHora = litrosPorHoraDaMassaAr(maf, dados, motor)
                metodo = MetodoConsumo.MAF
            } else {
                // Sem MAF, só o consumo que a própria ECU informa (o Speed-Density não vale no diesel).
                litrosPorHora = dados[Pids.CONSUMO_ECU] ?: return null
                metodo = MetodoConsumo.SENSOR_ECU
            }
        } else {
            val massaAr: Double
            if (maf != null) {
                massaAr = maf
                metodo = MetodoConsumo.MAF
            } else {
                massaAr = massaArSpeedDensity(
                    dados[Pids.RPM], dados[Pids.PRESSAO_COLETOR], dados[Pids.TEMPERATURA_AR_ADMISSAO], motor,
                ) ?: return null
                metodo = MetodoConsumo.SPEED_DENSITY
            }
            litrosPorHora = litrosPorHoraDaMassaAr(massaAr, dados, motor)
        }
        return ConsumoInstantaneo(litrosPorHora, kmPorLitro(dados[Pids.VELOCIDADE], litrosPorHora), metodo)
    }

    /**
     * Converte a massa de ar (g/s) em L/h pela relação ar/combustível do [motor], vezes a calibração
     * do veículo. Na gasolina/etanol/flex corrige ainda por λ comandado e pelos ajustes de
     * combustível (STFT/LTFT); no diesel (sem λ de banda estreita nem trims) vale só a relação
     * efetiva do [Combustivel.DIESEL_COMMON_RAIL].
     */
    fun litrosPorHoraDaMassaAr(massaArGs: Double, dados: ValoresSensores, motor: ParametrosMotor): Double {
        val combustivel = motor.combustivel(dados[Pids.PERCENTUAL_ETANOL])
        val diesel = motor.tipoCombustivel == TipoCombustivel.DIESEL
        val lambda = if (diesel) 1.0 else dados[Pids.LAMBDA_COMANDADO]?.takeIf { it in 0.5..1.5 } ?: 1.0
        val ajuste = if (diesel) 1.0 else (1.0 + ((dados[Pids.STFT] ?: 0.0) + (dados[Pids.LTFT] ?: 0.0)) / 100.0).coerceIn(0.5, 1.5)
        val combustivelGs = massaArGs.coerceAtLeast(0.0) / (combustivel.relacaoEstequiometrica * lambda) * ajuste
        return combustivelGs * 3600.0 / combustivel.densidadeGL * motor.calibracao
    }

    /** km/L instantâneo; null parado (abaixo de [VELOCIDADE_MINIMA_KM_L]) ou sem consumo. */
    fun kmPorLitro(velocidadeKmh: Double?, litrosPorHora: Double): Double? {
        if (velocidadeKmh == null || velocidadeKmh < VELOCIDADE_MINIMA_KM_L || litrosPorHora <= 0.0) return null
        return velocidadeKmh / litrosPorHora
    }
}

/** Totais da viagem desde a conexão (ou o último [ComputadorBordo.zerar]). */
data class ResumoViagem(
    val distanciaKm: Double = 0.0,
    val litros: Double = 0.0,
    val segundos: Double = 0.0,
) {
    /** Média da viagem; null até ter distância e combustível suficientes para não ser ruído. */
    val mediaKmPorLitro: Double?
        get() = if (distanciaKm >= DISTANCIA_MINIMA_MEDIA_KM && litros > 0.0) distanciaKm / litros else null

    private companion object {
        const val DISTANCIA_MINIMA_MEDIA_KM = 0.1
    }
}

/**
 * Integra consumo e velocidade no tempo para a média da viagem (litros = ∫ L/h dt; km = ∫ km/h dt).
 * Intervalos longos entre leituras (aba pausada, conexão caída) não são integrados: contam no
 * máximo [maxIntervaloMs], para não inventar quilômetros que ninguém mediu.
 */
class ComputadorBordo(private val maxIntervaloMs: Long = MAX_INTERVALO_MS) {

    var resumo: ResumoViagem = ResumoViagem()
        private set

    private var ultimoInstanteMs: Long? = null

    fun registrar(consumo: ConsumoInstantaneo?, velocidadeKmh: Double?, instanteMs: Long): ResumoViagem {
        val anterior = ultimoInstanteMs
        ultimoInstanteMs = instanteMs
        if (anterior == null || instanteMs <= anterior) return resumo
        val horas = (instanteMs - anterior).coerceAtMost(maxIntervaloMs) / 3_600_000.0
        resumo = resumo.copy(
            distanciaKm = resumo.distanciaKm + (velocidadeKmh ?: 0.0).coerceAtLeast(0.0) * horas,
            litros = resumo.litros + (consumo?.litrosPorHora ?: 0.0).coerceAtLeast(0.0) * horas,
            segundos = resumo.segundos + horas * 3600.0,
        )
        return resumo
    }

    /** Nova viagem (ex.: nova conexão). */
    fun zerar() {
        resumo = ResumoViagem()
        ultimoInstanteMs = null
    }

    companion object {
        const val MAX_INTERVALO_MS = 5_000L
    }
}
