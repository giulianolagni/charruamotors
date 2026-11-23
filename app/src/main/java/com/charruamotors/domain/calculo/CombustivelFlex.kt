package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids

/** Combustível que está no tanque, como o app o classifica (pelo `0152` no Auto Flex ou pelo cadastro). */
enum class ClasseCombustivel(val titulo: String) {
    GASOLINA_E27("Gasolina E27"),
    MISTURA_FLEX("Mistura Flex"),
    ETANOL_E100("Etanol E100"),
    DIESEL("Diesel"),
}

/**
 * Estado do combustível na sessão: o que o cadastro pede ([tipoCadastro]), o teor de etanol que a
 * ECU informou (`0152`, null = não suportado / ainda não lido), a [classe] resultante e a
 * estequiometria de referência ([relacaoAfReferencia], `13,2:1` na gasolina E27 a `9,0:1` no E100).
 * [automatico] = a classe veio da leitura da ECU (Auto Flex com `0152`), não de um valor fixo.
 */
data class EstadoFlex(
    val tipoCadastro: TipoCombustivel,
    val percentualEtanol: Double?,
    val classe: ClasseCombustivel,
    val relacaoAfReferencia: Double,
    val automatico: Boolean,
) {
    /** `Mistura Flex (E48) · A/F 11,6:1 · automático (0152)`. */
    val resumo: String
        get() {
            val etanol = percentualEtanol?.takeIf { classe != ClasseCombustivel.DIESEL }?.let { " (E${it.toInt()})" }.orEmpty()
            val origem = when {
                automatico -> " · automático (0152)"
                tipoCadastro == TipoCombustivel.FLEX -> " · sem 0152, assume E27"
                else -> " · manual"
            }
            return "${classe.titulo}$etanol · A/F ${formatar(relacaoAfReferencia)}:1$origem"
        }

    private fun formatar(v: Double) = "%.1f".format(java.util.Locale.ROOT, v).replace('.', ',')
}

/**
 * Modo Flex Automático: estequiometria pelo teor de etanol (`0152`), classificação do combustível
 * e relação A/F real. Objeto puro (JVM).
 *
 * A relação ar/combustível estequiométrica de referência é interpolada linearmente por trechos
 * entre três âncoras do mercado brasileiro: gasolina pura `14,7:1` (E0), gasolina C `13,2:1` (E27)
 * e etanol hidratado `9,0:1` (E100). Acima de 100 % ou abaixo de 0 % o valor satura.
 */
object EstequiometriaFlex {

    const val AFR_GASOLINA_PURA = 14.7
    const val AFR_GASOLINA_E27 = 13.2
    const val AFR_ETANOL_E100 = 9.0
    const val ETANOL_GASOLINA_C = 27.0

    /** Diesel: estequiométrica `14,5:1` (o consumo via MAF usa a relação efetiva do Common Rail). */
    const val AFR_DIESEL = 14.5

    /** Abaixo disto (%) o tanque é tratado como gasolina C; a partir de [LIMITE_ETANOL], etanol. */
    const val LIMITE_GASOLINA = 40.0
    const val LIMITE_ETANOL = 85.0

    /** λ aceito para o cálculo da A/F real (fora disso é leitura espúria ou corte de combustível). */
    private val FAIXA_LAMBDA = 0.5..1.6

    /** Estequiometria de referência (`:1`) para [percentualEtanol] (0–100 %). */
    fun relacaoAf(percentualEtanol: Double): Double {
        val e = percentualEtanol.coerceIn(0.0, 100.0)
        // As âncoras saem exatas (sem resíduo de ponto flutuante da interpolação).
        return when (e) {
            0.0 -> AFR_GASOLINA_PURA
            ETANOL_GASOLINA_C -> AFR_GASOLINA_E27
            100.0 -> AFR_ETANOL_E100
            else -> interpolar(e)
        }
    }

    private fun interpolar(e: Double): Double =
        if (e <= ETANOL_GASOLINA_C) {
            AFR_GASOLINA_PURA + (AFR_GASOLINA_E27 - AFR_GASOLINA_PURA) * e / ETANOL_GASOLINA_C
        } else {
            AFR_GASOLINA_E27 + (AFR_ETANOL_E100 - AFR_GASOLINA_E27) * (e - ETANOL_GASOLINA_C) / (100.0 - ETANOL_GASOLINA_C)
        }

    /** Classe do combustível pelo teor de etanol medido. */
    fun classificar(percentualEtanol: Double): ClasseCombustivel = when {
        percentualEtanol < LIMITE_GASOLINA -> ClasseCombustivel.GASOLINA_E27
        percentualEtanol < LIMITE_ETANOL -> ClasseCombustivel.MISTURA_FLEX
        else -> ClasseCombustivel.ETANOL_E100
    }

    /**
     * Estado do combustível: no Auto Flex ([TipoCombustivel.FLEX]) a classe e a A/F seguem o `0152`
     * (sem ele, assume gasolina E27); Gasolina/GNV, Etanol e Diesel são fixos pela seleção manual.
     */
    fun avaliar(tipo: TipoCombustivel?, percentualEtanol: Double?): EstadoFlex {
        val t = tipo ?: TipoCombustivel.FLEX
        val etanol = percentualEtanol?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        return when (t) {
            TipoCombustivel.DIESEL -> EstadoFlex(t, null, ClasseCombustivel.DIESEL, AFR_DIESEL, automatico = false)
            TipoCombustivel.GASOLINA, TipoCombustivel.GNV ->
                EstadoFlex(t, ETANOL_GASOLINA_C, ClasseCombustivel.GASOLINA_E27, AFR_GASOLINA_E27, automatico = false)
            TipoCombustivel.ETANOL -> EstadoFlex(t, 100.0, ClasseCombustivel.ETANOL_E100, AFR_ETANOL_E100, automatico = false)
            TipoCombustivel.FLEX ->
                if (etanol == null) {
                    EstadoFlex(t, null, ClasseCombustivel.GASOLINA_E27, AFR_GASOLINA_E27, automatico = false)
                } else {
                    EstadoFlex(t, etanol, classificar(etanol), relacaoAf(etanol), automatico = true)
                }
        }
    }

    /** λ da leitura: o medido pela sonda de banda larga (`0124`/`0134`) ou, sem ela, o comandado (`0144`). */
    fun lambda(dados: ValoresSensores): Double? =
        (Pids.COMANDOS_LAMBDA_MEDIDO + Pids.LAMBDA_COMANDADO).firstNotNullOfOrNull { c ->
            dados[c]?.takeIf { it.isFinite() && it in FAIXA_LAMBDA }
        }

    /**
     * Relação A/F real (`:1`) = λ × estequiometria do combustível em uso. Null no diesel (sem sonda
     * de banda estreita/larga em malha fechada) ou sem nenhuma leitura de λ.
     */
    fun relacaoAfReal(dados: ValoresSensores, tipo: TipoCombustivel?): Double? {
        val estado = avaliar(tipo, dados[Pids.PERCENTUAL_ETANOL])
        if (estado.classe == ClasseCombustivel.DIESEL) return null
        val l = lambda(dados) ?: return null
        return l * estado.relacaoAfReferencia
    }
}
