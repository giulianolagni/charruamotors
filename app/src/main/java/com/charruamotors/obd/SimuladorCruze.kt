package com.charruamotors.obd

import com.charruamotors.domain.calculo.EstequiometriaFlex
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.obd.pid.Pids
import kotlin.math.roundToInt
import kotlin.random.Random

/** Carro que o Modo Simulado imita, escolhido pela marca do veículo da sessão. */
enum class PerfilSimulacao(val titulo: String) {
    /** Carro genérico a gasolina/flex com os PIDs de montadora Toyota/VW (comportamento até a 2.0). */
    PADRAO("Veículo genérico (Toyota/VW nos PIDs de montadora)"),

    /** Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2) + câmbio automático de 6 marchas. */
    CRUZE_14T_FLEX("Chevrolet Cruze 1.4 Turbo Flex 2018 (LE2)"),
    ;

    companion object {
        fun paraMarca(marca: String?): PerfilSimulacao = if (PresetVeiculo.marcaGm(marca)) CRUZE_14T_FLEX else PADRAO
    }
}

/**
 * Física simplificada do Cruze 1.4 Turbo Flex (LE2) para o Modo Simulado: câmbio de 6 marchas pela
 * velocidade (troca mais tarde acelerando forte), turbo com vácuo na lenta e até ~230 kPa em plena
 * carga, MAF por Speed-Density, injeção direta (rail ~35 bar na lenta a ~190 bar), malha fechada da
 * sonda de banda larga (λ ≈ 1,00; 0,85 enriquecendo; pobre no corte de desaceleração), avanço com
 * retardo sob pressão, teor de etanol do tanque (`0152`), ATF do câmbio pelo UDS 22 1940 e a
 * regulagem variável de tensão (RVC) da GM. Usado pelo [ObdClientFake]; puro, testável na JVM.
 */
internal class SimuladorCruze(private val random: Random) {

    /** Teor de etanol do tanque (0–100 %): 48 % = mistura flex, para demonstrar a detecção automática. */
    @Volatile var percentualEtanol: Double = ETANOL_INICIAL

    private var atf = 30.0
    private var ltft = 1.6

    /** Marcha atual (1–6), 0 = parado em D. */
    var marcha: Int = 0
        private set

    fun reiniciar() {
        atf = 30.0
        marcha = 0
    }

    /**
     * PIDs do Cruze para [velocidade] (km/h), [aceleracao] (km/h/s) e as temperaturas já
     * simuladas pelo cliente; [dt] em segundos.
     */
    fun leitura(
        dt: Double,
        velocidade: Double,
        aceleracao: Double,
        temperaturaMotor: Double,
        temperaturaOleo: Double,
        segundosMotorLigado: Double,
        nivelCombustivel: Double,
        kmComLuzAcesa: Double,
        kmDesdeLimpeza: Double,
    ): Map<String, Double> {
        val acelerando = aceleracao.coerceAtLeast(0.0)
        val forte = acelerando > 6.0
        marcha = marchaPara(velocidade, forte)
        val rpm = rpmPara(velocidade, marcha, acelerando)
        val pedal = (acelerando * 9.5 + velocidade * 0.12 + random.nextDouble(0.0, 1.0)).coerceIn(0.0, 100.0)
        val carga = (17.0 + acelerando * 7.0 + velocidade * 0.18 + random.nextDouble(-1.5, 1.5)).coerceIn(10.0, 100.0)
        val map = mapPara(carga)
        val iat = (32.0 + (map - 100.0).coerceAtLeast(0.0) * 0.08 + random.nextDouble(-0.4, 0.4))
        val maf = (rpm / 120.0 * CILINDRADA_L * 0.88 * map / (0.287 * (iat + 273.15))).coerceAtLeast(1.5)

        val corteDesaceleracao = aceleracao < -4.0 && rpm > 1_300.0
        val enriquecendo = carga > 85.0
        val lambdaComandado = if (enriquecendo) 0.85 else 1.0
        val lambdaMedido = when {
            corteDesaceleracao -> 1.99 // sonda de banda larga satura no pobre (injeção cortada)
            else -> lambdaComandado + random.nextDouble(-0.02, 0.02)
        }
        // Sonda pós-catalisador (banda estreita): estável ~0,65–0,72 V com o catalisador quente.
        val sondaPos = if (corteDesaceleracao) random.nextDouble(0.05, 0.12) else random.nextDouble(0.62, 0.72)
        ltft += (2.0 + percentualEtanol * 0.01 - ltft) * (0.02 * dt).coerceAtMost(1.0)
        val stft = if (corteDesaceleracao) 0.0 else random.nextDouble(-3.5, 3.5)

        // Avanço: mais com etanol (octanagem maior), retardo do controle de detonação sob pressão.
        val avanco = (12.0 + rpm / 220.0 - (map - 100.0).coerceAtLeast(0.0) * 0.12 + percentualEtanol * 0.04 +
            random.nextDouble(-1.0, 1.0)).coerceIn(-5.0, 45.0)
        val railBar = (35.0 + carga * 1.55 + random.nextDouble(-2.0, 2.0)).coerceIn(30.0, 200.0)

        atf += ((temperaturaOleo - 6.0) - atf) * (0.03 * dt).coerceAtMost(1.0)
        // RVC: em cruzeiro leve a GM baixa a carga do alternador (~13,0 V); acelerando/desacelerando, 14,3 V.
        val cruzeiro = velocidade > 40.0 && kotlin.math.abs(aceleracao) < 1.0
        val tensao = (if (cruzeiro) 13.1 else 14.3) + random.nextDouble(-0.1, 0.1)
        val borboleta = (6.0 + pedal * 0.85).coerceIn(0.0, 100.0)

        return linkedMapOf(
            Pids.CARGA_MOTOR to arred(carga, 1),
            Pids.TEMPERATURA_MOTOR to temperaturaMotor.roundToInt().toDouble(),
            Pids.STFT to arred(stft, 1),
            Pids.LTFT to arred(ltft, 1),
            Pids.PRESSAO_COLETOR to map.roundToInt().toDouble(),
            Pids.RPM to rpm.roundToInt().toDouble(),
            Pids.VELOCIDADE to velocidade.roundToInt().toDouble(),
            Pids.AVANCO_IGNICAO to arred(avanco, 1),
            Pids.TEMPERATURA_AR_ADMISSAO to iat.roundToInt().toDouble(),
            Pids.MAF to arred(maf, 2),
            Pids.BORBOLETA to arred(borboleta, 1),
            Pids.SONDA_LAMBDA_TENSAO to arred(sondaPos, 3),
            "011F" to segundosMotorLigado.roundToInt().toDouble(),
            "0121" to kmComLuzAcesa.roundToInt().toDouble(),
            Pids.PRESSAO_RAIL to (railBar * 100.0).roundToInt().toDouble(), // kPa
            Pids.LAMBDA_BANDA_LARGA to arred(lambdaMedido, 3),
            "012F" to arred(nivelCombustivel, 1),
            "0131" to kmDesdeLimpeza.roundToInt().toDouble(),
            "0133" to BAROMETRO_KPA,
            Pids.TENSAO_ECU to arred(tensao - 0.15, 2),
            Pids.LAMBDA_COMANDADO to arred(lambdaComandado, 3),
            "0145" to arred((borboleta - 6.0).coerceAtLeast(0.0), 1),
            "0146" to 24.0,
            "0149" to arred(pedal, 1),
            Pids.PERCENTUAL_ETANOL to percentualEtanol.roundToInt().toDouble(),
            "015C" to temperaturaOleo.roundToInt().toDouble(),
            Pids.TENSAO_BATERIA to arred(tensao, 1),
            "GM_ATF" to atf.roundToInt().toDouble(),
        )
    }

    /** Consumo (L/h) com a estequiometria do etanol atual, para o nível do tanque baixar coerente. */
    fun litrosPorHora(maf: Double): Double {
        val afr = EstequiometriaFlex.relacaoAf(percentualEtanol)
        val densidade = 740.0 + (809.0 - 740.0) * percentualEtanol / 100.0
        return maf / afr * 3600.0 / densidade
    }

    private fun marchaPara(v: Double, forte: Boolean): Int {
        if (v < 1.0) return 0
        val fator = if (forte) 1.45 else 1.0
        val limites = TROCAS_KMH.map { it * fator }
        return (limites.indexOfFirst { v < it }.takeIf { it >= 0 } ?: limites.size) + 1
    }

    private fun rpmPara(v: Double, marcha: Int, acelerando: Double): Double {
        if (marcha == 0) return MARCHA_LENTA + random.nextDouble(-25.0, 25.0)
        val rodas = v * RPM_RODA_POR_KMH * RELACOES[marcha - 1] * DIFERENCIAL
        // Conversor de torque: patina em baixa e sob carga (o RPM não cai abaixo de ~1.000 rodando).
        val patinagem = (1_000.0 + acelerando * 90.0) - rodas
        return (rodas + patinagem.coerceAtLeast(0.0) + random.nextDouble(-40.0, 40.0)).coerceIn(700.0, 6_300.0)
    }

    /** Vácuo na lenta (~32 kPa), atmosfera em ~60 % de carga e pressão positiva do turbo acima disso. */
    private fun mapPara(carga: Double): Double {
        val base = 28.0 + carga * 1.1
        val turbo = (carga - 60.0).coerceAtLeast(0.0) * 3.2
        return (base + turbo + random.nextDouble(-1.0, 1.0)).coerceIn(25.0, 235.0)
    }

    private fun arred(v: Double, casas: Int): Double {
        var f = 1.0
        repeat(casas) { f *= 10 }
        return (v * f).roundToInt() / f
    }

    companion object {
        const val ETANOL_INICIAL = 48.0
        const val MARCHA_LENTA = 700.0
        const val CILINDRADA_L = 1.4
        const val BAROMETRO_KPA = 95.0

        /** Relações do câmbio automático de 6 marchas e diferencial efetivo (com o conversor travado). */
        val RELACOES = listOf(4.58, 2.96, 1.91, 1.45, 1.00, 0.75)
        const val DIFERENCIAL = 3.0

        /** Rotação da roda por km/h (pneu 205/55 R16, ~1,95 m de circunferência). */
        const val RPM_RODA_POR_KMH = 1000.0 / 60.0 / 1.95

        /** Velocidades de troca para cima (km/h) da 1ª→2ª até a 5ª→6ª, em aceleração moderada. */
        val TROCAS_KMH = listOf(18.0, 34.0, 52.0, 72.0, 92.0)

        /** PIDs que a ECU simulada do Cruze suporta (sem 010A, 0134 e 015E, como num LE2). */
        val COMANDOS: Set<String> = setOf(
            "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010E", "010F", "0110", "0111", "0114",
            "011F", "0121", "0123", "0124", "012F", "0131", "0133", "0142", "0144", "0145", "0146", "0149",
            "0152", "015C", "ATRV",
        )
    }
}
