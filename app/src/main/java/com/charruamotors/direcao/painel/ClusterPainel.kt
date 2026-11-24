package com.charruamotors.direcao.painel

import com.charruamotors.domain.calculo.AlertasProtecao
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.calculo.TipoAlertaProtecao
import com.charruamotors.obd.ValoresSensores

/** Indicador central do Cluster: [principal] grande (`P`, `D`, `3ª`) e, às vezes, a [legenda] pequena (`D`). */
data class IndicadorMarcha(val principal: String, val legenda: String? = null)

/** Aviso do Cluster (temperatura ou bateria fora da faixa normal). */
data class AlertaCluster(val texto: String)

/**
 * Regras do Modo Painel Automotivo (celular na horizontal): quais sensores vão nas 4 bolinhas,
 * como o toque troca o sensor, o texto da marcha/alavanca e os avisos. Objeto puro (JVM), sem
 * Android: a tela só desenha o que sai daqui.
 */
object ClusterPainel {

    /** As bolinhas laterais: 0/1 à esquerda (água, óleo/ATF) e 2/3 à direita (turbo/MAP, bateria). */
    const val BOLINHAS = 4

    /** Sensores que o toque numa bolinha alterna, em ordem. */
    val OPCOES_BOLINHA: List<SensorPainel> = listOf(
        SensorPainel.TEMP_MOTOR,
        SensorPainel.TEMP_CAMBIO,
        SensorPainel.TEMP_OLEO,
        SensorPainel.TURBO,
        SensorPainel.TENSAO,
        SensorPainel.TEMP_ADMISSAO,
        SensorPainel.CARGA,
        SensorPainel.TENSAO_ECU,
    )

    /** Padrão de cada bolinha; null = automático (ATF do câmbio se houver, senão óleo do motor). */
    val PADRAO: List<SensorPainel?> = listOf(SensorPainel.TEMP_MOTOR, null, SensorPainel.TURBO, SensorPainel.TENSAO)

    /** ATF (`7E1`) se estiver sendo lido; senão a temperatura do óleo; sem nenhum dos dois, o ATF ("—"). */
    fun oleoOuAtf(dados: ValoresSensores?): SensorPainel =
        listOf(SensorPainel.TEMP_CAMBIO, SensorPainel.TEMP_OLEO).firstOrNull { it.ler(dados) != null }
            ?: SensorPainel.TEMP_CAMBIO

    /** Sensor da bolinha [indice] dado o que o usuário escolheu ([escolhido] null = padrão/automático). */
    fun sensorDaBolinha(indice: Int, escolhido: SensorPainel?, dados: ValoresSensores?): SensorPainel =
        escolhido ?: PADRAO.getOrNull(indice) ?: oleoOuAtf(dados)

    /**
     * Próximo sensor ao tocar numa bolinha: o seguinte de [OPCOES_BOLINHA] que tenha leitura (para
     * não parar num mostrador vazio); sem nenhuma leitura em jogo, simplesmente o seguinte.
     */
    fun proximo(atual: SensorPainel, dados: ValoresSensores?): SensorPainel {
        val opcoes = OPCOES_BOLINHA
        val inicio = opcoes.indexOf(atual)
        val seguintes = List(opcoes.size - 1) { opcoes[(inicio + 1 + it).mod(opcoes.size)] }
        return seguintes.firstOrNull { it.ler(dados) != null } ?: seguintes.first()
    }

    /**
     * Marcha/alavanca do centro: com a alavanca em D/3/2/L e uma marcha válida (1ª..6ª, ou 1ª L..5ª L na reduzida) mostra a
     * marcha grande e a alavanca pequena; senão a alavanca (`P`, `R`, `N`, `D`); sem alavanca, a
     * marcha. `0xFF` (parado) e leituras fora das tabelas não contam.
     */
    fun indicadorMarcha(dados: ValoresSensores?): IndicadorMarcha {
        val alavanca = SensorPainel.MARCHA_ALAVANCA.ler(dados)?.let { SensorPainel.MARCHA_ALAVANCA.rotulos[it.toInt()] }
        val numero = SensorPainel.MARCHA_ATUAL.ler(dados)?.toInt()
        val marcha = numero?.takeIf { it in 1..6 || CambioA750f.ehReduzida(it) }?.let { SensorPainel.MARCHA_ATUAL.rotulos[it] }
        return when {
            alavanca != null && alavanca !in ALAVANCAS_SEM_MARCHA && marcha != null -> IndicadorMarcha(marcha, alavanca)
            alavanca != null -> IndicadorMarcha(alavanca)
            marcha != null -> IndicadorMarcha(marcha)
            else -> IndicadorMarcha(SensorPainel.SEM_LEITURA)
        }
    }

    /**
     * Avisos de temperatura (água, ATF, óleo) e bateria fora da faixa. Água, ATF e a tensão do
     * alternador seguem os [limites] do veículo (v1.7); óleo e bateria (parada/alta) têm faixa fixa.
     */
    fun alertas(dados: ValoresSensores?, limites: LimitesProtecao = LimitesProtecao()): List<AlertaCluster> {
        if (dados == null) return emptyList()
        val protecao = AlertasProtecao.avaliar(dados, limites).map { it.tipo }.toSet()
        return buildList {
            if (TipoAlertaProtecao.AGUA in protecao) add(AlertaCluster(TipoAlertaProtecao.AGUA.texto))
            if (TipoAlertaProtecao.ATF in protecao) add(AlertaCluster(TipoAlertaProtecao.ATF.texto))
            dados[SensorPainel.TEMP_OLEO.comando]?.takeIf { it >= OLEO_ALTA_C }?.let { add(AlertaCluster("ÓLEO QUENTE")) }
            if (TipoAlertaProtecao.TENSAO in protecao) add(AlertaCluster(TipoAlertaProtecao.TENSAO.texto))
            dados[SensorPainel.TENSAO.comando]?.let { v ->
                when {
                    v < BATERIA_BAIXA_V && TipoAlertaProtecao.TENSAO !in protecao -> add(AlertaCluster("BATERIA BAIXA"))
                    v > BATERIA_ALTA_V -> add(AlertaCluster("BATERIA ALTA"))
                }
            }
        }
    }

    /** Com a alavanca nestes estados a marcha engatada não é mostrada (só vale em D/3/2/L). */
    private val ALAVANCAS_SEM_MARCHA = setOf("P", "R", "N", "P/N")

    const val OLEO_ALTA_C = 130.0
    const val BATERIA_BAIXA_V = 12.0
    const val BATERIA_ALTA_V = 15.0
}
