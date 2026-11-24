package com.charruamotors.sistema.gps

/**
 * Um fix do GPS do celular (vindo de `android.location.Location`), sem dependência de Android.
 *
 * @property velocidadeMs velocidade em m/s; null = o fix não trouxe velocidade.
 * @property precisaoVelocidadeMs incerteza da velocidade (1 desvio, m/s); null = não informada.
 * @property precisaoHorizontalM raio de incerteza da posição (m); null = não informado.
 * @property instanteMs quando o fix foi obtido, no relógio monotônico do aparelho (`elapsedRealtime`).
 */
data class AmostraGps(
    val velocidadeMs: Float?,
    val precisaoVelocidadeMs: Float?,
    val precisaoHorizontalM: Float?,
    val instanteMs: Long,
)

/** Situação do GPS para a tela de Configurações. */
enum class SituacaoGps(val texto: String) {
    DESATIVADO("Desativada"),
    SEM_PERMISSAO("Sem permissão de localização"),
    DESLIGADO("Localização do aparelho desligada"),
    AGUARDANDO_SINAL("Aguardando sinal do GPS"),
    ATIVO("GPS com sinal"),
}

/**
 * Fallback seguro da velocidade GPS (puro, testável na JVM): só devolve um valor quando o fix é
 * recente e confiável; caso contrário devolve null e quem consome mostra "—" (Painel, Cluster, HUD),
 * deixa o campo vazio (CSV) ou volta para a velocidade da ECU (cronômetro). Nunca repete um valor
 * velho: em túnel, garagem ou com o GPS desligado a leitura some em até [VALIDADE_MS].
 */
object FiltroVelocidadeGps {
    /** Fix mais velho que isso (túnel, perda de sinal) não vale mais. */
    const val VALIDADE_MS = 2_500L

    /** Tolerância a relógios ligeiramente adiantados (fix "no futuro"). */
    private const val ADIANTAMENTO_MAX_MS = 1_000L

    /** Incerteza da velocidade acima disso (≈ 9 km/h) = leitura descartada. */
    const val PRECISAO_VELOCIDADE_MAX_MS = 2.5f

    /** Posição pior que isso (sinal refletido em prédios) = velocidade não confiável. */
    const val PRECISAO_HORIZONTAL_MAX_M = 50f

    /** Parado, o GPS oscila entre 0 e ~1 km/h: abaixo disto é 0 (o cronômetro precisa do 0 para armar). */
    const val LIMIAR_PARADO_KMH = 1.5

    /** Acima disso é erro de fix, não velocidade de carro. */
    const val VELOCIDADE_MAX_KMH = 400.0

    private const val MS_PARA_KMH = 3.6

    /** Velocidade em km/h do [amostra] em [agoraMs] (mesmo relógio do fix), ou null se não for confiável. */
    fun kmh(amostra: AmostraGps?, agoraMs: Long): Double? {
        val v = amostra?.velocidadeMs ?: return null
        if (!v.isFinite() || v < 0f) return null
        val idade = agoraMs - amostra.instanteMs
        if (idade > VALIDADE_MS || idade < -ADIANTAMENTO_MAX_MS) return null
        if (amostra.precisaoVelocidadeMs?.let { !it.isFinite() || it > PRECISAO_VELOCIDADE_MAX_MS } == true) return null
        if (amostra.precisaoHorizontalM?.let { !it.isFinite() || it > PRECISAO_HORIZONTAL_MAX_M } == true) return null
        val kmh = v * MS_PARA_KMH
        if (kmh > VELOCIDADE_MAX_KMH) return null
        return if (kmh < LIMIAR_PARADO_KMH) 0.0 else kmh
    }
}
