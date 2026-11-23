package com.charruamotors.domain.calculo

import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids

/** Proteções do motor/câmbio/alternador que o Painel vigia (limites por veículo, v1.7). */
enum class TipoAlertaProtecao(val texto: String) {
    AGUA("ÁGUA QUENTE"),
    ATF("ATF QUENTE"),
    TENSAO("ALTERNADOR BAIXO"),
}

/** Um limite ultrapassado: [valor] lido (°C ou V) contra o [limite] configurado. */
data class AlertaProtecao(val tipo: TipoAlertaProtecao, val valor: Double, val limite: Double)

/**
 * Limites editáveis no cadastro do veículo. A tensão só vale com o motor girando acima de
 * [rpmMinimoTensao]: com o motor desligado ou em partida a bateria fica abaixo de 13 V normalmente.
 */
data class LimitesProtecao(
    val aguaMaxC: Double = Veiculo.LIMITE_AGUA_PADRAO_C.toDouble(),
    val atfMaxC: Double = Veiculo.LIMITE_ATF_PADRAO_C.toDouble(),
    val tensaoMinV: Double = Veiculo.LIMITE_TENSAO_PADRAO_V,
    val rpmMinimoTensao: Double = RPM_MINIMO_TENSAO,
) {
    companion object {
        const val RPM_MINIMO_TENSAO = 600.0

        fun deVeiculo(v: Veiculo?): LimitesProtecao =
            if (v == null) LimitesProtecao() else LimitesProtecao(v.tempMaxAguaC.toDouble(), v.tempMaxAtfC.toDouble(), v.tensaoMinAlternadorV)
    }
}

/** Avalia a telemetria contra os [LimitesProtecao]. Objeto puro (JVM). */
object AlertasProtecao {

    /**
     * Alertas ativos em [dados] (°C e V do catálogo). O ATF pode vir do câmbio (`7E1`) ou do
     * espelho no motor (`7E0`); leitura ausente nunca dispara alerta. O limite é inclusivo na
     * temperatura (≥) e exclusivo na tensão (< mínimo).
     */
    fun avaliar(dados: ValoresSensores?, limites: LimitesProtecao): List<AlertaProtecao> {
        if (dados == null) return emptyList()
        return buildList {
            dados[Pids.TEMPERATURA_MOTOR]?.takeIf { it.isFinite() && it >= limites.aguaMaxC }
                ?.let { add(AlertaProtecao(TipoAlertaProtecao.AGUA, it, limites.aguaMaxC)) }
            Pids.COMANDOS_ATF.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } }
                ?.takeIf { it >= limites.atfMaxC }
                ?.let { add(AlertaProtecao(TipoAlertaProtecao.ATF, it, limites.atfMaxC)) }
            val rpm = dados[Pids.RPM]
            val tensao = dados[Pids.TENSAO_BATERIA]
            if (rpm != null && rpm > limites.rpmMinimoTensao && tensao != null && tensao.isFinite() && tensao < limites.tensaoMinV) {
                add(AlertaProtecao(TipoAlertaProtecao.TENSAO, tensao, limites.tensaoMinV))
            }
        }
    }
}

/**
 * Decide quando o aviso sonoro toca: uma vez ao *entrar* em alerta e, se ele persistir, de novo a
 * cada [repeticaoMs]. Sair do alerta rearma na hora. Discreto de propósito: nunca toca a cada leitura.
 */
class DisparadorAlertas(private val repeticaoMs: Long = REPETICAO_PADRAO_MS) {

    private val ultimoSomMs = mutableMapOf<TipoAlertaProtecao, Long>()

    /** Tipos que devem soar agora, dado o conjunto [ativos] no instante [agoraMs]. */
    fun avaliar(ativos: Collection<TipoAlertaProtecao>, agoraMs: Long): List<TipoAlertaProtecao> {
        ultimoSomMs.keys.retainAll(ativos.toSet())
        return ativos.distinct().filter { tipo ->
            val ultimo = ultimoSomMs[tipo]
            val soar = ultimo == null || agoraMs - ultimo >= repeticaoMs
            if (soar) ultimoSomMs[tipo] = agoraMs
            soar
        }
    }

    fun reiniciar() = ultimoSomMs.clear()

    companion object {
        const val REPETICAO_PADRAO_MS = 30_000L
    }
}

/**
 * Junta os [LimitesProtecao] do veículo ativo, o [DisparadorAlertas] e o som: as telas (Painel
 * vertical e Cluster horizontal) chamam [processar] a cada leitura e desenham o que voltar em
 * vermelho; o aviso sonoro toca só quando o disparador manda. Vive no Application.
 */
class CentralAlertas(
    private val som: () -> Unit,
    private val relogio: () -> Long = System::currentTimeMillis,
    private val disparador: DisparadorAlertas = DisparadorAlertas(),
) {
    @Volatile
    var limites: LimitesProtecao = LimitesProtecao()

    /** Faixas mínimas/máximas esperadas do veículo ativo (v2.1): destacam o mostrador fora da faixa. */
    @Volatile
    var faixas: FaixasVeiculo = FaixasVeiculo.genericas(null)

    /** O mostrador com estes [comandos] está fora da faixa esperada em [dados]? (sem som: só destaque). */
    fun foraDaFaixa(comandos: Collection<String>, dados: ValoresSensores?): Boolean = faixas.foraDaFaixa(comandos, dados)

    /** Chamado uma vez a cada alerta que *entra* em vigor (para o log da sessão); rearma ao sair. */
    @Volatile
    var aoEntrarEmAlerta: ((AlertaProtecao) -> Unit)? = null
    private var tiposAtivos: Set<TipoAlertaProtecao> = emptySet()

    @Synchronized
    private fun registrarEntradas(alertas: List<AlertaProtecao>) {
        val agora = alertas.map { it.tipo }.toSet()
        alertas.filter { it.tipo !in tiposAtivos }.forEach { a -> aoEntrarEmAlerta?.let { runCatching { it(a) } } }
        tiposAtivos = agora
    }

    /** Alertas ativos em [dados]; toca o [som] quando algum entra em alerta (ou persiste além da repetição). */
    fun processar(dados: ValoresSensores?): List<AlertaProtecao> {
        val alertas = AlertasProtecao.avaliar(dados, limites)
        registrarEntradas(alertas)
        if (disparador.avaliar(alertas.map { it.tipo }, relogio()).isNotEmpty()) som()
        return alertas
    }
}
