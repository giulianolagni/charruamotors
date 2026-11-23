package com.charruamotors.direcao.hud

import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.ui.util.comoNumero

/** O 4º mostrador do HUD: pressão do rail (diesel common-rail) ou, sem ela, a tensão da bateria. */
enum class TipoQuartoValor { RAIL, TENSAO }

/** Textos prontos do HUD flutuante (as unidades ficam nos rótulos do layout). */
data class PainelHud(
    val conectado: Boolean,
    val rpm: String,
    /** Pressão do coletor (MAP/turbo), na [unidadeMap] escolhida. */
    val map: String,
    /** `kPa`, `bar` ou `psi` (rótulo `TURBO/MAP bar`). */
    val unidadeMap: String = UnidadePressao.PADRAO.simbolo,
    /** Temperatura do líquido de arrefecimento, em °C. */
    val temperatura: String,
    val tipoQuarto: TipoQuartoValor,
    /** Rail em bar ou tensão em V, conforme [tipoQuarto]. */
    val quarto: String,
    /** Temperatura do motor acima do limite: o painel inteiro fica vermelho. */
    val alertaTemperatura: Boolean,
)

/** Monta o [PainelHud] a partir de um instantâneo da telemetria. Lógica pura, testável na JVM. */
object FormatadorHud {
    /** Acima disto (°C) o HUD acende o alerta vermelho. */
    const val LIMITE_TEMPERATURA_ALERTA = 105.0

    const val SEM_VALOR = "--"

    /** O PID 0123 vem em kPa; no rail se lê em bar (1 bar = 100 kPa). */
    private const val KPA_POR_BAR = 100.0

    fun montar(dados: ValoresSensores?, conectado: Boolean, unidade: UnidadePressao = UnidadePressao.PADRAO): PainelHud {
        val valores = dados.takeIf { conectado }
        val temperatura = valores?.get(Pids.TEMPERATURA_MOTOR)
        val rail = valores?.get(Pids.PRESSAO_RAIL)
        return PainelHud(
            conectado = conectado,
            rpm = formatar(valores?.get(Pids.RPM), 0),
            map = formatar(unidade.deKpa(valores?.get(Pids.PRESSAO_COLETOR)), unidade.casasDecimais),
            unidadeMap = unidade.simbolo,
            temperatura = formatar(temperatura, 0),
            tipoQuarto = if (rail != null) TipoQuartoValor.RAIL else TipoQuartoValor.TENSAO,
            quarto = if (rail != null) formatar(rail / KPA_POR_BAR, 0) else formatar(valores?.get(Pids.TENSAO_BATERIA), 1),
            alertaTemperatura = alertaTemperatura(temperatura),
        )
    }

    fun alertaTemperatura(temperaturaC: Double?): Boolean =
        temperaturaC != null && temperaturaC > LIMITE_TEMPERATURA_ALERTA

    private fun formatar(valor: Double?, casas: Int): String =
        valor?.takeIf { it.isFinite() }?.comoNumero(casas) ?: SEM_VALOR
}
