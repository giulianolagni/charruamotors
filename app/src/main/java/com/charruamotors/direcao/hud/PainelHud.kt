package com.charruamotors.direcao.hud

import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.EstiloHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.util.comoNumero

/**
 * Uma célula do HUD: [valor] já formatado na unidade global, [rotulo] com a unidade
 * (`TURBO/MAP psi`, `MOTOR °F`) e a [fracao] da escala (0–1) para o estilo com barra.
 */
data class CelulaHud(
    val sensor: SensorPainel,
    val valor: String,
    val rotulo: String,
    val fracao: Float? = null,
)

/** Textos prontos do HUD flutuante, no tamanho, sensores e estilo escolhidos na ativação. */
data class PainelHud(
    val conectado: Boolean,
    val celulas: List<CelulaHud>,
    /** Temperatura do motor acima do limite: o painel inteiro fica vermelho. */
    val alertaTemperatura: Boolean,
    val tamanho: TamanhoHud = TamanhoHud.EXPANDIDO,
    val estilo: EstiloHud = EstiloHud.NUMEROS,
) {
    fun celula(sensor: SensorPainel): CelulaHud? = celulas.firstOrNull { it.sensor == sensor }
}

/** Monta o [PainelHud] a partir de um instantâneo da telemetria. Lógica pura, testável na JVM. */
object FormatadorHud {
    /** Acima disto (°C, sempre medido no SI) o HUD acende o alerta vermelho. */
    const val LIMITE_TEMPERATURA_ALERTA = 105.0

    const val SEM_VALOR = "--"

    fun montar(
        dados: ValoresSensores?,
        conectado: Boolean,
        config: ConfigHud = ConfigHud(),
        conversor: ConversorUnidades = ConversorUnidades.PADRAO,
    ): PainelHud {
        val valores = dados.takeIf { conectado }
        val temperatura = valores?.get(Pids.TEMPERATURA_MOTOR)
        val celulas = sensoresExibidos(config.sensoresVisiveis, valores).map { celula(it, valores, conversor) }
        return PainelHud(
            conectado = conectado,
            celulas = celulas,
            alertaTemperatura = alertaTemperatura(temperatura),
            tamanho = config.tamanho,
            estilo = config.estilo,
        )
    }

    /**
     * Diesel common-rail × flex: o Rail sem leitura (motor sem o PID 0123) dá lugar à tensão da
     * bateria, se ela ainda não estiver no HUD — como o "4º mostrador" das versões anteriores.
     */
    fun sensoresExibidos(escolhidos: List<SensorPainel>, valores: ValoresSensores?): List<SensorPainel> =
        escolhidos.map { s ->
            if (s == SensorPainel.RAIL && s.ler(valores) == null && SensorPainel.TENSAO !in escolhidos) SensorPainel.TENSAO else s
        }

    /**
     * Consumo do HUD: em movimento, na unidade global (km/L, L/100km ou MPG — v2.3.0); parado (ou
     * sem velocidade), o consumo da ECU em L/h. A barra segue o km/L (0–20).
     */
    private fun celulaConsumo(valores: ValoresSensores?, conversor: ConversorUnidades): CelulaHud {
        val litrosHora = valores?.get("015E")?.takeIf { it.isFinite() && it > 0 }
        val velocidade = valores?.get(Pids.VELOCIDADE)?.takeIf { it.isFinite() }
        val emMovimento = litrosHora != null && velocidade != null && velocidade >= 5
        val sensor = SensorPainel.CONSUMO_KML
        return when {
            litrosHora == null -> CelulaHud(sensor, SEM_VALOR, sensor.rotuloHud, null)
            emMovimento -> {
                val kmL = (velocidade!! / litrosHora).coerceAtMost(SensorPainel.MAX_KM_L)
                val exibido = conversor.consumo(kmL)
                val texto = if (exibido.isFinite()) exibido.comoNumero(1) else SEM_VALOR
                CelulaHud(sensor, texto, "CONSUMO ${conversor.simboloConsumo}", (kmL / 20).toFloat().coerceIn(0f, 1f))
            }
            else -> CelulaHud(sensor, litrosHora.comoNumero(1), "CONSUMO L/h", (litrosHora / 30).toFloat().coerceIn(0f, 1f))
        }
    }

    private fun celula(sensor: SensorPainel, valores: ValoresSensores?, conversor: ConversorUnidades): CelulaHud {
        if (sensor == SensorPainel.CONSUMO_KML) return celulaConsumo(valores, conversor)
        val bruto = sensor.ler(valores)
        val exibicao = bruto?.let { sensor.exibir(it, conversor) }
        val escala = sensor.escala(conversor)
        return CelulaHud(
            sensor = sensor,
            valor = when {
                bruto == null -> SEM_VALOR
                sensor.ehEstado -> sensor.texto(bruto, conversor)
                else -> exibicao?.let { it.valor.comoNumero(it.casas) } ?: SEM_VALOR
            },
            rotulo = sensor.rotuloHud(conversor),
            fracao = exibicao?.let { escala.fracao(it.valor.toFloat()) },
        )
    }

    fun alertaTemperatura(temperaturaC: Double?): Boolean =
        temperaturaC != null && temperaturaC > LIMITE_TEMPERATURA_ALERTA
}
