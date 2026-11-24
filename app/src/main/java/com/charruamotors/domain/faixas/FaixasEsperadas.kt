package com.charruamotors.domain.faixas

import com.charruamotors.domain.calculo.LimitesProtecao
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids
import java.text.NumberFormat
import java.util.Locale

/**
 * Quando uma faixa vale: o RPM de marcha lenta só faz sentido parado e com o motor aquecido, a
 * tensão, o rail, o MAP e o MAF só com o motor girando (v2.2.6: `RPM ≥ 400`).
 */
enum class CondicaoFaixa(val descricao: String) {
    SEMPRE("sempre"),
    MOTOR_LIGADO("motor ligado"),
    MARCHA_LENTA("marcha lenta (parado, sem acelerar, motor aquecido)"),
}

/**
 * Grandeza com faixa esperada. [comandos] são as chaves da telemetria (na ordem de preferência);
 * com [cadaComando] cada uma é avaliada separadamente (os 4 bicos injetores). [fatorExibicao]
 * converte a unidade do catálogo na da faixa (rail: kPa → bar). Temperaturas não acusam "abaixo"
 * ao vivo ([minimoAoVivo] = false): o motor frio está fora da faixa por definição até aquecer.
 */
enum class ParametroFaixa(
    val titulo: String,
    val unidade: String,
    val comandos: List<String>,
    val casas: Int,
    val condicao: CondicaoFaixa,
    val minimoAoVivo: Boolean = true,
    val fatorExibicao: Double = 1.0,
    val cadaComando: Boolean = false,
) {
    RPM_LENTA("RPM marcha lenta", "rpm", listOf(Pids.RPM), 0, CondicaoFaixa.MARCHA_LENTA),
    RPM_MAXIMO("RPM máx. seguro", "rpm", listOf(Pids.RPM), 0, CondicaoFaixa.SEMPRE),
    TEMP_AGUA("Temp. Água", "°C", listOf(Pids.TEMPERATURA_MOTOR), 0, CondicaoFaixa.MOTOR_LIGADO, minimoAoVivo = false),
    TEMP_ATF("Temp. Câmbio ATF", "°C", Pids.COMANDOS_ATF, 0, CondicaoFaixa.MOTOR_LIGADO, minimoAoVivo = false),
    TENSAO("Bateria / Alternador", "V", listOf(Pids.TENSAO_BATERIA), 1, CondicaoFaixa.MOTOR_LIGADO),
    RAIL("Pressão do Rail", "bar", listOf(Pids.PRESSAO_RAIL), 0, CondicaoFaixa.MOTOR_LIGADO, fatorExibicao = 0.01),
    RAIL_LENTA("Pressão do Rail (lenta)", "bar", listOf(Pids.PRESSAO_RAIL), 0, CondicaoFaixa.MARCHA_LENTA, fatorExibicao = 0.01),
    MAP_TURBO("Pressão Turbo (MAP)", "kPa", listOf(Pids.PRESSAO_COLETOR), 0, CondicaoFaixa.MOTOR_LIGADO),
    BICOS(
        "Correção Bicos #1–#4", "mm³/st", listOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4"), 2,
        CondicaoFaixa.SEMPRE, cadaComando = true,
    ),
    STFT("Correção curto prazo (STFT)", "%", listOf(Pids.STFT), 1, CondicaoFaixa.MOTOR_LIGADO),
    LTFT("Correção longo prazo (LTFT)", "%", listOf(Pids.LTFT), 1, CondicaoFaixa.MOTOR_LIGADO),
    CARGA_MOTOR("Carga do motor", "%", listOf(Pids.CARGA_MOTOR), 0, CondicaoFaixa.MOTOR_LIGADO),
    TEMP_ADMISSAO("Temp. ar de admissão", "°C", listOf(Pids.TEMPERATURA_AR_ADMISSAO), 0, CondicaoFaixa.MOTOR_LIGADO, minimoAoVivo = false),
    MAF("Fluxo de ar (MAF)", "g/s", listOf(Pids.MAF), 0, CondicaoFaixa.MOTOR_LIGADO),
    BORBOLETA("Posição da borboleta", "%", listOf(Pids.BORBOLETA), 0, CondicaoFaixa.MOTOR_LIGADO),
    PRESSAO_BARO("Pressão barométrica", "kPa", listOf("TOYOTA_BARO", "0133"), 0, CondicaoFaixa.SEMPRE),
    TENSAO_ECU("Tensão da ECU", "V", listOf("TOYOTA_TENSAO_ECU", Pids.TENSAO_ECU), 1, CondicaoFaixa.MOTOR_LIGADO),
}

enum class SituacaoFaixa { ABAIXO, DENTRO, ACIMA }

/** Faixa de um parâmetro, na unidade da faixa; [min]/[max] null = sem limite daquele lado. */
data class FaixaEsperada(val parametro: ParametroFaixa, val min: Double?, val max: Double?) {
    init {
        require(min != null || max != null) { "${parametro.name}: faixa sem limites" }
        require(min == null || max == null || min < max) { "${parametro.name}: faixa inválida ($min..$max)" }
    }

    fun situacao(valor: Double): SituacaoFaixa = when {
        min != null && valor < min -> SituacaoFaixa.ABAIXO
        max != null && valor > max -> SituacaoFaixa.ACIMA
        else -> SituacaoFaixa.DENTRO
    }

    /** `650 – 800 rpm`, `máx. 4.200 rpm`, `-3,00 a +3,00 mm³/st`. */
    val texto: String
        get() {
            val u = parametro.unidade
            val c = parametro.casas
            return when {
                min == null -> "máx. ${numero(max!!, c)} $u"
                max == null -> "mín. ${numero(min, c)} $u"
                min < 0 -> "${numero(min, c)} a +${numero(max, c)} $u"
                else -> "${numero(min, c)} – ${numero(max, c)} $u"
            }
        }

    companion object {
        private val LOCALE_BR: Locale = Locale.forLanguageTag("pt-BR")

        fun numero(valor: Double, casas: Int): String = NumberFormat.getNumberInstance(LOCALE_BR).apply {
            minimumFractionDigits = casas
            maximumFractionDigits = casas
        }.format(valor)
    }
}

/** Uma leitura fora da faixa: [comando] da telemetria, [valor] na unidade da faixa. */
data class ViolacaoFaixa(val faixa: FaixaEsperada, val comando: String, val valor: Double, val situacao: SituacaoFaixa)

/**
 * Parâmetros mínimos e máximos esperados de um veículo ([perfil] = nome do preset ou "Genérico").
 * Usado na ficha do veículo, no destaque ao vivo dos mostradores e no resumo da sessão. Objeto puro.
 */
data class FaixasVeiculo(val perfil: String, val faixas: List<FaixaEsperada>) {

    operator fun get(parametro: ParametroFaixa): FaixaEsperada? = faixas.firstOrNull { it.parametro == parametro }

    /** Valores de [faixa] em [dados] (chave da telemetria → valor na unidade da faixa), se a condição vale. */
    fun leituras(faixa: FaixaEsperada, dados: ValoresSensores): List<Pair<String, Double>> {
        if (!condicaoAtendida(faixa.parametro.condicao, dados)) return emptyList()
        val p = faixa.parametro
        val lidos = p.comandos.mapNotNull { c -> dados[c]?.takeIf { it.isFinite() }?.let { c to it * p.fatorExibicao } }
        return if (p.cadaComando) lidos else lidos.take(1)
    }

    /** Leituras fora da faixa agora (temperaturas só acusam o "acima" ao vivo). */
    fun violacoes(dados: ValoresSensores?): List<ViolacaoFaixa> {
        if (dados == null) return emptyList()
        return faixas.flatMap { f ->
            leituras(f, dados).mapNotNull { (c, v) ->
                val s = f.situacao(v)
                val conta = s == SituacaoFaixa.ACIMA || (s == SituacaoFaixa.ABAIXO && f.parametro.minimoAoVivo)
                if (conta) ViolacaoFaixa(f, c, v, s) else null
            }
        }
    }

    /** Algum dos [comandos] de um mostrador está fora da faixa em [dados]? */
    fun foraDaFaixa(comandos: Collection<String>, dados: ValoresSensores?): Boolean {
        val chaves = comandos.map { it.uppercase() }.toSet()
        return violacoes(dados).any { it.comando.uppercase() in chaves }
    }

    /** Linhas da ficha do veículo: `RPM marcha lenta: 650 – 800 rpm (marcha lenta ...)`. */
    fun linhasFicha(): List<String> = faixas.map { "${it.parametro.titulo}: ${it.texto}" }

    companion object {
        /**
         * RPM a partir do qual o motor é considerado ligado (abaixo: desligado ou girando no motor de
         * partida). v2.2.6: inclusivo (`≥`) e igual ao do alerta de alternador ([LimitesProtecao.RPM_MINIMO_TENSAO]).
         */
        const val RPM_MOTOR_LIGADO = LimitesProtecao.RPM_MINIMO_TENSAO

        /**
         * Temperatura da água a partir da qual o motor conta como aquecido (v2.2.6): a marcha lenta
         * fria fica acelerada de propósito (e o rail mais alto), então as faixas de lenta só valem acima disso.
         */
        const val TEMP_MOTOR_AQUECIDO_C = 65.0
        private val FAIXA_RPM_LENTA = 400.0..1_100.0
        private const val VELOCIDADE_PARADO_KMH = 3.0
        private const val PEDAL_SOLTO_PCT = 5.0
        private val COMANDOS_PEDAL = listOf("0149", "TOYOTA_PEDAL_D")

        /** Motor girando por conta própria (`RPM ≥ 400`); sem leitura de RPM, não. */
        fun motorLigado(rpm: Double?): Boolean = rpm != null && rpm.isFinite() && rpm >= RPM_MOTOR_LIGADO

        fun condicaoAtendida(condicao: CondicaoFaixa, dados: ValoresSensores): Boolean {
            val rpm = dados[Pids.RPM]
            return when (condicao) {
                CondicaoFaixa.SEMPRE -> true
                CondicaoFaixa.MOTOR_LIGADO -> motorLigado(rpm)
                CondicaoFaixa.MARCHA_LENTA -> {
                    val velocidade = dados[Pids.VELOCIDADE]
                    val pedal = COMANDOS_PEDAL.firstNotNullOfOrNull { dados[it] }
                    val agua = dados[Pids.TEMPERATURA_MOTOR]
                    rpm != null && rpm in FAIXA_RPM_LENTA &&
                        agua != null && agua.isFinite() && agua >= TEMP_MOTOR_AQUECIDO_C &&
                        (velocidade == null || velocidade < VELOCIDADE_PARADO_KMH) &&
                        (pedal == null || pedal < PEDAL_SOLTO_PCT)
                }
            }
        }

        /**
         * Faixas de um veículo sem preset: os limites de alerta do cadastro (água, ATF, alternador)
         * e, nos motores de ciclo Otto, as correções de combustível em ±10 %.
         */
        fun genericas(combustivel: TipoCombustivel?, limites: LimitesProtecao = LimitesProtecao()): FaixasVeiculo {
            val faixas = buildList {
                add(FaixaEsperada(ParametroFaixa.TEMP_AGUA, 75.0, limites.aguaMaxC))
                add(FaixaEsperada(ParametroFaixa.TEMP_ATF, 50.0, limites.atfMaxC))
                add(FaixaEsperada(ParametroFaixa.TENSAO, limites.tensaoMinV, TENSAO_MAX_GENERICA))
                if (combustivel != TipoCombustivel.DIESEL) {
                    add(FaixaEsperada(ParametroFaixa.STFT, -10.0, 10.0))
                    add(FaixaEsperada(ParametroFaixa.LTFT, -10.0, 10.0))
                }
            }
            return FaixasVeiculo(PERFIL_GENERICO, faixas)
        }

        const val PERFIL_GENERICO = "Genérico (limites do cadastro)"
        const val SUFIXO_LIMITES_CADASTRO = "(limites do cadastro)"
        private const val TENSAO_MAX_GENERICA = 14.8
        private const val AGUA_MIN_GENERICA = 75.0
        private const val ATF_MIN_GENERICA = 50.0

        /**
         * Faixas de um perfil genérico do catálogo ([generico]) com os limites do cadastro por cima:
         * água e ATF máximos e a tensão mínima do alternador são os que o usuário configurou (o ATF
         * entra sempre, como nas [genericas], porque o genérico não conhece o câmbio).
         */
        fun comLimitesDoCadastro(generico: PresetVeiculo, limites: LimitesProtecao): FaixasVeiculo {
            val base = generico.faixas.faixas.associateBy { it.parametro }.toMutableMap()
            base[ParametroFaixa.TEMP_AGUA] = faixaSegura(
                ParametroFaixa.TEMP_AGUA, base[ParametroFaixa.TEMP_AGUA]?.min ?: AGUA_MIN_GENERICA, limites.aguaMaxC,
            )
            base[ParametroFaixa.TEMP_ATF] = faixaSegura(
                ParametroFaixa.TEMP_ATF, base[ParametroFaixa.TEMP_ATF]?.min ?: ATF_MIN_GENERICA, limites.atfMaxC,
            )
            base[ParametroFaixa.TENSAO] = faixaSegura(
                ParametroFaixa.TENSAO, limites.tensaoMinV, base[ParametroFaixa.TENSAO]?.max ?: TENSAO_MAX_GENERICA,
            )
            return FaixasVeiculo("${generico.titulo} $SUFIXO_LIMITES_CADASTRO", base.values.sortedBy { it.parametro.ordinal })
        }

        /** Faixa que nunca fica inválida: se o limite do cadastro cruzar o outro lado, só ele vale. */
        private fun faixaSegura(p: ParametroFaixa, min: Double, max: Double): FaixaEsperada =
            if (min < max) FaixaEsperada(p, min, max) else if (p == ParametroFaixa.TENSAO) FaixaEsperada(p, min, null) else FaixaEsperada(p, null, max)

        /**
         * Cascata de fallback: perfil exato do catálogo → perfil genérico pela motorização com os
         * limites do cadastro → [genericas] (OBD2 modo 01 padrão). Ver [ResolucaoPerfil.resolver].
         */
        fun paraVeiculo(v: Veiculo?): FaixasVeiculo {
            if (v == null) return genericas(null)
            return ResolucaoPerfil.deVeiculo(v).faixas
        }
    }
}
