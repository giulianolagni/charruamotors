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

    private fun celula(sensor: SensorPainel, valores: ValoresSensores?, conversor: ConversorUnidades): CelulaHud {
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
