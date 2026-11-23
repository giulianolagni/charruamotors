package com.charruamotors.direcao.desempenho

import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids

/** De onde o cronômetro de desempenho (0–60 / 0–100 km/h) tira a velocidade. */
enum class FonteVelocidade(val rotulo: String) {
    /** PID `010D` da ECU (padrão). */
    OBD("ECU"),

    /** GPS do celular; sem sinal válido, volta para a ECU. */
    GPS("GPS"),
    ;

    companion object {
        val PADRAO = OBD

        fun deCodigo(codigo: String?): FonteVelocidade =
            entries.firstOrNull { it.name.equals(codigo?.trim(), ignoreCase = true) } ?: PADRAO
    }
}

/** Velocidade escolhida para o cronômetro e a fonte que de fato a forneceu. */
data class VelocidadeEscolhida(val kmh: Double, val fonte: FonteVelocidade)

/**
 * Escolha da velocidade do cronômetro (pura): com [FonteVelocidade.GPS] preferida e um fix válido
 * no instantâneo ([Pids.VELOCIDADE_GPS]), usa o GPS; sem fix, cai para o `010D` da ECU. Quem
 * consome deve reiniciar a medição quando a fonte mudar no meio de um teste (ver [mudouDeFonte]):
 * um tempo nunca mistura GPS e ECU.
 */
object SeletorVelocidade {
    fun escolher(dados: ValoresSensores?, preferida: FonteVelocidade): VelocidadeEscolhida? {
        if (dados == null) return null
        if (preferida == FonteVelocidade.GPS) {
            dados[Pids.VELOCIDADE_GPS]?.takeIf { it.isFinite() }?.let { return VelocidadeEscolhida(it, FonteVelocidade.GPS) }
        }
        return dados[Pids.VELOCIDADE]?.takeIf { it.isFinite() }?.let { VelocidadeEscolhida(it, FonteVelocidade.OBD) }
    }

    /** A fonte trocou entre dois instantâneos com leitura (GPS perdeu/ganhou sinal)? */
    fun mudouDeFonte(anterior: FonteVelocidade?, atual: FonteVelocidade?): Boolean =
        anterior != null && atual != null && anterior != atual
}
