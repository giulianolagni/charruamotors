package com.charruamotors.domain.calculo

import kotlin.math.abs

/**
 * v2.3.8 — descarta o `010D = FF` (255 km/h) espúrio, que contaminaria a velocidade máxima, a
 * distância e o consumo médio. Só o 255 é suspeito: vale como falha quando o RPM está abaixo de
 * [RPM_MINIMO] ou quando salta mais de [SALTO_MAXIMO_KMH] em menos de [JANELA_SALTO_MS] desde a
 * última amostra aceita. Não é thread-safe (uma instância por sessão).
 */
class FiltroVelocidadeEspuria {

    private var anterior: Double? = null
    private var anteriorMs = 0L
    private var rpmConhecido: Double? = null

    /**
     * A leitura [velocidadeKmh] do instante [agoraMs] é aceitável? [rpm] é o do mesmo ciclo (null =
     * não lido: vale o último conhecido). Só a leitura aceita vira referência da próxima.
     */
    fun aceitar(velocidadeKmh: Double?, rpm: Double?, agoraMs: Long): Boolean {
        if (rpm != null) rpmConhecido = rpm
        if (velocidadeKmh == null) return true
        if (velocidadeKmh >= VALOR_ESPURIO_KMH) {
            val motorLento = rpmConhecido?.let { it < RPM_MINIMO } == true
            val salto = anterior?.let {
                abs(velocidadeKmh - it) > SALTO_MAXIMO_KMH && agoraMs - anteriorMs < JANELA_SALTO_MS
            } == true
            if (motorLento || salto) return false
        }
        anterior = velocidadeKmh
        anteriorMs = agoraMs
        return true
    }

    fun reiniciar() {
        anterior = null
        anteriorMs = 0L
        rpmConhecido = null
    }

    companion object {
        const val VALOR_ESPURIO_KMH = 255.0
        const val RPM_MINIMO = 1200.0
        const val SALTO_MAXIMO_KMH = 80.0
        const val JANELA_SALTO_MS = 2000L
    }
}
