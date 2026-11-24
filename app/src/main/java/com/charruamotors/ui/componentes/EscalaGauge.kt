package com.charruamotors.ui.componentes

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Matemática do mostrador [GaugeView], sem dependências de Android: converte valores em
 * frações/ângulos do arco e gera os rótulos da escala.
 *
 * O arco tem [VARREDURA] graus começando em [ANGULO_INICIAL] (ângulos do `Canvas`:
 * 0° = 3 h, sentido horário), deixando a abertura embaixo para o título.
 *
 * @param zonaVermelha início da faixa vermelha; null = sem zona vermelha.
 * @param divisorRotulos divide os rótulos (ex.: 1000 para exibir RPM como 0..8 "×1000").
 */
data class EscalaGauge(
    val minimo: Float = 0f,
    val maximo: Float = 100f,
    val zonaVermelha: Float? = null,
    val divisoes: Int = 10,
    val subdivisoes: Int = 5,
    val divisorRotulos: Float = 1f,
) {
    init {
        require(maximo > minimo) { "Escala inválida: $minimo..$maximo" }
        require(divisoes >= 1 && subdivisoes >= 1) { "Divisões inválidas" }
        require(divisorRotulos > 0f) { "Divisor de rótulos inválido" }
    }

    /** Posição de [valor] no arco, de 0 (mínimo) a 1 (máximo), saturando fora da faixa. */
    fun fracao(valor: Float): Float = ((valor - minimo) / (maximo - minimo)).coerceIn(0f, 1f)

    fun angulo(valor: Float): Float = ANGULO_INICIAL + VARREDURA * fracao(valor)

    /** Fração onde a zona vermelha começa; null se não houver (ou se começar além do máximo). */
    val fracaoZonaVermelha: Float?
        get() = zonaVermelha?.takeIf { it < maximo }?.let(::fracao)

    fun naZonaVermelha(valor: Float): Boolean = zonaVermelha != null && valor >= zonaVermelha

    /** Quantidade total de traços da escala (maiores + menores), incluindo o último. */
    val totalTracos: Int get() = divisoes * subdivisoes + 1

    fun tracoMaior(indice: Int): Boolean = indice % subdivisoes == 0

    /** Rótulos dos traços maiores (divisoes + 1), já divididos por [divisorRotulos]. */
    val rotulos: List<String>
        get() = (0..divisoes).map { i -> formatarRotulo((minimo + (maximo - minimo) * i / divisoes) / divisorRotulos) }

    companion object {
        const val ANGULO_INICIAL = 135f
        const val VARREDURA = 270f

        /** Inteiro quando exato ("40", "8"); senão uma casa decimal com vírgula ("0,5"). */
        fun formatarRotulo(v: Float): String {
            val arredondado = v.roundToLong()
            return if (abs(v - arredondado) < 0.01f) {
                arredondado.toString()
            } else {
                ((v * 10).roundToLong() / 10.0).toString().replace('.', ',')
            }
        }
    }
}
